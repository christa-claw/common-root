#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Right-to-left probe: can this toolchain set Hebrew and Arabic scripture?

SUPERSEDED: this layout now lives in build_pdf.py (RtlChapter / RtlPiece), which sets
Arabic and Hebrew with the full page composer. Kept as the standalone proof.

NOT the generator. build_pdf.py sets left-to-right text through ReportLab's
Paragraph, and Paragraph can only run right-to-left with `rlbidi`, which
ReportLab does not publish on PyPI (its own index wants a login). This probe
answers the question that matters before any of that is solved: do the shapes
come out right?

It needs only `uharfbuzz` (pip install uharfbuzz), and does the three things a
right-to-left page needs by hand:

  SHAPING     each word goes through HarfBuzz, which joins Arabic letters and
              seats Hebrew points and cantillation marks on their consonants
  DIRECTION   scripture is pure right-to-left text — the only left-to-right
              runs are the verse numbers, which are set separately — so a line
              is laid out by placing shaped words from the right edge leftwards.
              No bidi algorithm is needed for that.
  MIRRORING   the first column is the RIGHT one, the drop cap sits on the
              right, and the gutter swaps sides the other way round
  PAGE RULES  as in the English layout: the book heading is a rule across the
              WHOLE page over columns balanced to end level, one empty line
              follows it, and a page-wide rule closes the last book only

USAGE
    python3 scripts/print/rtl_probe.py --translation bible-he-wlc \\
        --font fonts/ezra/SILEOT.ttf --digits fonts/crimson/CrimsonPro-Regular.ttf \\
        --verses 120 --out out/print/rtl-wlc.pdf
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from reportlab.lib.units import mm                      # noqa: E402
from reportlab.pdfbase import pdfmetrics                # noqa: E402
from reportlab.pdfbase.pdfmetrics import stringWidth    # noqa: E402
from reportlab.pdfbase.ttfonts import TTFont, shapeStr  # noqa: E402
from reportlab.pdfgen import canvas                     # noqa: E402

from build_edition import stream_verses, xq, NS         # noqa: E402
from build_pdf import localised_book_names              # noqa: E402

W, H = 140 * mm, 216 * mm
INNER, OUTER, TOP, BOTTOM, GAP = 17 * mm, 12 * mm, 14 * mm, 15 * mm, 5 * mm


