#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
patch_lineage.py — stamp @basedOn (antecedent-translation lineage) onto the
corpus editions. The reader's rung controls walk these chains to show, beneath
each verse, the translations an edition stands on (the maintainer's ladder idea,
2026-07-24 — the Qur'an-companion mechanism generalised to lineage).

Keyed by ABBREVIATION and resolved to doc ids at run time, so it never guesses
ids. Idempotent: delete-then-insert of the whole attribute each run.

Curation is deliberately conservative — only well-attested revision/derivation
lines, main line first. Editions whose antecedents aren't in the corpus
(Tyndale, Bishops', Reina 1602, Biblia 1642 …) simply root where the corpus
runs out. VUL←WLC is a stand-in: Jerome translated the OT from Hebrew, and the
WLC is the corpus's Masoretic witness (the codex itself postdates him).

Usage:
    python3 patch_lineage.py --list    # audit current @basedOn values
    python3 patch_lineage.py           # apply LINEAGE (idempotent)

Stdlib only; reuses stamp_chronological's REST helpers. Restart/reload the app
afterwards to refresh the source catalogue.
"""

import sys
import importlib
sc = importlib.import_module("05_stamp_chronological")

# Original-language witnesses: marked @original='true' so the reader can give
# EVERY Bible edition an original-language floor — a final lineage generation
# of the Hebrew/Greek — even when no intermediate chain is recorded. For
# critical-text translations (NIV …) the TR is a witness of "the Greek", not
# their actual base text; the About page carries that caveat.
ORIGINALS = ["WLC", "TR"]

# abbreviation -> publication year shown on rung chips (and available to any
# future UI). Manuscript witnesses carry their codex/edition dates. Editions
# absent here simply render without a year — add as verified, don't guess.
YEARS = {
    "WEB":    "2000",  "ASV":  "1901", "RV":     "1885", "KJV": "1611",
    "GNV":    "1599",  "TR":   "1550", "WLC":    "1008", "VUL": "405",
    "FB1776": "1776",  "KR3338": "1933", "DRA":  "1899", "NIV": "2011",
    "FB1642": "1642",  "LUT1545": "1545", "AGR1548": "1548",
    "RVR09":  "1909",  "SYN":  "1876", "CUV":    "1919", "SVD": "1865",
    "IRVHIN": "2019",  "YTC":  "2023", "NASB": "2020", "LUT1912": "1912",
}

# abbreviation -> ordered parent abbreviations (main line first)
LINEAGE = {
    "WEB":    ["ASV"],             # WEB is a revision of the ASV
    "ASV":    ["RV"],              # American edition of the Revised Version
    "RV":     ["KJV"],             # the RV is formally a revision of the KJV
    "KJV":    ["GNV", "TR", "WLC"],  # Geneva's influence + TR (NT) + Masoretic (OT)
    "GNV":    ["TR", "WLC"],       # Geneva from the Greek and Hebrew
    "DRA":    ["VUL"],             # Douay-Rheims translated from the Vulgate
    # VUL deliberately has NO recorded parent: Jerome's Hebrew/Greek sources
    # aren't in the corpus, and the WLC is only a Masoretic WITNESS — the
    # original-language floor supplies it, clearly marked as such.
    "KR3338": ["FB1776"],          # Finnish church-Bible line
    "FB1776": ["FB1642"],          # 1776 is a revision of the 1642 Biblia
    "FB1642": ["AGR1548", "LUT1545"],  # 1642: Agricola's Finnish revised (main
                                       # line), Luther open on the table
    # Agricola triangulated: Luther's German, Erasmus' Greek (TR witness) and
    # the Vulgate (the Swedish NT too, but that's not in the corpus). Decided
    # with the maintainer 2026-08-10 — the 08-05 plan deferred Luther only because
    # the corpus didn't hold LUT1545 yet.
    "AGR1548": ["LUT1545", "TR", "VUL"],
    "LUT1545": ["TR", "WLC"],      # Luther: Erasmus' Greek (TR witness) + Hebrew
    "LUT1912": ["LUT1545"],        # 1912 is a revision of Luther's 1545 text
}


def rows():
    """abbr|id for every corpus text."""
    result = sc.xquery(
        f"declare namespace rt='{sc.NS}';"
        f" for $t in db:open('religioustext')//rt:text"
        f" order by string($t/@abbreviation)"
        f" return concat(string($t/@abbreviation), '|', string($t/@id))"
    )
    out = {}
    for line in result.splitlines():
        line = line.strip()
        if not line or "|" not in line:
            continue
        abbr, doc_id = line.split("|", 1)
        out[abbr] = doc_id
    return out


def list_current():
    result = sc.xquery(
        f"declare namespace rt='{sc.NS}';"
        f" for $t in db:open('religioustext')//rt:text[@basedOn]"
        f" order by string($t/@abbreviation)"
        f" return concat(string($t/@abbreviation), ' -> ', string($t/@basedOn))"
    )
    print("Current @basedOn values:\n")
    print("  (none)" if not result.strip() else "\n".join("  " + l for l in result.splitlines()))


def patch(doc_id, based_on_ids):
    ids = ",".join(based_on_ids)
    sc.xquery_update(
        f"declare namespace rt='{sc.NS}';"
        f" let $t := db:open('religioustext','{doc_id}.xml')/rt:text"
        f" return (delete node $t/@basedOn,"
        f" insert node attribute basedOn {{'{ids}'}} into $t)"
    )


def main():
    if "--list" in sys.argv:
        list_current()
        return
    by_abbr = rows()
    for abbr, parents in LINEAGE.items():
        doc_id = by_abbr.get(abbr)
        if not doc_id:
            print(f"  SKIP {abbr}: not in corpus")
            continue
        parent_ids, missing = [], []
        for p in parents:
            (parent_ids if p in by_abbr else missing).append(
                by_abbr.get(p, p))
        if missing:
            print(f"  WARN {abbr}: parents not in corpus, skipped: {missing}")
        if not parent_ids:
            print(f"  SKIP {abbr}: no parents resolvable")
            continue
        try:
            patch(doc_id, parent_ids)
            print(f"  {abbr} ({doc_id}) -> {parent_ids}")
        except Exception as e:
            print(f"  ERROR {abbr}: {e}")
    # Converge: clear @basedOn from any edition NOT in the curated map, so a
    # demoted entry (VUL after 2026-07-24) loses its stale attribute on re-run.
    stale = sc.xquery(
        f"declare namespace rt='{sc.NS}';"
        f" for $t in db:open('religioustext')//rt:text[@basedOn]"
        f" return concat(string($t/@abbreviation), '|', string($t/@id))"
    )
    for line in stale.splitlines():
        line = line.strip()
        if not line or "|" not in line:
            continue
        abbr, doc_id = line.split("|", 1)
        if abbr in LINEAGE:
            continue
        try:
            sc.xquery_update(
                f"declare namespace rt='{sc.NS}';"
                f" let $t := db:open('religioustext','{doc_id}.xml')/rt:text"
                f" return delete node $t/@basedOn"
            )
            print(f"  cleared stale @basedOn on {abbr} ({doc_id})")
        except Exception as e:
            print(f"  ERROR clearing {abbr}: {e}")
    for abbr, year in YEARS.items():
        doc_id = by_abbr.get(abbr)
        if not doc_id:
            print(f"  SKIP year {abbr}: not in corpus")
            continue
        try:
            sc.xquery_update(
                f"declare namespace rt='{sc.NS}';"
                f" let $t := db:open('religioustext','{doc_id}.xml')/rt:text"
                f" return (delete node $t/@year,"
                f" insert node attribute year {{'{year}'}} into $t)"
            )
            print(f"  year: {abbr} = {year}")
        except Exception as e:
            print(f"  ERROR year {abbr}: {e}")
    for abbr in ORIGINALS:
        doc_id = by_abbr.get(abbr)
        if not doc_id:
            print(f"  SKIP original {abbr}: not in corpus")
            continue
        try:
            sc.xquery_update(
                f"declare namespace rt='{sc.NS}';"
                f" let $t := db:open('religioustext','{doc_id}.xml')/rt:text"
                f" return (delete node $t/@original,"
                f" insert node attribute original {{'true'}} into $t)"
            )
            print(f"  original: {abbr} ({doc_id})")
        except Exception as e:
            print(f"  ERROR original {abbr}: {e}")
    print("\nDone. Restart/reload the app to refresh the source catalogue.")


if __name__ == "__main__":
    main()
