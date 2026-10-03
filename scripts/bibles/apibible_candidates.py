#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
List API.Bible editions that are NOT yet configured in bible-sources.yml, and
(optionally) fetch each candidate's detail record so its copyright / info text
can be checked for a public-domain statement.

Read by the weekly corpus-fetch task (docs/weekly-corpus-fetch.md). Stdlib only.

Candidates are cross-referenced against docs/bible-queue.tsv. A candidate the
queue already covers by a runnable method (ebible/apibible) is filtered out and
reported, because the queued path gets that text for free and spending
API.Bible quota on it pays twice. A candidate matching a *manual* queue row is
the opposite case -- a text nobody could source, now machine-readable -- so it
is promoted to the top of the ranking and flagged. Pass --include-queued to
disable the filtering.

Candidates already recorded in docs/apibible-vetted.tsv are filtered out before
any detail request is spent, so a nightly run costs 1 request on a night when
API.Bible has gained nothing. Pass --include-vetted to disable that.

Quota cost: 1 request for the listing, plus 1 per --details candidate that is
new (not in the corpus, not on the queue, not already vetted).
Every request made is reported on stderr so the caller can ledger it.

Usage:
  python3 scripts/bibles/apibible_candidates.py                  # listing only, 1 request
  python3 scripts/bibles/apibible_candidates.py --details 8      # + detail for the top 8
  python3 scripts/bibles/apibible_candidates.py --langs eng,deu  # restrict languages
  python3 scripts/bibles/apibible_candidates.py --json           # machine-readable

