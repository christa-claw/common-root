#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
List API.Bible editions that are NOT yet configured in bible-sources.yml, and
(optionally) fetch each candidate's detail record so its copyright / info text
can be checked for a public-domain statement.

Read by the weekly corpus-fetch task (docs/weekly-corpus-fetch.md). Stdlib only.

Quota cost: 1 request for the listing, plus 1 per --details candidate.
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
BASE = "https://rest.api.bible/v1"

ORIGINAL_LANGS = {"grc", "hbo", "heb", "arc", "lat", "syc", "cop", "chu"}
PD_PATTERN = re.compile(r"public\s+domain|no\s+copyright|cc0|creative\s+commons\s+zero", re.I)
YEAR_PATTERN = re.compile(r"\b(1[5-9]\d\d)\b")

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


def rank(b, corpus_langs):
    lang = b["language"]["id"]
    name = b["name"] + " " + (b.get("description") or "")
    score = 0
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
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    key = api_key()
    known_ids, corpus_langs, known_abbrs, known_names = configured()
    wanted = set(args.langs.split(",")) if args.langs else corpus_langs | ORIGINAL_LANGS

    bibles = get("/bibles", key)
    cands, skipped_local = [], []
    for b in bibles:
        if b["id"] in known_ids or b["dblId"] in {i.split("-")[0] for i in known_ids}:
            continue
        if already_local(b, known_abbrs, known_names):
            skipped_local.append(f"{b['abbreviation']} ({b['name'][:40]})")
            continue
        if not args.all_langs and b["language"]["id"] not in wanted:
            continue
        cands.append(b)
    cands.sort(key=lambda b: rank(b, corpus_langs))

    out = []
    for i, b in enumerate(cands):
        row = {
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
        json.dump({"requestsMade": requests_made, "candidates": out}, sys.stdout, indent=1, ensure_ascii=False)
        print()
        return

    print(f"{len(out)} candidates not yet in bible-sources.yml (languages: {'all' if args.all_langs else ','.join(sorted(wanted))})")
    if skipped_local:
        print(f"(skipped {len(skipped_local)} that match a local-clone edition by abbreviation/name: {', '.join(skipped_local)})")
    for r in out:
        flag = ""
        if "publicDomainStated" in r:
            flag = "  PD:YES" if r["publicDomainStated"] else "  PD:no-statement"
        print(f"{r['id']:22} {r['abbreviation']:12} {r['lang']:4} {r['name'][:60]}{flag}")
        if r.get("copyright"):
            print(f"{'':40} copyright: {r['copyright'][:200]}")
    print(f"\nrequests made: {requests_made}", file=sys.stderr)


if __name__ == "__main__":
    main()
