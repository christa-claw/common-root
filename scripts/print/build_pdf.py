#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
build_pdf.py — a print-ready interior, in our own typography.

WHY THIS EXISTS
---------------
build_edition.py pours the corpus into a PRINTER'S template: their fonts,
their ornaments, their style names. That is the right answer for a shop that
typesets in-house, and it was written for shops that said "send us your text
in our styles".

Pretore said something different (2026-09-22): *"The files can be supplied to
us as regular print-ready PDF files. Please make sure that all fonts are
embedded in the PDF and that the fonts used are suitable and licensed for
printing."* No template, no style names, no InDesign — a finished book.

So this module owns what build_edition.py deliberately refuses to own: page
geometry, measure, leading, running heads, and the choice of typeface. That is
not a contradiction of the other script, it is the other half of the problem.

FONTS ARE A LICENCE QUESTION, NOT A TASTE QUESTION
--------------------------------------------------
A system font (macOS Times, Helvetica) is NOT licensed for a third party to
embed and commercially print. Every font this script will use must carry a
licence that permits embedding and commercial print, and its licence file is
copied into the output directory so the printer never has to ask. The SIL
Open Font Licence does permit both; so does the Crimson Pro / EB Garamond
family. Point --fonts at a directory of OFL faces and the licence travels with
the book.

Recommended faces, all OFL, all with the diacritic coverage scripture needs:
    Charis SIL / Gentium Plus  — built by SIL for scripture publishing
    EB Garamond, Crimson Pro   — book faces with a classical colour
    Cardo                      — polytonic Greek + pointed Hebrew
    Noto Serif CJK             — the Chinese editions, when they come

USAGE
    python3 scripts/print/build_pdf.py --self-test --out out/selftest.pdf
    python3 scripts/print/build_pdf.py --translation bible-web \\
        --ordering chronological --canon 66 --trim standard \\
        --fonts fonts/crimson --out out/web-chronological.pdf
"""

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from reportlab.lib import colors, pagesizes  # noqa: E402
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY  # noqa: E402
from reportlab.lib.styles import ParagraphStyle  # noqa: E402
from reportlab.lib.units import mm  # noqa: E402
from reportlab.pdfbase import pdfmetrics  # noqa: E402
from reportlab.pdfbase.ttfonts import TTFont, shapeStr  # noqa: E402
from reportlab.platypus import (BaseDocTemplate, Flowable, Frame,  # noqa: E402
                                PageBreak, PageTemplate, Paragraph, Spacer)
from reportlab.platypus.paragraph import ParaLines  # noqa: E402
from reportlab.pdfbase.pdfmetrics import stringWidth  # noqa: E402


DROP_CAP_LINES = 2


class CapNumeral(Flowable):
    """The chapter numeral, sized and placed as a drop cap.

    A drop cap is defined by two alignments, not by a point size: the top of
    the digit sits level with the ascenders of line one, and its baseline sits
    on the baseline of line N. So the cap height we need is

        (N - 1) * leading  +  the body face's own cap height

    and the point size falls out of the face's capHeight metric. At 8.8 pt on
    10.6 pt leading that works out at about 2.9x the body size — measured, not
    chosen, which is why it holds when the body-size slider moves.

    Chapter asks this flowable for its height and narrows
    1 + int(height / leading) lines by its width. Reporting the cap height
    (not the full N lines) is what makes that arithmetic land on N.
    """

    def __init__(self, text, font, body_style, colour, lines=2):
        Flowable.__init__(self)
        self.text = str(text)
        self.font = font
        self.colour = colour
        self.leading = body_style.leading
        self.lines = lines
        face = pdfmetrics.getFont(font).face
        body_cap = (getattr(face, "capHeight", None) or 700) / 1000.0 * body_style.fontSize
        self.cap_h = (lines - 1) * self.leading + body_cap
        self.size = self.cap_h / ((getattr(face, "capHeight", None) or 700) / 1000.0)

    def wrap(self, availWidth, availHeight):
        self.width = stringWidth(self.text, self.font, self.size)
        self.height = self.cap_h
        return self.width, self.height

    def draw(self):
        # drawOn() puts this box's top at the paragraph's top. The baseline
        # belongs on line N, which is lines*leading below that top, so the
        # glyph is drawn below its own box by the difference.
        c = self.canv
        c.setFillColor(self.colour)
        c.setFont(self.font, self.size)
        c.drawString(0, self.cap_h - self.lines * self.leading, self.text)


class Chapter:
    """One chapter, broken into lines exactly once.

    The page composer below places text by the LINE, so a chapter is broken
    at the column measure once and then handed out in slices. Nothing is ever
    re-broken: a slice continued in the next column keeps the breaks it was
    given, which is what makes line counts exact and keeps hyphenation
    honest — re-breaking the tail of a hyphenated paragraph is how
    "accord-ing" ends up in the middle of a line.

    The first 1 + int(cap height / leading) lines are narrowed by the width
    of the drop cap; with a two-line cap that is the first two.
    """

    def __init__(self, book, number, para, cap, xpad):
        self.book = book
        self.number = number
        self.para = para
        self.cap = cap
        self.xpad = xpad
        self.n = None

    def break_lines(self, measure):
        para, style = self.para, self.para.style
        w_cap, h_cap = self.cap.wrap(measure, 0)
        self.leading = style.leading
        self.measure = measure
        self.narrow = 1 + int(h_cap / self.leading)
        self.offset = w_cap + self.xpad
        para.width = 0
        widths = [measure - self.offset] * self.narrow + [measure]
        para.blPara = (para.breakLinesCJK(widths)
                       if style.wordWrap == "CJK" else para.breakLines(widths))
        self.n = len(para.blPara.lines)

    def piece(self, a, b, leading=None):
        """Lines [a, b) as something that can be drawn.

        `leading` opens the lines a fraction of a point when a column is
        being feathered to the depth of its neighbour; the line BREAKS do not
        change.
        """
        para, bl = self.para, self.para.blPara
        style = para.style
        if leading and abs(leading - self.leading) > 1e-6:
            style = ParagraphStyle("feathered", parent=style, leading=leading)
        else:
            leading = self.leading
        func = para._get_split_blParaFunc()
        p = para.__class__(None, style, frags=func(bl, a, b))
        p.blPara = ParaLines(kind=1, lines=bl.lines[a:b],
                             aH=(b - a) * leading, aW=self.measure)
        # Every slice but the last ends mid-sentence and must fill its line.
        p._JustifyLast = b < self.n
        p._splitpara = 1
        p.height = (b - a) * leading
        p.width = self.measure
        if a == 0:
            offsets = [self.offset] * min(self.narrow, b) + [0]
            return ChapterPiece(p, offsets, self.cap)
        return ChapterPiece(p, [0], None)


class ChapterPiece(Flowable):
    """A slice of a chapter's lines, with the drop cap if it is the first."""

    def __init__(self, para, offsets, cap):
        Flowable.__init__(self)
        self.para = para
        self.offsets = offsets
        self.cap = cap
        self.width = para.width
        self.height = para.height

    def wrap(self, availWidth, availHeight):
        return self.width, self.height

    def draw(self):
        if self.cap is not None:
            self.cap.drawOn(self.canv, 0, self.height - self.cap.height)
        self.para._offsets = list(self.offsets)
        self.para.drawOn(self.canv, 0, 0)


class PageBody(Flowable):
    """One composed page of the text block.

    ReportLab's frames flow a story down one column and into the next, which
    cannot set a rule across both columns in the middle of a page: the text
    above it has to be balanced into two columns that end level, and a frame
    does not know how to do that. So the page is composed here — see
    compose_pages — and handed to ReportLab as a single flowable that fills
    the frame. `items` are (top offset, x, flowable, height).
    """

    def __init__(self, width, height):
        Flowable.__init__(self)
        self.width = width
        self.height = height
        self.items = []
        self.marks = []          # (book, chapter) for each chapter begun here
        self.short = 0           # lines by which a column was left short

    def wrap(self, availWidth, availHeight):
        return self.width, self.height

    def draw(self):
        for top, x, f, h in self.items:
            f.drawOn(self.canv, x, self.height - top - h)


# ── right-to-left setting ─────────────────────────────────────────────────────
#
# ReportLab's Paragraph runs right-to-left only with `rlbidi`, which is not
# published on PyPI. Scripture needs none of it: it is pure right-to-left text,
# and the only left-to-right runs are numbers, which are set separately. So a
# right-to-left chapter is laid out by hand — HarfBuzz shapes each word (joins
# Arabic letters, seats Hebrew points and cantillation on their consonants) and
# a line is filled from the RIGHT edge leftwards. rtl_probe.py is where this
# was proved on Hebrew and Arabic; this is the same layout inside the page
# composer, so every rule of the left-to-right book (balanced columns, drop
# cap, feathering, full-width book rules) applies unchanged. What is mirrored:
#   * the first column is the right one, the drop cap sits on the right;
#   * a right-to-left book is bound on the RIGHT, so the gutter swaps sides;
#   * running heads, book rules and the "continued" line are shaped, not
#     letterspaced or upper-cased (both destroy Arabic joining).

RTL_LANGS = ("ar", "he", "hbo", "fa", "ur", "yi", "syr", "arc")
RTL_FAMILIES = ("ScheherazadeNew", "SILEOT", "EzraSIL", "Ezra")
RTL_CONTINUED = {"ar": "تابع", "he": "המשך"}

_shape_cache = {}


def is_rtl(lang):
    return (lang or "en").split("-")[0].lower() in RTL_LANGS


