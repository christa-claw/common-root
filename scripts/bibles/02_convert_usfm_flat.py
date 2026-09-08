#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Convert an ebible.org USFM bundle into the flat JSON that
import_json_bible.py --format flat consumes.

Input:  a .zip as downloaded from https://ebible.org/Scriptures/<id>_usfm.zip
        (e.g. hlt_usfm.zip), OR a directory of .usfm files.
Output: {"metadata":{...}, "verses":[{"book":1-66,"chapter":N,"verse":N,"text":"..."}]}

Book identity is taken from each file's \\id line (USFM code), never the
filename. Only the 66-book Protestant canon is emitted; anything else (FRT
front matter, GLO glossary, deuterocanon) is skipped with a notice — extend
CODE_TO_NUM if a DC-carrying edition ever needs it.

Kept: verse text, including continuation lines (\\p/\\q/\\m/... between verses)
      and the inner text of character markers (\\add, \\nd, \\wj, \\qs, ...).
Dropped: section headings (\\s*), parallel refs (\\r), psalm superscriptions
      (\\d), footnotes (\\f...\\f*), cross-references (\\x...\\x*), figures,
      \\w word|gloss\\w* glosses (word kept), alternate verse numbers.

Stdlib only (zipfile/re/json/argparse) — no pip on this Mac (libexpat issue),
and no xml.etree, matching the other repo scripts.

Example (HLT — Hindi Literal Text):
  curl -L -o /tmp/hlt_usfm.zip https://ebible.org/Scriptures/hlt_usfm.zip
  python3 scripts/bibles/02_convert_usfm_flat.py --input /tmp/hlt_usfm.zip \
      --output /tmp/hlt-flat.json
  python3 scripts/bibles/03_import_json_bible.py --input /tmp/hlt-flat.json --format flat \
      --id bible-hlt --abbr HLT --translation "Hindi Literal Text (HLT)" \
      --lang hi --iso3 hin --license "CC BY-SA 4.0 — Bridge Connectivity Solutions" \
      --region Protestant --source "https://ebible.org/details.php?id=hlt"
  # then, MANDATORY for the JSON-import path (session 26):
  #   consolidate_basex.py -> stamp_canonical.py --id bible-hlt -> stamp_chronological.py
