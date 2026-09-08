#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
extract_diaglott_pdf.py — pull Wilson's ENGLISH TRANSLATION column out of the
scanned Emphatic Diaglott PDF and emit flat import JSON.

The Diaglott prints two columns per page: LEFT = Greek + interlinear word-gloss
(OCR-garbled, we ignore it), RIGHT = Wilson's "Emphatic Version" reading text
(what we want). We isolate the translation by PAGE GEOMETRY (x-position), which
sidesteps the garbled Greek entirely, then parse verse numbers and emit:

    {"verses": [{"book": 40-66, "chapter": N, "verse": N, "text": "..."}]}

==> The OCR is dirty. This produces a DRAFT for proofreading, not final text. <==

Dependencies:  pip install pdfplumber
PDF (download locally first):
    curl -L -o ~/Downloads/diaglott.pdf \\
      "https://antipas.org/library/The%20Bible/1864%20Emphatic%20Diaglott.pdf"

THREE MODES (run them in this order the first time):

1. CALIBRATE BOOK PAGES — dump each page's running header so we can map books:
     python3 scripts/bibles/01_extract_diaglott_pdf.py --pdf ~/Downloads/diaglott.pdf \\
         --mode headers --output /tmp/diaglott_headers.txt

2. CALIBRATE COLUMNS — show the left/right split on a few known pages so we can
   confirm the translation is the RIGHT column and where header/footnotes sit:
     python3 scripts/bibles/01_extract_diaglott_pdf.py --pdf ~/Downloads/diaglott.pdf \\
         --mode inspect --start-page 38 --end-page 41 \\
         --output /tmp/diaglott_inspect.txt

3. EXTRACT one book (e.g. Mark) once the geometry checks out:
     python3 scripts/bibles/01_extract_diaglott_pdf.py --pdf ~/Downloads/diaglott.pdf \\
         --mode extract --book Mark --start-page <X> --end-page <Y> \\
         --output ~/Downloads/diaglott-mark.json --debug /tmp/diaglott-mark.debug.txt
   then feed --output to import_json_bible.py --allow-partial --format flat.
"""
import argparse, json, re, sys

try:
    import pdfplumber
except ImportError:
    sys.exit("Missing dependency: pip install pdfplumber")

NT_BOOKS = {
    "matthew": 40, "mark": 41, "luke": 42, "john": 43, "acts": 44,
    "romans": 45, "1 corinthians": 46, "2 corinthians": 47, "galatians": 48,
    "ephesians": 49, "philippians": 50, "colossians": 51,
    "1 thessalonians": 52, "2 thessalonians": 53, "1 timothy": 54,
    "2 timothy": 55, "titus": 56, "philemon": 57, "hebrews": 58, "james": 59,
    "1 peter": 60, "2 peter": 61, "1 john": 62, "2 john": 63, "3 john": 64,
    "jude": 65, "revelation": 66,
}

# Conservative, obvious OCR fixes only — proofreading catches the rest. Extend freely.
CLEANUP = {
    "gou": "you", "Gou": "You", "Tsrael": "Israel", "tben": "then",
    "tbe": "the", "Tbe": "The",
}

# Header line for each page sits in the top band; footnotes in the bottom band.
# These are points from the top/bottom edge — tune from `inspect` output.
DEFAULT_HEADER_BAND = 44
DEFAULT_FOOTER_BAND = 0
DEFAULT_SPLIT_FRAC = 0.60  # translation column starts ~60% across; left (Greek) column is wider
HEADERREF_BAND = 70       # wider top window for READING the running header (it sits ~45-55pt down)


def words_in(page, top_skip=0, bottom_skip=0):
    """Words on the page, optionally trimming a top header band and bottom footer band."""
    h = page.height
    out = []
    for w in page.extract_words(use_text_flow=False, keep_blank_chars=False):
        if w["top"] < top_skip:
            continue
        if bottom_skip and w["bottom"] > h - bottom_skip:
            continue
        out.append(w)
    return out


def detect_split(words, page_width, override=None, frac=DEFAULT_SPLIT_FRAC):
    """X coordinate dividing the interlinear (left) from the translation (right).

    The Diaglott's gutter sits consistently near 0.60x width (the Greek column is
    wider). We look for the widest word-gap, but only inside a TIGHT band around
    that gutter (0.52-0.66) so a stray gap in the Greek can't hijack it — the bug
    that put page 39's split at x=166. Falls back to frac*width if the band is empty."""
    if override is not None:
        return override
    centres = sorted((w["x0"] + w["x1"]) / 2 for w in words)
    lo, hi = page_width * 0.54, page_width * 0.64
    mids = [c for c in centres if lo <= c <= hi]
    best_gap, best_x = 0, page_width * frac
    for a, b in zip(mids, mids[1:]):
        if b - a > best_gap:
            best_gap, best_x = b - a, (a + b) / 2
    return best_x