def shaped(word, font, size):
    """(shaped string, width) for one word of a right-to-left run."""
    key = (word, font, size)
    hit = _shape_cache.get(key)
    if hit is None:
        s = shapeStr(word, font, size, force=True)
        data = getattr(s, "__shapeData__", None)
        w = (sum(d.x_advance for d in data) * size / 1000.0 if data
             else stringWidth(word, font, size))
        hit = _shape_cache[key] = (s, w)
    return hit


_NUMERIC = re.compile(r"^[0-9–—\-:.]+$")


def rtl_tokens(label, font, digits, size):
    """A short right-to-left label ("Genesis 1–3") as [(drawable, width, font)]
    in logical order; the numbers are left-to-right units in the digits face."""
    out = []
    for tok in label.split():
        if _NUMERIC.match(tok):
            out.append((tok, stringWidth(tok, digits, size), digits))
        else:
            s, w = shaped(tok, font, size)
            out.append((s, w, font))
    return out


def draw_rtl_label(c, label, font, digits, size, x_left, y):
    """Draw `label` so its leftmost point is x_left; returns its width."""
    toks = rtl_tokens(label, font, digits, size)
    sp = size * 0.3
    total = sum(w for _s, w, _f in toks) + sp * max(0, len(toks) - 1)
    xr = x_left + total
    for s, w, f in toks:
        xr -= w
        c.setFont(f, size)
        c.drawString(xr, y, s)
        xr -= sp
    return total


class RtlLine(Flowable):
    """One centred right-to-left line (the "continued" under a re-entered book)."""

    def __init__(self, text, font, digits, size, leading, colour, space_after=0):
        Flowable.__init__(self)
        self.text, self.font, self.digits = text, font, digits
        self.size, self.leading, self.colour = size, leading, colour
        self.space_after = space_after

    def getSpaceBefore(self):
        return 0

    def getSpaceAfter(self):
        return self.space_after

    def wrap(self, availWidth, availHeight):
        self.width, self.height = availWidth, self.leading
        return self.width, self.height

    def draw(self):
        c = self.canv
        c.setFillColor(self.colour)
        w = sum(t[1] for t in rtl_tokens(self.text, self.font, self.digits, self.size))
        draw_rtl_label(c, self.text, self.font, self.digits, self.size,
                       (self.width - w) / 2.0, (self.leading - self.size) / 2.0)


class RtlChapter:
    """The right-to-left counterpart of Chapter: one chapter broken into lines
    once, handed out in slices. Same interface (book, number, n, break_lines,
    piece) so the page composer cannot tell them apart."""

    def __init__(self, book, number, verses, st, fonts, colour):
        self.book, self.number, self.colour = book, number, colour
        body = st["body"]
        self.font, self.digits = fonts["regular"], fonts["digits"]
        self.size, self.leading = body.fontSize, body.leading
        self.space = stringWidth(" ", self.font, self.size) or self.size * 0.25
        self.cap = CapNumeral(number, fonts["digits_bold"], body, colour,
                              lines=DROP_CAP_LINES)
        self.xpad = self.size * 0.3
        self.items = []
        for vno, text in verses:
            num = str(vno)
            wn = stringWidth(num, self.digits, self.size * 0.62) + self.size * 0.12
            first = True
            for word in text.split():
                # Hebrew joins words with a maqaf (U+05BE) and may break the
                # line AFTER one; otherwise a line of names is a single block.
                parts = [q for q in word.replace("־", "־\0").split("\0") if q]
                for pi, part in enumerate(parts):
                    sw, ww = shaped(part, self.font, self.size)
                    glue = pi > 0
                    if first:
                        # A verse number is glued to the word after it: a number
                        # stranded at a line end is worse than a loose line.
                        self.items.append(("vw", (num, sw, wn, ww), ww + wn, glue))
                        first = False
                    else:
                        self.items.append(("word", sw, ww, glue))
        self.n = None

    def break_lines(self, measure):
        self.measure = measure
        w_cap, h_cap = self.cap.wrap(measure, 0)
        self.narrow = 1 + int(h_cap / self.leading)
        self.offset = w_cap + self.xpad
        lines, line, width = [], [], 0.0
        for item in self.items:
            avail = measure - (self.offset if len(lines) < self.narrow else 0)
            add = item[2] + (self.space if line and not item[3] else 0)
            if line and width + add > avail:
                lines.append(line)
                line, width = [], 0.0
                add = item[2]
            line.append(item)
            width += add
        if line:
            lines.append(line)
        self.lines = lines
        self.n = len(lines)

    def piece(self, a, b, leading=None):
        rows = [(self.lines[i], i < self.n - 1,
                 self.offset if i < self.narrow else 0) for i in range(a, b)]
        return RtlPiece(self, rows, leading or self.leading, a == 0)


class RtlPiece(Flowable):
    """A slice of a right-to-left chapter's lines, drop cap on the right."""

    def __init__(self, chap, rows, leading, with_cap):
        Flowable.__init__(self)
        self.chap, self.rows, self.leading, self.with_cap = chap, rows, leading, with_cap
        self.width = chap.measure
        self.height = len(rows) * leading

    def wrap(self, availWidth, availHeight):
        return self.width, self.height

    def draw(self):
        ch, c, size, lead = self.chap, self.canv, self.chap.size, self.leading
        # Baseline of line i, measured from the piece's top.
        base = lambda i: self.height - (i + 1) * lead + lead * 0.3
        if self.with_cap:
            cap = ch.cap
            c.setFillColor(ch.colour)
            c.setFont(cap.font, cap.size)
            tw = stringWidth(cap.text, cap.font, cap.size)
            c.drawString(self.width - tw, base(1 if len(self.rows) > 1 else 0),
                         cap.text)
        c.setFillColor(colors.black)
        for i, (items, justify, indent) in enumerate(self.rows):
            y = base(i)
            spaces = sum(1 for it in items[1:] if not it[3])
            natural = sum(it[2] for it in items) + ch.space * spaces
            gap = ch.space
            if justify and spaces:
                gap += (self.width - indent - natural) / spaces
            x = self.width - indent
            for k, (kind, obj, w, glue) in enumerate(items):
                if k and not glue:
                    x -= gap
                x -= w
                if kind == "vw":
                    num, sw, wn, ww = obj
                    c.setFont(ch.digits, size * 0.62)
                    c.drawString(x + ww + size * 0.12, y + size * 0.42, num)
                    c.setFont(ch.font, size)
                    c.drawString(x, y, sw)
                else:
                    c.setFont(ch.font, size)
                    c.drawString(x, y, obj)


