#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Import a flat-JSON Bible into the Common Root? BaseX schema.

Handles two public-domain JSON shapes and PUTs a <text> document straight into
BaseX in the exact schema the reader expects (book @code/@canonicalOrder/@testament,
chapter @number, verse @number/@bookName/@chapterNumber). No Java pipeline needed.

Supported --format values:
  flat   {"metadata":{...}, "verses":[{"book":1-66,"chapter":N,"verse":N,"text":"..."}]}
         (e.g. biblesuper SourceForge FI-Finnish/finn.json)
  books  [{"abbrev":"gn","name":"Genesis","chapters":[["v1","v2",...], ...]}, ...]
         (e.g. thiagobodruk/bible json/*.json — 66 books in canonical order)
  getbible {"books":[{"nr":1-87,"chapters":[{"chapter":N,"verses":[{"verse":N,"text":"..."}]}]}]}
         (e.g. api.getbible.net/v2/<translation>.json — Apocrypha at nr>66 map to DC codes)

All shapes are mapped onto the canonical book ordering below, so the USFM codes match
the rest of the corpus. The 66-book Protestant canon is required; getbible Apocrypha
(nr>66) are additionally accepted and mapped to the same DC codes the KJV uses.
Unmapped book numbers are rejected rather than guessed.

Example:
  python3 import_json_bible.py --input finn.json --format flat \
      --id bible-fi-1776 --abbr FB1776 --translation "Biblia 1776" \
      --lang fi --iso3 fin --year 1776 --license "Public Domain" \
      --region Protestant --source https://sourceforge.net/projects/biblesuper
"""
import argparse, json, sys, urllib.request
from xml.sax.saxutils import escape, quoteattr

# canonicalOrder -> (USFM code, English book name, testament) — authoritative, matches the live DB.
CANON = {
 1:("GEN","Genesis","OT"),2:("EXO","Exodus","OT"),3:("LEV","Leviticus","OT"),4:("NUM","Numbers","OT"),
 5:("DEU","Deuteronomy","OT"),6:("JOS","Joshua","OT"),7:("JDG","Judges","OT"),8:("RUT","Ruth","OT"),
 9:("1SA","1 Samuel","OT"),10:("2SA","2 Samuel","OT"),11:("1KI","1 Kings","OT"),12:("2KI","2 Kings","OT"),
 13:("1CH","1 Chronicles","OT"),14:("2CH","2 Chronicles","OT"),15:("EZR","Ezra","OT"),16:("NEH","Nehemiah","OT"),
 17:("EST","Esther","OT"),18:("JOB","Job","OT"),19:("PSA","Psalm","OT"),20:("PRO","Proverbs","OT"),
 21:("ECC","Ecclesiastes","OT"),22:("SNG","Song of Songs","OT"),23:("ISA","Isaiah","OT"),24:("JER","Jeremiah","OT"),
 25:("LAM","Lamentations","OT"),26:("EZK","Ezekiel","OT"),27:("DAN","Daniel","OT"),28:("HOS","Hosea","OT"),
 29:("JOL","Joel","OT"),30:("AMO","Amos","OT"),31:("OBA","Obadiah","OT"),32:("JON","Jonah","OT"),
 33:("MIC","Micah","OT"),34:("NAM","Nahum","OT"),35:("HAB","Habakkuk","OT"),36:("ZEP","Zephaniah","OT"),
 37:("HAG","Haggai","OT"),38:("ZEC","Zechariah","OT"),39:("MAL","Malachi","OT"),40:("MAT","Matthew","NT"),
 41:("MRK","Mark","NT"),42:("LUK","Luke","NT"),43:("JHN","John","NT"),44:("ACT","Acts","NT"),
 45:("ROM","Romans","NT"),46:("1CO","1 Corinthians","NT"),47:("2CO","2 Corinthians","NT"),48:("GAL","Galatians","NT"),
 49:("EPH","Ephesians","NT"),50:("PHP","Philippians","NT"),51:("COL","Colossians","NT"),52:("1TH","1 Thessalonians","NT"),
 53:("2TH","2 Thessalonians","NT"),54:("1TI","1 Timothy","NT"),55:("2TI","2 Timothy","NT"),56:("TIT","Titus","NT"),
 57:("PHM","Philemon","NT"),58:("HEB","Hebrews","NT"),59:("JAS","James","NT"),60:("1PE","1 Peter","NT"),
 61:("2PE","2 Peter","NT"),62:("1JN","1 John","NT"),63:("2JN","2 John","NT"),64:("3JN","3 John","NT"),
 65:("JUD","Jude","NT"),66:("REV","Revelation","NT"),
}

# getbible Apocrypha book number -> (USFM code, canonicalOrder, English name). Codes and
# orders match the KJV deuterocanon already in the live DB (DC sorts after the NT, 67-82).
# Verified against getbible's Swedish 1917 (nr 69-81); any other nr>66 errors out until mapped.
GETBIBLE_DC = {
 69:("TOB",67,"Tobit"), 70:("JDT",68,"Judith"), 71:("ESG",82,"Esther (Greek)"),
 73:("WIS",71,"Wisdom"), 74:("SIR",72,"Sirach"), 75:("BAR",73,"Baruch"),
 76:("S3Y",80,"Song of the Three"), 77:("SUS",79,"Susanna"), 78:("BEL",81,"Bel and the Dragon"),
 79:("MAN",78,"Prayer of Manasseh"), 80:("1MA",69,"1 Maccabees"), 81:("2MA",70,"2 Maccabees"),
}

def load_json(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "latin-1"):
        try:
            return json.loads(raw.decode(enc))
        except Exception:
            continue
    sys.exit("ERROR: could not decode JSON in any known encoding")

def books_from_flat(data):
    """biblesuper shape -> {bookNum: {chapter: [(verse, text)]}}"""
    verses = data["verses"] if isinstance(data, dict) else data
    out = {}
    for r in verses:
        out.setdefault(int(r["book"]), {}).setdefault(int(r["chapter"]), []).append((int(r["verse"]), r["text"] or ""))
    return out

def books_from_array(data):
    """thiagobodruk shape -> {bookNum: {chapter: [(verse, text)]}} (array order is canonical)"""
    out = {}
    for i, book in enumerate(data):
        bn = i + 1
        for ci, chap in enumerate(book["chapters"]):
            cn = ci + 1
            out[bn] = out.get(bn, {})
            out[bn][cn] = [(vi + 1, txt or "") for vi, txt in enumerate(chap)]
    return out

def books_from_getbible(data):
    """getbible.net v2 shape -> {bookNum: {chapter: [(verse, text)]}}.
    Includes Apocrypha (nr>66); main() maps them via GETBIBLE_DC. Whitespace trimmed."""
    out = {}
    for b in data["books"]:
        nr = int(b["nr"])
        for ch in b["chapters"]:
            cn = int(ch["chapter"])
            out.setdefault(nr, {})[cn] = [
                (int(v["verse"]), (v.get("text") or "").strip()) for v in ch["verses"]
            ]
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--format", required=True, choices=["flat", "books", "getbible"])
    ap.add_argument("--id", required=True)
    ap.add_argument("--abbr", required=True)
    ap.add_argument("--translation", required=True)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--iso3", required=True)
    ap.add_argument("--year", default="")
    ap.add_argument("--license", default="Public Domain")
    ap.add_argument("--region", default="Protestant")
    ap.add_argument("--direction", default="ltr")
    ap.add_argument("--source", default="")
    ap.add_argument("--basex", default="http://localhost:8984/rest")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--database", default="religioustext")
    ap.add_argument("--dry-run", action="store_true", help="build + validate, don't PUT")
    ap.add_argument("--allow-partial", action="store_true",
                    help="permit a partial canon (e.g. the NT-only Emphatic Diaglott); "
                         "skips the all-66-books requirement. Books that ARE present must "
                         "still be valid protocanon (1-66) or mapped DC numbers.")
    a = ap.parse_args()

    data = load_json(a.input)
    if a.format == "flat":
        books = books_from_flat(data)
    elif a.format == "books":
        books = books_from_array(data)
    else:
        books = books_from_getbible(data)

    missing = set(range(1, 67)) - set(books)
    extra = set(books) - set(range(1, 67)) - set(GETBIBLE_DC)
    # Unmapped book numbers are always rejected rather than guessed.
    if extra:
        sys.exit(f"ERROR: unmapped book number(s) {sorted(extra)} — not protocanon 1-66 "
                 f"and not in GETBIBLE_DC. Add them to GETBIBLE_DC or drop them.")
    # The full 66-book canon is required UNLESS --allow-partial is given (NT-only / partial
    # editions like the Emphatic Diaglott, which carries only books 40-66).
    if missing and not a.allow_partial:
        sys.exit(f"ERROR: missing protocanon book(s) {sorted(missing)}. "
                 f"The 66-book Protestant canon is required "
                 f"(pass --allow-partial for NT-only / partial editions).")
    if missing and a.allow_partial:
        present = sorted(set(books) & set(range(1, 67)))
        print(f"--allow-partial: {len(present)} of 66 books present "
              f"(skipping {len(missing)} missing): {present}")
    total = sum(len(vs) for ch in books.values() for vs in ch.values())

    yr = f' year="{a.year}"' if a.year else ""
    src = f' source={quoteattr(a.source)}' if a.source else ""
    out = ['<text xmlns="http://religioustext.org/schema/1.0" '
           f'id={quoteattr(a.id)} type="bible" translation={quoteattr(a.translation)} '
           f'abbreviation={quoteattr(a.abbr)} bcp47Language={quoteattr(a.lang)} '
           f'iso639_3={quoteattr(a.iso3)} direction={quoteattr(a.direction)} '
           f'license={quoteattr(a.license)} region={quoteattr(a.region)}{yr}{src} '
           f'totalVerses="{total}">']
    for bn in sorted(books):
        if bn <= 66:
            code, name, test = CANON[bn]
            order = bn
        else:
            code, order, name = GETBIBLE_DC[bn]
            test = "DC"
        out.append(f'<book code="{code}" name={quoteattr(name)} canonicalOrder="{order}" testament="{test}">')
        for cn in sorted(books[bn]):
            out.append(f'<chapter number="{cn}">')
            for vn, txt in sorted(books[bn][cn]):
                out.append(f'<verse number="{vn}" bookName={quoteattr(name)} chapterNumber="{cn}">{escape(txt)}</verse>')
            out.append('</chapter>')
        out.append('</book>')
    out.append('</text>')
    xml = "".join(out).encode("utf-8")
    print(f"built {a.id}: {len(books)} books, {total} verses, {len(xml)} bytes")

    if a.dry_run:
        print("dry-run: not PUTting")
        return

    # Store the resource WITH a .xml extension: every reader query opens documents
    # as db:open(db, sourceId || '.xml'), so the path must carry the extension or
    # the book list / verse queries silently return nothing.
    url = f"{a.basex}/{a.database}/{a.id}.xml"
    req = urllib.request.Request(url, data=xml, method="PUT",
                                 headers={"Content-Type": "application/xml; charset=UTF-8"})
    import base64
    tok = base64.b64encode(f"{a.user}:{a.password}".encode()).decode()
    req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req) as resp:
        print("BaseX:", resp.read().decode().strip())

if __name__ == "__main__":
    main()
