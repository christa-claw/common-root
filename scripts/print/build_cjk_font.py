#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Make the Chinese print font: Noto Serif TC, cut down to the characters a book
actually uses, as two static TrueType faces (Regular, Bold) ReportLab can embed.

WHY NOT JUST POINT --fonts AT NOTO SERIF
----------------------------------------
  * ReportLab embeds TrueType (glyf) outlines only. The CFF-based "Source Han /
    Noto Serif CJK" OpenType files are refused.
  * Google's Noto Serif TC is a TrueType font, but a 17 MB VARIABLE one; ReportLab
    reads only its default weight. fontTools' instancer cuts static Regular (400)
    and Bold (700) from it.
  * A whole CJK font is ~20,000 glyphs. A Bible uses ~3,100, and every embedded
    face is carried in the PDF, so the subset is also what keeps the file small.
  * The 1919 Union Version uses old character forms (爲 愼 册 槪 ...) that the
    Traditional-Chinese cut does not carry. Noto Serif SC does, so exactly those
    glyphs are taken from it and merged in; they are single, region-neutral
    characters, so the mix is invisible. Anything neither font has is reported.

Both fonts are SIL Open Font Licence 1.1; the licence text is copied beside the
output, which is what the printer asked to see.

USAGE
    python3 scripts/print/build_cjk_font.py \\
        --text out/cuv1919-flat.json app/src/main/resources/i18n/booknames_zh_Hant.properties \\
        --out fonts/notoserif-tc \\
        --tc NotoSerifTC.ttf --sc NotoSerifSC.ttf
    (the two input fonts: github.com/google/fonts/tree/main/ofl/notoseriftc and
     .../notoserifsc, the "[wght].ttf" files)

--text takes the flat JSON of 01_fetch_wikisource_cuv.py / 03_import_json_bible.py,
or any UTF-8 text file; every character in it ends up in the font.
"""
import argparse
import io
import json
import os
import shutil
import sys

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer


def chars_of(paths):
    raw = ""
    for path in paths:
        with io.open(path, encoding="utf-8") as f:
            part = f.read()
        try:
            d = json.loads(part)
            part = "".join(v.get("text", "") for v in d["verses"])
        except (ValueError, KeyError, TypeError):
            pass
        raw += part
    # Latin digits, punctuation and book furniture are needed too.
    extra = "".join(chr(c) for c in range(0x20, 0x7F)) + "–—‘’“”·…•"
    return sorted({c for c in raw + extra if not c.isspace() or c == " "})


def static(path, weight):
    f = TTFont(path)
    f = instancer.instantiateVariableFont(f, {"wght": weight}, inplace=False)
    # A static font carries no variation tables; stray ones (HVAR/MVAR hold a
    # variation store) make the merger fail and ReportLab ignore the face.
    for tag in ("HVAR", "VVAR", "MVAR", "STAT", "avar", "fvar", "gvar", "cvar"):
        if tag in f:
            del f[tag]
    return f


def cut(font, chars, keep_layout=False):
    opts = subset.Options()
    opts.layout_features = ["kern", "locl"] if keep_layout else []
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.hinting = False
    # Horizontal setting needs no layout tables; GDEF/BASE can still carry a
    # variation store after instancing, which the merger cannot handle.
    opts.drop_tables += ["DSIG", "GSUB", "GPOS", "GDEF", "BASE", "JSTF",
                         "vhea", "vmtx", "VORG"]
    s = subset.Subsetter(opts)
    s.populate(text="".join(chars))
    s.subset(font)
    return font


def rename(font, family, style):
    for rec in list(font["name"].names):
        if rec.nameID in (1, 2, 3, 4, 6, 16, 17):
            font["name"].removeNames(nameID=rec.nameID)
    ps = f"{family}-{style}"
    for nid, val in ((1, family), (2, style), (3, ps), (4, f"{family} {style}"), (6, ps)):
        font["name"].setName(val, nid, 3, 1, 0x409)
        font["name"].setName(val, nid, 1, 0, 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--text", required=True, nargs="+",
                    help="one or more files: the verses JSON, the book-names file, front matter")
    ap.add_argument("--out", required=True)
    ap.add_argument("--tc", required=True, help="NotoSerifTC[wght].ttf")
    ap.add_argument("--sc", required=True, help="NotoSerifSC[wght].ttf (donor for old forms)")
    ap.add_argument("--family", default="NotoSerifTCSubset")
    a = ap.parse_args()

    chars = chars_of(a.text)
    tc_cmap = TTFont(a.tc).getBestCmap()
    sc_cmap = TTFont(a.sc).getBestCmap()
    from_tc = [c for c in chars if ord(c) in tc_cmap]
    donor = [c for c in chars if ord(c) not in tc_cmap and ord(c) in sc_cmap]
    nowhere = [c for c in chars if ord(c) not in tc_cmap and ord(c) not in sc_cmap]
    print(f"{len(chars)} characters: {len(from_tc)} from Noto Serif TC, "
          f"{len(donor)} borrowed from Noto Serif SC ({''.join(donor)}), "
          f"{len(nowhere)} in neither")
    if nowhere:
        print("  NOT COVERED:", "".join(nowhere), [hex(ord(c)) for c in nowhere])

    os.makedirs(a.out, exist_ok=True)
    for style, weight in (("Regular", 400), ("Bold", 700)):
        main_font = cut(static(a.tc, weight), from_tc)
        if donor:
            extra = cut(static(a.sc, weight), donor)
            tmp_a, tmp_b = os.path.join(a.out, "_a.ttf"), os.path.join(a.out, "_b.ttf")
            main_font.save(tmp_a)
            extra.save(tmp_b)
            merged = Merger().merge([tmp_a, tmp_b])
            os.remove(tmp_a)
            os.remove(tmp_b)
        else:
            merged = main_font
        rename(merged, a.family, style)
        path = os.path.join(a.out, f"{a.family}-{style}.ttf")
        merged.save(path)
        have = set(TTFont(path).getBestCmap())
        lost = [c for c in chars if ord(c) not in have]
        print(f"  {path}: {os.path.getsize(path) // 1024} KB, "
              f"{len(have)} code points, {len(lost)} missing")
    lic = os.path.join(os.path.dirname(os.path.abspath(a.tc)), "NotoSerifTC-OFL.txt")
    if os.path.exists(lic):
        shutil.copyfile(lic, os.path.join(a.out, "OFL.txt"))
    else:
        print("note: copy the OFL.txt that ships with the font into", a.out)


if __name__ == "__main__":
    main()
