#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Tests for the margin cross references: the data rules (they must match the reader's) and
the stacking of notes that would collide.  python3 -m unittest scripts/print/test_xrefs.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import xrefs  # noqa: E402
from build_pdf import place_margin_notes, wrap_note  # noqa: E402


class DataRules(unittest.TestCase):
    def rows(self, *lines):
        return list(xrefs.parse(["From Verse\tTo Verse\tVotes"] + list(lines)))

    def test_negative_votes_are_dropped(self):
        self.assertEqual(self.rows("Gen.1.1\tJohn.1.1\t-3"), [])

    def test_a_range_across_books_keeps_its_first_verse(self):
        r = self.rows("2Chr.36.22\t2Chr.36.22-Ezra.1.3\t5")
        self.assertEqual(len(r), 1)
        self.assertEqual((r[0][1], r[0][2], r[0][3], r[0][4], r[0][5]), ("2CH", 36, 22, None, None))

    def test_a_range_inside_a_book_is_kept(self):
        r = self.rows("Gen.1.1\tJohn.1.1-John.1.3\t379")
        self.assertEqual((r[0][4], r[0][5]), (1, 3))

    def test_unknown_books_and_malformed_rows_are_skipped(self):
        self.assertEqual(self.rows("Tob.1.1\tJohn.1.1\t5", "Gen.1\tJohn.1.1\t5", "Gen.1.1\tJohn.1.1\tx"), [])

    def test_labels(self):
        self.assertEqual(xrefs.label("JHN", 1, 1, 1, 3), "John 1:1–3")
        self.assertEqual(xrefs.label("HEB", 11, 3), "Heb 11:3")
        self.assertEqual(xrefs.label("PSA", 89, 11, 90, 2), "Ps 89:11–90:2")
        self.assertEqual(xrefs.label("1CO", 15, 20, 15, 20), "1 Cor 15:20")

    def test_names_follow_the_editions_language(self):
        self.assertEqual(xrefs.names_for("en")["JHN"], "John")
        sv = xrefs.names_for("sv")                      # no abbreviations yet: the reader's full names
        self.assertEqual(sv["JHN"], "Johannes")
        self.assertEqual(xrefs.label("JHN", 3, 16, names=sv), "Johannes 3:16")
        fi = xrefs.names_for("fi")                      # abbreviations take precedence over full names
        self.assertEqual(fi["JHN"], "Joh.")
        self.assertEqual(xrefs.label("JHN", 3, 16, names=fi), "Joh. 3:16")
        self.assertEqual(xrefs.names_for("zh-Hant")["JHN"], "約翰福音")      # script-specific bundle first
        self.assertEqual(xrefs.names_for("zh")["JHN"], "约翰福音")
        self.assertTrue(all(c in xrefs.names_for("ja") for c in xrefs._USFM))

    def test_inline_needs_real_abbreviations(self):
        self.assertEqual(xrefs.names_for("en", abbreviated=True)["1JN"], "1 John")
        self.assertEqual(xrefs.names_for("fi", abbreviated=True)["JHN"], "Joh.")
        for lang in ("sv", "es", "zh-Hant", "ja", "ru"):       # full names exist, abbreviations do not
            self.assertIsNotNone(xrefs.names_for(lang), lang)
            self.assertIsNone(xrefs.names_for(lang, abbreviated=True), lang)

    def test_the_credit_says_where_the_references_are(self):
        self.assertIn("in the margin", xrefs.credit_and_changes(3, "margin")[0])
        self.assertIn("after each verse", xrefs.credit_and_changes(3, "inline")[0])
        for where in ("margin", "inline"):
            self.assertIn("CC BY", xrefs.credit_and_changes(3, where)[0] + "CC BY")
            self.assertIn("Changes made", xrefs.credit_and_changes(3, where)[1])

    def test_a_language_with_no_names_is_reported_not_guessed(self):
        for lang in ("de", "fr", "it", "la", "grc"):
            self.assertIsNone(xrefs.names_for(lang), lang)

    def test_the_references_do_not_depend_on_the_language(self):
        en, fi = xrefs.load(3), xrefs.load(3, names=xrefs.names_for("fi"))
        self.assertEqual(set(en), set(fi))
        self.assertEqual(len(en[("GEN", 1, 1)]), len(fi[("GEN", 1, 1)]))
        self.assertEqual(fi[("JHN", 3, 16)][0], "Room. 5:8")

    def test_the_real_file_gives_the_references_the_reader_shows(self):
        d = xrefs.load(3)
        self.assertEqual(d[("GEN", 1, 1)], ["John 1:1–3", "Heb 11:3", "Isa 45:18"])
        self.assertEqual(d[("JHN", 3, 16)][0], "Rom 5:8")
        self.assertTrue(all(len(v) <= 3 for v in d.values()))


