#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Convert an OSIS XML Bible (e.g. github.com/gratis-bible/bible) into the
flat-JSON shape accepted by 03_import_json_bible.py --format flat.

Handles the simple OSIS profile those repos use:
  <div type='book' osisID='Gen'> <chapter osisID='Gen.1'>
    <verse osisID='Gen.1.1'>text</verse>
Verse addresses are read from each verse's own osisID (self-describing, so
book/chapter div nesting is never trusted). Inline markup inside a verse
(<note>, <w>, …) is dropped; nested element TEXT is kept except <note>
content, which is removed entirely (translator footnotes, not scripture).

--notes writes those removed footnotes to a ledger instead of discarding
them, so the same pass that produces clean scripture also captures the
apparatus. They are still never inline: the flat JSON is identical whether
or not --notes is given. The ledger is seeded into MySQL as comments owned
by a system account (see DataSeeder), which is why every entry carries a
deterministic cmt_ id from scripts/channels/comment_id.py — the seed merge
keys on it, so re-running converges instead of duplicating.

Only protocanon OSIS codes are mapped (1-66). Deuterocanonical OSIS codes
(Tob, Jdt, Wis, Sir, Bar, 1Macc …) error out until mapped — extend
OSIS_BOOKS with the getbible DC numbers if an edition carries them.

Example (Luther 1545, gratis-bible de/luth1545.xml):
  python3 02_convert_osis_flat.py --input sources/luther1545/luth1545.osis.xml \
      --output sources/luther1545/luther1545_flat.json
  python3 03_import_json_bible.py --input sources/luther1545/luther1545_flat.json \
      --format flat --id bible-de-1545 --abbr LUT1545 \
      --translation "Lutherbibel 1545" --lang de --iso3 deu --year 1545 \
      --license "Public Domain" --region Protestant \
      --source "https://github.com/gratis-bible/bible/blob/master/de/luth1545.xml" \
      --dry-run
