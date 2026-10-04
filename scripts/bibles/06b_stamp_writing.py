#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
06b_stamp_writing.py — stamps globalWritingSeq on every verse of every BIBLE
doc in BaseX: the Bible in the order its books were WRITTEN.

The second of the two "chronological" orders. 05_stamp_chronological.py orders
by when the events a passage DESCRIBES happened (Genesis 1-11, Job, Genesis
12-50 ...); this one orders by when the passage was composed, on the basis set
out at the top of orderings/writing.yml (critical consensus, as a range,
sorted by midpoint). The plan format, book-name resolution and batch-update
machinery are 05's, reused unchanged — only the attribute differs.

Usage:
    python3 scripts/bibles/06b_stamp_writing.py [--dry-run] [--translation TRANSLATION_ID]

Default: every translation in the corpus. Idempotent (clear-then-stamp, per
chapter). Stdlib only. A --dry-run reads BaseX and writes nothing.

AFTERWARDS the ordering is only half there: the reader and the print pipeline
know it as OrderMode.WRITING / --ordering writing (see CLAUDE_NOTES), and prod
needs the BaseX content image like any other stamped attribute.
"""

import importlib
import os
import sys

sc = importlib.import_module("05_stamp_chronological")

ATTR = "globalWritingSeq"
PLAN_PATH = os.path.join(sc.SCRIPT_DIR, "orderings", "writing.yml")


def stamp_chapter_batch(doc, book, ch, verse_seq_pairs, dry_run):
    """05's two-step chapter stamp, on this ordering's attribute."""
    if dry_run or not verse_seq_pairs:
        return
    book_esc = sc._escape_xq(book)
    sc.xquery_update(
        f"declare namespace rt='{sc.NS}';"
        f" for $v in db:open('religioustext','{doc}')"
        f"/rt:text/rt:book[@name='{book_esc}']"
        f"/rt:chapter[string(@number)='{ch}']/rt:verse"
        f" where exists($v/@{ATTR})"
        f" return delete node $v/@{ATTR}")
    inserts = sc.verse_inserts(doc, book_esc, ch, verse_seq_pairs, ATTR)
    sc.xquery_update(f"declare namespace rt='{sc.NS}';" + ", ".join(inserts))


# 05's stamp_translation calls stamp_chapter_batch through its own module
# globals, so replacing it there is what points the whole run at our attribute.
sc.stamp_chapter_batch = stamp_chapter_batch


def main():
    dry_run = "--dry-run" in sys.argv
    target_id = None
    if "--translation" in sys.argv:
        target_id = sys.argv[sys.argv.index("--translation") + 1]
    if dry_run:
        print("DRY RUN — no changes written to BaseX\n")

    print(f"Loading plan: {PLAN_PATH}")
    flat = sc.load_plan(PLAN_PATH)
    print(f"Plan entries after flattening: {len(flat)}")
    ref_doc = "bible-niv-2011.xml"
    tuples, errors = sc.build_tuples(flat, ref_doc)
    for e in errors:
        print(f"  {e}")
    if any(e.startswith("NOT FOUND") for e in errors):
        sys.exit("Fix NOT FOUND errors before stamping. Aborting.")
    print(f"Plan resolves to {len(tuples)} chapter-blocks, validation OK.")

    ids = [target_id] if target_id else sc.get_all_translation_ids()
    print(f"\nTranslations to stamp: {len(ids)}")
    for tid in ids:
        doc = f"{tid}.xml"
        stamped, skipped, gaps = sc.stamp_translation(doc, tuples, dry_run)
        print(f"\n  {'Would stamp' if dry_run else 'Stamped'} {stamped} verses "
              f"in {doc} ({skipped} chapter-blocks not in this translation)")
        if gaps:
            by_book = {}
            for b, c in gaps:
                by_book.setdefault(b, []).append(c)
            print(f"  {len(gaps)} chapters without a writing seq (they sort to "
                  f"the end): {', '.join(sorted(by_book))}")
        else:
            print("  Coverage: all chapters stamped ✓")
    print("\nDone.")


if __name__ == "__main__":
    main()