def column(words, split, side):
    """Words whose centre falls on the requested side of the split."""
    keep = []
    for w in words:
        c = (w["x0"] + w["x1"]) / 2
        if (side == "right" and c >= split) or (side == "left" and c < split):
            keep.append(w)
    return keep


def reconstruct(words, line_tol=3):
    """Group words into visual lines (by `top`), order them, and join to text."""
    lines = []
    for w in sorted(words, key=lambda w: (round(w["top"]), w["x0"])):
        if lines and abs(w["top"] - lines[-1]["top"]) <= line_tol:
            lines[-1]["words"].append(w)
        else:
            lines.append({"top": w["top"], "words": [w]})
    out = []
    for ln in lines:
        out.append(" ".join(x["text"] for x in sorted(ln["words"], key=lambda w: w["x0"])))
    return " ".join(out)


def drop_footnotes(words, page_height, lower_frac=0.68, gap_mult=1.6):
    """Cut Wilson's footnote block off the bottom of a column.

    The footnotes sit in a separate block at the page foot, set off from the body by
    extra leading. We find the largest vertical gap between line-tops in the lower
    part of the page; if it clearly exceeds the body's line spacing, everything below
    it is footnotes and gets dropped. Returns (kept_words, cut_y|None). This is what
    stops footnote numbers ('1.', '2.') from being mistaken for verse markers."""
    if len(words) < 6:
        return words, None
    tops = sorted({round(w["top"]) for w in words})
    gaps = [(b - a, a) for a, b in zip(tops, tops[1:])]
    if not gaps:
        return words, None
    body_gap = sorted(g for g, _ in gaps)[len(gaps) // 2] or 12
    candidates = [(g, a) for g, a in gaps if a > page_height * lower_frac]
    if not candidates:
        return words, None
    gap, at = max(candidates)
    if gap > body_gap * gap_mult and gap > 10:
        cut = at + gap / 2
        return [w for w in words if w["top"] < cut], cut
    return words, None


def strip_heads(text):
    """Remove running-head / chapter-heading noise that bleeds into the column top."""
    # OCR'd chapter headings: "CHAPTER XIV", "CHAPTEK XIII", "CHAPTER, X"
    text = re.sub(r"\bC?[HO]APTE[RUKN][.,]?\s+[IVXLCDM]+\b", " ", text)
    # running chapter:verse head numbers: "10: 26", "1; 19" (would derail verse parsing)
    text = re.sub(r"\b\d{1,3}\s*[:;]\s*\d{1,3}\b", " ", text)
    # spaced-out book titles: "M A T T H E W"
    text = re.sub(r"\b(?:[A-Z]\s){2,}[A-Z]\b", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def clean(text):
    text = re.sub(r"\s+", " ", text).strip()
    # rejoin words hyphenated across a line break: "Assa- rius" -> "Assarius"
    text = re.sub(r"(\w)[-\u2010\u2014]\s+(\w)", r"\1\2", text)
    for bad, good in CLEANUP.items():
        text = re.sub(rf"\b{re.escape(bad)}\b", good, text)
    # drop stray footnote daggers / asterisks left mid-text
    text = re.sub(r"\s*[\*\u2020\u2021]\s*", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def parse_book(pages, book_num, start_chapter=1):
    """Split per-page translation text into verses, gating chapter rollovers by the
    running-header chapter.

    Verse numbers drive the boundaries (direction over exact value, since OCR garbles
    them): a number just above the current one is the next verse; a wildly larger one
    (OCR garble, e.g. 89 for 39) is still taken as the next verse. A small number
    (<=2) after a high verse only starts a NEW CHAPTER when the running header has
    attested the book reaching a higher chapter than we're on — this is what stops
    stray '1'/'2' tokens (residual footnotes, OCR noise) from inventing phantom
    chapters. `pages` is a list of (header_chapter|None, text). Verify with --debug."""
    verses = []
    chapter, verse, buf = start_chapter, 1, []
    header_max = start_chapter

    def flush():
        if buf:
            t = clean(" ".join(buf))
            if t:
                verses.append({"book": book_num, "chapter": chapter, "verse": verse, "text": t})

    for page_ceiling, text in pages:
        header_max = page_ceiling
        for tok in text.split():
            m = re.fullmatch(r"(\d{1,3})\.?", tok)
            if m:
                n = int(m.group(1))
                if n <= 2 and verse >= 5 and chapter < header_max:  # gated chapter rollover
                    flush(); chapter += 1; verse = n; buf = []; continue
                if n == verse + 1:                  # clean next verse
                    flush(); verse = n; buf = []; continue
                if verse + 1 < n <= verse + 4:      # tolerate a missed/garbled number
                    flush(); verse = n; buf = []; continue
                if n > verse + 4:                   # badly garbled big number -> next verse
                    flush(); verse += 1; buf = []; continue
                # n <= verse and not a gated chapter reset -> stray digit, keep in text
            buf.append(tok)
    flush()
    return verses


def run_headers(pdf, start, end, out):
    lines = []
    for i in range(start - 1, end):
        page = pdf.pages[i]
        top = words_in(page, top_skip=0)
        top.sort(key=lambda w: (round(w["top"]), w["x0"]))
        header = " ".join(w["text"] for w in top[:14])
        lines.append(f"p{i+1}: {header}")
    out.write("\n".join(lines) + "\n")
    print(f"Wrote headers for pages {start}-{end} to {out.name}")


def run_inspect(pdf, start, end, side, split_override, out):
    for i in range(start - 1, end):
        page = pdf.pages[i]
        ws = words_in(page, top_skip=DEFAULT_HEADER_BAND)
        split = detect_split(ws, page.width, split_override)
        left = clean(reconstruct(column(ws, split, "left")))
        right = clean(reconstruct(column(ws, split, "right")))
        out.write(f"\n===== PAGE {i+1}  (width={page.width:.0f}, split_x={split:.0f}) =====\n")
        out.write(f"--- LEFT column (first 300 chars) ---\n{left[:300]}\n")
        out.write(f"--- RIGHT column (first 600 chars) ---\n{right[:600]}\n")
    print(f"Wrote column inspection for pages {start}-{end} to {out.name}")
    print(f"Check which side is Wilson's readable English — that's --side for extract.")


def page_header_chapter(page, nwords=16):
    """Chapter number from a page's running header (e.g. 'Chap. 2: 8' -> 2).

    The running head is the TOP-MOST line on the page (book name + two chap:verse
    refs). We read the first `nwords` words BY VERTICAL POSITION rather than a fixed
    point-band, because the text block's absolute Y varies with the scan's top margin
    (~75pt here) — a fixed band missed the header entirely. Returns the MAX chapter
    found so a boundary page reports the new chapter; None if no ref parses. Feeds
    build_ceiling / parse_book's rollover gate."""
    words = sorted(page.extract_words(use_text_flow=False, keep_blank_chars=False),
                   key=lambda w: (round(w["top"]), w["x0"]))
    txt = " ".join(w["text"] for w in words[:nwords])
    chaps = [int(m.group(1)) for m in re.finditer(r"(\d{1,3})\s*[:;]\s*\d{1,3}", txt)]
    chaps = [c for c in chaps if 1 <= c <= 150]
    return max(chaps) if chaps else None


def build_ceiling(pchaps, start_chapter=1):
    """Turn per-page header chapters (many None/garbled) into a monotonic, garble-
    resistant 'chapters the book has reached by this page' ceiling.

    Looks ONE PAGE AHEAD so a boundary page whose own new-chapter ref is mangled
    still opens the gate using the next page's clean header (e.g. Mark 4 starts on a
    page reading only '3:29', but the next page reads '4:18'). A readable chapter may
    advance the ceiling by up to +3, so a clean ref can carry across two consecutive
    chapters whose own headers are garbled (the short epistles do this); larger jumps
    (OCR junk like a stray 47) are still ignored."""
    attested = start_chapter
    out = []
    n = len(pchaps)
    for i in range(n):
        cand = []
        if pchaps[i]:
            cand.append(pchaps[i])
        if i + 1 < n and pchaps[i + 1]:
            cand.append(pchaps[i + 1])
        for c in sorted(cand):
            if attested < c <= attested + 3:
                attested = c
        out.append(attested)
    return out


def run_extract(pdf, args, book_num):
    scanned, pchaps, debug = [], [], []
    for i in range(args.start_page - 1, args.end_page):
        page = pdf.pages[i]
        pchap = page_header_chapter(page)
        ws = words_in(page, top_skip=args.header_band, bottom_skip=args.footer_band)
        split = detect_split(ws, page.width, args.split_x)
        col = column(ws, split, args.side)
        col, cut = drop_footnotes(col, page.height)
        text = strip_heads(reconstruct(col))
        scanned.append((i + 1, split, cut, pchap, text))
        pchaps.append(pchap)

    ceiling = build_ceiling(pchaps, args.start_chapter)
    pages = []
    for (pno, split, cut, pchap, text), ceil in zip(scanned, ceiling):
        pages.append((ceil, text))
        debug.append(f"--- p{pno} (split={split:.0f}, fn_cut={round(cut) if cut else '-'}, hdr_ch={pchap}, ceil={ceil}) ---\n{clean(text)}")

    verses = parse_book(pages, book_num, args.start_chapter)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump({"verses": verses}, f, ensure_ascii=False)

    chapters = sorted({v["chapter"] for v in verses})
    print(f"Wrote {len(verses)} verses to {args.output}")
    print(f"  book: {args.book} ({book_num}), chapters detected: {chapters}")
    print(f"  !! DRAFT from dirty OCR — proofread against the PDF before importing !!")
    if args.debug:
        with open(args.debug, "w", encoding="utf-8") as f:
            f.write("\n\n".join(debug))
            f.write("\n\n===== PARSED VERSES =====\n")
            for v in verses:
                f.write(f"{v['chapter']}:{v['verse']}  {v['text']}\n")
        print(f"  debug trace: {args.debug}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pdf", required=True)
    ap.add_argument("--mode", choices=["headers", "inspect", "extract"], default="extract")
    ap.add_argument("--start-page", type=int, default=1)
    ap.add_argument("--end-page", type=int)
    ap.add_argument("--book", help="NT book name (extract mode), e.g. Mark")
    ap.add_argument("--start-chapter", type=int, default=1)
    ap.add_argument("--side", choices=["left", "right"], default="right",
                    help="which column is Wilson's translation (default right; confirm via inspect)")
    ap.add_argument("--split-x", type=float, default=None,
                    help="manual column boundary x; default auto-detects the gutter")
    ap.add_argument("--header-band", type=float, default=DEFAULT_HEADER_BAND,
                    help="points from top to drop (running header)")
    ap.add_argument("--footer-band", type=float, default=DEFAULT_FOOTER_BAND,
                    help="points from bottom to drop (footnotes)")
    ap.add_argument("--output", required=True)
    ap.add_argument("--debug", default=None)
    a = ap.parse_args()

    with pdfplumber.open(a.pdf) as pdf:
        end = a.end_page or len(pdf.pages)
        if a.mode == "headers":
            with open(a.output, "w", encoding="utf-8") as out:
                run_headers(pdf, a.start_page, end, out)
            return
        if a.mode == "inspect":
            with open(a.output, "w", encoding="utf-8") as out:
                run_inspect(pdf, a.start_page, end, a.side, a.split_x, out)
            return
        # extract
        if not a.book:
            sys.exit("--book is required in extract mode")
        num = NT_BOOKS.get(re.sub(r"\s+", " ", a.book.strip().lower()))
        if num is None:
            sys.exit(f"Unrecognized book {a.book!r}")
        a.end_page = end
        run_extract(pdf, a, num)


if __name__ == "__main__":
    main()