def shaped(word, font, size):
    s = shapeStr(word, font, size, force=True)
    data = getattr(s, "__shapeData__", None)
    w = (sum(d.x_advance for d in data) * size / 1000.0 if data
         else stringWidth(word, font, size))
    return s, w


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--translation", required=True)
    ap.add_argument("--font", required=True, help="the script's .ttf")
    ap.add_argument("--digits", required=True, help="a .ttf for verse numbers")
    ap.add_argument("--verses", type=int, default=120)
    ap.add_argument("--size", type=float, default=10.0)
    ap.add_argument("--leading", type=float, default=15.0)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    pdfmetrics.registerFont(TTFont("Body", a.font))
    pdfmetrics.registerFont(TTFont("Digits", a.digits, shapable=False))
    size, leading = a.size, a.leading
    space = stringWidth(" ", "Body", size) or size * 0.25
    cap_size = size * 2.4

    lang = xq(NS + f"string(db:open('religioustext', '{a.translation}.xml')"
              f"/rt:text/@bcp47Language)").strip()
    names = localised_book_names(a.translation, lang)
    rows = list(stream_verses(a.translation, "canonical", limit=a.verses))

    # ── break into lines ─────────────────────────────────────────────────────
    usable = W - INNER - OUTER
    cw = (usable - GAP) / 2
    segments = []       # [(book heading or None, [line | "gap", ...])]
    cur_book = cur_chap = None
    chapter_items, chapter_no = [], None
    body = []

    def flush_chapter():
        if not chapter_items:
            return
        cap_w = stringWidth(str(chapter_no), "Digits", cap_size) + size * 0.3
        line, width, n, first = [], 0.0, 0, True
        lines_out = []
        for item in chapter_items:
            avail = cw - (cap_w if n < 2 else 0)
            add = item[2] + (space if line and not item[3] else 0)
            if line and width + add > avail:
                lines_out.append(("line", line, False, cap_w if n < 2 else 0,
                                  chapter_no if first else None))
                first, n, line, width = False, n + 1, [], 0.0
                add = item[2]
            line.append(item)
            width += add
        if line:
            lines_out.append(("line", line, True, cap_w if n < 2 else 0,
                              chapter_no if first else None))
        body.append(lines_out)          # one chapter = one list of lines

    for book, chap, vno, text in rows:
        if book != cur_book:
            flush_chapter()
            chapter_items = []
            body = []
            segments.append((names.get(book, book), body))
            cur_book, cur_chap = book, None
        if chap != cur_chap:
            flush_chapter()
            chapter_items, chapter_no, cur_chap = [], chap, chap
        # A verse number is glued to the word after it: a number stranded at
        # the end of a line, away from its verse, is worse than a loose line.
        num = str(vno)
        wn = stringWidth(num, "Digits", size * 0.62) + size * 0.12
        # Hebrew joins words with a maqaf (U+05BE) and may break the line
        # AFTER one; without that, "ואת־אלמודד" is a single 60-pt block and a
        # line of names stretches its two words across the whole column.
        first = True
        for word in text.split():
            parts = [q for q in word.replace("\u05be", "\u05be\0").split("\0") if q]
            for pi, part in enumerate(parts):
                sw, ww = shaped(part, "Body", size)
                glue = pi > 0
                if first:
                    chapter_items.append(("vw", (num, sw, wn, ww), ww + wn, glue))
                    first = False
                else:
                    chapter_items.append(("word", sw, ww, glue))
    flush_chapter()

    # ── paginate and draw ────────────────────────────────────────────────────
    c = canvas.Canvas(a.out, pagesize=(W, H))
    per_col = int((H - TOP - BOTTOM) / leading)
    st = {"page": 1, "row": 0}

    def block_right(page):
        # A right-to-left book is bound on the right, so the gutter is on the
        # RIGHT of a recto - the mirror of build_pdf.py.
        return W - (INNER if page % 2 == 1 else OUTER)

    def row_y(row):
        return H - TOP - (row + 1) * leading + leading * 0.3

    def folio():
        c.setFont("Digits", 8)
        c.drawCentredString(W / 2, BOTTOM - 8 * mm, str(st["page"]))

    def new_page():
        folio()
        c.showPage()
        st["page"] += 1
        st["row"] = 0

    def full_rule(row, label=None):
        right = block_right(st["page"])
        left = right - usable
        y = row_y(row)
        c.setLineWidth(0.5)
        if label is None:
            c.line(left, y + size * 0.3, right, y + size * 0.3)
            return
        s2, w = shaped(label, "Body", size + 1.5)
        mid = (left + right) / 2
        c.line(left, y + size * 0.3, mid - w / 2 - 6, y + size * 0.3)
        c.line(mid + w / 2 + 6, y + size * 0.3, right, y + size * 0.3)
        c.setFont("Body", size + 1.5)
        c.drawString(mid - w / 2, y, s2)

    def draw_line(entry, col, y, lead):
        _k, items, last, indent, cap = entry
        xr = block_right(st["page"]) - col * (cw + GAP)
        if cap:
            c.setFont("Digits", cap_size)
            cap_w = stringWidth(str(cap), "Digits", cap_size)
            c.drawString(xr - cap_w, y - lead + size * 0.1, str(cap))
        spaces = sum(1 for it in items[1:] if not it[3])
        natural = sum(it[2] for it in items) + space * spaces
        gap = space
        if not last and spaces:
            gap += (cw - indent - natural) / spaces
        x = xr - indent
        for k, (kind, obj, w, glue) in enumerate(items):
            if k and not glue:
                x -= gap
            x -= w
            if kind == "vw":
                num, sw, wn, ww = obj
                c.setFont("Digits", size * 0.62)
                c.drawString(x + ww + size * 0.12, y + size * 0.42, num)
                c.setFont("Body", size)
                c.drawString(x, y, sw)
            else:
                c.setFont("Body", size)
                c.drawString(x, y, obj)

    # The same rules as build_pdf.py, in lines of the grid:
    #   one empty line between chapters, never at the top of a column;
    #   a chapter opens at the foot of a column only if 3 of its lines fit
    #       (two would be the drop cap and nothing else);
    #   a single line is never carried to the next column or left behind.
    CHAPTER_GAP, MIN_START, MIN_CARRIED = 1, 3, 2

    def fill(chapters, ci, a_, lines):
        """Pour chapters[ci:] into two columns of `lines` rows."""
        cols, deepest = [], 0
        for _ in range(2):
            col, used = [], 0
            while ci < len(chapters):
                ch = chapters[ci]
                gap = CHAPTER_GAP if (used and a_ == 0) else 0
                space, rem = lines - used - gap, len(ch) - a_
                need = max(rem, 2) if a_ == 0 else rem
                if need <= space:
                    col.append((gap, need, ci, a_, len(ch)))
                    used += gap + need
                    ci, a_ = ci + 1, 0
                    continue
                take = min(space, rem - MIN_CARRIED)
                if take < (MIN_START if a_ == 0 else MIN_CARRIED):
                    break
                col.append((gap, take, ci, a_, a_ + take))
                used += gap + take
                a_ += take
                break
            cols.append((col, used))
            deepest = max(deepest, used)
            if ci >= len(chapters):
                break
        return cols, deepest, ci, a_

    MAX_FEATHER, MAX_STRETCH = 5, 0.10

    def place(chapters, cols, depth):
        """Draw the columns, each brought to `depth` rows when it is close:
        the shortfall goes into the gaps between chapters first (up to one
        extra line each), then into the line spacing (up to 10%)."""
        base = H - TOP - st["row"] * leading
        for k, (col, used) in enumerate(cols):
            extra, lead = 0.0, leading
            short = depth - used
            if col and 0 < short <= MAX_FEATHER:
                pts = short * leading
                gaps = sum(1 for g, *_ in col if g)
                if gaps:
                    extra = min(pts / gaps, leading)
                    pts -= extra * gaps
                if pts > 1e-6:
                    stretch = pts / sum(n_ for _g, n_, *_ in col)
                    if stretch <= MAX_STRETCH * leading:
                        lead = leading + stretch
                    elif gaps:
                        extra += pts / gaps
            cur = 0.0
            for gap, need, ci, a_, b_ in col:
                if gap:
                    cur += gap * leading + extra
                for e in chapters[ci][a_:b_]:
                    cur += lead
                    draw_line(e, k, base - cur + lead * 0.3, lead)
        st["row"] += depth

    def pour(chapters):
        """Pour a book's chapters into two columns, page after page. The last
        stretch is BALANCED so both columns end level under whatever follows."""
        ci = a_ = 0
        while ci < len(chapters):
            room = per_col - st["row"]
            if room < MIN_START:
                new_page()
                continue
            left = sum(len(c_) for c_ in chapters[ci:]) - a_
            if left <= 2 * room:
                for lines in range(max(1, -(-left // 2)), room + 1):
                    cols, deep, ci2, _a2 = fill(chapters, ci, a_, lines)
                    if ci2 >= len(chapters):
                        place(chapters, cols, deep)
                        return
            cols, deep, ci2, a2 = fill(chapters, ci, a_, room)
            if (ci2, a2) == (ci, a_):
                if st["row"] == 0:
                    raise SystemExit("a chapter cannot be placed on an empty page")
            else:
                place(chapters, cols, room)
                ci, a_ = ci2, a2
            new_page()

    for label, entries in segments:
        if st["row"]:
            st["row"] += 1
        if st["row"] + 5 > per_col:
            new_page()
        full_rule(st["row"], label)
        st["row"] += 2          # the rule, then one empty line
        pour(entries)
    if st["row"] + 1 <= per_col:
        full_rule(st["row"] + 0)
    folio()
    c.save()
    print(f"  {a.out}: {st['page']} page(s), {len(rows)} verses")


if __name__ == "__main__":
    main()
