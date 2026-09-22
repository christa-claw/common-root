#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
02_convert_sefaria_commentary.py — turn a Sefaria v3 JSON drop (as fetched by the
weekly corpus-fetch task into sources/commentaries/) into ONE religious-text.xsd
<text> document per language, and optionally PUT it into BaseX.

WHY THIS SHAPE
  There is no commentary pipeline and no commentary renderer. What the reader
  *does* have is a catalogue built straight from BaseX — TextQueryService.
  listSources() is "every rt:text in the database" — and a generic
  book > chapter > verse reader that syncs columns by reference. So a commentary
  becomes displayable the moment it is expressed in that same shape:

      book   = the SCRIPTURE book being commented on ("Isaiah"), so the column
               syncs verse-by-verse against any Bible column.
      verse  = every comment Sefaria anchors at that verse, joined into one
               string (the schema's verse is simpleContent — no structure).
      gaps   = verses with no comment are simply absent. The reader tolerates a
               sparse edition the same way it tolerates an NT-only one.

  This is deliberately a *projection*, not a model: one comment per verse loses
  Sefaria's per-comment boundaries, and a commentary that addresses a whole
  chapter has nowhere to sit. Both are recorded in @note-free plain text here and
  in docs/, and both are the reason a real COMMENTARY type would eventually want
  its own element rather than borrowing <verse>.

USAGE
  python3 scripts/commentaries/02_convert_sefaria_commentary.py \
      --input sources/commentaries/sefaria/ibn-ezra-on-isaiah-friedlander-1873/Ibn_Ezra_on_Isaiah.json \
      --lang en --id commentary-ibn-ezra-isaiah-en --abbr IE-ISA-EN \
      --translation "Ibn Ezra on Isaiah (Friedlander, 1873)" --year 1873 \
      --database commentary_test           # --dry-run to build without PUTting

  Env/flags for BaseX match 03_import_json_bible.py: --basex/--user/--password/--database.

No third-party deps — stdlib only.
"""
import argparse, html, importlib, json, re, sys, urllib.request, base64
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "bibles"))
# Numbered sibling: plain import can't take a leading digit (COMMANDS.md convention).
CANON = importlib.import_module("03_import_json_bible").CANON
BY_NAME = {name: (code, num, test) for num, (code, name, test) in CANON.items()}

FOOTNOTE_MARKER = re.compile(r"<sup[^>]*class=\"footnote-marker\"[^>]*>.*?</sup>", re.S)
FOOTNOTE_BODY   = re.compile(r"<i[^>]*class=\"footnote\"[^>]*>(.*?)</i>", re.S)
TAG             = re.compile(r"<[^>]+>")
WS              = re.compile(r"\s+")


def clean(fragment):
    """Sefaria comment HTML -> plain text. Footnote markers go; footnote bodies
    survive in parentheses; every other tag is dropped and entities decoded."""
    s = FOOTNOTE_MARKER.sub("", fragment)
    s = FOOTNOTE_BODY.sub(lambda m: " (" + m.group(1) + ")", s)
    s = TAG.sub("", s)
    return WS.sub(" ", html.unescape(s)).strip()


def comments_for(cell):
    """One Sefaria verse cell -> list of cleaned comment strings."""
    if cell is None:
        return []
    if isinstance(cell, str):
        c = clean(cell)
        return [c] if c else []
    out = []
    for item in cell:
        out.extend(comments_for(item))
    return out


def pick_version(doc, lang):
    for v in doc.get("versions", []):
        if (v.get("actualLanguage") or v.get("language")) == lang:
            return v
    sys.exit(f"ERROR: no version with language {lang!r} in this file "
             f"(have: {[v.get('actualLanguage') for v in doc.get('versions', [])]})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--lang", required=True, help="actualLanguage of the version to convert (en, he)")
    ap.add_argument("--id", required=True)
    ap.add_argument("--abbr", required=True)
    ap.add_argument("--translation", required=True)
    ap.add_argument("--book", help="scripture book commented on (default: parsed from the Sefaria title after ' on ')")
    ap.add_argument("--type", default="commentary", help="@type written into the document")
    ap.add_argument("--iso3", default="")
    ap.add_argument("--year", default="")
    ap.add_argument("--license", default="")
    ap.add_argument("--region", default="Jewish")
    ap.add_argument("--direction", default="")
    ap.add_argument("--source", default="")
    ap.add_argument("--join", default=" ¶ ", help="separator between several comments on one verse")
    ap.add_argument("--basex", default="http://localhost:8984/rest")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--database", default="commentary_test")
    ap.add_argument("--out", help="also write the XML to this path")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    doc = json.loads(Path(a.input).read_text(encoding="utf-8"))
    version = pick_version(doc, a.lang)

    title = doc.get("indexTitle") or doc.get("book") or ""
    book = a.book or (title.split(" on ", 1)[1] if " on " in title else "")
    if book not in BY_NAME:
        sys.exit(f"ERROR: book {book!r} (from {title!r}) is not one of the 66 canon names — pass --book.")
    code, order, testament = BY_NAME[book]

    direction = a.direction or ("rtl" if a.lang in ("he", "ar") else "ltr")
    iso3 = a.iso3 or {"en": "eng", "he": "heb"}.get(a.lang, a.lang)
    license_ = a.license or version.get("license") or ""
    source = a.source or version.get("versionSource") or ""

    chapters = version.get("text") or []
    body, verses, comments = [], 0, 0
    for ci, cell in enumerate(chapters, start=1):
        rows = []
        for vi, vcell in enumerate(cell or [], start=1):
            cs = comments_for(vcell)
            if not cs:
                continue                      # gap: this verse has no comment
            rows.append(f'<verse number="{vi}" bookName={quoteattr(book)} chapterNumber="{ci}">'
                        + escape(a.join.join(cs)) + '</verse>')
            verses += 1
            comments += len(cs)
        if rows:
            body.append(f'<chapter number="{ci}">' + "".join(rows) + "</chapter>")

    if not body:
        sys.exit("ERROR: no comments found — wrong --lang, or an unexpected JSON shape.")

    yr  = f' year="{a.year}"' if a.year else ""
    src = f' source={quoteattr(source)}' if source else ""
    lic = f' license={quoteattr(license_)}' if license_ else ""
    xml = ('<text xmlns="http://religioustext.org/schema/1.0" '
           f'id={quoteattr(a.id)} type={quoteattr(a.type)} translation={quoteattr(a.translation)} '
           f'abbreviation={quoteattr(a.abbr)} bcp47Language={quoteattr(a.lang)} '
           f'iso639_3={quoteattr(iso3)} direction={quoteattr(direction)}'
           f'{lic} region={quoteattr(a.region)}{yr}{src} totalVerses="{verses}">'
           f'<book code={quoteattr(code)} name={quoteattr(book)} canonicalOrder="{order}" '
           f'testament={quoteattr(testament)}>' + "".join(body) + "</book></text>").encode("utf-8")

    print(f"built {a.id}: {book} ({code}), {len(body)} chapters, {verses} commented verses, "
          f"{comments} comments, {len(xml)} bytes, licence {license_!r}")
    if a.out:
        Path(a.out).write_bytes(xml)
        print("wrote", a.out)
    if a.dry_run:
        print("dry-run: not PUTting")
        return

    # .xml extension is load-bearing: every reader query is db:open(db, id || '.xml').
    req = urllib.request.Request(f"{a.basex}/{a.database}/{a.id}.xml", data=xml, method="PUT",
                                 headers={"Content-Type": "application/xml; charset=UTF-8"})
    req.add_header("Authorization", "Basic " +
                   base64.b64encode(f"{a.user}:{a.password}".encode()).decode())
    with urllib.request.urlopen(req) as resp:
        print("BaseX:", resp.read().decode().strip())


if __name__ == "__main__":
    main()
