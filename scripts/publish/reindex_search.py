#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
reindex_search.py — populate the Meilisearch full-text index from the two
authoritative sources, per docs/search.md (step 1 of §8).

  * Scripture  -> BaseX  (every edition by default — see "Editions" below).
  * Comments   -> MySQL  (ONLY is_public=1 AND moderation_status='approved').

One index, two document kinds (kind = bible|quran|hadith|lds | comment).
Scope toggles in the UI are Meilisearch filter expressions, not code paths.

How a run behaves (changed 2026-10: it used to delete the whole scripture slice,
then re-add only the editions named — so a run without SCRIPTURE_EDITIONS left
KJV and nothing else):

  * ADDITIVE, one edition at a time. Each edition is fetched, hashed, upserted
    (document ids are edition-unique and deterministic, so re-adding is safe) and
    only then are that edition's STALE documents (ids no longer produced) deleted.
    Search is never emptied, and memory holds one edition, not the corpus.
  * Editions. Default = every .xml resource in BaseX. Narrow it with --editions
    or SCRIPTURE_EDITIONS (comma-separated resource names); a narrowed run leaves
    every other edition in the index untouched.
  * Hash guard, per edition. An edition is skipped when its hash matches the state
    file AND the index already holds that many documents for it, so a wiped index
    is never mistaken for an up-to-date one. --force overrides.
  * --prune removes scripture documents of editions that are no longer in BaseX.
    It is refused on a narrowed run, because there it would delete the rest.
  * Comments are upserted the same way (live writes by the app are never lost to
    a delete-then-add gap); unapproved or withdrawn ones are pruned as stale.

Governance (docs/search.md §2/§6): the scripture pass never touches comment
documents, and the comment pass writes only `kind = comment`.

Connectivity: talks to BaseX and Meilisearch over HTTP and to MySQL over TCP
(pymysql), all by service/container name. Designed to run as a one-off container
ON the docker network, identically on local and prod (no published DB ports
needed, no `docker exec`).

Config via env:
  MEILI_URL   MEILI_KEY   MEILI_INDEX=search
  BASEX_URL   BASEX_USER=admin   BASEX_PASS   BASEX_DB=religioustext
  DB_HOST=religioustext-mysql  DB_PORT=3306  DB_USER=root  DB_PASS  DB_NAME=religioustext
  SCRIPTURE_EDITIONS=                        (optional; default is every edition)
  SEARCH_STATE_FILE                          (per-edition hashes, JSON; mount a volume)

Run (one-off container on the network) — same shape local and prod. NB the prod
network is named after the compose project: common-root_religioustext-net.

  docker run --rm --network common-root_religioustext-net \
    -e MEILI_URL=http://religioustext-meili:7700 -e MEILI_KEY="$MEILI_MASTER_KEY" \
    -e BASEX_URL=http://religioustext-basex:8984/rest \
    -e BASEX_USER=admin -e BASEX_PASS="$BASEX_PASSWORD" \
    -e DB_HOST=religioustext-mysql -e DB_PASS="$MYSQL_ROOT_PASSWORD" \
    -e SEARCH_STATE_FILE=/state/corpus_hash \
    -v /opt/common-root/reindex_search.py:/app/reindex_search.py:ro \
    -v reindex-state:/state -w /app python:3.12-slim \
    sh -c "pip install -q pymysql && python reindex_search.py --scripture"

  (local: MEILI_KEY=masterKey-dev-local, BASEX_PASS=admin, DB_PASS=rootpassword)

Flags:
  --scripture        scripture slice only
  --comments         comment backfill only
  --editions A,B     only these BaseX resources (e.g. bible-fi-1933.xml)
  --prune            drop indexed editions that BaseX no longer has (full runs only)
  --force            ignore the per-edition hash guard
  --dry-run          read BaseX, report what would change, write nothing
                     (needs no Meilisearch and no MySQL)
