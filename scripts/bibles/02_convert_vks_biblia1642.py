#!/usr/bin/env python3
"""Convert the Kotus VKS Biblia 1642 corpus (saved kaino.kotus.fi HTML pages)
into the flat-JSON shape accepted by 03_import_json_bible.py --format flat.

INPUT: a directory of pages saved (e.g. with SingleFile) from
  https://kaino.kotus.fi/korpus/vks/meta/biblia/…
Each scripture page carries verse markers of the form
  [B1-<bookCode>-<chapter>:<verse>-<page><column>]
where the marker FOLLOWS the verse text it closes. Verse 0 segments are
front matter, chapter summaries and the 1642 marginal notes — skipped here
(counted in the report; a future feature could ingest them as comments).
A verse token may carry a trailing caret (e.g. `0:65^`) — treated as its
numeric part. A verse split across a page/column boundary appears as two
consecutive markers with the same address; the segments are concatenated.

Apocrypha addition booklets (lisEst a-f, lisDan a-c, lis2Ak) are marked as
chapter 0 with continuous verse numbers. They map onto the getbible DC book
numbers already used by the importer (ESG gets the six Esther additions as
chapters 1-6; Susanna / Song of the Three / Bel identified by incipit).

The registers (1rek/2rek) and the front-matter page contain no canonical
book codes and are skipped with a log line.

Example:
  python3 02_convert_vks_biblia1642.py --input sources/vks \
      --output sources/vks/biblia1642_flat.json
  python3 03_import_json_bible.py --input sources/vks/biblia1642_flat.json \
      --format flat --id bible-fi-1642 --abbr FB1642 \
      --translation "Biblia 1642" --lang fi --iso3 fin --year 1642 \
      --license "Public Domain" --region Protestant \
      --source "https://kaino.kotus.fi/korpus/vks/meta/biblia/biblia_coll_rdf.xml" \
      --allow-partial --dry-run
  (--allow-partial is REQUIRED until the missing "Uuden testamentin II osa"
   page — Romans through Revelation — has been saved into the input dir.)
"""
import argparse, collections, glob, html as htmlmod, json, os, re, sys

# VKS book code -> flat/getbible book number.
# 1-66 protocanon; >66 are the getbible DC numbers 03_import_json_bible.py maps
# (69 Tob, 70 Jdt, 71 ESG, 73 Wis, 74 Sir, 75 Bar, 76 S3Y, 77 SUS, 78 BEL,
#  79 MAN, 80 1Ma, 81 2Ma).
BOOK_MAP = {
    "1Ms":1, "2Ms":2, "3Ms":3, "4Ms":4, "5Ms":5,
    "Jos":6, "Tm":7, "Rt":8, "1Sm":9, "2Sm":10, "1Kn":11, "2Kn":12,
    "1Ak":13, "2Ak":14, "Esr":15, "Neh":16, "Est":17,
    "Job":18, "Ps":19, "Snl":20, "Srn":21, "KV":22,
    "Jes":23, "Jer":24, "Vlt":25, "Hes":26, "Dan":27,
    "Hos":28, "Joel":29, "Am":30, "Ob":31, "Jon":32, "Mik":33,
    "Nah":34, "Hab":35, "Sef":36, "Hgg":37, "Sak":38, "Mal":39,
    "Mt":40, "Mk":41, "Lk":42, "Jh":43, "Ap":44,
    # UT II osa — the 1642 prints these in LUTHER's order (Heb/Jak/Jud/Ilm
    # last); canonical numbers reorder them, matching the rest of the corpus.
    "Rom":45, "1Kor":46, "2Kor":47, "Gl":48, "Ef":49, "Fil":50, "Kol":51,
    "1Tss":52, "2Tss":53, "1Tim":54, "2Tim":55, "Tit":56, "Flm":57,
    "Heb":58, "Jak":59, "1Pt":60, "2Pt":61, "1Jh":62, "2Jh":63, "3Jh":64,
    "Jud":65, "Ilm":66,
    "Tob":69, "Judit":70, "Viis":73, "Siir":74, "Bar":75,
    "1Mkk":80, "2Mkk":81,
    "lisDana":77,   # Susanna           ("OLi yxi mies Babelis Jojakim nimeldä")
    "lisDanb":78,   # Bel and the Dragon("AStyagexen cuoleman jälken…")
    "lisDanc":76,   # Song of the Three (Prayer of Azariah)
    "lis2Ak":79,    # Prayer of Manasseh("HERRA Caickiwaldias / meidän Isäim…")
}
# Esther additions A-F: one getbible book (71 = ESG), part letter -> chapter.
LIS_EST = {"lisEsta":1, "lisEstb":2, "lisEstc":3, "lisEstd":4, "lisEste":5, "lisEstf":6}

