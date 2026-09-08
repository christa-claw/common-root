#!/usr/bin/env python3
"""
ingest_originals.py - ingest original-language scripture from a local
wldeh/bible-api clone into the Common Root? BaseX schema, as type="bible"
documents.

Why type="bible": these slot into the reader exactly like the translations
(standalone columns, full display modes, grouped by language) AND share the
USFM book-code space, so a user comment on JHN 3:16 attaches to the verse in
every Bible column INCLUDING the original-language one. The original text is
the real draw for the comment/argument layer (the Greek behind a NT proof-text,
the Hebrew behind an OT one).

Clone layout (per edition):
    <bible-dir>/books/<englishname>/chapters/<n>.json
    each {"data":[{"book":"Genesis","chapter":"1","verse":"1","text":"..."}, ...]}
    (the chapter-level files; the parallel chapters/<n>/verses/<v>.json per-verse
     files are ignored - the chapter files already hold every verse).

Schema EXACTLY matches scripts/bibles/03_import_json_bible.py (book @code/@name/
@canonicalOrder/@testament; chapter @number; verse @number/@bookName/
@chapterNumber; no globalCanonicalSeq - the reader falls back to
canonicalOrder/chapter/verse, same as the other clone-imported Bibles). Unlike
that importer this ALLOWS partial canons (WLC is OT-only; a Greek NT is NT-only).

Repeatable: re-running PUTs (replaces) the document. Stdlib only (urllib, like
import_json_bible.py - Python's RestTemplate-prolog issue is a Java problem).

Usage:
    python3 ingest_originals.py --preset wlc
    python3 ingest_originals.py --preset wlc --dry-run
"""
import argparse
import base64
import glob
import json
import os
import sys
import urllib.request
from xml.sax.saxutils import escape, quoteattr

# canonicalOrder -> (USFM code, English book name, testament). Identical to
# scripts/bibles/03_import_json_bible.py, so the codes match the rest of the corpus.
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
# English book name (lowercased) -> canonicalOrder. A few common aliases are
# added so a clone's naming ("Song of Solomon", "Psalms") still resolves.
NAME_TO_ORDER = {name.lower(): order for order, (code, name, test) in CANON.items()}
NAME_TO_ORDER.update({
    "psalms": 19, "song of solomon": 22, "songs": 22, "canticles": 22,
    "revelation of john": 66, "the revelation": 66,
})

SRC_ROOT = os.environ.get("BIBLE_API_SOURCE", os.path.expanduser("~/bible-api-source"))
CLONE = SRC_ROOT + "/bibles"

# Ready-to-run editions. Hebrew is wired; the Greek slot is intentionally left
# for a decision (the clone only has Byzantine-1904, not the Textus Receptus).
PRESETS = {
    "wlc": {
        "kind": "wldeh",
        "bible_dir": CLONE + "/he-wlc",
        "id": "bible-he-wlc", "abbr": "WLC",
        "translation": "Westminster Leningrad Codex",
        "lang": "he", "iso3": "hbo", "direction": "rtl",
        "region": "Hebrew Bible", "license": "Public Domain",
        "source": "wldeh/bible-api (Westminster Leningrad Codex; United Bible Societies)",
    },
    "tr": {
        "kind": "getbible",
        "getbible_file": SRC_ROOT + "/getbible/textusreceptus.json",
        "id": "bible-grc-tr", "abbr": "TR",
        "translation": "Textus Receptus (1550/1894)",
        "lang": "grc", "iso3": "grc", "direction": "ltr",
        "region": "Received Text", "license": "Public Domain",
        "source": "getbible.net v2 (NT Textus Receptus 1550/1894; Stephanus/Scrivener)",
    },
}


def load_clone(bible_dir):
    """Read the chapter-level files -> {canonicalOrder: {chapter: [(verse, text)]}}."""
    pattern = os.path.join(bible_dir, "books", "*", "chapters", "*.json")
    files = glob.glob(pattern)
    if not files:
        sys.exit("ERROR: no chapter files at %s" % pattern)
    books = {}
    for fp in files:
        with open(fp, encoding="utf-8") as f:
            rows = json.load(f).get("data", [])
        for r in rows:
            name = str(r.get("book", "")).strip().lower()
            if name.startswith("the "):   # clone uses "The Song of Songs"
                name = name[4:]
            order = NAME_TO_ORDER.get(name)
            if order is None:
                sys.exit("ERROR: unmapped book name %r (in %s)" % (r.get("book"), fp))
            ch = int(r["chapter"])
            vn = int(r["verse"])
            txt = (r.get("text") or "").strip()
            books.setdefault(order, {}).setdefault(ch, []).append((vn, txt))
    return books


