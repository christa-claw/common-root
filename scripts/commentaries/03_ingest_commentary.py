#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
03_ingest_commentary.py — convert fetched commentaries (sources/commentaries/)
into religious-text.xsd v1.5 documents and load them into the commentary
STAGING database. Nothing here writes to the production 'religioustext'
database; promotion is a separate, deliberate step (docs/commentary-pipeline.md).

  # one work (every catalogued language of it)
  python3 scripts/commentaries/03_ingest_commentary.py --slug rashi-on-torah-rosenbaum-silbermann-1934
  # everything in docs/commentary-catalog.tsv
  python3 scripts/commentaries/03_ingest_commentary.py --all
  # build + validate only, keep the XML for inspection
  python3 scripts/commentaries/03_ingest_commentary.py --all --dry-run --out /tmp/commentaries
  # also refresh the Bibles the staging reader shows next to the commentaries
  python3 scripts/commentaries/03_ingest_commentary.py --peers

Every document is validated against schema/religious-text.xsd before it is
sent; an invalid one is reported and skipped, never loaded. A document whose
licence does not say Public Domain or CC0 is refused. Exit status is 1 when
anything failed, so the nightly task can tell.

BaseX: --basex/--user/--password, or BASEX_URL/BASEX_USER/BASEX_PASSWORD.
Writes go through curl (CLAUDE.md: never RestTemplate).
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import commentary_lib as L          # noqa: E402
import read_ccel                    # noqa: E402
import read_sefaria                 # noqa: E402
import read_sword                   # noqa: E402

CATALOG = L.REPO / "docs" / "commentary-catalog.tsv"
STAGING = "commentary_test"
PRODUCTION = "religioustext"
# Bibles copied into staging so a commentary can be read beside a text:
# English, the Jewish Publication Society 1917, the Hebrew (WLC), the Latin Vulgate.
PEERS = ["bible-kjv-1611", "bible-jps1917-1917", "bible-he-wlc", "bible-la-clementina-1598"]
ISO3 = {"en": "eng", "he": "heb", "la": "lat", "nl": "nld", "de": "deu", "fr": "fra", "grc": "grc", "ar": "arb"}
RTL = {"he", "ar", "arc", "syc"}
ALLOWED = ("public domain", "cc0")


def load_catalog(path: Path = CATALOG) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as f:
        lines = [ln for ln in f if ln.strip() and not ln.startswith("#")]
    header = ["slug", "source", "lang", "id", "abbr", "title", "author", "year", "region"]
    for rec in csv.reader(lines, delimiter="\t", quoting=csv.QUOTE_NONE):
        rec += [""] * (len(header) - len(rec))
        rows.append(dict(zip(header, (x.strip() for x in rec))))
    return rows


def source_url(row: dict, facts: dict) -> str:
    if row["source"] == "crosswire":
        return f"https://www.crosswire.org/sword/modules/ModInfo.jsp?modName={facts.get('module', '')}"
    if row["source"] == "ccel":
        return "https://www.ccel.org/"
    srcs = [s for s in facts.get("sources", []) if s.startswith("http")]
    return srcs[0] if srcs else "https://www.sefaria.org/"


_cache: dict = {}