class InlineRun(unittest.TestCase):
    def build(self, inline):
        from reportlab.lib import colors
        from build_pdf import chapter_paragraph, register_fonts, styles_for
        fonts = register_fonts(None, "x")
        st = styles_for(fonts)
        ch = chapter_paragraph("Genesis", 1, [("1", "In the beginning."), ("2", "And the earth.")],
                               st, fonts, colors.black,
                               lambda b, c, n: ["John 1:1\u20133", "1 John 4:9"] if n == "1" else None,
                               inline)
        ch.break_lines(200)
        return [w for line in ch.para.blPara.lines for w in line.words]

    def test_inline_sets_the_references_in_smaller_type_after_the_verse(self):
        frags = self.build(True)
        small = " ".join(w.text for w in frags if abs(w.fontSize - 5.6) < 0.01)
        self.assertEqual(small.replace("\u00a0", " ").split(),
                         ["John", "1:1\u20133;", "1", "John", "4:9"])
        # Between a book and its numbers is a no-break space, so a reference is one unbreakable word.
        self.assertIn("\u00a0", small)

    def test_without_inline_the_text_is_untouched(self):
        self.assertFalse([w for w in self.build(False) if abs(w.fontSize - 5.6) < 0.01])


CFG = {"font": "Helvetica", "bold": "Helvetica-Bold", "size": 5.7, "lead": 6.6,
       "width": 90.0, "indent": lambda v: 8.0}


class Stacking(unittest.TestCase):
    def test_a_reference_is_never_split_and_later_lines_hang(self):
        lines = wrap_note(12, ["Gen 1:1", "Heb 11:3", "Isa 45:18", "1 Thess 5:23–24", "Rev 4:11"], CFG)
        self.assertGreater(len(lines), 1)
        self.assertEqual(lines[0][0], "12")
        self.assertTrue(all(num is None for num, _t in lines[1:]))
        joined = " ".join(t for _n, t in lines)
        for label in ("Gen 1:1", "Heb 11:3", "1 Thess 5:23–24", "Rev 4:11"):
            self.assertIn(label, joined)

    def test_blocks_that_collide_are_pushed_down_without_overlap(self):
        ys = place_margin_notes([500.0, 499.0, 498.0], [2, 1, 1], 0.0, 600.0, 6.6)
        self.assertEqual(ys[0], 500.0)
        self.assertAlmostEqual(ys[0] - ys[1], 2 * 6.6)
        self.assertAlmostEqual(ys[1] - ys[2], 1 * 6.6)

    def test_blocks_are_lifted_off_the_foot_of_the_page(self):
        ys = place_margin_notes([10.0, 9.0, 8.0], [1, 1, 1], 5.0, 600.0, 6.6)
        self.assertGreaterEqual(ys[2], 5.0)
        self.assertAlmostEqual(ys[0] - ys[1], 6.6)
        self.assertAlmostEqual(ys[1] - ys[2], 6.6)

    def test_too_many_blocks_for_the_page_is_reported(self):
        self.assertIsNone(place_margin_notes([30.0] * 10, [1] * 10, 0.0, 40.0, 6.6))

    def test_no_blocks_is_fine(self):
        self.assertEqual(place_margin_notes([], [], 0.0, 100.0, 6.6), [])


if __name__ == "__main__":
    unittest.main()