Ranking (highest first): languages already in the corpus first, then an
"old edition" bonus when the name carries a year <= 1930 or an original-language
tag, then alphabetical. The ranking is a hint, not a verdict — the LICENCE check
in the detail record is what decides, and only an explicit public-domain
statement passes.
"""
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCES_YML = REPO / "ingestion/src/main/resources/bible-sources.yml"
LOCAL_PROPS = REPO / "ingestion/src/main/resources/application-local.properties"
QUEUE_TSV = REPO / "docs/bible-queue.tsv"
VETTED_TSV = REPO / "docs/apibible-vetted.tsv"
BASE = "https://rest.api.bible/v1"

ORIGINAL_LANGS = {"grc", "hbo", "heb", "arc", "lat", "syc", "cop", "chu"}
PD_PATTERN = re.compile(r"public\s+domain|no\s+copyright|cc0|creative\s+commons\s+zero", re.I)
YEAR_PATTERN = re.compile(r"\b(1[5-9]\d\d)\b")
API_ID_PATTERN = re.compile(r"\b[0-9a-f]{16}-\d{2}\b")
LANG_PREFIXES = ("ENG", "DEU", "SPA", "FIN", "SWE", "FRA", "ITA", "GRC", "HBO", "HEB",
                 "LAT", "ARC", "SYC", "COP", "CHU", "POR", "NLD", "RUS", "POL", "DAN",
                 "NOR", "CES", "HUN", "TUR", "ARB", "IND", "JPN", "ZHO")
PREFIX_RE = re.compile(r"^(" + "|".join(LANG_PREFIXES) + r")")
# Words too generic to carry a name match on their own.
NAME_STOPWORDS = {"the", "of", "and", "in", "a", "an", "on", "to", "for", "with", "by",
                  "bible", "holy", "scriptures", "version", "translation", "edition",
                  "new", "old", "testament", "text"}

requests_made = 0


def api_key():
    for line in LOCAL_PROPS.read_text(encoding="utf-8").splitlines():
        if line.startswith("apibible.api-key="):
            return line.split("=", 1)[1].strip()
    sys.exit(f"apibible.api-key not found in {LOCAL_PROPS}")


def get(path, key):
    global requests_made
    req = urllib.request.Request(BASE + path, headers={"api-key": key, "accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        requests_made += 1
        print(f"[request {requests_made}] GET {path}", file=sys.stderr)
        return json.load(r)["data"]


def configured():
    """(apiBibleIds, iso639_3 languages, abbreviations, translation names) already in bible-sources.yml."""
    ids, langs, abbrs, names = set(), set(), set(), set()
    for line in SOURCES_YML.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s.startswith("apiBibleId:"):
            ids.add(s.split(":", 1)[1].strip())
        elif s.startswith("iso639_3:"):
            langs.add(s.split(":", 1)[1].strip())
        elif s.startswith("abbreviation:"):
            abbrs.add(s.split(":", 1)[1].strip().upper())
        elif s.startswith("translation:"):
            names.add(norm(s.split(":", 1)[1]))
    return ids, langs, abbrs, names


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def already_local(b, abbrs, names):
    """True when a local-clone edition (no apiBibleId) is plainly the same text."""
    a = b["abbreviation"].upper()
    a_stripped = re.sub(r"^(ENG|DEU|SPA|FIN|SWE|FRA|ITA|GRC|HBO|LAT)", "", a)
    if a in abbrs or (a_stripped and a_stripped in abbrs):
        return True
    return norm(b["name"]) in names


def queue_rows():
    """Parse docs/bible-queue.tsv into dicts. Empty list if the file is missing."""
    rows = []
    if not QUEUE_TSV.exists():
        return rows
    for line in QUEUE_TSV.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        f = line.split("\t")
        if len(f) < 13:
            continue
        rows.append({
            "status": f[0].strip(), "id": f[1].strip(), "abbr": f[2].strip().upper(),
            "translation": f[3].strip(), "method": f[8].strip(),
            "source": f[9].strip(), "notes": f[11],
        })
    return rows


def sig_tokens(s):
    """Significant lowercase tokens of a title, generic words removed."""
    return {t for t in re.split(r"[^a-z0-9]+", s.lower()) if t and t not in NAME_STOPWORDS}


def vetted_rows():
    """docs/apibible-vetted.tsv as {apiBibleId: row}. Empty if the file is missing.

    The nightly run sees a near-identical API.Bible catalogue every night. Without
    this, --details N re-spends a detail request on the same records indefinitely.
    """
    out = {}
    if not VETTED_TSV.exists():
        return out
    for line in VETTED_TSV.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        f = line.split("\t")
        if len(f) < 6:
            continue
        out[f[1].strip()] = {"date": f[0].strip(), "id": f[1].strip(), "abbr": f[2].strip(),
                             "verdict": f[3].strip(), "name": f[4].strip(), "reason": f[5].strip()}
    return out


def queue_match(b, rows):
    """The bible-queue.tsv row describing this candidate, plus why it matched.

    Three tests, strongest first: an API.Bible id written into the row's source
    or notes; the abbreviation with any language prefix stripped, so deutkw
    matches TKW and grcSRGNT matches SRGNT; and a title comparison.

    The title test is the one that needs care, because Bible titles share a lot
    of generic vocabulary. After NAME_STOPWORDS are removed, "Text-Critical
    Greek New Testament" is just {critical, greek}, which is a clean subset of
    Tischendorf's {tischendorf, greek, 8th, critical} -- a false positive that
    would have silently hidden a real candidate. So a title matches only on
    Jaccard overlap >= 0.6, or on a complete subset of at least three
    significant tokens. All rows are scored and the strongest match wins,
    rather than the first one in file order.
    """
    a = b["abbreviation"].upper()
    a_stripped = PREFIX_RE.sub("", a)
    cand_ids = {i for i in (b.get("id"), b.get("dblId")) if i}
    cand_tok = sig_tokens(b["name"])
    best = None  # (strength, similarity, row, why)
    for r in rows:
        blob = r["notes"] + " " + r["source"]
        if any(i in blob for i in cand_ids):
            cand = (3, 1.0, r, "api-id")
        elif r["abbr"] and r["abbr"] != "-" and r["abbr"] in (a, a_stripped):
            cand = (2, 1.0, r, "abbr")
        else:
            q_tok = sig_tokens(r["translation"])
            if len(q_tok) < 2 or len(cand_tok) < 2:
                continue
            inter = len(q_tok & cand_tok)
            if not inter:
                continue
            jac = inter / len(q_tok | cand_tok)
            subset = (q_tok <= cand_tok or cand_tok <= q_tok) and min(len(q_tok), len(cand_tok)) >= 3
            if jac < 0.6 and not subset:
                continue
            cand = (1, jac, r, f"name~{jac:.2f}")
        if best is None or cand[:2] > best[:2]:
            best = cand
    if best is None:
        return None, None
    return best[2], best[3]


def rank(b, corpus_langs):
    lang = b["language"]["id"]
    name = b["name"] + " " + (b.get("description") or "")
    score = 0
    if b.get("_queue_manual"):
        # The runbook's best possible pick: a row whose text was never sourced,
        # now available machine-readable. Converts research into a done rung.
        score += 200
    if lang in corpus_langs:
        score += 100
    if lang in ORIGINAL_LANGS:
        score += 80
    years = [int(y) for y in YEAR_PATTERN.findall(name)]
    if years and min(years) <= 1930:
        score += 50
    if b["type"] != "text":
        score -= 1000
    return (-score, lang, b["name"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--details", type=int, default=0, help="fetch detail records for the top N (1 request each)")
    ap.add_argument("--langs", help="comma-separated iso639_3 filter (default: corpus languages + original languages)")
    ap.add_argument("--all-langs", action="store_true", help="no language filter")
    ap.add_argument("--include-vetted", action="store_true",
                    help="do not filter out candidates already vetted in docs/apibible-vetted.tsv")
    ap.add_argument("--include-queued", action="store_true",
                    help="do not filter out candidates already on docs/bible-queue.tsv")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    key = api_key()
    known_ids, corpus_langs, known_abbrs, known_names = configured()
    qrows = queue_rows()
    vrows = vetted_rows()
    wanted = set(args.langs.split(",")) if args.langs else corpus_langs | ORIGINAL_LANGS

    bibles = get("/bibles", key)
    cands, skipped_local, skipped_queued, promoted, skipped_vetted = [], [], [], [], []
    for b in bibles:
        if b["id"] in known_ids or b["dblId"] in {i.split("-")[0] for i in known_ids}:
            continue
        if already_local(b, known_abbrs, known_names):
            skipped_local.append(f"{b['abbreviation']} ({b['name'][:40]})")
            continue
        if not args.all_langs and b["language"]["id"] not in wanted:
            continue
        vr = vrows.get(b["id"])
        if vr and not args.include_vetted:
            skipped_vetted.append(f"{b['abbreviation']} ({b['name'][:34]}) = {vr['verdict']} "
                                  f"on {vr['date']}")
            continue
        qr, why = queue_match(b, qrows)
        if qr and not args.include_queued:
            # A "manual" row is the opposite of a duplicate: its text was never
            # sourced, and API.Bible having it is exactly the win the runbook
            # wants. Promote those; filter everything else the queue covers.
            if qr["method"] == "manual" and qr["status"] not in ("done", "blocked"):
                b["_queue_manual"] = qr
                promoted.append((b, qr, why))
            else:
                skipped_queued.append(
                    f"{b['abbreviation']} ({b['name'][:34]}) = {qr['status']}/{qr['method']} "
                    f"row {qr['abbr']} [{why}]")
                continue
        cands.append(b)
    cands.sort(key=lambda b: rank(b, corpus_langs))

    out = []
    for i, b in enumerate(cands):
        row = {
            "queueManualRow": b["_queue_manual"]["id"] if b.get("_queue_manual") else None,
            "id": b["id"], "abbreviation": b["abbreviation"], "name": b["name"],
            "lang": b["language"]["id"], "langName": b["language"]["name"],
            "direction": b["language"]["scriptDirection"].lower(),
            "description": b.get("description") or "",
        }
        if i < args.details:
            d = get(f"/bibles/{b['id']}", key)
            text = " ".join(str(d.get(k) or "") for k in ("copyright", "info"))
            row["copyright"] = (d.get("copyright") or "").strip()
            row["info"] = re.sub(r"<[^>]+>", " ", d.get("info") or "").strip()[:600]
            row["publicDomainStated"] = bool(PD_PATTERN.search(text))
        out.append(row)

    if args.json:
        json.dump({"requestsMade": requests_made, "candidates": out,
                   "skippedAlreadyQueued": skipped_queued,
                   "skippedAlreadyVetted": skipped_vetted,
                   "matchedManualQueueRow": [
                       {"id": b["id"], "abbreviation": b["abbreviation"], "name": b["name"],
                        "queueRow": qr["id"], "queueAbbr": qr["abbr"], "matchedBy": why}
                       for b, qr, why in promoted]},
                  sys.stdout, indent=1, ensure_ascii=False)
        print()
        return

    print(f"{len(out)} candidates not yet in bible-sources.yml (languages: {'all' if args.all_langs else ','.join(sorted(wanted))})")
    if skipped_local:
        print(f"(skipped {len(skipped_local)} that match a local-clone edition by abbreviation/name: {', '.join(skipped_local)})")
    if skipped_queued:
        print(f"(skipped {len(skipped_queued)} already on docs/bible-queue.tsv \u2014 the queued path "
              f"already covers them, so spending API.Bible quota here would pay twice:")
        for line in skipped_queued:
            print(f"   - {line}")
        print("   pass --include-queued to see them anyway)")
    if skipped_vetted:
        print(f"(skipped {len(skipped_vetted)} already vetted in docs/apibible-vetted.tsv \u2014 "
              f"no detail request spent re-deriving a verdict already reached:")
        for line in skipped_vetted:
            print(f"   - {line}")
        print("   delete a row there to force a re-vet, or pass --include-vetted)")
    if promoted:
        print(f"** {len(promoted)} candidate(s) match a MANUAL queue row \u2014 the runbook's best possible "
              f"pick, a text that was never sourced and now has a machine-readable edition:")
        for b, qr, why in promoted:
            print(f"   - {b['abbreviation']} ({b['name'][:40]}) -> queue row {qr['abbr']} "
                  f"{qr['id']} [{why}]")
    for r in out:
        flag = ""
        if "publicDomainStated" in r:
            flag = "  PD:YES" if r["publicDomainStated"] else "  PD:no-statement"
        mark = "  << MANUAL QUEUE ROW" if r.get("queueManualRow") else ""
        print(f"{r['id']:22} {r['abbreviation']:12} {r['lang']:4} {r['name'][:60]}{flag}{mark}")
        if r.get("copyright"):
            print(f"{'':40} copyright: {r['copyright'][:200]}")
    if not out:
        print("\nNOTHING NEW ON API.BIBLE. Every candidate is already in the corpus, on the "
              "queue, or vetted and rejected.\nSkip Part A: there is no Bible to ingest tonight "
              "and no detail quota was spent.")
    elif promoted:
        print(f"\n{len(out)} candidate(s) need a look, and {len(promoted)} match a MANUAL queue "
              f"row \u2014 take that one first if its licence holds up.")
    else:
        print(f"\n{len(out)} candidate(s) never vetted before. Check the licence strings above "
              f"against runbook rule 5, then record each verdict in docs/apibible-vetted.tsv "
              f"whether or not it is ingested \u2014 that is what keeps tomorrow night cheap.")
    print(f"\nrequests made: {requests_made}", file=sys.stderr)


if __name__ == "__main__":
    main()