MARKER = re.compile(r"\[B1-([^-\]]+)-(\d+):(\d+)\^?-([^\]]+)\]")

def page_text(path):
    raw = open(path, encoding="utf-8", errors="replace").read()
    txt = re.sub(r"<script.*?</script>|<style.*?</style>", " ", raw, flags=re.S)
    txt = re.sub(r"<[^>]+>", " ", txt)
    txt = htmlmod.unescape(txt)
    return re.sub(r"\s+", " ", txt)

def parse_file(path):
    """-> {(bookNum, chapter, verse): text} for this file; plus stats."""
    parts = MARKER.split(page_text(path))
    verses, skipped_codes, note_segs = {}, collections.Counter(), 0
    # parts = [pre, code, ch, vs, page, pre, code, ...]
    for i in range(0, len(parts) - 4, 5):
        seg  = parts[i].strip()
        code = parts[i + 1]
        ch, vs = int(parts[i + 2]), int(parts[i + 3])
        if vs == 0:                      # front matter / summaries / marginal notes
            note_segs += 1
            continue
        if code in LIS_EST:
            bn, ch = 71, LIS_EST[code]
        elif code in BOOK_MAP:
            bn = BOOK_MAP[code]
            if ch == 0:                  # addition booklets: continuous numbering
                ch = 1
        else:
            skipped_codes[code] += 1
            continue
        key = (bn, ch, vs)
        # Same address twice in a row = page/column continuation: concatenate.
        verses[key] = (verses[key] + " " + seg).strip() if key in verses else seg
    return verses, skipped_codes, note_segs

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="directory of saved VKS HTML pages")
    ap.add_argument("--output", required=True, help="flat JSON output path")
    a = ap.parse_args()

    merged, conflicts = {}, 0
    files = sorted(glob.glob(os.path.join(a.input, "*.html")))
    if not files:
        sys.exit(f"ERROR: no .html files in {a.input}")

    for f in files:
        verses, skipped, notes = parse_file(f)
        base = os.path.basename(f)[:48]
        if not verses:
            print(f"SKIP (no canonical verses): {base}  "
                  f"[non-canonical codes: {dict(skipped) or 'none'}]")
            continue
        dup_same = dup_diff = new = 0
        for k, txt in verses.items():
            if k in merged:
                if merged[k] == txt:
                    dup_same += 1        # duplicate save of the same page
                else:
                    dup_diff += 1        # conflicting text: keep first, warn below
            else:
                merged[k] = txt
                new += 1
        conflicts += dup_diff
        print(f"OK   {base}  +{new} verses "
              f"(dupes: {dup_same} identical, {dup_diff} CONFLICTING; "
              f"note/summary segments skipped: {notes})")

    empties = [k for k, t in merged.items() if not t]
    per_book = collections.Counter(bn for bn, _, _ in merged)
    print(f"\nTOTAL: {len(merged)} verses across {len(per_book)} books; "
          f"{len(empties)} empty, {conflicts} conflicting duplicates")
    if empties:
        print("EMPTY:", sorted(empties)[:20], "…" if len(empties) > 20 else "")
    missing_nt = [n for n in range(45, 67) if n not in per_book]
    if missing_nt:
        print(f"NOTE: NT books {missing_nt[0]}-{missing_nt[-1]} absent — "
              f"save 'Uuden testamentin II osa' from kaino and re-run.")

    out = {
        "metadata": {"name": "Biblia 1642", "language": "Finnish",
                     "source": "Kotus VKS corpus (kaino.kotus.fi), CC BY / PUB"},
        "verses": [{"book": bn, "chapter": ch, "verse": vs, "text": merged[(bn, ch, vs)]}
                   for bn, ch, vs in sorted(merged)],
    }
    with open(a.output, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False)
    print(f"wrote {a.output} ({os.path.getsize(a.output)} bytes)")

if __name__ == "__main__":
    main()
