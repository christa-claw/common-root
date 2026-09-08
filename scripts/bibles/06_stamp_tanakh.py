#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
stamp_tanakh.py — stamps globalTanakhSeq on every verse of every BIBLE doc in
BaseX, in the Hebrew-Bible (Tanakh) arrangement: Torah → Nevi'im → Ketuvim
(Chronicles LAST), then the New Testament unchanged after it.

Order-design decisions (see CLAUDE_NOTES "READING ORDERS — TANAKH ORDER"):
  * 66-book granularity kept — only the ORDER changes (1/2 Samuel etc. sort
    adjacently); never merged to the Jewish 24-book counting, which would
    break cross-edition sync.
  * Ketuvim follow the standard PRINTED (BHS) order: Psalms, Proverbs, Job,
    the five Megillot (Song, Ruth, Lamentations, Ecclesiastes, Esther),
    Daniel, Ezra, Nehemiah, 1–2 Chronicles. (Talmud Bava Batra 14b differs.)

Whole books in sequence — no chapter interleaving — so no orderings/ plan file
is needed; the order IS the list below. Reuses stamp_chronological.py's BaseX
helpers, book-name resolution (incl. Spanish map) and batch-update machinery.

Usage:
    python3 scripts/bibles/06_stamp_tanakh.py [--dry-run] [--translation TRANSLATION_ID]

Default: all docs whose rt:text/@type is 'bible'. Idempotent (clear-then-stamp).
Stdlib only.
"""

import sys
import importlib
sc = importlib.import_module("05_stamp_chronological")

ATTR = "globalTanakhSeq"

# Plan book names (resolved per-doc via sc.resolve_book_name, so localized
# @name docs and Spanish RVR09 map through the same machinery as chrono).
TANAKH_ORDER = [
    # ── Torah ──────────────────────────────────────────────────────────
    "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy",
    # ── Nevi'im (Former Prophets) ──────────────────────────────────────
    "Joshua", "Judges", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings",
    # ── Nevi'im (Latter Prophets) ──────────────────────────────────────
    "Isaiah", "Jeremiah", "Ezekiel",
    # ── The Twelve ─────────────────────────────────────────────────────
    "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah",
    "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi",
    # ── Ketuvim (printed/BHS order; Chronicles last) ───────────────────
    "Psalms", "Proverbs", "Job",
    "Song of Solomon", "Ruth", "Lamentations", "Ecclesiastes", "Esther",
    "Daniel", "Ezra", "Nehemiah", "1 Chronicles", "2 Chronicles",
    # ── New Testament (unchanged) ──────────────────────────────────────
    "Matthew", "Mark", "Luke", "John", "Acts",
    "Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians",
    "Philippians", "Colossians", "1 Thessalonians", "2 Thessalonians",
    "1 Timothy", "2 Timothy", "Titus", "Philemon",
    "Hebrews", "James", "1 Peter", "2 Peter",
    "1 John", "2 John", "3 John", "Jude", "Revelation",
]

# Extra aliases beyond stamp_chronological's set (keyed on OUR plan names).
sc._ALIASES.setdefault('songofsolomon',
    ['songofsongs', 'songofsongsongs', 'cantares', 'cantardeloscantares',
     'canticleofcanticles'])
sc._ALIASES.setdefault('psalms', ['psalm', 'salmos'])


def get_bible_ids():
    """All translation ids whose rt:text/@type is 'bible'."""
    result = sc.xquery(
        f"declare namespace rt='{sc.NS}';"
        f" for $t in db:open('religioustext')//rt:text[@type='bible']"
        f" return string($t/@id)"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]


def get_chapters_in_order(doc, book):
    """Chapter @number values of a book in document order (as strings)."""
    book_esc = sc._escape_xq(book)
    result = sc.xquery(
        f"declare namespace rt='{sc.NS}';"
        f" for $c in db:open('religioustext','{doc}')"
        f"/rt:text/rt:book[@name='{book_esc}']/rt:chapter"
        f" return string($c/@number)"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]


def stamp_chapter(doc, book, ch, verse_seq_pairs, dry_run):
    """Clear + stamp ATTR for one chapter (mirrors sc.stamp_chapter_batch,
    parameterised for the Tanakh attribute)."""
    if dry_run or not verse_seq_pairs:
        return
    book_esc = sc._escape_xq(book)

    q_delete = (
        f"declare namespace rt='{sc.NS}';"
        f" for $v in db:open('religioustext','{doc}')"
        f"/rt:text/rt:book[@name='{book_esc}']"
        f"/rt:chapter[string(@number)='{ch}']/rt:verse"
        f" where exists($v/@{ATTR})"
        f" return delete node $v/@{ATTR}"
    )
    sc.xquery_update(q_delete)

    inserts = []
    for vnum, seq in verse_seq_pairs:
        inserts.append(
            f"let $v := db:open('religioustext','{doc}')"
            f"/rt:text/rt:book[@name='{book_esc}']"
            f"/rt:chapter[string(@number)='{ch}']"
            f"/rt:verse[string(@number)='{vnum}']"
            f" return if (exists($v)) then"
            f" insert node attribute {ATTR} {{'{seq}'}} into $v"
            f" else ()"
        )
    sc.xquery_update(f"declare namespace rt='{sc.NS}';" + ", ".join(inserts))


def stamp_translation(doc, dry_run):
    name_map = sc.build_name_map(doc)

    resolved, missing = [], []
    for plan_book in TANAKH_ORDER:
        actual = sc.resolve_book_name(plan_book, name_map)
        (resolved if actual else missing).append(actual or plan_book)

    if missing:
        print(f"    Books in plan not found in {doc} "
              f"(expected for OT-only/NT-only editions): {missing}")

    covered_books = set(resolved)
    extra = [b for b in sc.get_book_names(doc) if b not in covered_books]
    if extra:
        print(f"    Books in {doc} NOT in the Tanakh plan (left unstamped, "
              f"will fall back to canonical): {extra}")

    seq, stamped = 1, 0
    print(f"\n  Stamping {doc} ...")
    for book in resolved:
        for ch in get_chapters_in_order(doc, book):
            nums = sc.get_verse_numbers(doc, book, ch)
            if not nums:
                continue
            pairs = [(n, seq + i) for i, n in enumerate(nums)]
            try:
                stamp_chapter(doc, book, ch, pairs, dry_run)
            except Exception as e:
                print(f"    ERROR: {book} ch.{ch}: {e}")
            seq     += len(nums)
            stamped += len(nums)
            if stamped % 5000 == 0:
                print(f"    ... {stamped} verses stamped (last: {book} ch.{ch})")

    return stamped


def main():
    dry_run   = "--dry-run" in sys.argv
    target_id = None
    if "--translation" in sys.argv:
        target_id = sys.argv[sys.argv.index("--translation") + 1]

    if dry_run:
        print("DRY RUN — no changes written to BaseX\n")

    ids = [target_id] if target_id else get_bible_ids()
    print(f"Bible docs to stamp with @{ATTR}: {ids}")

    for tid in ids:
        doc = f"{tid}.xml"
        n = stamp_translation(doc, dry_run)
        action = "Would stamp" if dry_run else "Stamped"
        print(f"\n  {action} {n} verses in {doc}")

    print("\nDone.")


if __name__ == "__main__":
    main()
