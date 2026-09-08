#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
ingest_bom.py — ingest the LDS Standard Works (Book of Mormon, Doctrine and
Covenants, Pearl of Great Price) into the BaseX 'religioustext' database, in the
SAME schema as the Bible texts. Three separate <text> documents, one per work, so
each is an independently selectable source in the reader (like KJV vs NIV, or
Bukhari vs Muslim).

Schema mapping (book > chapter > verse, exactly like the Bibles):
    Book of Mormon  : 15 books -> <book>; each chapter -> <chapter>; verse -> <verse>.
    Doctrine & Cov. : ONE book "Doctrine and Covenants"; each SECTION -> <chapter>
                      (chapter number = section number); verse -> <verse>.
                      (D&C has no book/chapter layer — sections are the unit.)
    Pearl of G.P.   : 5 books (Moses, Abraham, Joseph Smith—Matthew,
                      Joseph Smith—History, Articles of Faith) -> <book>; etc.
    type="lds" on every document. No baseText (these are standalone primaries,
    not translations of a base text), so the reader treats them Bible-like:
    hasCompanions("lds") is false -> standalone column, full display modes,
    grouped under English. No reader code change is needed to READ them.

Sequence stamping:
    globalCanonicalSeq — running 1..N in document (canonical) order, per document,
                         exactly like the Bibles. This is what every reader mode
                         depends on, so it is always stamped.
    globalChronologicalSeq — NOT stamped in this pass. The works have no single,
                         uncontested chronological ordering to hardcode (D&C is
                         roughly section-date order; BoM internal chronology is
                         non-linear; PoGP is mixed). Unstamped verses fall back to
                         canonical in the reader. A future ordering plan (esp. a
                         D&C section-date order) can stamp it later, like the
                         Bible chronological pass.

Source: bcbooks/scriptures-json (raw GitHub). PUBLIC DOMAIN — the maintainer's
README states the JSON editions are public domain and explicitly EXCLUDE the
copyrighted apparatus (footnotes, modern chapter summaries, the Book of Mormon
introduction). Text is from the 2013 Mormon Documentation Project export.
Typographical note: small caps are rendered as ALL CAPS (e.g. "LORD") and italics
are not distinguished — a property of the source, ingested as-is.

Repeatable: re-running replaces the documents (curl PUT). No third-party Python
deps (stdlib + curl), mirroring ingest_quran.py / ingest_hadith.py.

Usage:
    python3 ingest_bom.py                 # all three works
    python3 ingest_bom.py --work bom      # one work (bom | dc | pgp)
    python3 ingest_bom.py --list          # list the works and exit
