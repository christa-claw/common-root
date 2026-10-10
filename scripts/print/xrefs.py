#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Cross references for the margin of a printed Bible.

The data is OpenBible.info's cross-reference file (about 340,000 references, drawn mostly
from the public-domain Treasury of Scripture Knowledge, ranked by reader votes), the same
zip the app seeds its reader from: app/src/main/resources/crossrefs/. It is read here
directly and with the same rules as XrefSeeder.java and CrossRefQueryService.java, so a
printed margin and the reader's cross-reference dialog show the same references in the
same order:

  * a row with negative votes is dropped;
  * a target range that runs from one book into the next is cut back to its first verse;
  * books are recoded from OpenBible's OSIS names to USFM codes;
  * a verse's references are ordered by votes, highest first, then by target book code,
    chapter and verse, so the order is stable.

Anchors are USFM code + chapter + verse in the KJV numbering, like comments. An edition numbered
differently would put some references beside the wrong verse, so build_pdf.py gives no references
to a chapter whose last verse number is not the KJV's. Names come from names_for(): English short
names, or the reader's own book names in the edition's language.

Licence: CC BY 4.0, which asks for the credit and for a statement of what was changed. Both
come from credit_and_changes(), for the rights page of the book and of the package.

    python3 scripts/print/xrefs.py JHN 3 16     # what the margin would carry beside John 3:16
