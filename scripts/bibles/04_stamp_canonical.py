#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
stamp_canonical.py — stamps @globalCanonicalSeq on every verse of a Bible
edition in BaseX, in canonical reading order: book @canonicalOrder, then
chapter @number, then verse document order, as a dense 1..N counter.

WHY THIS EXISTS
  Editions imported via scripts/bibles/03_import_json_bible.py are written WITHOUT a
  @globalCanonicalSeq — that script only sets @number/@bookName/@chapterNumber.
  The reader's verse-window loader orders by @globalCanonicalSeq (see
  TextQueryService.seqLet / verseWindowFrom). An unstamped verse hits seqLet's
  else branch — sum((10000000, xs:integer(@globalCanonicalSeq))) — and because
  the attribute is ABSENT, xs:integer(()) is empty and sum() ignores it, so
  EVERY unstamped verse collapses to a flat 10000000. They all tie, and
  "open at/after 10000000" lands on whatever verse the tie-break surfaces — the
  GNV-opens-at-1-Peter bug. The Java ingestion path (KJV/NIV/ASV/WEB/DRA/RVR09)
  stamps during ingestion; the JSON-import path never did and had no stamper.

  This is the canonical-order analogue of stamp_chronological.py. Run it after
  import_json_bible.py + consolidate_basex.py for any JSON-imported edition.

  The seq is dense PER EDITION and need not match other editions' values: every
  reader open/sync resolves against the column's own edition (seqForRef /
  seqForBookChapter), so only within-edition monotonicity matters. Idempotent —
  existing @globalCanonicalSeq attrs are cleared first, so a re-run is safe.

USAGE
  python3 scripts/bibles/04_stamp_canonical.py --id bible-gnv-1599          # one edition
  python3 scripts/bibles/04_stamp_canonical.py --all-unstamped              # every gap
  python3 scripts/bibles/04_stamp_canonical.py --all-unstamped --dry-run    # list, change nothing

  Env (BaseX target): BASEX_URL (default http://localhost:8984/rest),
  BASEX_USER (admin), BASEX_PASS (admin), BASEX_DB (religioustext).

No third-party deps — stdlib urllib only, so it runs as-is in python:3.12-slim
(the same one-off-container pattern as the prod reindex).
"""
import argparse, base64, os, sys, urllib.error, urllib.parse, urllib.request

BASEX_URL = os.environ.get("BASEX_URL", "http://localhost:8984/rest").rstrip("/")
USER      = os.environ.get("BASEX_USER", "admin")
PASS      = os.environ.get("BASEX_PASS", "admin")
DB        = os.environ.get("BASEX_DB", "religioustext")
NS        = "http://religioustext.org/schema/1.0"
AUTH      = base64.b64encode(f"{USER}:{PASS}".encode()).decode()


def _req(url, data=None, method="GET", ctype=None):
    headers = {"Authorization": f"Basic {AUTH}"}
    if ctype:
        headers["Content-Type"] = ctype
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req) as r:
            return r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {e.code}: {body[:400]}") from e


def query(q):
    """Read-only XQuery via REST GET ?query=."""
    url = f"{BASEX_URL}/{DB}?{urllib.parse.urlencode({'query': q})}"
    return _req(url).strip()


def _xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;")
             .replace(">", "&gt;").replace('"', "&quot;"))


def update(q):
    """XQuery Update via REST POST of a <query> wrapper (no curl dependency)."""
    body = ('<query xmlns="http://basex.org/rest"><text>'
            + _xml_escape(q) + "</text></query>").encode("utf-8")
    _req(f"{BASEX_URL}/{DB}", data=body, method="POST", ctype="application/xml")


def _int(s, default=0):
    try:
        return int(s)
    except (TypeError, ValueError):
        return default


def bible_editions():
    out = query(
        f"declare namespace rt='{NS}';"
        f" for $t in db:open('{DB}')//rt:text[@type='bible']"
        f" return string($t/@id)")
    return [l.strip() for l in out.splitlines() if l.strip()]


def total_count(eid):
    return _int(query(
        f"declare namespace rt='{NS}';"
        f" count(db:open('{DB}','{eid}.xml')//rt:verse)"))


def unstamped_count(eid):
    return _int(query(
        f"declare namespace rt='{NS}';"
        f" count(db:open('{DB}','{eid}.xml')//rt:verse"
        f"[not(@globalCanonicalSeq) or string(@globalCanonicalSeq)=''])"))


def seq_range(eid):
    return query(
        f"declare namespace rt='{NS}';"
        f" let $s := db:open('{DB}','{eid}.xml')//rt:verse/@globalCanonicalSeq"
        f" return if (empty($s)) then 'none' else"
        f" string(min(for $x in $s return xs:integer($x)))||'..'||"
        f" string(max(for $x in $s return xs:integer($x)))")


def stamp(eid):
    doc = f"{eid}.xml"
    # 1) clear any existing stamps so a re-run is idempotent (no-op when none).
    update(
        f"declare namespace rt='{NS}';"
        f" for $v in db:open('{DB}','{doc}')//rt:verse[@globalCanonicalSeq]"
        f" return delete node $v/@globalCanonicalSeq")
    # 2) dense walk: books by @canonicalOrder, chapters by @number, verses in
    #    document order (= ascending @number as written by import_json_bible.py).
    #    `for $v at $pos` numbers the flattened stream 1..N — the query text stays
    #    tiny; BaseX generates the N inserts internally.
    update(
        f"declare namespace rt='{NS}';"
        f" let $vs :="
        f"   for $b in db:open('{DB}','{doc}')/rt:text/rt:book"
        f"   order by xs:integer($b/@canonicalOrder)"
        f"   return for $c in $b/rt:chapter"
        f"          order by xs:integer($c/@number)"
        f"          return $c/rt:verse"
        f" for $v at $pos in $vs"
        f" return insert node attribute globalCanonicalSeq {{ $pos }} into $v")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--id", help="stamp one edition id (e.g. bible-gnv-1599)")
    ap.add_argument("--all-unstamped", action="store_true",
                    help="stamp every bible edition with any unstamped verse")
    ap.add_argument("--dry-run", action="store_true",
                    help="list targets and counts; change nothing")
    a = ap.parse_args()

    if not a.id and not a.all_unstamped:
        sys.exit("Pass --id <edition> or --all-unstamped.")

    if a.id:
        targets = [a.id]
    else:
        targets = [e for e in bible_editions() if unstamped_count(e) > 0]

    if not targets:
        print("Nothing to stamp — every bible edition already has @globalCanonicalSeq.")
        return

    print(("DRY RUN — " if a.dry_run else "") + f"target editions: {targets}")
    for eid in targets:
        tot, uns = total_count(eid), unstamped_count(eid)
        print(f"  {eid}: total={tot} unstamped-before={uns}")
        if a.dry_run:
            continue
        try:
            stamp(eid)
        except Exception as e:
            print(f"    ERROR: {e}")
            continue
        print(f"    -> unstamped-after={unstamped_count(eid)} seq-range={seq_range(eid)}")

    if a.dry_run:
        print("Dry run only — nothing written.")
    else:
        print("Done. The reader reads BaseX live, so no reindex or app redeploy "
              "is needed — reopen the affected edition to verify.")


if __name__ == "__main__":
    main()