# ── long book titles ──────────────────────────────────────────────────────────
#
# The traditional English titles, as they stand at the head of each book in the
# King James tradition. They are public domain and they are what an English
# Bible has said for four hundred years.
#
# They are also, in a handful of cases, an EDITORIAL CLAIM the text does not
# make about itself. "The Epistle of Paul the Apostle to the Hebrews" names an
# author the letter never names; "The Lamentations of Jeremiah" likewise; the
# Mosaic authorship of the Pentateuch and the "Saint" before each evangelist
# are tradition rather than text. That sits awkwardly in a project whose whole
# argument is that chapters (1227) and verses (1551) are later additions worth
# showing as such. So the long titles are a SETTING, not a fact:
#     --book-titles long   traditional titles, as below
#     --book-titles short  the bare book name, and nothing asserted
# Either way the short name stays on the rule; the long title is a second,
# quieter line beneath it, which is also the only way it fits a 53 mm column.
def _load_long_titles():
    """English book name -> traditional long title.

    The titles live in app/src/main/resources/i18n/booklongnames.properties,
    keyed by USFM code like the booknames bundles, because the READER shows the
    same titles under each book for English editions — one list, so print and
    screen cannot disagree. The English names come from booknames.properties.
    """
    here = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    i18n = os.path.join(here, "app", "src", "main", "resources", "i18n")

    def read(name):
        out = {}
        with open(os.path.join(i18n, name), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith(("#", "!")) and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
        return out

    titles, names = read("booklongnames.properties"), read("booknames.properties")
    by_name = {names[c]: titles[c] for c in titles if c in names}
    # The corpus spells two books differently from the UI bundle.
    by_name.setdefault("Song of Solomon", titles.get("SNG", ""))
    by_name.setdefault("Psalm", titles.get("PSA", ""))
    return by_name


LONG_TITLES = _load_long_titles()

# Titles whose attribution the book itself does not make. Named here so the
# claim is at least visible in the source rather than smuggled in as a string.
DISPUTED_ATTRIBUTION = (
    "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy",
    "Lamentations", "Hebrews",
    "Matthew", "Mark", "Luke", "John", "Revelation",
)

BOOK_TITLE_MODES = ("long", "short")


def long_title(book):
    """The traditional long title, or "" when we have none for this book.

    Lookup is on the English book name. A translation whose book names are not
    English simply gets no long title — better an empty second line than a
    Finnish Bible with an English subtitle under every book.
    """
    return LONG_TITLES.get(book, "")


HEAD_MODES = ("none", "book", "book-chapter", "split")


# ── accent colour ─────────────────────────────────────────────────────────────
#
# Colour goes on the APPARATUS only — running heads, book titles, chapter
# numerals — never on scripture. That is the rubrication tradition (the red in
# a manuscript marks the editor's hand, not the author's), and it is also the
# practical choice: a coloured text block is harder to read and lays far more
# ink on 40 gsm paper than it can take.
#
# Values are CMYK, not RGB, because the press works in CMYK and an RGB value
# converted by someone else's profile is a colour nobody chose. Total ink
# coverage is kept well under 240% so thin paper is not soaked.
#
# ⚠ ANY accent but `black` turns a monochrome job into a colour one. Pretore
# quoted digital MONOCHROME (2026-09-22). The price delta is unquoted, and
# whether they run colour at this quantity at all is unconfirmed. build_package
# writes the change into SPEC.json so it cannot go to a printer unnoticed.

ACCENTS = {
    #           C     M     Y     K     description for the spec sheet
    "black":   (0.00, 0.00, 0.00, 1.00, "Black only — monochrome job"),
    "rubric":  (0.00, 0.85, 0.80, 0.25, "Deep rubric red, after the red of "
                                        "manuscript rubrication"),
    "indigo":  (0.85, 0.65, 0.00, 0.45, "Deep indigo"),
    "sepia":   (0.30, 0.55, 0.75, 0.35, "Warm sepia brown"),
    "forest":  (0.75, 0.25, 0.75, 0.45, "Deep forest green"),
}


def accent_colour(name):
    from reportlab.lib.colors import CMYKColor
    c, m, y, k, _ = ACCENTS[name]
    return CMYKColor(c, m, y, k)

# ── the canon question ────────────────────────────────────────────────────────
#
# We do not adjudicate anyone's canon. An edition carries what it carries; this
# set marks the 66 books every Protestant Bible shares, so that everything else
# an edition happens to hold can be OFFERED rather than assumed. WEB carries a
# broad set (3-4 Maccabees, Psalm 151) that is Orthodox rather than Protestant
# "Apocrypha" or Catholic deuterocanon — which is exactly why the option names
# what the edition contains instead of naming a tradition.

CANON_66 = {
    "genesis", "exodus", "leviticus", "numbers", "deuteronomy", "joshua",
    "judges", "ruth", "1samuel", "2samuel", "1kings", "2kings",
    "1chronicles", "2chronicles", "ezra", "nehemiah", "esther", "job",
    "psalms", "proverbs", "ecclesiastes", "songofsolomon", "isaiah",
    "jeremiah", "lamentations", "ezekiel", "daniel", "hosea", "joel", "amos",
    "obadiah", "jonah", "micah", "nahum", "habakkuk", "zephaniah", "haggai",
    "zechariah", "malachi",
    "matthew", "mark", "luke", "john", "acts", "romans", "1corinthians",
    "2corinthians", "galatians", "ephesians", "philippians", "colossians",
    "1thessalonians", "2thessalonians", "1timothy", "2timothy", "titus",
    "philemon", "hebrews", "james", "1peter", "2peter", "1john", "2john",
    "3john", "jude", "revelation",
}

# Corpus book names differ between editions for the same book. These are the
# variants seen in the corpus; an unknown name is treated as NOT in the 66,
# which fails safe (it becomes an optional book rather than silently joining
# the canon).
_CANON_ALIASES = {
    "psalm": "psalms",
    "songofsongs": "songofsolomon",
    "canticleofcanticles": "songofsolomon",
    "cantares": "songofsolomon",
    "actsoftheapostles": "acts",
    "revelationofjohn": "revelation",
}


def norm_book(name):
    key = re.sub(r"[^a-z0-9]", "", (name or "").lower())
    return _CANON_ALIASES.get(key, key)


def in_canon_66(name):
    return norm_book(name) in CANON_66


# ── page geometry ─────────────────────────────────────────────────────────────
#
# Inner margins are deliberately larger than outer. A 1,200-page book on 40 gsm
# is ~25-30 mm at the spine, and a sewn book of that thickness eats several
# millimetres of the inner margin in the curve of the gutter. A symmetrical
# margin looks correct on screen and wrong in the hand.

TRIMS = {
    # name        width   height   inner  outer  top   bottom  cols  body pt
    "pocket":    (122*mm, 180*mm, 16*mm, 11*mm, 13*mm, 14*mm, 2, 7.2),
    "standard":  (140*mm, 216*mm, 17*mm, 12*mm, 14*mm, 15*mm, 2, 8.8),
    "large":     (152*mm, 229*mm, 18*mm, 13*mm, 15*mm, 16*mm, 2, 9.4),
    # One wide outer margin for the reader's own hand. Costs ~15% more pages.
    "notes":     (152*mm, 229*mm, 17*mm, 40*mm, 15*mm, 16*mm, 1, 9.4),
}

COL_GAP = 5 * mm

# Hard floors. Below these the book is defective rather than merely tight, so
# the script refuses instead of warning.
#
#   outer  Commercial trimming runs to about +/-1.5 mm, and three stacked
#          sheets of a sewn book do not trim identically. An 8 mm fore-edge
#          can arrive as 6 mm; a 5 mm one can arrive as 3 mm, or as text with
#          its descenders cut off.
#   inner  A sewn book loses margin into the curve of the gutter, and the
#          loss grows with the spine. At 1,200 pages on 40 gsm — about 28 mm
#          of spine — several millimetres of the inner margin are simply not
#          visible without breaking the back of the book.
#   head/  Running heads and folios live here. Below 10 mm they collide with
#   foot   the text block.
FLOOR = {"outer": 8 * mm, "inner": 12 * mm, "top": 10 * mm, "bottom": 10 * mm}

# Above this extent the gutter needs more than the floor, because the spine
# curve eats it. Roughly 1 mm of extra inner margin per 300 pages.
def gutter_advice(pages):
    return 12 * mm + (pages / 300.0) * mm


def min_measure(body_pt):
    """The narrowest column that will justify at this type size.

    A fixed millimetre floor is wrong: 45 mm is cramped at 8.8 pt and entirely
    normal at 7 pt, which is why real pocket Bibles set small type in narrow
    columns and look fine. What actually has to hold is CHARACTERS per line —
    below roughly 28, justification opens rivers whatever the measure. Crimson
    Pro averages near 0.46 em per character in running text, so 28 characters
    needs about 12.9 em, and 1 em is the point size.
    """
    return 28 * 0.46 * body_pt


def resolve_geometry(trim, outer=None, inner=None, top=None, bottom=None,
                     columns=None, body_pt=None):
    """Start from a named trim, then apply whatever the caller overrode.

    Margins are exposed as four independent numbers here because the geometry
    needs them, but a reader should only ever be given ONE of them to move —
    the fore-edge. The inner margin is a consequence of how thick the book is,
    and head and foot are a consequence of the running heads. Offering all
    four to a customer invites a book that cannot be bound.
    """
    w, h, d_inner, d_outer, d_top, d_bottom, d_cols, d_body = TRIMS[trim]
    g = {
        "inner": inner * mm if inner is not None else d_inner,
        "outer": outer * mm if outer is not None else d_outer,
        "top": top * mm if top is not None else d_top,
        "bottom": bottom * mm if bottom is not None else d_bottom,
        "columns": columns if columns is not None else d_cols,
        "body_pt": body_pt if body_pt is not None else d_body,
    }
    for k, floor in FLOOR.items():
        if g[k] < floor:
            raise SystemExit(
                f"{k} margin {g[k]/mm:.1f} mm is below the {floor/mm:.0f} mm "
                f"floor. See FLOOR in this file for why; if you truly want it, "
                f"change the floor deliberately rather than passing it in.")
    measure = w - g["inner"] - g["outer"]
    if g["columns"] == 2:
        measure = (measure - COL_GAP) / 2
    floor_measure = min_measure(g["body_pt"])
    if measure < floor_measure - 0.01:
        raise SystemExit(
            f"Those margins leave a {measure/mm:.0f} mm column"
            f"{' (two columns)' if g['columns'] == 2 else ''} at "
            f"{g['body_pt']} pt, which needs at least "
            f"{floor_measure/mm:.0f} mm to justify without rivers. Widen the "
            f"trim, drop to one column, reduce the margins, or set smaller "
            f"type.")
    g["measure"] = measure
    g["width"], g["height"] = w, h
    return g


# ── fonts ─────────────────────────────────────────────────────────────────────

FONT_ROLES = ("regular", "italic", "bold", "bolditalic")

# Families made by build_cjk_font.py: Regular and Bold only.
CJK_FAMILIES = ("NotoSerifTC",)
CJK_LANGS = ("zh", "ja")

_FILE_HINTS = {
    "regular":    ("-Regular", "-Roman", "Regular"),
    "italic":     ("-Italic", "-It", "Italic"),
    "bold":       ("-Bold", "-Bd", "Bold"),
    "bolditalic": ("-BoldItalic", "-BdIt", "BoldItalic"),
}


def register_fonts(font_dir, family):
    """Register a family from a directory of .ttf files and return its names.

    Deliberately strict: a missing face is an error, not a silent substitution
    to Helvetica. A book that silently falls back has an unlicensed font in it
    and nobody finds out until the printer's preflight.
    """
    if not font_dir:
        # Smoke-testing path only. Base-14 fonts are NOT embedded and must
        # never reach a printer, so say so loudly.
        sys.stderr.write(
            "WARNING: no --fonts given; using non-embedded base-14 fonts. "
            "This PDF is for layout inspection ONLY and must not be sent to "
            "a printer.\n")
        return {"regular": "Times-Roman", "italic": "Times-Italic",
                "bold": "Times-Bold", "bolditalic": "Times-BoldItalic"}

    files = os.listdir(font_dir)
    names = {}
    for role in FONT_ROLES:
        match = None
        for f in files:
            if not f.lower().endswith((".ttf", ".otf")):
                continue
            if family.lower() not in f.lower().replace("-", "").replace("_", ""):
                if family.lower() not in f.lower():
                    continue
            stem = os.path.splitext(f)[0]
            # A one-file family (SILEOT.ttf) is its own regular face.
            if role == "regular" and family.startswith(RTL_FAMILIES) \
                    and stem.lower() == family.lower():
                match = f
                break
            # BoldItalic must win over Bold and over Italic.
            if role == "bolditalic" and any(h in stem for h in _FILE_HINTS["bolditalic"]):
                match = f
                break
            if role in ("bold", "italic") and any(h in stem for h in _FILE_HINTS["bolditalic"]):
                continue
            if any(h in stem for h in _FILE_HINTS[role]):
                match = f
                break
        if not match and role != "regular" and family.startswith(CJK_FAMILIES + RTL_FAMILIES):
            # Han, Arabic and Hebrew are not set in italic; the face a Western
            # book would italicise is simply the upright one. A script face
            # with no bold (Ezra SIL) sets its headings in the regular.
            if role in ("italic", "bold"):
                names[role] = names["regular"]
            else:
                names[role] = names["bold"] if role == "bolditalic" else names["regular"]
            if role == "bold":
                sys.stderr.write(f"note: no bold face for '{family}'; headings "
                                 f"use the regular face.\n")
            continue
        if not match:
            if role == "bolditalic":
                # Bible interiors rarely set bold italic, and several OFL
                # families ship without it. Reuse bold rather than refuse to
                # build — but say so, so nobody is surprised by the mapping.
                sys.stderr.write(
                    f"note: no bolditalic face for '{family}'; mapping it to "
                    f"bold.\n")
                names[role] = names["bold"]
                continue
            raise SystemExit(
                f"No {role} face for '{family}' in {font_dir}. Files there: "
                f"{sorted(files)}. Every face must be present — a missing one "
                f"would silently fall back to an unlicensed system font.")
        ps = f"{family}-{role}"
        pdfmetrics.registerFont(TTFont(ps, os.path.join(font_dir, match)))
        names[role] = ps
    pdfmetrics.registerFontFamily(
        names["regular"], normal=names["regular"], bold=names["bold"],
        italic=names["italic"], boldItalic=names["bolditalic"])

    # ReportLab stamps its base font into EVERY page's resource dictionary,
    # whether or not anything is drawn with it, and that default is Helvetica
    # — a base-14 face, which means not embedded. Left alone it puts an
    # unembedded font in a 1,200-page book and fails the printer's preflight.
    # Changing the base name is the only place this can be fixed; doing it
    # after registration guarantees the replacement is a real, embedded face.
    from reportlab import rl_config
    rl_config.canvas_basefontname = names["regular"]
    return names


# ── the book ──────────────────────────────────────────────────────────────────

class BookDoc(BaseDocTemplate):
    """Tracks which book and chapter each page is showing, for running heads.

    ReportLab has no notion of a running head that follows content, so the
    state is recorded as flowables are placed and read back when the page is
    painted. afterFlowable fires before the page is finished, so the value it
    leaves is the LAST book to appear on the page — which is the convention a
    Bible uses.
    """

    def __init__(self, filename, geom, fonts, title, head_mode="book-chapter",
                 accent="black", **kw):
        w, h = geom["width"], geom["height"]
        inner, outer = geom["inner"], geom["outer"]
        top, bottom = geom["top"], geom["bottom"]
        BaseDocTemplate.__init__(self, filename, pagesize=(w, h),
                                 leftMargin=outer, rightMargin=outer,
                                 topMargin=top, bottomMargin=bottom,
                                 title=title, author="common-root.org",
                                 subject="Public domain scripture",
                                 **kw)
        self.fonts = fonts
        self.rtl = bool(geom.get("rtl"))
        self.head_mode = head_mode
        self.accent = accent_colour(accent)
        self.marks = []          # (page, book, chapter), in placement order
        self.book_opens = []     # (page, y) of every book rule actually drawn
        self.frame_w = w
        self.frame_h = h

        def frames_for(is_recto):
            # One frame the width of the text block. The columns are made by
            # the page composer, not by frames — which is also what lets the
            # title and "about" pages set across the full measure.
            lm = inner if is_recto else outer
            rm = outer if is_recto else inner
            if self.rtl:
                # Bound on the right: the gutter of a recto is on its RIGHT.
                lm, rm = rm, lm
            return [Frame(lm, bottom, w - lm - rm, h - top - bottom, id="c1",
                          leftPadding=0, rightPadding=0,
                          topPadding=0, bottomPadding=0)]

        self.addPageTemplates([
            PageTemplate(id="recto", frames=frames_for(True),
                         onPage=self._prime, onPageEnd=self._furniture),
            PageTemplate(id="verso", frames=frames_for(False),
                         onPage=self._prime, onPageEnd=self._furniture),
            PageTemplate(id="plain", frames=frames_for(True),
                         onPage=self._prime),
        ])

    def _prime(self, canvas, doc):
        """Claim the page's initial font before ReportLab's default does.

        The canvas starts every page in Helvetica. Even when nothing is drawn
        with it, the reference lands in the page's resources — and Helvetica
        is a base-14 font, which means NOT embedded. A printer's preflight
        rejects that, and it is precisely the thing Pretore asked us to avoid.
        """
        canvas.setFont(self.fonts["regular"], 9)

    def afterFlowable(self, flowable):
        if not isinstance(flowable, PageBody):
            return
        for book, chapter in flowable.marks:
            self.marks.append((self.page, book, chapter))
        for _top, _x, f, _h in flowable.items:
            if isinstance(f, BookHeading) and f.drawn_y is not None:
                self.book_opens.append((self.page, f.drawn_y))

    def head_label(self, page, recto=True):
        """What the running head says on `page`.

        Crossing a book boundary mid-page is common in a chronological
        edition — Psalms are threaded through Samuel and Kings — so the head
        names both sides rather than picking one and lying about the other.

        In "split" mode the spread is treated as one unit, which is the old
        dictionary convention: the verso carries the BOOK and the recto carries
        the CHAPTERS. Neither side repeats the other, the eye learns which side
        to look at for which question, and nothing on the page says the same
        word twice. When a spread crosses a book boundary the recto has to name
        the books too, or "50 – 1" would be a riddle.
        """
        if self.head_mode == "none":
            return ""
        before = [m for m in self.marks if m[0] < page]
        here = [m for m in self.marks if m[0] == page]
        if not before and not here:
            return ""
        first = before[-1] if before else here[0]
        last = here[-1] if here else first
        one_book = first[1] == last[1]

        if self.head_mode == "book":
            return first[1] if one_book else f"{first[1]} – {last[1]}"

        if self.head_mode == "split":
            if not recto:
                return first[1] if one_book else f"{first[1]} – {last[1]}"
            if not one_book:
                return f"{first[1]} {first[2]} – {last[1]} {last[2]}"
            return (str(first[2]) if first[2] == last[2]
                    else f"{first[2]}–{last[2]}")

        if not one_book:
            return f"{first[1]} {first[2]} – {last[1]} {last[2]}"
        if first[2] == last[2]:
            return f"{first[1]} {first[2]}"
        return f"{first[1]} {first[2]}–{last[2]}"

    def opens_a_book_at_the_top(self, page, doc):
        """Did a book rule land in the top band of this page's text block?

        This is the one case where the running head and the book rule say the
        same word within a centimetre of each other. Page furniture should not
        repeat what the page already says.
        """
        top = self.frame_h - doc.topMargin
        height = top - doc.bottomMargin
        band = top - height * 0.18
        return any(pg == page and y >= band for pg, y in self.book_opens)

    def _furniture(self, canvas, doc):
        if canvas.getPageNumber() <= 1:
            return
        canvas.saveState()
        page = canvas.getPageNumber()
        recto = page % 2 == 1
        label = self.head_label(page, recto)
        # A page that opens a book already names it, in larger type, between
        # rules. Book typography drops the running head on an opening page for
        # exactly this reason, and it costs nothing in a monochrome job — which
        # is the only differentiator available when colour is not.
        if self.opens_a_book_at_the_top(page, doc):
            label = ""
        w = self.frame_w
        if label and self.rtl:
            size = 7.6
            canvas.setFillColor(self.accent)
            width = sum(t[1] for t in rtl_tokens(
                label, self.fonts["regular"], self.fonts["digits"], size)) \
                + size * 0.3 * (len(label.split()) - 1)
            # The outer margin of a right-to-left recto is on the left.
            x = doc.leftMargin if recto else w - doc.rightMargin - width
            draw_rtl_label(canvas, label, self.fonts["regular"],
                           self.fonts["digits"], size, x,
                           self.frame_h - doc.topMargin + 5 * mm)
        elif label:
            # The head is set in the REGULAR face, letterspaced, at 7.6 pt:
            # a quiet locator. The book rule is bold at body size between two
            # rules: a title. Weight, size and the rules are three differences
            # that all survive black-only printing.
            canvas.setFillColor(self.accent)
            text = label.upper()
            tx = canvas.beginText()
            tx.setFont(self.fonts["regular"], 7.6)
            tx.setCharSpace(0.7)
            tx.setFillColor(self.accent)
            width = (stringWidth(text, self.fonts["regular"], 7.6)
                     + 0.7 * (len(text) - 1))
            y = self.frame_h - doc.topMargin + 5 * mm
            tx.setTextOrigin(w - doc.rightMargin - width if recto
                             else doc.leftMargin, y)
            tx.textOut(text)
            canvas.drawText(tx)
        canvas.setFont(self.fonts.get("digits", self.fonts["regular"]), 8)
        canvas.setFillGray(0.25)
        canvas.drawCentredString(w / 2.0, doc.bottomMargin - 8 * mm,
                                 str(canvas.getPageNumber()))
        canvas.restoreState()

    def handle_pageBegin(self):
        """Alternate recto/verso templates so the gutter swaps sides."""
        self._handle_pageBegin()
        # This chooses the template of the page AFTER the one now beginning, so
        # an even page is followed by a recto. (It was the other way round, which
        # put every gutter on the outer edge from page 2 on.)
        nxt = "recto" if self.page % 2 == 0 else "verso"
        self._handle_nextPageTemplate(nxt)


class BookEnd(Flowable):
    """A rule across the page closing the Bible.

    Books do not get one. Each book OPENS with a rule across the page, set
    under columns that have been balanced to end level, and that already says
    where the book before it stopped; a closing rule directly above an opening
    one is the same statement twice. Only the last book has nothing after it
    to do that job, so the Bible ends on a rule of its own.
    """

    def __init__(self, leading, colour, width_fraction=1.0):
        Flowable.__init__(self)
        self.leading = leading
        self.colour = colour
        self.fraction = width_fraction

    def wrap(self, availWidth, availHeight):
        self.width = availWidth
        self.height = self.leading
        return self.width, self.height

    def draw(self):
        c = self.canv
        rule = self.width * self.fraction
        y = self.leading * 0.45
        c.setStrokeColor(self.colour)
        c.setLineWidth(max(0.4, self.leading * 0.045))
        c.line((self.width - rule) / 2.0, y, (self.width + rule) / 2.0, y)


class BookHeading(Flowable):
    """The book opening, set as a single ruled line:  ── GENESIS ──

    A stacked title with white space around it costs four or five lines every
    time a book opens, and in a chronological Bible books open far more often
    than in a canonical one — Psalms alone are entered dozens of times. One
    ruled line does the same job of saying "a new book starts here" in a
    single line of the grid, and the rules carry the reader's eye across the
    measure in a way centred type on its own does not.

    Running heads are driven by PageBody.marks, not by this.
    """

    def __init__(self, text, style, leading, rtl=False):
        Flowable.__init__(self)
        self.text = text
        self.rtl = rtl
        self.style = style
        self.leading = leading
        # Where this rule actually landed, filled in at draw time. The running
        # head is drawn after the content (onPageEnd), so by then the document
        # can ask whether this page opens a book and drop the head if it does.
        self.drawn_y = None
        self.space_before = leading * 1.2
        self.space_after = leading * 0.6

    def getSpaceBefore(self):
        return self.space_before

    def getSpaceAfter(self):
        return self.space_after

    def wrap(self, availWidth, availHeight):
        self.width = availWidth
        self.height = self.leading
        return self.width, self.height

    def draw(self):
        c = self.canv
        st = self.style
        size = st.fontSize
        self.drawn_y = c.absolutePosition(0, 0)[1]
        gap = size * 0.9                       # air between rule and word
        char_space = size * 0.14
        if self.rtl:
            # Shaped, never upper-cased or letterspaced: both break Arabic.
            sh, w = shaped(self.text, st.fontName, size)
            x = (self.width - w) / 2.0
            y = (self.leading - size) / 2.0 + size * 0.06
            c.setFillColor(st.textColor)
            c.setStrokeColor(st.textColor)
            c.setFont(st.fontName, size)
            c.drawString(x, y, sh)
            ry = y + size * 0.34
            c.setLineWidth(max(0.4, size * 0.055))
            if x - gap > 0:
                c.line(0, ry, x - gap, ry)
                c.line(x + w + gap, ry, self.width, ry)
            return
        label = self.text.upper()
        # setCharSpace adds space AFTER every glyph, the last one included, so
        # the measured width overshoots by one unit; take it back or the word
        # sits left of centre.
        w = stringWidth(label, st.fontName, size) + char_space * (len(label) - 1)
        c.setFillColor(st.textColor)
        c.setStrokeColor(st.textColor)
        x = (self.width - w) / 2.0
        y = (self.leading - size) / 2.0 + size * 0.06
        # Letterspacing lives on the text object, not the canvas.
        tx = c.beginText(x, y)
        tx.setFont(st.fontName, size)
        tx.setCharSpace(char_space)
        tx.setFillColor(st.textColor)
        tx.textOut(label)
        c.drawText(tx)
        # The rule sits on the visual centre of the capitals, not the baseline.
        ry = y + size * 0.34
        c.setLineWidth(max(0.4, size * 0.055))
        if x - gap > 0:
            c.line(0, ry, x - gap, ry)
            c.line(x + w + gap, ry, self.width, ry)


# Hyphenation patterns by edition language, as pyphen names them. A language
# missing here is set WITHOUT hyphenation: English patterns applied to Finnish
# or Greek break words in places no reader of that language would, which is
# worse than a loose line. pyphen ships no Finnish or Latin dictionary, and its
# Greek one is modern monotonic, not the polytonic of these editions.
HYPHENATION = {"en": "en_GB", "de": "de_DE", "es": "es", "fr": "fr",
               "it": "it_IT", "ru": "ru_RU", "sv": "sv"}


def styles_for(fonts, body_size=8.8, leading=10.6, accent="black", lang="en"):
    cjk = lang.split("-")[0].lower() in CJK_LANGS
    rtl = is_rtl(lang)
    r, i, b = fonts["regular"], fonts["italic"], fonts["bold"]
    # Front pages are English, set in a Latin face: the script's own face (Ezra
    # SIL) has no Latin, and Paragraph cannot run right-to-left anyway.
    fr, fi, fb = ((fonts["latin_regular"], fonts["latin_italic"], fonts["latin_bold"])
                  if rtl else (r, i, b))
    ac = accent_colour(accent)
    # Justified text at a ~62 mm measure wants hyphenation or it opens rivers.
    # ReportLab only hyphenates when pyphen is importable, so ask once and say
    # so rather than shipping loose lines without anyone noticing.
    try:
        import pyphen  # noqa: F401
        patterns = HYPHENATION.get(lang.split("-")[0].lower())
        hyphen = (dict(hyphenationLang=patterns, embeddedHyphenation=1)
                  if patterns else {})
        if not patterns:
            if lang.split("-")[0].lower() not in CJK_LANGS and not rtl:
                sys.stderr.write(
                    f"note: no hyphenation patterns for '{lang}'; columns are "
                    f"justified without hyphenation and may set loose.\n")
    except ImportError:
        hyphen = {}
        sys.stderr.write(
            "note: pyphen is not installed, so justified columns will not be "
            "hyphenated and word spacing will be loose. "
            "pip3 install pyphen --break-system-packages\n")
    if cjk or rtl:
        hyphen = {}              # no hyphenation; lines break between characters
    return {
        "_cjk": cjk,
        "_rtl": rtl,
        "fhead": ParagraphStyle(
            "fhead", fontName=fb, fontSize=body_size + 1.2,
            leading=body_size + 6, alignment=TA_CENTER, textColor=ac,
            spaceBefore=leading * 1.6, spaceAfter=leading * 0.7,
            textTransform="uppercase", letterSpacing=0.6),
        "body": ParagraphStyle(
            "body", fontName=r, fontSize=body_size, leading=leading,
            alignment=TA_JUSTIFY, spaceAfter=0, firstLineIndent=0,
            **({"wordWrap": "CJK"} if cjk else {}), **hyphen),
        "book": ParagraphStyle(
            "book", fontName=b, fontSize=body_size + 1.2,
            leading=body_size + 6, alignment=TA_CENTER, textColor=ac,
            spaceBefore=leading * 1.6, spaceAfter=leading * 0.7,
            textTransform="uppercase", letterSpacing=0.6),
        # The long title, under the rule: quiet, italic, and mixed case, so it
        # reads as a gloss on the name rather than a second shout of it.
        "booklong": ParagraphStyle(
            "booklong", fontName=i, fontSize=body_size - 1.2,
            leading=leading - 1.2, alignment=TA_CENTER, textColor=ac,
            spaceBefore=0, spaceAfter=leading * 0.45),
        "seam": ParagraphStyle(
            "seam", fontName=i, fontSize=body_size - 1.2,
            leading=leading - 1, alignment=TA_CENTER,
            textColor="#555555", spaceBefore=2, spaceAfter=leading * 0.5),
        "title": ParagraphStyle(
            "title", fontName=fb, fontSize=22, leading=26, alignment=TA_CENTER,
            spaceAfter=10),
        "subtitle": ParagraphStyle(
            "subtitle", fontName=fi, fontSize=11, leading=15,
            alignment=TA_CENTER, spaceAfter=6),
        "front": ParagraphStyle(
            "front", fontName=fr, fontSize=9.4, leading=13.4,
            alignment=TA_JUSTIFY, spaceAfter=7),
    }


def esc(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;"))


def chapter_paragraph(book, chapter, verses, st, fonts, accent_colour_obj):
    """One chapter as one justified block, verse numbers superscript.

    Bibles set verses run-on rather than one per line; a verse-per-paragraph
    setting would add roughly a third to the page count and read as a list.
    The chapter numeral carries the accent; the verse numbers do not — there
    are thirty times as many of them, and colouring those would both busy the
    page and multiply the ink on very thin paper.

    The numeral is a DROP CAP two lines deep: its cap height reaches the
    ascenders of line one and its baseline sits on line two, with the text
    wrapping around it. That is what a printed Bible does, and an inline
    numeral sitting mid-line — which is all Paragraph can do on its own —
    reads as a slightly bold verse number rather than the start of a chapter.

    The result is a Chapter, which the page composer breaks into lines once
    and places slice by slice.
    """
    if st["_rtl"]:
        return RtlChapter(book, chapter, verses, st, fonts, accent_colour_obj)
    parts = []
    for n, text in verses:
        parts.append(
            f'<super><font size="6.2">{n}</font></super>'
            f'{"" if st["_cjk"] else "&nbsp;"}{esc(text)}{"" if st["_cjk"] else " "}')
    para = Paragraph("".join(parts), st["body"])
    cap = CapNumeral(chapter, fonts["bold"], st["body"], accent_colour_obj,
                     lines=DROP_CAP_LINES)
    return Chapter(book, chapter, para, cap, xpad=st["body"].fontSize * 0.16)


# ── page composition ──────────────────────────────────────────────────────────
#
# The rules, in lines of the body grid:
#
#   CHAPTER_GAP        one empty line between chapters, never at a column top
#   MIN_CHAPTER_START  a chapter may not open at the foot of a column unless
#                      at least three of its lines fit: two would be the drop
#                      cap and nothing else
#   MIN_CARRIED        nor may a single line be carried over to the next
#                      column, or left behind in the last one
#
# A column that cannot honour them comes up a few lines short, and columns
# that end at different depths are the first thing the eye catches on a
# two-column page. So a short column is FEATHERED to the depth of its
# neighbour: the space is given to the gaps between its chapters first (up to
# one extra line each), and whatever is left is spread across its lines as a
# fraction of a point of leading. A column short by more than MAX_FEATHER
# lines, or one that would need its lines opened by more than
# MAX_LINE_STRETCH, is left alone — that is a genuinely short last column,
# not a rounding error, and stretching it would look worse than leaving it.

CHAPTER_GAP = 1
MIN_CHAPTER_START = 3
MIN_CARRIED = 2
MAX_FEATHER = 5
MAX_LINE_STRETCH = 0.10


def fill_columns(chapters, ci, a, lines, ncols):
    """Pour chapters[ci:] (starting at line `a` of the first) into `ncols`
    columns of `lines` lines. Returns the columns, each a pair of
    ([(gap lines, lines taken, chapter, from, to)], lines used), the lines
    used by the fullest column, and where the pour stopped."""
    cols, deepest = [], 0
    for _ in range(ncols):
        col, used = [], 0
        while ci < len(chapters):
            c = chapters[ci]
            gap = CHAPTER_GAP if (used and a == 0) else 0
            space = lines - used - gap
            rem = c.n - a
            # A whole chapter shorter than its own drop cap still needs the
            # cap's two lines.
            need = max(rem, DROP_CAP_LINES) if a == 0 else rem
            if need <= space:
                col.append((gap, need, c, a, c.n))
                used += gap + need
                ci, a = ci + 1, 0
                continue
            take = min(space, rem - MIN_CARRIED)
            if take < (MIN_CHAPTER_START if a == 0 else MIN_CARRIED):
                break
            col.append((gap, take, c, a, a + take))
            used += gap + take
            a += take
            break
        cols.append((col, used))
        deepest = max(deepest, used)
        if ci >= len(chapters):
            break
    return cols, deepest, ci, a


def compose_pages(units, geom, leading):
    """Lay the whole Bible out as a list of PageBody.

    `units` is a list of ("open", [flowables]), ("chapters", [Chapter]) and
    ("end", flowable). Openings and endings run the full width of the text
    block; the chapters between them are poured into columns, and where a run
    of chapters ends mid-page its columns are BALANCED — made as short as
    they can be while still holding everything — so that the rule which
    follows has a level floor to sit under.
    """
    ncols = geom["columns"]
    measure = geom["measure"]
    width = geom["width"] - geom["inner"] - geom["outer"]
    height = geom["height"] - geom["top"] - geom["bottom"]
    eps = 1e-4
    pages = [PageBody(width, height)]
    y = [0.0]

    def new_page():
        pages[-1].height = height
        pages.append(PageBody(width, height))
        y[0] = 0.0

    def place_columns(cols, depth):
        """Draw the columns, each brought to `depth` lines if it is close."""
        page = pages[-1]
        for k, (col, used) in enumerate(cols):
            # In a right-to-left book the first column is the RIGHT one.
            x = ((ncols - 1 - k) if geom.get("rtl") else k) * (measure + COL_GAP)
            gap_extra, lead = 0.0, leading
            short = depth - used
            if col and 0 < short <= MAX_FEATHER:
                pts = short * leading
                gaps = sum(1 for g, *_ in col if g)
                if gaps:
                    gap_extra = min(pts / gaps, leading)
                    pts -= gap_extra * gaps
                if pts > eps:
                    stretch = pts / sum(n for _g, n, *_ in col)
                    if stretch <= MAX_LINE_STRETCH * leading:
                        lead = leading + stretch
                        pts = 0
                    elif gaps:
                        gap_extra += pts / gaps
                        pts = 0
                if pts > eps:
                    page.short = max(page.short, short)
            elif short > 0:
                page.short = max(page.short, short)
            yy = y[0]
            for gap, need, c, a, b in col:
                if gap:
                    yy += gap * leading + gap_extra
                piece = c.piece(a, b, lead)
                page.items.append((yy, x, piece, piece.height))
                yy += need * lead
                if a == 0:
                    page.marks.append((c.book, c.number))
        y[0] += depth * leading

    def pour(chapters, reserve):
        ci = a = 0
        for c in chapters:
            if c.n is None:
                c.break_lines(measure)
        while ci < len(chapters):
            avail = height - y[0]
            room = int((avail - reserve + eps) / leading)
            left = sum(c.n for c in chapters[ci:]) - a
            # Will what is left fit on this page at all? If it might, find
            # the shallowest columns that hold it.
            if left <= ncols * room:
                start = room if ncols == 1 else max(1, -(-left // ncols))
                for lines in range(start, room + 1):
                    cols, deep, ci2, _a2 = fill_columns(chapters, ci, a,
                                                        lines, ncols)
                    if ci2 >= len(chapters):
                        place_columns(cols, deep)
                        return
            full = int((avail + eps) / leading)
            cols, deep, ci2, a2 = fill_columns(chapters, ci, a, full, ncols)
            if (ci2, a2) == (ci, a):
                if y[0] == 0:
                    raise SystemExit(
                        f"{chapters[ci].book} {chapters[ci].number} cannot be "
                        f"placed on an empty page — the text block is too "
                        f"small for the rules in compose_pages.")
            else:
                place_columns(cols, full)
                ci, a = ci2, a2
            new_page()

    for i, (kind, what) in enumerate(units):
        if kind == "open":
            head = what[0]
            sizes = [f.wrap(width, height)[1] for f in what]
            # One empty line between the opening and the text it opens.
            after = leading + (what[-1].getSpaceAfter() if len(what) > 1
                               else head.space_after)
            block = sum(sizes) + after
            # The rule, its title and the opening of the first chapter stay
            # together: a book rule alone at the foot of a page, with its
            # text overleaf, is worse than a few empty lines.
            before = head.space_before if y[0] else 0
            if y[0] + before + block + MIN_CHAPTER_START * leading > height:
                new_page()
                before = 0
            y[0] += before
            for f, h in zip(what, sizes):
                pages[-1].items.append((y[0], 0, f, h))
                y[0] += h
            y[0] += after
        elif kind == "chapters":
            nxt = units[i + 1] if i + 1 < len(units) else None
            reserve = leading if (nxt and nxt[0] == "end") else 0
            pour(what, reserve)
        else:
            h = what.wrap(width, height)[1]
            if y[0] + h > height + eps:
                new_page()
            pages[-1].items.append((y[0], 0, what, h))
            y[0] += h
    pages[-1].height = max(y[0], leading)
    return pages


def build_story(rows, st, fonts, ordering, geom, accent="black",
                book_titles="long", book_end=True):
    """rows: iterable of (book, chapter, verse_no, text) in final order."""
    accent_obj = accent_colour(accent)
    leading = st["body"].leading
    units = []
    cur_book = cur_chap = None
    buf = []
    seen_books = set()

    def flush():
        if cur_chap is not None and buf:
            if not units or units[-1][0] != "chapters":
                units.append(("chapters", []))
            units[-1][1].append(chapter_paragraph(cur_book, cur_chap, buf, st,
                                                  fonts, accent_obj))

    def open_book(book):
        """The rule, and under it the long title when we have one."""
        head = BookHeading(book, st["book"], leading, rtl=st["_rtl"])
        under = []
        repeat = book in seen_books and ordering != "canonical"
        if repeat:
            # A book re-entered later in a reordered Bible. "continued" is the
            # honest line; the long title belongs only to the first entry.
            if st["_rtl"]:
                under.append(RtlLine(st["_continued"], fonts["regular"],
                                     fonts["digits"], st["seam"].fontSize,
                                     st["seam"].leading, accent_obj,
                                     space_after=leading * 0.5))
            else:
                under.append(Paragraph(st.get("_continued", "continued"), st["seam"]))
        elif book_titles == "long":
            title = long_title(book)
            # The long title cannot go ON the rule in a small trim — "THE
            # FIRST BOOK OF MOSES, CALLED GENESIS" is 96 mm of capitals — so
            # it is a second, quieter line beneath, which is also how a
            # printed Bible has always set it.
            if title:
                under.append(Paragraph(esc(title), st["booklong"]))
        return [head] + under

    for book, chap, vno, text in rows:
        if book != cur_book:
            flush()
            buf = []
            cur_chap = None
            units.append(("open", open_book(book)))
            seen_books.add(book)
            cur_book = book
        if chap != cur_chap:
            flush()
            buf = []
            cur_chap = chap
        buf.append((vno, text))
    flush()
    if cur_book is not None and book_end:
        units.append(("end", BookEnd(leading, accent_obj)))

    story = []
    for page in compose_pages(units, geom, leading):
        story += [page, PageBreak()]
    return story[:-1]


def front_matter(st, meta):
    """Title, what this ordering is, and the source attribution.

    The attribution page is not optional: the project's standing rule is that
    the source is shown wherever the text is printed. It is also the page a
    printer looks at when asking who owns the text.
    """
    s = [
        Spacer(1, 40 * mm),
        Paragraph(meta["title"], st["title"]),
        Paragraph(meta["subtitle"], st["subtitle"]),
        Spacer(1, 12 * mm),
        Paragraph(meta["imprint"], st["subtitle"]),
        PageBreak(),
        Paragraph(meta.get("h_about", "About this arrangement"), st["fhead"]),
        Paragraph(meta["about"], st["front"]),
        Spacer(1, 6 * mm),
        Paragraph(meta.get("h_rights", "Source and rights"), st["fhead"]),
        Paragraph(meta["rights"], st["front"]),
        PageBreak(),
    ]
    return s


ABOUT = {
    "writing":
        "The books of this Bible are arranged by when they were written, not by the order of the events they tell and not by their traditional place. Each book stands at the middle of the range of years in which scholars generally hold it to have reached its present form, on the critical consensus: so Amos comes near the beginning, Deuteronomy comes before Genesis, and the Gospel of John is among the last. A book that grew over centuries is placed by its final form, and Isaiah and Zechariah, whose parts are dated separately, are entered more than once; the heading says <i>continued</i>. Traditional attribution — Moses as the author of the Pentateuch, David of the Psalms — would give a different order, and this one does not follow it. The dating is disputed in places; it is a way of reading, not a claim about the text.",
    "chronological":
        "The books and chapters of this Bible are not in the order you will "
        "find in any other. They are arranged by when the events they "
        "describe are generally held to have happened, so that Job stands "
        "between Genesis 11 and Genesis 12, and Psalm 90 — ascribed to Moses "
        "— falls at the death of Moses rather than four hundred pages later. "
        "Where a book is entered more than once, the heading says "
        "<i>continued</i>. The dating behind this order is a scholarly "
        "reconstruction and is disputed in places; it is a way of reading, "
        "not a claim about the text.",
    "canonical":
        "The books stand in their traditional printed order.",
    "tanakh":
        "The Hebrew Bible's own order: Torah, Prophets, Writings, ending at "
        "Chronicles rather than Malachi. This is the sequence the books were "
        "gathered in, and the one the New Testament's readers would have "
        "known.",
    "narrative":
        "The books are arranged to follow the narrative thread rather than "
        "the traditional order.",
}


BOOKNAMES_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "app", "src", "main", "resources", "i18n")


def localised_book_names(translation, lang):
    """{corpus book name: the name in the edition's own language}.

    The corpus names every book in English whatever the edition's language,
    so a Russian Bible would open on GENESIS. The reader already solves this
    with booknames_<lang>.properties, keyed by USFM code; the same bundles
    are used here so print and screen call a book by the same name. A
    language with no bundle keeps the English names, and says so.
    """
    code = lang.split("-")[0].lower()
    if code == "en":
        return {}
    # Script-specific names first (booknames_zh_Hant.properties for a Traditional
    # Chinese edition), then the language's own bundle.
    path = os.path.join(BOOKNAMES_DIR, f"booknames_{lang.replace('-', '_')}.properties")
    if not os.path.exists(path):
        path = os.path.join(BOOKNAMES_DIR, f"booknames_{code}.properties")
    if not os.path.exists(path):
        sys.stderr.write(
            f"note: no booknames_{code}.properties; book headings and running "
            f"heads stay in English.\n")
        return {}
    names = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith(("#", "!")) and "=" in line:
                k, v = line.split("=", 1)
                if "\\u" in v:
                    v = v.encode("ascii", "backslashreplace").decode(
                        "unicode_escape")
                names[k.strip()] = v.strip()
    from build_edition import xq, NS
    out = {}
    for ln in xq(NS + f"for $b in db:open('religioustext', '{translation}.xml')"
                 f"//rt:book return string($b/@name) || '&#9;' || "
                 f"string($b/@code)").split("\n"):
        if "\t" in ln:
            name, usfm_code = ln.split("\t", 1)
            if usfm_code.strip() in names:
                out[name] = names[usfm_code.strip()]
    return out


# What the title page calls each reading order. "Chronological" alone no longer
# says enough: the Bible can be ordered by when its EVENTS happened or by when
# its books were WRITTEN, and the two are different books.
ORDER_LABEL = {"chronological": "Event order", "writing": "Writing order", "canonical": "Canonical order", "tanakh": "Tanakh order", "narrative": "Narrative order"}


TRADITIONAL_ONLY = "創說為國這與們來時從還個後對點學當見麼無會過覺開關門聽讀"
SIMPLIFIED_ONLY = "创说为国这与们来时从还个后对点学当见么无会过觉开关门听读"


def han_script(rows):
    """"zh-Hant" or "zh-Hans" from the text itself, by counting characters that
    only one script uses. The corpus records only "zh"."""
    sample = "".join(r[3] for r in rows[:4000])
    t = sum(sample.count(c) for c in TRADITIONAL_ONLY)
    h = sum(sample.count(c) for c in SIMPLIFIED_ONLY)
    return "zh-Hant" if t >= h else "zh-Hans"


# Editions that are NOT public domain but carry an open licence that allows
# printing on conditions. The conditions are printed in the book.
LICENSED = {"bible-ar-onav"}


def licensed_notice(translation, ordering):
    if translation == "bible-ar-onav":
        moved = ("" if ordering == "canonical" else
                 " The order of the books has been rearranged from the original, "
                 "which makes this book a derivative of it.")
        return ("The text is the Open New Arabic Version, \u00a9 1988, 1997, 2012 "
                "Biblica, Inc., used under the Creative Commons Attribution-"
                "ShareAlike 4.0 International licence "
                "(creativecommons.org/licenses/by-sa/4.0)." + moved +
                " The original work by Biblica, Inc. is available for free at "
                "www.biblica.com and open.bible. This book is itself offered "
                "under the same licence. ")
    raise KeyError(translation)


def load_frontmatter(lang):
    """The front matter in the edition's own language, or None (English). Kept in
    scripts/print/frontmatter_<lang>.json, e.g. frontmatter_zh_Hant.json; the
    same file feeds build_cjk_font.py so the font has every character it needs."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "frontmatter_" + lang.replace("-", "_") + ".json")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--translation", help="BaseX document id, e.g. bible-web")
    ap.add_argument("--ordering", default="chronological",
                    choices=sorted(ABOUT))
    ap.add_argument("--canon", default="66", choices=("66", "full"),
                    help="66 = the shared Protestant canon; full = every book "
                         "this edition carries, apocrypha included")
    ap.add_argument("--trim", default="standard", choices=sorted(TRIMS))
    ap.add_argument("--outer", type=float,
                    help="fore-edge margin in mm — THE reader-facing control; "
                         "wide values give a notes edition and cost pages")
    ap.add_argument("--inner", type=float,
                    help="gutter margin in mm (normally derived from extent)")
    ap.add_argument("--top", type=float, help="head margin in mm")
    ap.add_argument("--bottom", type=float, help="foot margin in mm")
    ap.add_argument("--columns", type=int, choices=(1, 2),
                    help="override the trim's column count")
    ap.add_argument("--fonts", help="directory of licensed .ttf faces")
    ap.add_argument("--font-family", default="CrimsonPro")
    ap.add_argument("--latin-fonts",
                    help="right-to-left editions only: directory of the Latin "
                         "face for numbers and the English front pages "
                         "(default: ../crimson next to --fonts)")
    ap.add_argument("--latin-family", default="CrimsonPro")
    ap.add_argument("--title", default="The Holy Bible")
    ap.add_argument("--lang",
                    help="language of the text, for hyphenation (default: the "
                         "edition's own language as the corpus records it)")
    ap.add_argument("--edition-name", default="")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, help="stop after N verses (dry runs)")
    ap.add_argument("--body-size", type=float,
                    help="body type size in points; defaults to what the trim "
                         "was designed around (pocket 7.2, standard 8.8, "
                         "large 9.4)")
    ap.add_argument("--book-titles", default="long", choices=BOOK_TITLE_MODES,
                    help="'long' sets the traditional English title as a "
                         "second line under each book rule (\"The First Book "
                         "of Moses, called Genesis\"). Those titles assert "
                         "authorship the books do not claim for themselves — "
                         "see DISPUTED_ATTRIBUTION — so 'short' prints the "
                         "bare name and asserts nothing")
    ap.add_argument("--no-book-end", action="store_true",
                    help="suppress the rule that closes the Bible")
    ap.add_argument("--accent", default="black", choices=sorted(ACCENTS),
                    help="colour for the apparatus — running heads, book "
                         "titles and chapter numerals. Scripture stays black. "
                         "ANYTHING BUT black makes this a colour print job, "
                         "which is not what Pretore quoted")
    ap.add_argument("--running-head", default="split",
                    choices=HEAD_MODES,
                    help="what the top of each page says. 'split' treats the "
                         "spread as one unit, the old dictionary convention: "
                         "the verso names the BOOK, the recto the CHAPTERS, "
                         "and neither side repeats the other. 'book-chapter' "
                         "puts both on every page (JEREMIAH 41-42); 'book' "
                         "suits an edition that prints no chapter numbers; "
                         "'none' suits scriptio continua, where any furniture "
                         "breaks the effect")
    ap.add_argument("--self-test", action="store_true",
                    help="build from synthetic verses, no BaseX")
    ap.add_argument("--self-test-chapters", type=int, default=3,
                    help="synthetic chapters per book; raise it to get page "
                         "counts stable enough to compare geometries")
    a = ap.parse_args()

    geom = resolve_geometry(a.trim, a.outer, a.inner, a.top, a.bottom,
                            a.columns, a.body_size)
    fonts = register_fonts(a.fonts, a.font_family)
    lang = a.lang
    if not lang and a.translation and not a.self_test:
        from build_edition import xq, NS, CorpusError
        try:
            lang = xq(NS + f"string(db:open('religioustext', "
                      f"'{a.translation}.xml')/rt:text/@bcp47Language)").strip()
        except CorpusError as e:
            raise SystemExit(str(e))
    cjk = (lang or "en").split("-")[0].lower() in CJK_LANGS
    rtl = is_rtl(lang)
    latin_dir = None
    if rtl:
        if not a.fonts:
            raise SystemExit("A right-to-left edition needs --fonts with a face "
                             "that carries its script (Scheherazade New, Ezra SIL).")
        latin_dir = a.latin_fonts or os.path.join(
            os.path.dirname(os.path.abspath(a.fonts)), "crimson")
        lat = register_fonts(latin_dir, a.latin_family)
        fonts.update(digits=lat["regular"], digits_bold=lat["bold"],
                     latin_regular=lat["regular"], latin_italic=lat["italic"],
                     latin_bold=lat["bold"])
        geom["rtl"] = True
    # Han characters are a full em square and carry far more detail than Latin
    # letters, so they are set a little larger and on much more open leading
    # (about 1.55 against 1.2) — the same text at Latin size would be dense.
    # Arabic and Hebrew carry vowel points and cantillation above and below
    # the line and read small at a Latin size, so they too are set larger on
    # open leading.
    body_pt = geom["body_pt"] * (1.1 if cjk else 1.15 if rtl else 1.0)
    st = styles_for(fonts, body_size=body_pt,
                    leading=round(body_pt * (1.55 if cjk else 1.7 if rtl else 1.205), 2),
                    accent=a.accent, lang=lang or "en")
    # The long titles are the English tradition's. Under a Greek or Russian
    # text "The First Book of Moses, called Genesis" is a foreign-language
    # caption, so they are an English-edition feature only.
    if (lang or "en").split("-")[0].lower() != "en":
        a.book_titles = "short"

    if a.self_test:
        rows = []
        for ch in range(1, a.self_test_chapters + 1):
            for v in range(1, 26):
                rows.append(("Genesis", ch, v,
                             "In the beginning God created the heavens and "
                             "the earth, and the earth was formless and "
                             "empty, and darkness was on the surface of the "
                             "deep."))
        for v in range(1, 30):
            rows.append(("Job", 1, v,
                         "There was a man in the land of Uz whose name was "
                         "Job, and that man was blameless and upright."))
        rows += [("Genesis", 12, v, "Now Yahweh said to Abram, Leave your "
                  "country, and your relatives, and your father's house.")
                 for v in range(1, 21)]
        src_note = "Synthetic text — self-test only."
    else:
        if not a.translation:
            ap.error("--translation is required (or use --self-test)")
        from build_edition import stream_verses, CorpusError
        try:
            rows = list(stream_verses(a.translation, a.ordering,
                                      limit=a.limit))
        except CorpusError as e:
            raise SystemExit(str(e))
        # Some editions print consecutive verses as one passage (the 1919 Chinese
        # Union Version does); the corpus keeps the standard numbering with the
        # text on the first verse and the rest empty. An empty verse has nothing
        # to set, and its bare number would dangle in the text.
        rows = [r for r in rows if r[3].strip()]
        src_note = a.translation
        if a.canon == "66":
            before = len(rows)
            rows = [r for r in rows if in_canon_66(r[0])]
            dropped = before - len(rows)
            if dropped:
                print(f"  canon=66: excluded {dropped} verses from books "
                      f"outside the shared 66")

    if not rows:
        raise SystemExit("No verses to set.")

    extra = sorted({r[0] for r in rows if not in_canon_66(r[0])})
    if (lang or "").split("-")[0].lower() == "zh" and "-" not in (lang or ""):
        lang = han_script(rows)
    front = load_frontmatter(lang) if lang and not a.self_test else None
    if rtl:
        st["_continued"] = RTL_CONTINUED.get((lang or "").split("-")[0].lower(), "")
    if front:
        st["_continued"] = front.get("continued", "continued")
        if a.title == "The Holy Bible":
            a.title = front["title"]
    if not a.self_test:
        local = localised_book_names(a.translation, lang or "en")
        if local:
            rows = [(local.get(r[0], r[0]),) + tuple(r[1:]) for r in rows]
            extra = [local.get(b, b) for b in extra]
    meta = {
        "title": a.title,
        "subtitle": a.edition_name or ORDER_LABEL.get(
            a.ordering, f"{a.ordering.capitalize()} order"),
        "imprint": "common-root.org",
        "about": ABOUT[a.ordering] + (
            "<br/><br/>This edition also includes "
            + ", ".join(extra) + "." if extra else ""),
        "rights": (
            f"Text: {src_note}. "
            + (licensed_notice(a.translation, a.ordering) if a.translation in LICENSED
               else "This translation is in the public domain: it has no rights "
                    "holder and carries no per-copy licence. ")
            + "Typeset by Common Root (common-root.org) from a structured text "
            "corpus. Set in " + a.font_family
            + (" and " + a.latin_family if rtl else "")
            + ", used under the SIL Open Font Licence. The full text of that "
            "licence accompanies this file."),
    }

    if front:
        # The front pages in the edition's own language.
        edition = front.get("edition_names", {}).get(a.translation, src_note)
        rights = front["rights"].format(edition=edition)
        credit = front.get("credits", {}).get(a.translation)
        meta.update({
            "subtitle": a.edition_name or front["order"].get(a.ordering, meta["subtitle"]),
            "about": front["about"].get(a.ordering, ""),
            "rights": rights + (" " + credit if credit else ""),
            "h_about": front["headings"]["about"],
            "h_rights": front["headings"]["rights"],
        })

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    doc = BookDoc(a.out, geom, fonts, a.title, head_mode=a.running_head,
                  accent=a.accent)
    story = front_matter(st, meta) + build_story(rows, st, fonts, a.ordering,
                                                 geom, accent=a.accent,
                                                 book_titles=a.book_titles,
                                                 book_end=not a.no_book_end)
    doc.build(story)
    bodies = [f for f in story if isinstance(f, PageBody)]
    first_body = doc.page - len(bodies) + 1
    uneven = [(first_body + i, b.short) for i, b in enumerate(bodies)
              if b.short]

    # The printer asked for fonts "licensed for printing". Answer it before it
    # is asked again: put the licence next to the file it applies to.
    licences = []
    if a.fonts:
        import shutil
        outdir = os.path.dirname(os.path.abspath(a.out)) or "."
        # Two font directories (script face + Latin face) each carry an
        # OFL.txt; prefix them with the directory so neither overwrites the other.
        for d in [x for x in (a.fonts, latin_dir) if x]:
            for f in sorted(os.listdir(d)):
                if re.search(r"(ofl|licen[cs]e|copying)", f, re.I):
                    name = f if not latin_dir else (
                        os.path.basename(os.path.abspath(d)) + "-" + f)
                    dest = os.path.join(outdir, name)
                    shutil.copyfile(os.path.join(d, f), dest)
                    licences.append(os.path.basename(dest))

    pages = doc.page
    w, h = geom["width"], geom["height"]
    # 40 gsm India paper runs about 48 microns a leaf including the bulk the
    # binding adds; enough for a spine estimate to quote, not to cut a cover to.
    spine = (pages / 2.0) * 0.048
    print(f"\n  {a.out}")
    print(f"  {pages} pages · {w/mm:.0f} × {h/mm:.0f} mm · "
          f"spine ≈ {spine:.0f} mm at 40 gsm")
    print(f"  margins mm: inner {geom['inner']/mm:.0f} · outer "
          f"{geom['outer']/mm:.0f} · head {geom['top']/mm:.0f} · foot "
          f"{geom['bottom']/mm:.0f} · column {geom['measure']/mm:.0f} mm × "
          f"{geom['columns']} at {geom['body_pt']} pt")
    print(f"  {len(rows)} verses · {len({r[0] for r in rows})} books · "
          f"canon={a.canon} · ordering={a.ordering} · "
          f"head={a.running_head} · accent={a.accent}")
    if uneven:
        shown = ", ".join(f"{pg} ({n})" for pg, n in uneven[:12])
        print(f"  {len(uneven)} page(s) with a column left short — page "
              f"(lines): {shown}{' …' if len(uneven) > 12 else ''}")
    if a.accent != "black":
        c, m, y, k, desc = ACCENTS[a.accent]
        print(f"  ⚠ COLOUR JOB — {desc}, CMYK "
              f"{c*100:.0f}/{m*100:.0f}/{y*100:.0f}/{k*100:.0f}. "
              f"Pretore quoted monochrome; the price delta is unquoted.")
    want = gutter_advice(pages)
    if geom["inner"] < want:
        print(f"  ⚠ at {pages} pages the gutter wants ≈{want/mm:.0f} mm "
              f"inner margin; this book has {geom['inner']/mm:.0f} mm")
    if not a.fonts:
        print("  ⚠ fonts NOT embedded — layout inspection only")
    elif licences:
        print(f"  font licence(s) copied beside it: {', '.join(licences)}")
    else:
        print("  ⚠ no licence file found in the font directory — the printer "
              "asked for fonts licensed for printing, so ship one")


if __name__ == "__main__":
    main()