"""
import argparse, io, json, os, re, sys, zipfile

# USFM code -> canonical book number (matches CANON in import_json_bible.py).
CODE_TO_NUM = {
 "GEN":1,"EXO":2,"LEV":3,"NUM":4,"DEU":5,"JOS":6,"JDG":7,"RUT":8,"1SA":9,"2SA":10,
 "1KI":11,"2KI":12,"1CH":13,"2CH":14,"EZR":15,"NEH":16,"EST":17,"JOB":18,"PSA":19,
 "PRO":20,"ECC":21,"SNG":22,"ISA":23,"JER":24,"LAM":25,"EZK":26,"DAN":27,"HOS":28,
 "JOL":29,"AMO":30,"OBA":31,"JON":32,"MIC":33,"NAM":34,"HAB":35,"ZEP":36,"HAG":37,
 "ZEC":38,"MAL":39,"MAT":40,"MRK":41,"LUK":42,"JHN":43,"ACT":44,"ROM":45,"1CO":46,
 "2CO":47,"GAL":48,"EPH":49,"PHP":50,"COL":51,"1TH":52,"2TH":53,"1TI":54,"2TI":55,
 "TIT":56,"PHM":57,"HEB":58,"JAS":59,"1PE":60,"2PE":61,"1JN":62,"2JN":63,"3JN":64,
 "JUD":65,"REV":66,
}

# ── marker stripping ─────────────────────────────────────────────────────────
RE_NOTE   = re.compile(r"\\(f|fe|x)\s.*?\\\1\*", re.S)          # \f ... \f*, \x ... \x*
RE_FIG    = re.compile(r"\\fig\s.*?\\fig\*", re.S)
RE_WORD   = re.compile(r"\\\+?w\s+([^|\\]*)(?:\|[^\\]*)?\\\+?w\*")  # \w text|gloss\w* -> text
RE_CHAR   = re.compile(r"\\\+?[a-z0-9]+\s*\*?")                  # any remaining \marker / \marker*
RE_SPACE  = re.compile(r"\s+")

def clean(text):
    text = RE_NOTE.sub("", text)
    text = RE_FIG.sub("", text)
    text = RE_WORD.sub(r"\1", text)
    text = RE_CHAR.sub(" ", text)      # drops \add \nd \wj \qs etc., keeps their inner text
    text = text.replace("~", " ")      # USFM no-break space
    return RE_SPACE.sub(" ", text).strip()

# Paragraph-type markers whose TEXT continues the current verse.
CONTINUATION = re.compile(r"^\\(p|m|pi\d?|q\d?|qr|qc|qm\d?|mi|nb|pc|ph\d?|li\d?|b|po|pr|cls)\b\s*(.*)$")
# Markers whose whole line is discarded (headings, refs, superscriptions, chapter labels...).
DISCARD = re.compile(r"^\\(id|usfm|ide|sts|rem|h|toc\d?|toca\d?|mt\d?|mte\d?|ms\d?|mr|s\d?|sr|r|d|sp|sd\d?|cl|cp|cd|va|vp|ib|ie|imt\d?|is\d?|ip|ipi|im|imi|ili\d?|iot|io\d?|iex|periph|esb|esbe|ef|ex)\b")

def parse_usfm(text, path, verses, warn):
    m = re.search(r"^\\id\s+([A-Z0-9]{3})", text, re.M)
    if not m:
        warn.append(f"skip (no \\id): {path}")
        return
    code = m.group(1)
    book = CODE_TO_NUM.get(code)
    if book is None:
        warn.append(f"skip (non-canon \\id {code}): {path}")
        return
    chapter, verse, buf = 0, 0, []

    def flush():
        if verse > 0 and chapter > 0:
            t = clean(" ".join(buf))
            if t:
                verses.append({"book": book, "chapter": chapter, "verse": verse, "text": t})

    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("\\c "):
            flush(); verse, buf = 0, []
            try: chapter = int(re.match(r"\\c\s+(\d+)", line).group(1))
            except Exception: chapter = 0
            continue
        vm = re.match(r"\\v\s+(\d+)(?:[-\u2013,]\d+)?\s*(.*)$", line)  # "1" or bridged "1-2" -> first
        if vm:
            flush()
            verse, buf = int(vm.group(1)), [vm.group(2)]
            continue
        cm = CONTINUATION.match(line)
        if cm:
            if verse > 0 and cm.group(2):
                buf.append(cm.group(2))
            continue
        if DISCARD.match(line):
            continue
        if verse > 0:                    # bare continuation text inside a verse
            buf.append(line)
    flush()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help=".zip from ebible.org or a directory of .usfm files")
    ap.add_argument("--output", required=True, help="flat JSON path for import_json_bible.py")
    args = ap.parse_args()

    verses, warn = [], []
    if os.path.isdir(args.input):
        names = sorted(n for n in os.listdir(args.input) if n.lower().endswith((".usfm", ".sfm")))
        for n in names:
            with io.open(os.path.join(args.input, n), encoding="utf-8-sig") as f:
                parse_usfm(f.read(), n, verses, warn)
    else:
        with zipfile.ZipFile(args.input) as z:
            for n in sorted(z.namelist()):
                if n.lower().endswith((".usfm", ".sfm")):
                    parse_usfm(z.read(n).decode("utf-8-sig"), n, verses, warn)

    for w in warn:
        print("NOTE:", w, file=sys.stderr)

    books = sorted({v["book"] for v in verses})
    if not verses:
        sys.exit("ERROR: no verses parsed — wrong input?")
    print(f"Parsed {len(verses)} verses across {len(books)} books "
          f"(book numbers {books[0]}..{books[-1]})")
    missing = sorted(set(range(1, 67)) - set(books))
    if missing:
        print("WARNING: missing canonical books:", missing, file=sys.stderr)

    verses.sort(key=lambda v: (v["book"], v["chapter"], v["verse"]))
    with io.open(args.output, "w", encoding="utf-8") as f:
        json.dump({"metadata": {"converter": "convert_usfm_flat.py"}, "verses": verses},
                  f, ensure_ascii=False)
    print("Wrote", args.output)

if __name__ == "__main__":
    main()