"""
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ZIP_PATH = os.path.join(os.path.dirname(os.path.dirname(HERE)), "app", "src", "main",
                        "resources", "crossrefs", "openbible-cross-references.zip")

CREDIT_NAME = "OpenBible.info"
CREDIT_URL = "https://www.openbible.info/labs/cross-references/"
LICENCE_NAME = "Creative Commons Attribution 4.0 International (CC BY 4.0)"
LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"

# OpenBible's OSIS book names, in canonical order, and the USFM codes the app keys them by
# (the same two lists as XrefSeeder.osisToUsfm).
_OSIS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job "
         "Ps Prov Eccl Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag "
         "Zech Mal Matt Mark Luke John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim "
         "2Tim Titus Phlm Heb Jas 1Pet 2Pet 1John 2John 3John Jude Rev").split()
_USFM = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG "
         "ISA JER LAM EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT "
         "ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD "
         "REV").split()
USFM_BY_OSIS = dict(zip(_OSIS, _USFM))

# What the margin calls each book, from the file the app's order-page preview reads too.
NAMES_PATH = os.path.join(os.path.dirname(os.path.dirname(ZIP_PATH)), "print", "xref-names.properties")


def _short_names(path=NAMES_PATH):
    names = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith(("#", "!")) and "=" in line:
                code, name = line.split("=", 1)
                name = name.strip()
                if "\\u" in name:        # a bundle may carry \uXXXX escapes
                    name = name.encode("ascii", "backslashreplace").decode("unicode_escape")
                names[code.strip()] = name
    return names


SHORT = _short_names()
assert set(SHORT) == set(_USFM), "xref-names.properties must name exactly the 66 books"

PRINT_DIR = os.path.dirname(NAMES_PATH)
I18N_DIR = os.path.join(os.path.dirname(os.path.dirname(ZIP_PATH)), "i18n")


def names_for(lang, abbreviated=False):
    """{USFM code: the name the margin uses} for an edition's language, or None.

    English has its own short names. Any other language looks first for margin abbreviations,
    print/xref-names_<lang>.properties (none exist yet), and otherwise uses the full book names
    the reader already shows in that language (i18n/booknames_<lang>.properties, a script-specific
    file such as booknames_zh_Hant first). Full names are long for a margin that is 32 mm wide,
    so the notes wrap to more lines; real abbreviations would set tighter. None when the
    language has neither, so a caller can say so instead of printing English names under it.

    With abbreviated=True only real abbreviations count (English, or an xref-names_<lang> file):
    a reference set INLINE, after every verse, must be short, and full names would bloat the text."""
    code = (lang or "en").split("-")[0].lower()
    if code == "en":
        return dict(SHORT)
    stems = [lang.replace("-", "_"), code] if "-" in lang else [code]
    places = [(PRINT_DIR, "xref-names_")] + ([] if abbreviated else [(I18N_DIR, "booknames_")])
    for directory, prefix in places:
        for stem in stems:
            path = os.path.join(directory, f"{prefix}{stem}.properties")
            if os.path.exists(path):
                names = _short_names(path)
                if all(c in names for c in _USFM):
                    return {c: names[c] for c in _USFM}
    return None


def _verse(token):
    """'Gen.1.1' -> ('GEN', 1, 1); None when it is not a verse in the 66-book canon."""
    parts = token.split(".")
    if len(parts) != 3:
        return None
    code = USFM_BY_OSIS.get(parts[0])
    try:
        return (code, int(parts[1]), int(parts[2])) if code else None
    except ValueError:
        return None


def parse(lines):
    """Yield (from_key, to_book, to_ch, to_v, end_ch, end_v, votes) for each usable row."""
    for line in lines:
        if not line or line.startswith("From Verse"):
            continue
        f = line.split("\t")
        if len(f) < 3:
            continue
        origin = _verse(f[0])
        target = f[1].split("-", 1)
        start = _verse(target[0])
        try:
            votes = int(f[2])
        except ValueError:
            continue
        if origin is None or start is None or votes < 0:
            continue
        end = _verse(target[1]) if len(target) == 2 else None
        if len(target) == 2 and end is None:
            continue
        # A range that crosses into the next book keeps its first verse and loses its end.
        if end is not None and end[0] != start[0]:
            end = None
        yield (origin, start[0], start[1], start[2],
               end[1] if end else None, end[2] if end else None, votes)


def load(top=3, path=ZIP_PATH, names=None):
    """{(usfm, chapter, verse): [label, ...]}, the `top` most-voted references of each verse,
    named with `names` (default: the English short names)."""
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.lower().endswith(".txt"))
        text = z.read(name).decode("utf-8")
    by_verse = {}
    for origin, book, ch, v, end_ch, end_v, votes in parse(text.splitlines()):
        by_verse.setdefault(origin, []).append((votes, book, ch, v, end_ch, end_v))
    out = {}
    for key, refs in by_verse.items():
        # Votes high to low, then target book code, chapter, verse: CrossRefQueryService.shape.
        refs.sort(key=lambda r: (-r[0], r[1], r[2], r[3]))
        out[key] = [label(*r[1:], names=names) for r in refs[:top]]
    return out


def label(book, chapter, verse, end_chapter=None, end_verse=None, names=None):
    """'John 1:1–3', 'Heb 11:3', 'Ps 89:11–90:2'."""
    text = f"{(names or SHORT)[book]} {chapter}:{verse}"
    if end_chapter is None or end_verse is None:
        return text
    if end_chapter == chapter:
        return text if end_verse == verse else f"{text}–{end_verse}"
    return f"{text}–{end_chapter}:{end_verse}"


def credit_and_changes(top=3, where="margin"):
    """The credit and the statement of changes CC BY 4.0 asks the book to carry."""
    place = ("in the margin" if where == "margin"
             else "after each verse, in small type")
    credit = (f"Cross references {place}: {CREDIT_NAME} (openbible.info/labs/cross-references), "
              "compiled mostly from the Treasury of Scripture Knowledge and ranked by reader votes, "
              "used under the Creative Commons Attribution 4.0 International licence "
              "(creativecommons.org/licenses/by/4.0).")
    words = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five"}
    changes = ("Changes made: references with negative votes removed; ranges that cross into another "
               f"book shortened to their first verse; only the {words.get(top, top)} most-voted references to each "
               "verse printed; book names abbreviated.")
    return credit, changes


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    book, ch, v = sys.argv[1].upper(), int(sys.argv[2]), int(sys.argv[3])
    print(load().get((book, ch, v), []))