def load_getbible(path):
    """Read a getbible.net v2 JSON -> {canonicalOrder: {chapter: [(verse, text)]}}.
    Book nr 40-66 are the NT and map straight onto CANON; any nr outside 1-66
    (Apocrypha) is rejected rather than guessed (the TR is NT-only anyway)."""
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    books = {}
    for b in d["books"]:
        nr = int(b["nr"])
        if nr not in CANON:
            sys.exit("ERROR: getbible book nr %d not in the 66-book canon" % nr)
        for ch in b["chapters"]:
            cn = int(ch["chapter"])
            for v in ch["verses"]:
                books.setdefault(nr, {}).setdefault(cn, []).append(
                    (int(v["verse"]), (v.get("text") or "").strip()))
    return books


def build_xml(books, p):
    total = sum(len(vs) for ch in books.values() for vs in ch.values())
    src = (" source=" + quoteattr(p["source"])) if p.get("source") else ""
    out = ['<text xmlns="http://religioustext.org/schema/1.0" '
           "id=" + quoteattr(p["id"]) + ' type="bible" '
           "translation=" + quoteattr(p["translation"]) + " "
           "abbreviation=" + quoteattr(p["abbr"]) + " "
           "bcp47Language=" + quoteattr(p["lang"]) + " "
           "iso639_3=" + quoteattr(p["iso3"]) + " "
           "direction=" + quoteattr(p["direction"]) + " "
           "license=" + quoteattr(p.get("license", "Public Domain")) + " "
           "region=" + quoteattr(p["region"]) + src + " "
           'totalVerses="%d">' % total]
    for order in sorted(books):
        code, name, test = CANON[order]
        out.append('<book code="%s" name=%s canonicalOrder="%d" testament="%s">'
                   % (code, quoteattr(name), order, test))
        for cn in sorted(books[order]):
            out.append('<chapter number="%d">' % cn)
            for vn, txt in sorted(books[order][cn]):
                out.append('<verse number="%d" bookName=%s chapterNumber="%d">%s</verse>'
                           % (vn, quoteattr(name), cn, escape(txt)))
            out.append("</chapter>")
        out.append("</book>")
    out.append("</text>")
    return "".join(out).encode("utf-8"), total, len(books)


def main():
    ap = argparse.ArgumentParser(description="Ingest original-language scripture from a wldeh clone.")
    ap.add_argument("--preset", choices=list(PRESETS), help="ready-made edition (e.g. wlc)")
    ap.add_argument("--bible-dir")
    ap.add_argument("--getbible", help="path to a getbible.net v2 JSON (NT-only ok)")
    ap.add_argument("--id")
    ap.add_argument("--abbr")
    ap.add_argument("--translation")
    ap.add_argument("--lang")
    ap.add_argument("--iso3")
    ap.add_argument("--direction", default="ltr")
    ap.add_argument("--region", default="")
    ap.add_argument("--license", default="Public Domain")
    ap.add_argument("--source", default="")
    ap.add_argument("--basex", default="http://localhost:8984/rest")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--database", default="religioustext")
    ap.add_argument("--dry-run", action="store_true", help="build + validate, don't PUT")
    a = ap.parse_args()

    if a.preset:
        p = dict(PRESETS[a.preset])
    else:
        if not all((a.id, a.abbr, a.translation, a.lang, a.iso3)):
            sys.exit("ERROR: without --preset, need --id --abbr --translation "
                     "--lang --iso3 plus --bible-dir or --getbible")
        if not (a.bible_dir or a.getbible):
            sys.exit("ERROR: need --bible-dir (wldeh clone) or --getbible (JSON file)")
        p = {"kind": "getbible" if a.getbible else "wldeh",
             "bible_dir": a.bible_dir, "getbible_file": a.getbible,
             "id": a.id, "abbr": a.abbr,
             "translation": a.translation, "lang": a.lang, "iso3": a.iso3,
             "direction": a.direction, "region": a.region or a.translation,
             "license": a.license, "source": a.source}

    if p.get("kind") == "getbible":
        books = load_getbible(p["getbible_file"])
    else:
        books = load_clone(p["bible_dir"])
    xml, total, nbooks = build_xml(books, p)
    testaments = sorted({CANON[o][2] for o in books})
    print("built %s: %d books (%s), %d verses, %d bytes"
          % (p["id"], nbooks, "/".join(testaments), total, len(xml)))

    if a.dry_run:
        print("dry-run: not PUTting")
        return

    url = "%s/%s/%s.xml" % (a.basex, a.database, p["id"])
    req = urllib.request.Request(url, data=xml, method="PUT",
                                 headers={"Content-Type": "application/xml; charset=UTF-8"})
    tok = base64.b64encode(("%s:%s" % (a.user, a.password)).encode()).decode()
    req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req) as resp:
        print("BaseX:", resp.read().decode().strip())


if __name__ == "__main__":
    main()