"""
import argparse
import html
import json
import os
import subprocess
import tempfile

BASEX = "http://localhost:8984/rest/religioustext"
AUTH  = "admin:admin"
NS    = "http://religioustext.org/schema/1.0"

RAW_BASE = "https://raw.githubusercontent.com/bcbooks/scriptures-json/master"

TYPE    = "lds"
LANG    = "en"
LICENSE = "Public domain (LDS standard works; copyrighted apparatus excluded)"
SOURCE  = "bcbooks/scriptures-json"

# key, doc_id, abbreviation, title, source filename, layout kind
#   "books"    -> top-level data["books"], each {book, chapters:[{chapter, verses}]}
#   "sections" -> top-level data["sections"], each {section, verses}; folded under
#                 ONE synthetic book named <title>, section number = chapter number
WORKS = [
    ("bom", "lds-book-of-mormon",         "BOM", "Book of Mormon",         "book-of-mormon.json",         "books"),
    ("dc",  "lds-doctrine-and-covenants", "DC",  "Doctrine and Covenants", "doctrine-and-covenants.json", "sections"),
    ("pgp", "lds-pearl-of-great-price",   "PGP", "Pearl of Great Price",   "pearl-of-great-price.json",   "books"),
]

# Stable, unique, NON-NUMERIC book codes (USFM-style) per work, in the source's
# document order. These become <book @code>, which is the key a user comment
# attaches to (CommentReference.bookCode, matched in CommentQueryService). They
# MUST be:
#   - unique across ALL THREE works — a comment ref carries no source id, so the
#     code alone has to identify the book (else 1 Nephi / Moses / D&C collide), and
#   - non-numeric — the reader's isSurahCode() treats an all-digits code as a
#     Qur'an surah, so integer codes would mis-render LDS refs as "Q n:m".
# Codes are assigned by POSITION (not by name) so the em-dashes in the Pearl of
# Great Price titles can't break a name lookup. The matcher (extract_arguments.py)
# maps spoken book names to these same codes.
BOOK_CODES = {
    "bom": ["1NE", "2NE", "JAC", "ENO", "JAR", "OMN", "WOM", "MOS",
            "ALM", "HEL", "3NE", "4NE", "MRM", "ETH", "MNI"],
    "dc":  ["DC"],
    "pgp": ["MOSE", "ABR", "JSM", "JSH", "AOF"],
}


def fetch_work(filename):
    url = f"{RAW_BASE}/{filename}"
    r = subprocess.run(["curl", "-s", "--max-time", "90", url],
                       capture_output=True, text=True, timeout=120)
    if r.returncode != 0 or not r.stdout.strip():
        raise SystemExit(f"Fetch failed for {filename}: rc={r.returncode} {r.stderr[:200]}")
    return json.loads(r.stdout)


def esc_attr(s):
    return html.escape(str(s), quote=True)


def esc_text(s):
    return html.escape(str(s), quote=False)


def normalize(data, kind, title):
    """Return a uniform [(book_name, [(chapter_number, [verse, ...]), ...]), ...]
    regardless of which of the two source layouts the work uses. A verse is the
    raw source object: {"reference", "text", "verse"}."""
    books = []
    if kind == "books":
        for b in data["books"]:
            chapters = [(c["chapter"], c["verses"]) for c in b["chapters"]]
            books.append((b["book"], chapters))
    elif kind == "sections":
        # D&C: one book; each section becomes a chapter (chapter no. = section no.)
        chapters = [(s["section"], s["verses"]) for s in data["sections"]]
        books.append((title, chapters))
    else:
        raise SystemExit(f"Unknown layout kind: {kind}")
    return books


def build_xml(books, doc_id, abbr, title, codes):
    total = sum(len(verses) for _, chapters in books for _, verses in chapters)
    out = ['<?xml version="1.0" encoding="UTF-8"?>']
    out.append(
        f'<text xmlns="{NS}" id="{esc_attr(doc_id)}" type="{TYPE}" lang="{LANG}"'
        f' translation="{esc_attr(title)}" abbreviation="{esc_attr(abbr)}"'
        f' direction="ltr" license="{esc_attr(LICENSE)}"'
        f' source="{esc_attr(SOURCE)}" totalVerses="{total}">'
    )
    if len(codes) != len(books):
        raise SystemExit(
            f"{doc_id}: source has {len(books)} books but BOOK_CODES has "
            f"{len(codes)} — the source book list changed; update BOOK_CODES.")
    canon = 0
    for idx, (book_name, chapters) in enumerate(books, start=1):
        code = codes[idx - 1]
        out.append(
            f'  <book name="{esc_attr(book_name)}" code="{esc_attr(code)}" canonicalOrder="{idx}">'
        )
        for chap_num, verses in chapters:
            out.append(f'    <chapter number="{esc_attr(chap_num)}">')
            for v in verses:
                canon += 1
                out.append(
                    f'      <verse number="{esc_attr(v["verse"])}"'
                    f' bookName="{esc_attr(book_name)}" chapterNumber="{esc_attr(chap_num)}"'
                    f' globalCanonicalSeq="{canon}">'
                    f'{esc_text(v["text"])}</verse>'
                )
            out.append('    </chapter>')
        out.append('  </book>')
    out.append('</text>')
    return "\n".join(out), total


def put_doc(doc_id, xml):
    with tempfile.NamedTemporaryFile("w", suffix=".xml", encoding="utf-8", delete=False) as f:
        f.write(xml)
        tmp = f.name
    try:
        r = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-u", AUTH,
             "-X", "PUT", "-H", "Content-Type: application/xml",
             "--data-binary", f"@{tmp}", f"{BASEX}/{doc_id}.xml"],
            capture_output=True, text=True, timeout=180)
        return r.stdout.strip()
    finally:
        os.unlink(tmp)


def main():
    ap = argparse.ArgumentParser(description="Ingest the LDS Standard Works into BaseX.")
    ap.add_argument("--work", choices=[w[0] for w in WORKS],
                    help="ingest a single work (default: all)")
    ap.add_argument("--list", action="store_true", help="list works and exit")
    args = ap.parse_args()

    if args.list:
        for key, doc_id, abbr, title, fn, kind in WORKS:
            print(f"  {key:4s} {abbr:4s} {title:24s} -> {doc_id}.xml  ({fn}, {kind})")
        return

    selected = [w for w in WORKS if (args.work is None or w[0] == args.work)]
    for key, doc_id, abbr, title, filename, kind in selected:
        print(f"Fetching {title} ({filename}) ...")
        data = fetch_work(filename)
        books = normalize(data, kind, title)
        xml, total = build_xml(books, doc_id, abbr, title, BOOK_CODES[key])
        code = put_doc(doc_id, xml)
        print(f"  PUT {doc_id}: HTTP {code}  ({len(books)} book(s), "
              f"{sum(len(ch) for _, ch in books)} chapters, {total} verses)")

    print("Done.")


if __name__ == "__main__":
    main()