"""

import argparse
import base64
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.request

MEILI_URL   = os.environ.get("MEILI_URL", "http://localhost:7700")
MEILI_KEY   = os.environ.get("MEILI_KEY", "masterKey-dev-local")
MEILI_INDEX = os.environ.get("MEILI_INDEX", "search")

BASEX_URL  = os.environ.get("BASEX_URL", "http://localhost:8984/rest")
BASEX_USER = os.environ.get("BASEX_USER", "admin")
BASEX_PASS = os.environ.get("BASEX_PASS", "admin")
BASEX_DB   = os.environ.get("BASEX_DB", "religioustext")
# Empty = every edition in BaseX (see list_editions). Set to narrow a run.
EDITIONS   = [e.strip() for e in
              os.environ.get("SCRIPTURE_EDITIONS", "").split(",")
              if e.strip()]

# MySQL over TCP (pymysql). Default host is the container name so a one-off
# container on religioustext-net needs no override; set DB_HOST=localhost only
# when running directly against a published port.
DB_HOST = os.environ.get("DB_HOST", "religioustext-mysql")
DB_PORT = int(os.environ.get("DB_PORT", "3306"))
DB_USER = os.environ.get("DB_USER", "root")
DB_PASS = os.environ.get("DB_PASS", "rootpassword")
DB_NAME = os.environ.get("DB_NAME", "religioustext")

SYSTEM_USER_ID  = "usr-00000000-0000-7000-8000-000000000001"
SCRIPTURE_KINDS = ["bible", "quran", "hadith", "lds"]
BATCH           = 5000
STATE_FILE      = os.environ.get(
    "SEARCH_STATE_FILE",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), ".search_corpus_hash"))


# ----------------------------------------------------------------------------- Meilisearch
def meili(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{MEILI_URL}{path}", data=data, method=method)
    req.add_header("Authorization", f"Bearer {MEILI_KEY}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Meili {method} {path} -> {e.code}: {e.read().decode()}")


def wait_task(task):
    uid = task.get("taskUid", task.get("uid"))
    if uid is None:
        return task
    while True:
        t = meili("GET", f"/tasks/{uid}")
        st = t.get("status")
        if st in ("succeeded", "failed", "canceled"):
            if st != "succeeded":
                raise RuntimeError(f"Meili task {uid} {st}: {t.get('error')}")
            return t
        time.sleep(0.25)


def ensure_index():
    try:
        meili("GET", f"/indexes/{MEILI_INDEX}")
    except RuntimeError:
        wait_task(meili("POST", "/indexes", {"uid": MEILI_INDEX, "primaryKey": "id"}))
    wait_task(meili("PATCH", f"/indexes/{MEILI_INDEX}/settings", {
        "filterableAttributes": ["kind", "language", "source_id", "book_code",
                                 "testament", "region", "ref_books", "author_type",
                                 "ref"],
        "sortableAttributes":   ["canonical_order", "chapter", "verse", "created_at"],
        "searchableAttributes": ["text", "content", "book_name"],
    }))


def delete_by_filter(filt):
    wait_task(meili("POST", f"/indexes/{MEILI_INDEX}/documents/delete", {"filter": filt}))


def add_docs(docs):
    for i in range(0, len(docs), BATCH):
        wait_task(meili("POST", f"/indexes/{MEILI_INDEX}/documents", docs[i:i + BATCH]))


def delete_ids(ids):
    ids = list(ids)
    for i in range(0, len(ids), BATCH):
        wait_task(meili("POST", f"/indexes/{MEILI_INDEX}/documents/delete-batch",
                        ids[i:i + BATCH]))


def existing_ids(filt):
    """Every document id in the index matching `filt`."""
    ids, offset, page = set(), 0, 1000
    while True:
        r = meili("POST", f"/indexes/{MEILI_INDEX}/documents/fetch",
                  {"filter": filt, "fields": ["id"], "limit": page, "offset": offset})
        res = r.get("results", [])
        ids.update(d["id"] for d in res)
        if len(res) < page:
            return ids
        offset += page


def facet_counts(field, filt=None):
    """{value: document count} for one filterable field, optionally filtered."""
    body = {"q": "", "limit": 0, "facets": [field]}
    if filt:
        body["filter"] = filt
    r = meili("POST", f"/indexes/{MEILI_INDEX}/search", body)
    return r.get("facetDistribution", {}).get(field, {})


def sync(docs, filt):
    """Make the index's documents matching `filt` equal `docs`, additively:
    upsert first, then delete only the ids `docs` no longer produces. The index
    is never empty in between."""
    add_docs(docs)
    stale = existing_ids(filt) - {d["id"] for d in docs}
    if stale:
        delete_ids(stale)
    return len(stale)


# ----------------------------------------------------------------------------- BaseX
# Elements live in a default namespace, so match by local-name (*:book etc.).
# Unprefixed attributes are in no namespace, so @code/@number resolve directly.
_SCRIPTURE_XQ = """
let $t := db:open("%(db)s","%(ed)s")/*
let $type := string($t/@type)
let $sid := string($t/@id)
let $abbr := string($t/@abbreviation)
let $lang := string($t/@bcp47Language)
let $region := string($t/@region)
let $dir := string($t/@direction)
return string-join(
  for $b in $t/*:book
    let $code := string($b/@code)
    let $bn := string($b/@name)
    let $co := string($b/@canonicalOrder)
    let $test := string($b/@testament)
    for $c in $b/*:chapter
      let $cn := string($c/@number)
      for $v in $c/*:verse
        return string-join((
          $code, $cn, string($v/@number), $type, $lang, $sid, $abbr, $bn,
          $co, $test, $region, $dir, normalize-space(string($v))
        ), "&#9;")
, "&#10;")
"""


def basex_query(xq):
    body = ('<query xmlns="http://basex.org/rest"><text><![CDATA['
            + xq + ']]></text></query>').encode()
    req = urllib.request.Request(BASEX_URL, data=body, method="POST")
    auth = base64.b64encode(f"{BASEX_USER}:{BASEX_PASS}".encode()).decode()
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Content-Type", "application/xml")
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read().decode()


def _int(s):
    try:
        return int(s)
    except (TypeError, ValueError):
        return 0


def list_editions():
    """Every XML resource in BaseX — the default set of editions to index."""
    out = basex_query(f'string-join(db:list("{BASEX_DB}")[ends-with(., ".xml")], "&#10;")')
    return sorted(line.strip() for line in out.splitlines() if line.strip())


def fetch_edition(ed):
    """The verse documents of ONE edition (one BaseX resource)."""
    docs = []
    out = basex_query(_SCRIPTURE_XQ % {"db": BASEX_DB, "ed": ed})
    for line in out.split("\n"):
        if not line.strip():
            continue
        f = line.split("\t")
        if len(f) < 13:
            continue
        code, cn, vn = f[0], f[1], f[2]
        # Meilisearch document ids allow only [a-zA-Z0-9_-] (no dots), so the
        # primary key uses underscores; `ref` keeps the dotted verse-axis form
        # that comment references already use (JHN.3.16).
        docs.append({
            # id MUST be edition-unique. The same verse exists in every edition,
            # so WITHOUT source_id, KJV/ASV/Finnish/... all collide on one id
            # (e.g. JHN_3_16) and silently overwrite each other — only the
            # last-indexed edition survives. Prefixing source_id keeps them
            # distinct; `ref` (below) stays edition-independent for jumps.
            "id":              re.sub(r"[^A-Za-z0-9_-]", "_",
                                      f"{f[5]}_{code}_{cn}_{vn}"),
            "ref":             f"{code}.{cn}.{vn}",
            "kind":            f[3],
            "language":        f[4],
            "source_id":       f[5],
            "abbreviation":    f[6],
            "book_code":       code,
            "book_name":       f[7],
            "chapter":         _int(cn),
            "verse":           _int(vn),
            "canonical_order": _int(f[8]),
            "testament":       f[9],
            "region":          f[10],
            "direction":       f[11],
            "text":            f[12],
        })
    return docs


# ----------------------------------------------------------------------------- MySQL (TCP / pymysql)
def _mysql_rows(sql):
    import pymysql  # lazy import — only the comment pass needs it
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME, charset="utf8mb4",
                           cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
            return cur.fetchall()
    finally:
        conn.close()


def fetch_comments():
    crows = _mysql_rows(
        "SELECT c.id AS id, c.content AS content, c.user_id AS user_id, "
        "UNIX_TIMESTAMP(c.created_at) AS created_at "
        "FROM comments c "
        "WHERE c.is_public=1 AND c.moderation_status='approved'")
    rrows = _mysql_rows(
        "SELECT r.comment_id AS cid, r.book_code AS book, "
        "r.chapter AS ch, r.verse AS vs "
        "FROM comment_references r JOIN comments c ON c.id=r.comment_id "
        "WHERE r.ref_type='internal' "
        "AND c.is_public=1 AND c.moderation_status='approved'")

    refs = {}
    for r in rrows:
        if r["book"] is not None and r["ch"] is not None and r["vs"] is not None:
            refs.setdefault(r["cid"], []).append((r["book"], r["ch"], r["vs"]))

    docs = []
    for c in crows:
        rlist = refs.get(c["id"], [])
        docs.append({
            "id":          c["id"],
            "kind":        "comment",
            "content":     c.get("content") or "",
            "author_type": "system" if c.get("user_id") == SYSTEM_USER_ID else "user",
            "refs":        [f"{b}.{ch}.{vs}" for (b, ch, vs) in rlist],
            "ref_books":   sorted({b for (b, ch, vs) in rlist}),
            "created_at":  _int(c.get("created_at")),
        })
    return docs


# ----------------------------------------------------------------------------- hash guard
def corpus_hash(docs):
    h = hashlib.sha256()
    for d in docs:
        h.update(d["id"].encode())
        h.update(b"\x1f")
        h.update(d["text"].encode("utf-8", "replace"))
        h.update(b"\x1e")
    return h.hexdigest()


def load_state():
    """{edition resource: corpus hash}. A missing, unreadable or legacy
    (single bare hash) state file reads as empty, so the next run re-checks
    every edition rather than trusting it."""
    try:
        with open(STATE_FILE) as fh:
            d = json.load(fh)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def save_state(state):
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(state, fh, indent=0, sort_keys=True)
    os.replace(tmp, STATE_FILE)


# ----------------------------------------------------------------------------- main
def index_scripture(a, explicit):
    editions = a.editions or EDITIONS or list_editions()
    print(f"[scripture] {len(editions)} edition(s)"
          + ("" if explicit else " (every resource in BaseX)"))
    state = load_state()
    in_index = {} if a.dry_run else facet_counts("source_id")
    seen, total, changed = set(), 0, 0
    t_all = time.time()
    for ed in editions:
        t0 = time.time()
        docs = fetch_edition(ed)
        if not docs:
            print(f"[scripture]   {ed}: no verses — skipped")
            continue
        sid = docs[0]["source_id"]
        seen.add(sid)
        unique = len({d["id"] for d in docs})
        h = corpus_hash(docs)
        total += len(docs)
        fresh = state.get(ed) == h and in_index.get(sid, 0) == unique
        if fresh and not a.force:
            print(f"[scripture]   {ed}: {len(docs)} verses — unchanged, skipped")
            continue
        changed += 1
        if a.dry_run:
            print(f"[scripture]   {ed}: {len(docs)} verses — WOULD index "
                  f"(index holds {in_index.get(sid, 0)}; {time.time() - t0:.1f}s to read)")
            continue
        stale = sync(docs, f'source_id = "{sid}"')
        state[ed] = h
        save_state(state)
        print(f"[scripture]   {ed}: indexed {len(docs)} verses"
              f"{f', removed {stale} stale' if stale else ''}  ({time.time() - t_all:.0f}s elapsed)")
    if a.prune:
        gone = sorted(set(facet_counts("source_id", "kind IN [" + ", ".join(SCRIPTURE_KINDS) + "]")) - seen)
        for sid in gone:
            if a.dry_run:
                print(f"[scripture]   {sid}: WOULD prune (not in BaseX)")
            else:
                delete_by_filter(f'source_id = "{sid}"')
                print(f"[scripture]   {sid}: pruned (not in BaseX)")
    verb = "would index" if a.dry_run else "indexed"
    print(f"[scripture] {verb} {changed} of {len(editions)} edition(s); "
          f"{total} verse docs read in {time.time() - t_all:.0f}s")


def main():
    ap = argparse.ArgumentParser(description="Populate the Meilisearch FT index.")
    ap.add_argument("--scripture", action="store_true", help="scripture slice only")
    ap.add_argument("--comments", action="store_true", help="comment backfill only")
    ap.add_argument("--editions", type=lambda v: [e.strip() for e in v.split(",") if e.strip()],
                    help="comma-separated BaseX resources (default: all)")
    ap.add_argument("--prune", action="store_true",
                    help="delete indexed editions that BaseX no longer has (full runs only)")
    ap.add_argument("--force", action="store_true", help="ignore the per-edition hash guard")
    ap.add_argument("--dry-run", action="store_true",
                    help="read BaseX and report; write nothing, needs no Meilisearch/MySQL")
    a = ap.parse_args()
    do_scr = a.scripture or not (a.scripture or a.comments)
    do_com = a.comments or not (a.scripture or a.comments)
    explicit = bool(a.editions or EDITIONS)
    if a.prune and explicit:
        ap.error("--prune needs a full run: it would delete every edition not named")

    print(f"Meili={MEILI_URL} index={MEILI_INDEX}  BaseX={BASEX_URL}  DB={DB_HOST}:{DB_PORT}"
          + ("  [DRY RUN]" if a.dry_run else ""))
    if not a.dry_run:
        ensure_index()

    if do_scr:
        index_scripture(a, explicit)

    if do_com:
        if a.dry_run:
            print("[comments] dry run — not read")
        else:
            docs = fetch_comments()
            print(f"[comments] {len(docs)} public+approved comment docs")
            stale = sync(docs, "kind = comment")
            print(f"[comments] indexed {len(docs)} docs"
                  f"{f', removed {stale} stale' if stale else ''}")

    if not a.dry_run:
        stats = meili("GET", f"/indexes/{MEILI_INDEX}/stats")
        print(f"[index] total documents now: {stats.get('numberOfDocuments')}")


if __name__ == "__main__":
    main()
