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
    "JFB":    "2026",  "LXX": "1851", "TYN": "1534", "LXXE": "1851",
}

# abbreviation -> ordered parent abbreviations. Two rules, in this order:
#   1. REAL SOURCES FIRST, main line first among them — the text the translators
#      actually worked from leads.
#   2. ORIGINAL-LANGUAGE WITNESSES LAST. A witness (@original='true' — TR, WLC)
#      is the floor the ladder rests on, not a book anyone held; it belongs at
#      the bottom of the group, below every genuine source. AGR1548 broke this
#      until 2026-09-25 and rendered Greek above the Vulgate that Agricola
#      actually used.
LINEAGE = {
    "WEB":    ["ASV"],             # WEB is a revision of the ASV
    "ASV":    ["RV"],              # American edition of the Revised Version
    "RV":     ["KJV"],             # the RV is formally a revision of the KJV
    # TYN added 2026-09-29, the Tier 1 rung. Something like four fifths of the
    # KJV New Testament's wording is Tyndale's, and the Geneva NT is built on
    # him too, so one text puts a rung under both — and therefore under RV, ASV
    # and WEB above them. GNV leads for the KJV because it is the nearer
    # generation and a book the translators had open; TYN follows as the deeper
    # substrate. Witnesses last, as everywhere else.
    "KJV":    ["GNV", "TYN", "TR", "WLC"],
    "GNV":    ["TYN", "TR", "WLC"],
    # TYN itself deliberately gets NO entry: Tyndale worked from Erasmus' Greek
    # and Luther's 1522 German NT, and neither is in the corpus as a text he
    # could have held — TR is a 1550 edition, LUT1545 a 1545 one, both
    # postdating 1534. The originals floor supplies the Greek, which is the
    # honest stopping point. This is the case the module docstring already
    # describes: an edition whose antecedents are absent roots where the corpus
    # runs out.
    "DRA":    ["VUL"],             # Douay-Rheims translated from the Vulgate
    # VUL -> LXX (added 2026-09-29, with the maintainer). This REVERSES the
    # earlier decision that VUL should have no recorded parent. That decision
    # was made when the corpus held no Greek Old Testament, so the only
    # candidate rung was the WLC — a Masoretic WITNESS, and the wrong one:
    # Jerome went back to the Hebraica veritas for the protocanon precisely to
    # get away from the Greek. The Brenton LXX changes the position, because
    # part of the Vulgate is genuinely Greek-derived and always was: the
    # Psalter carried in the Vulgate is the Gallicanum, made from the Greek of
    # the Hexapla and never revised to Jerome's own iuxta Hebraeos version, and
    # Wisdom, Sirach, Baruch and 1-2 Maccabees were left standing as Old Latin
    # translated from the Greek. So the LXX is a real source for a real part of
    # this book, not a witness. The Hebrew still appears beneath it, supplied by
    # the originals floor, which is the honest shape: Greek for the parts that
    # came through Greek, Hebrew underneath for the parts that did not.
    "VUL":    ["LXX"],
    # LXXE -> LXX (2026-09-29). Brenton printed the Greek and his own English
    # facing translation in the same 1851 volume, so this is about as literal a
    # parent as the corpus contains: one man, one book, the English made from
    # the Greek on the opposite page. Not a witness — a source.
    "LXXE":   ["LXX"],
    # The Clementine is a recension of Jerome's text, not a new translation, so
    # it reaches the Greek the same way VUL does — through VUL, one rung down.
    "VULC":   ["VUL"],
    # SYN -> LXX. The 1876 Synodal Old Testament was made from the Masoretic
    # text but follows the Septuagint at a great many points, and it stands in
    # the Slavonic tradition (Ostrog 1581, Elizabeth 1751), which is wholly
    # LXX-derived — the Elizabeth Bible is still queued as the intermediate
    # rung, and when it lands SYN should point at it instead and let ELIZ carry
    # the LXX. The Hebrew arrives via the originals floor. The LXX is OT-only,
    # so content-aware stepping simply skips this rung under the New Testament,
    # where the Synodal text descends from the Byzantine line instead (BYZ1904
    # is in the corpus and is the obvious next rung to add here).
    "SYN":    ["LXX"],
    # LXX itself deliberately has NO recorded parent, for the reason VUL used to
    # have none: its Hebrew Vorlage is not in the corpus and was demonstrably
    # not the Masoretic text — that is the whole scholarly interest of the
    # thing. The WLC is a witness a millennium younger, so it belongs under the
    # LXX as the originals floor already puts it, and nowhere else.
    # The 1890 Shanghai missionary conference set the English Revised Version as
    # the base text for the Union Version, so the RV is a real dependency rather
    # than an influence — and it is already in the corpus, which is why this rung
    # costs one line and no ingest. It turns /read/zh's bare originals floor into
    # a full chain: CUV -> RV -> KJV -> GNV -> the Greek and Hebrew beneath.
    # RV alone: the committees worked from the originals too, but the rungs below
    # RV already carry the TR and WLC witnesses, so naming them here would just
    # duplicate the step down.
    "CUV":    ["RV"],              # Union Version 1919, on the English RV
    "KR3338": ["FB1776"],          # Finnish church-Bible line
    "FB1776": ["FB1642"],          # 1776 is a revision of the 1642 Biblia
    "FB1642": ["AGR1548", "LUT1545"],  # 1642: Agricola's Finnish revised (main
                                       # line), Luther open on the table
    # Agricola triangulated: Luther's German, the Vulgate, and Erasmus' Greek
    # (the Swedish NT too, but that's not in the corpus). Decided with the
    # maintainer 2026-08-10 — the 08-05 plan deferred Luther only because the
    # corpus didn't hold LUT1545 yet.
    #
    # VUL BEFORE TR (fixed 2026-09-25). The Vulgate is a book Agricola had on
    # the table; TR is a witness standing in for "the Greek Erasmus printed".
    # The dates say so themselves: the TR edition is 1550 and Agricola is 1548,
    # so the rung cannot be a literal source — a parent postdating its child is
    # exactly what marks a witness. Putting it last also restores the pattern
    # every other entry follows (see KJV: GNV, then TR and WLC).
    "AGR1548": ["LUT1545", "VUL", "TR"],
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
