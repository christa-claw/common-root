#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
fetch_diaglott_studybible.py — fetch the Emphatic Diaglott INTERLINEAR from
studybible.info and emit flat import JSON.

This is the literal word-for-word sublinear gloss — John 1:1 reads
"...and a god was the Word" — NOT Wilson's Emphatic Version translation
(which reads "...the LOGOS was God"; that one only exists in the scanned PDF).
Clean transcription, no OCR, perfect verse/chapter structure.

Public-domain text (Benjamin Wilson, 1864). One request per chapter with a
polite delay; ~260 chapters across the NT.

Usage:
    # one book first, to eyeball it:
    python3 scripts/bibles/01_fetch_diaglott_studybible.py --book John \\
        --output ~/Downloads/diaglott-sb-john.json
    # then the whole NT into one flat file:
    python3 scripts/bibles/01_fetch_diaglott_studybible.py --all \\
        --output ~/Downloads/diaglott-interlinear.json

Then import (NT-only, so --allow-partial):
    python3 scripts/bibles/03_import_json_bible.py --allow-partial --format flat \\
        --input ~/Downloads/diaglott-interlinear.json ...
"""
import argparse, json, re, sys, time, html
from urllib.request import Request, urlopen
from urllib.parse import quote

BASE = "https://studybible.info/Diaglott/"

# book name | import book number (40-66) | chapter count
NT = [
    ("Matthew", 40, 28), ("Mark", 41, 16), ("Luke", 42, 24), ("John", 43, 21),
    ("Acts", 44, 28), ("Romans", 45, 16), ("1 Corinthians", 46, 16),
    ("2 Corinthians", 47, 13), ("Galatians", 48, 6), ("Ephesians", 49, 6),
    ("Philippians", 50, 4), ("Colossians", 51, 4), ("1 Thessalonians", 52, 5),
    ("2 Thessalonians", 53, 3), ("1 Timothy", 54, 6), ("2 Timothy", 55, 4),
    ("Titus", 56, 3), ("Philemon", 57, 1), ("Hebrews", 58, 13), ("James", 59, 5),
    ("1 Peter", 60, 5), ("2 Peter", 61, 3), ("1 John", 62, 5), ("2 John", 63, 1),
    ("3 John", 64, 1), ("Jude", 65, 1), ("Revelation", 66, 22),
]
BOOKNUM = {n: num for n, num, _ in NT}
CHAPTERS = {n: ch for n, _, ch in NT}

# A verse is: an anchor whose title is "Book C:V Diaglott" and whose inner text is
# the verse NUMBER, followed by the verse text up to the next such anchor. The
# inner-text-is-a-number guard excludes the "Previous/Next Verse" nav links, which
# share the title format but read "Next Verse".
VERSE_RE = re.compile(
    r'title="[^"]*?\s(\d+):(\d+)\s+Diaglott"[^>]*>\s*\d+\s*</a>(.*?)'
    r'(?=<a[^>]*title="[^"]*?\s\d+:\d+\s+Diaglott"|</div|<h[23]|Online Parallel|$)',
    re.DOTALL,
)
TAG_RE = re.compile(r"<[^>]+>")


def fetch(url):
    req = Request(url, headers={"User-Agent": "common-root-study-ingest/1.0 (personal PD-text project)"})
    with urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def clean(text):
    text = TAG_RE.sub(" ", text)
    text = html.unescape(text)
    return re.sub(r"\s+", " ", text).strip()


def fetch_book(name, delay):
    booknum, nchap = BOOKNUM[name], CHAPTERS[name]
    verses = []
    for ch in range(1, nchap + 1):
        page = fetch(BASE + quote(f"{name} {ch}"))
        found = 0
        for m in VERSE_RE.finditer(page):
            c, v, body = int(m.group(1)), int(m.group(2)), clean(m.group(3))
            if c == ch and v >= 1 and body:           # ignore stray cross-chapter nav refs
                verses.append({"book": booknum, "chapter": c, "verse": v, "text": body})
                found += 1
        print(f"  {name} {ch}: {found} verses")
        time.sleep(delay)
    return verses


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--book", help="one NT book, e.g. John")
    g.add_argument("--all", action="store_true", help="all 27 NT books")
    ap.add_argument("--output", required=True)
    ap.add_argument("--delay", type=float, default=1.0, help="seconds between requests (be polite)")
    a = ap.parse_args()

    books = [n for n, _, _ in NT] if a.all else [a.book]
    if not a.all and a.book not in BOOKNUM:
        sys.exit(f"Unrecognized book {a.book!r}. One of: {', '.join(BOOKNUM)}")

    all_verses = []
    for name in books:
        print(f"=== {name} ===")
        all_verses.extend(fetch_book(name, a.delay))

    with open(a.output, "w", encoding="utf-8") as f:
        json.dump({"verses": all_verses}, f, ensure_ascii=False)

    chapters = len({(v["book"], v["chapter"]) for v in all_verses})
    print(f"\nWrote {len(all_verses)} verses across {chapters} chapters to {a.output}")
    print("Clean transcription — spot-check a few verses, but no full proofread needed.")


if __name__ == "__main__":
    main()