"""
import argparse, json, os, re, sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "channels"))
from comment_id import mint_note_id      # one id scheme for every comment kind

OSIS_NS = "{http://www.bibletechnologies.net/2003/OSIS/namespace}"

# Canonical book number -> USFM code (matches CANON in 03_import_json_bible.py).
# The notes ledger's verse_refs carry USFM codes because that is the DB/reader
# reference vocabulary (CommentReference.bookCode, arguments.json refs) — while
# each note's "ref" stays the OSIS-style ref, because mint_note_id is keyed on
# it and the ids in shipped ledgers must never re-mint.
USFM_BY_NUM = {
    1:"GEN", 2:"EXO", 3:"LEV", 4:"NUM", 5:"DEU", 6:"JOS", 7:"JDG", 8:"RUT",
    9:"1SA", 10:"2SA", 11:"1KI", 12:"2KI", 13:"1CH", 14:"2CH", 15:"EZR",
    16:"NEH", 17:"EST", 18:"JOB", 19:"PSA", 20:"PRO", 21:"ECC", 22:"SNG",
    23:"ISA", 24:"JER", 25:"LAM", 26:"EZK", 27:"DAN", 28:"HOS", 29:"JOL",
    30:"AMO", 31:"OBA", 32:"JON", 33:"MIC", 34:"NAM", 35:"HAB", 36:"ZEP",
    37:"HAG", 38:"ZEC", 39:"MAL",
    40:"MAT", 41:"MRK", 42:"LUK", 43:"JHN", 44:"ACT", 45:"ROM", 46:"1CO",
    47:"2CO", 48:"GAL", 49:"EPH", 50:"PHP", 51:"COL", 52:"1TH", 53:"2TH",
    54:"1TI", 55:"2TI", 56:"TIT", 57:"PHM", 58:"HEB", 59:"JAS", 60:"1PE",
    61:"2PE", 62:"1JN", 63:"2JN", 64:"3JN", 65:"JUD", 66:"REV",
}

# OSIS book code -> canonical book number (matches CANON in 03_import_json_bible.py)
OSIS_BOOKS = {
    "Gen":1, "Exod":2, "Lev":3, "Num":4, "Deut":5, "Josh":6, "Judg":7, "Ruth":8,
    "1Sam":9, "2Sam":10, "1Kgs":11, "2Kgs":12, "1Chr":13, "2Chr":14, "Ezra":15,
    "Neh":16, "Esth":17, "Job":18, "Ps":19, "Prov":20, "Eccl":21, "Song":22,
    "Isa":23, "Jer":24, "Lam":25, "Ezek":26, "Dan":27, "Hos":28, "Joel":29,
    "Amos":30, "Obad":31, "Jonah":32, "Mic":33, "Nah":34, "Hab":35, "Zeph":36,
    "Hag":37, "Zech":38, "Mal":39,
    "Matt":40, "Mark":41, "Luke":42, "John":43, "Acts":44, "Rom":45, "1Cor":46,
    "2Cor":47, "Gal":48, "Eph":49, "Phil":50, "Col":51, "1Thess":52, "2Thess":53,
    "1Tim":54, "2Tim":55, "Titus":56, "Phlm":57, "Heb":58, "Jas":59, "1Pet":60,
    "2Pet":61, "1John":62, "2John":63, "3John":64, "Jude":65, "Rev":66,
}

def verse_text(el):
    """All text content of a verse element, <note> subtrees removed."""
    parts = [el.text or ""]
    for child in el:
        if child.tag != f"{OSIS_NS}note":
            parts.append(verse_text(child))
        parts.append(child.tail or "")
    return "".join(parts)


def element_text(el):
    """All text content of an element, including nested markup."""
    parts = [el.text or ""]
    for child in el:
        parts.append(element_text(child))
        parts.append(child.tail or "")
    return "".join(parts)


def verse_notes(el):
    """The <note> subtrees of a verse, in document order.

    Returns (text, anchor) pairs. The anchor is the scripture immediately
    preceding the note — kept because a note anchored only to a verse has lost
    the word it hung on, and a human reviewing the ledger needs to see where it
    sat to judge whether it survived the trip.
    """
    out, before = [], []

    def walk(node, is_root):
        if is_root:
            before.append(node.text or "")
        for child in node:
            if child.tag == f"{OSIS_NS}note":
                anchor = re.sub(r"\s+", " ", "".join(before)).strip()
                out.append((re.sub(r"\s+", " ", element_text(child)).strip(), anchor[-60:]))
            else:
                before.append(child.text or "")
                walk(child, False)
            before.append(child.tail or "")

    walk(el, True)
    return [(t, a) for t, a in out if t]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="OSIS XML file")
    ap.add_argument("--output", required=True, help="flat JSON output path")
    ap.add_argument("--notes", help="also write the translator-note ledger here")
    ap.add_argument("--abbr", help="edition abbreviation, required with --notes "
                                   "(it is part of every note id)")
    a = ap.parse_args()
    if a.notes and not a.abbr:
        sys.exit("ERROR: --notes needs --abbr; the edition is part of each note id.")

    root = ET.parse(a.input).getroot()
    verses, bad_ids, unmapped, notes = [], 0, set(), []
    for v in root.iter(f"{OSIS_NS}verse"):
        osis_id = v.get("osisID", "")
        m = re.fullmatch(r"([1-3]?[A-Za-z]+)\.(\d+)\.(\d+)", osis_id.split(" ")[0])
        if not m:
            bad_ids += 1
            continue
        code, ch, vs = m.group(1), int(m.group(2)), int(m.group(3))
        if code not in OSIS_BOOKS:
            unmapped.add(code)
            continue
        txt = re.sub(r"\s+", " ", verse_text(v)).strip()
        verses.append({"book": OSIS_BOOKS[code], "chapter": ch, "verse": vs, "text": txt})

        if a.notes:
            ref = f"{code}.{ch}.{vs}"
            for i, (note_txt, anchor) in enumerate(verse_notes(v)):
                notes.append({
                    "id": mint_note_id(a.abbr, ref, i, note_txt),
                    "edition": a.abbr,
                    # reader source token; the seeder maps this to
                    # CommentReference.sourceId so the note shows under THIS
                    # edition only, unlike a reader comment (NULL source_id)
                    # which is edition-independent by design (see V5).
                    "source": a.abbr.lower(),
                    # "ref" is OSIS (the id key — immutable); "code" is USFM
                    # (what CommentReference.bookCode and the reader use).
                    "verse_refs": [{"ref": ref, "code": USFM_BY_NUM[OSIS_BOOKS[code]],
                                    "book": OSIS_BOOKS[code],
                                    "chapter": ch, "verse": vs}],
                    "note_index": i,
                    "anchor": anchor,
                    "text": note_txt,
                })

    if unmapped:
        sys.exit(f"ERROR: unmapped OSIS book code(s): {sorted(unmapped)} — "
                 f"extend OSIS_BOOKS (DC books need getbible DC numbers).")

    books = {v["book"] for v in verses}
    empty = sum(1 for v in verses if not v["text"])
    print(f"{len(verses)} verses, {len(books)} books, "
          f"{empty} empty, {bad_ids} unparseable osisIDs")

    with open(a.output, "w", encoding="utf-8") as fh:
        json.dump({"metadata": {"source": os.path.basename(a.input)},
                   "verses": verses}, fh, ensure_ascii=False)
    print(f"wrote {a.output} ({os.path.getsize(a.output)} bytes)")

    if a.notes:
        dupes = len(notes) - len({n["id"] for n in notes})
        if dupes:
            sys.exit(f"ERROR: {dupes} duplicate note id(s) — the seed merge keys "
                     f"on id, so this would silently drop notes.")
        with open(a.notes, "w", encoding="utf-8") as fh:
            json.dump({"metadata": {"edition": a.abbr,
                                    "source": os.path.basename(a.input),
                                    "count": len(notes)},
                       "notes": notes}, fh, ensure_ascii=False, indent=1)
        print(f"wrote {a.notes} ({len(notes)} notes)")

if __name__ == "__main__":
    main()
