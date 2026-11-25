# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Unit tests for the commentary pipeline. Stdlib only:
    python3 -m unittest scripts/commentaries/test_commentary_lib.py
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import commentary_lib as L   # noqa: E402
import read_sword as W       # noqa: E402


class Books(unittest.TestCase):
    def test_names_abbreviations_and_osis_resolve(self):
        for name, code in [("Genesis", "GEN"), ("Ge", "GEN"), ("Gen", "GEN"), ("Exod", "EXO"), ("Mr", "MRK"),
                           ("Joh", "JHN"), ("1Jo", "1JN"), ("1 Cor", "1CO"), ("II Kings", "2KI"), ("Ps", "PSA"),
                           ("Song of Solomon", "SNG"), ("Devarim", "DEU"), ("Philem", "PHM")]:
            self.assertEqual(L.book_code(name), code, name)

    def test_unknown_is_none(self):
        self.assertIsNone(L.book_code("Xyzzy"))


class Refs(unittest.TestCase):
    def test_forms(self):
        self.assertEqual(L.ref_target("Ge 1:1"), "GEN.1.1")
        self.assertEqual(L.ref_target("Isa 7:14-16"), "ISA.7.14-16")
        self.assertEqual(L.ref_target("Bible:Gen.1.1"), "GEN.1.1")
        self.assertEqual(L.ref_target("Matt.5.3"), "MAT.5.3")
        self.assertEqual(L.ref_target("|Gen|1|1|0|0"), "GEN.1.1")
        self.assertEqual(L.ref_target("|Gen|1|1|1|5"), "GEN.1.1-5")
        self.assertEqual(L.ref_target("|Gen|1|0|0|0"), "GEN.1")
        self.assertIsNone(L.ref_target("see above"))

    def test_lists_link_each_reference_and_keep_the_text(self):
        out = L.linkify_list("Pr 8:22-24; 16:4; Ps 33:6,9")
        self.assertIn('<ref target="PRO.8.22-24">Pr 8:22-24</ref>', out)
        self.assertIn('<ref target="PRO.16.4">16:4</ref>', out)
        self.assertIn('<ref target="PSA.33.9">9</ref>', out)
        self.assertEqual(re.sub(r"<[^>]+>", "", out), "Pr 8:22-24; 16:4; Ps 33:6,9")


class Markup(unittest.TestCase):
    def test_paragraphs_and_inline(self):
        paras = L.to_paragraphs('<p>One <i>two</i></p><p><b>Three</b><br/>four</p>')
        self.assertEqual(paras, ["One <em>two</em>", "<strong>Three</strong>", "four"])

    def test_osis_hi_and_reference(self):
        paras = L.to_paragraphs('<hi type="bold">Verse 1</hi> see <reference osisRef="John.1.3">John 1:3</reference>')
        self.assertEqual(paras, ['<strong>Verse 1</strong> see <ref target="JHN.1.3">John 1:3</ref>'])

    def test_sefaria_footnotes(self):
        paras = L.to_paragraphs('a<sup class="footnote-marker">1</sup><i class="footnote">note</i> b')
        self.assertEqual(paras, ["a<note>note</note> b"])

    def test_scripref_list(self):
        paras = L.to_paragraphs('<scripRef>Job 26:7; Isa 45:18</scripRef>')
        self.assertEqual(paras, ['<ref target="JOB.26.7">Job 26:7</ref>; <ref target="ISA.45.18">Isa 45:18</ref>'])

    def test_breaks_inside_inline_do_not_split_the_paragraph(self):
        self.assertEqual(L.to_paragraphs("<i>a<br/>b</i>"), ["<em>a b</em>"])

    def test_bad_characters_and_leaked_tags(self):
        self.assertEqual(L.to_paragraphs("c. 7, \x15 3"), ["c. 7, § 3"])
        self.assertEqual(L.to_paragraphs("(&lt;hi type=\"small-caps\"&gt;b. c.&lt;/hi&gt; 4004.)"), ["(b. c. 4004.)"])

    def test_text_is_escaped(self):
        self.assertEqual(L.to_paragraphs("a &amp; b &lt; c"), ["a &amp; b &lt; c"])


class Document(unittest.TestCase):
    def _doc(self):
        d = L.Commentary(id="commentary-test", abbr="TEST", title="Test", lang="en", iso3="eng", license="Public Domain")
        return d

    def test_valid_and_ranges(self):
        d = self._doc()
        d.add("GEN", 1, 1, L.Comment(paras=["In the beginning"], to_verse=2))
        d.add("GEN", 1, 3, L.Comment(paras=["Light"]))
        d.add_intro("GEN", None, L.Comment(paras=["Book intro"], heading="Preface"))
        xml = L.to_xml(d)
        self.assertIsNone(L.validate(xml))
        self.assertIn(b'toVerse="2"', xml)
        self.assertIn(b'globalCanonicalSeq="2"', xml)

    def test_intro_only_chapter_becomes_a_whole_chapter_comment(self):
        d = self._doc()
        d.add("GEN", 1, 1, L.Comment(paras=["x"]))
        d.add_intro("GEN", 2, L.Comment(paras=["Chapter two as a whole"]))
        xml = L.to_xml(d).decode()
        self.assertIn('<chapter number="2"><verse number="1"', xml)
        self.assertIn('toVerse="25"', xml)          # Genesis 2 has 25 verses
        self.assertIsNone(L.validate(xml.encode()))

    def test_empty_is_refused(self):
        with self.assertRaises(ValueError):
            L.to_xml(self._doc())


class Sword(unittest.TestCase):
    def test_layout_matches_the_sword_index(self):
        books = L.versification("kjv")["ot"]
        layout = W._layout(books)
        self.assertEqual(layout[2], (0, 0, 0))       # Genesis heading
        self.assertEqual(layout[3], (0, 1, 0))       # Genesis 1 heading
        self.assertEqual(layout[4], (0, 1, 1))       # Genesis 1:1
        self.assertEqual(layout[4 + 31], (0, 2, 0))  # Genesis 2 heading after 31 verses
        self.assertEqual(len(layout), 2 + sum(sum(b[2]) + len(b[2]) + 1 for b in books))


if __name__ == "__main__":
    unittest.main()