def build(row: dict, sources: Path) -> tuple[L.Commentary, dict]:
    key = (row["source"], row["slug"], row["lang"])
    work = sources / row["source"] / row["slug"]
    if not work.is_dir():
        raise FileNotFoundError(f"{work} does not exist (has the fetch run?)")
    if row["source"] == "sefaria":
        doc, facts = read_sefaria.read(work, row["lang"])
    elif row["source"] == "crosswire":
        doc, facts = read_sword.read(work)
    elif row["source"] == "ccel":
        doc, facts = read_ccel.read(work)
    else:
        raise ValueError(f"unknown source {row['source']!r}")
    _cache[key] = True
    doc.id, doc.abbr, doc.title = row["id"], row["abbr"], row["title"]
    doc.lang = row["lang"]
    doc.iso3 = ISO3.get(row["lang"], row["lang"][:3])
    doc.direction = "rtl" if row["lang"] in RTL else "ltr"
    doc.author, doc.year, doc.region = row["author"], row["year"], row["region"]
    lic = [x for x in facts.get("licences", []) if x]
    doc.license = "; ".join(lic)
    doc.source = source_url(row, facts)
    return doc, facts


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--slug", action="append", default=[], help="work folder name (repeatable)")
    ap.add_argument("--id", action="append", default=[], help="document id from the catalog (repeatable)")
    ap.add_argument("--all", action="store_true", help="every catalog row")
    ap.add_argument("--peers", action="store_true", help="copy the peer Bibles into the staging database")
    ap.add_argument("--database", default=STAGING)
    ap.add_argument("--sources", default=str(L.REPO / "sources" / "commentaries"))
    ap.add_argument("--catalog", default=str(CATALOG))
    ap.add_argument("--out", help="also write each document's XML into this directory")
    ap.add_argument("--dry-run", action="store_true", help="build and validate, do not load")
    ap.add_argument("--basex", default=os.environ.get("BASEX_URL", "http://localhost:8984/rest"))
    ap.add_argument("--user", default=os.environ.get("BASEX_USER", "admin"))
    ap.add_argument("--password", default=os.environ.get("BASEX_PASSWORD", "admin"))
    a = ap.parse_args()

    if a.database == PRODUCTION:
        print(f"refusing to write to {PRODUCTION!r}: this script loads the staging database only "
              "(see docs/commentary-pipeline.md, 'Promotion')", file=sys.stderr)
        return 2

    rows = load_catalog(Path(a.catalog))
    ids = [r["id"] for r in rows]
    abbrs = [r["abbr"].lower() for r in rows]
    dup = {x for x in ids if ids.count(x) > 1} | {x for x in abbrs if abbrs.count(x) > 1}
    if dup:
        print(f"catalog has duplicate ids/abbreviations: {sorted(dup)}", file=sys.stderr)
        return 2
    chosen = rows if a.all else [r for r in rows if r["slug"] in a.slug or r["id"] in a.id]
    missing = [s for s in a.slug if s not in {r["slug"] for r in rows}]
    if missing:
        print(f"not in {a.catalog}: {missing} — add a catalog row first", file=sys.stderr)
        return 2
    if not chosen and not a.peers:
        ap.print_usage()
        return 2

    bx = L.BaseX(a.basex, a.user, a.password)
    if not a.dry_run:
        bx.ensure_db(a.database)
        # An abbreviation is a reader source token; it must not shadow an edition.
        taken = bx.abbreviations(PRODUCTION)
        clash = [(r["abbr"], taken[r["abbr"].lower()]) for r in chosen if r["abbr"].lower() in taken]
        if clash:
            print(f"abbreviation already used in {PRODUCTION}: {clash}", file=sys.stderr)
            return 2

    failed = 0
    if a.peers and not a.dry_run:
        for pid in PEERS:
            ok = bx.copy_doc(PRODUCTION, a.database, pid)
            print(f"peer {pid}: {'copied' if ok else 'NOT FOUND in ' + PRODUCTION}")

    for row in chosen:
        t0 = time.time()
        try:
            doc, facts = build(row, Path(a.sources))
        except Exception as e:                       # noqa: BLE001 — report and go on
            print(f"FAIL {row['id']}: {e}")
            failed += 1
            continue
        if not any(ok in doc.license.lower() for ok in ALLOWED):
            print(f"REFUSED {row['id']}: licence {doc.license!r} is not Public Domain / CC0")
            failed += 1
            continue
        v11n = facts.get("versification", "kjv")
        try:
            xml = L.to_xml(doc, v11n)
        except ValueError as e:
            print(f"FAIL {row['id']}: {e}")
            failed += 1
            continue
        err = L.validate(xml)
        books, verses, comments, intros = doc.counts()
        line = (f"{row['id']} ({row['abbr']}): {books} books, {verses} verses, {comments} comments, "
                f"{intros} intros, {len(xml) / 1e6:.1f} MB, licence {doc.license!r}")
        if doc.dropped:
            line += f", dropped {doc.dropped}"
        if a.out:
            Path(a.out).mkdir(parents=True, exist_ok=True)
            (Path(a.out) / f"{row['id']}.xml").write_bytes(xml)
        if err:
            print(f"INVALID {line}\n  {err}")
            failed += 1
            continue
        if a.dry_run:
            print(f"ok (dry run) {line} [{time.time() - t0:.1f}s]")
            continue
        try:
            bx.put(a.database, row["id"], xml)
        except RuntimeError as e:
            print(f"FAIL {row['id']}: {e}")
            failed += 1
            continue
        print(f"loaded {line} -> {a.database} [{time.time() - t0:.1f}s]")
    print(f"{len(chosen) - failed} of {len(chosen)} documents ok" + (f", {failed} failed" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
