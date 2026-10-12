# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Tests for oshb_lemma.py. Run:  python3 scripts/strongs/test_oshb_lemma.py -v

The pure parsing tests need nothing. The verse and index tests read the OSHB and Hebrew
Lexicon downloads (sources/, gitignored) and are skipped when those are absent."""
import os, sys, unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oshb_lemma as L

WLC = L._default("sources", "oshb", "src", "wlc")
AUG = L._default("sources", "hebrew-lexicon", "src", "AugIndex.xml")
HAVE_DATA = os.path.isdir(WLC) and os.path.exists(AUG)


def verse_lemmas(book, osis_id):
    for v in ET.parse(os.path.join(WLC, book + ".xml")).iter(L.OSIS + "verse"):
        if v.get("osisID") == osis_id:
            return [w.get("lemma") for w in v.findall(L.OSIS + "w")]
    raise KeyError(osis_id)


class ParseTest(unittest.TestCase):
    def test_plain_number(self):
        lem = L.parse("430")
        self.assertEqual((lem.prefixes, lem.number, lem.sense, lem.plus, lem.strongs),
                         ([], 430, None, False, "H430"))

    def test_sense_letter_with_space(self):
        lem = L.parse("1254 a")
        self.assertEqual((lem.number, lem.sense, lem.strongs), (1254, "a", "H1254"))

    def test_prefix_and_number(self):
        lem = L.parse("b/7225")
        self.assertEqual((lem.prefixes, lem.strongs), (["b"], "H7225"))

    def test_two_prefixes_and_three(self):
        self.assertEqual(L.parse("c/d/776").prefixes, ["c", "d"])
        self.assertEqual(L.parse("c/l/m/7117").prefixes, ["c", "l", "m"])

    def test_prefixes_number_and_sense(self):
        lem = L.parse("c/l/4723 c")
        self.assertEqual((lem.prefixes, lem.number, lem.sense), (["c", "l"], 4723, "c"))

    def test_trailing_plus_is_kept_and_does_not_change_the_number(self):
        lem = L.parse("1008+")
        self.assertEqual((lem.number, lem.plus, lem.strongs), (1008, True, "H1008"))
        self.assertTrue(L.parse("l/1008+").plus)

    def test_prefix_only_word_has_no_strongs_number(self):
        for lemma, prefixes in (("b", ["b"]), ("c/l", ["c", "l"])):
            lem = L.parse(lemma)
            self.assertEqual((lem.prefixes, lem.number, lem.strongs), (prefixes, None, None))

    def test_missing_lemma(self):
        for empty in (None, "", "  "):
            self.assertEqual(L.parse(empty).number, None)

    def test_unknown_prefix_is_an_error_not_a_guess(self):
        with self.assertRaises(ValueError):
            L.parse("z/430")

    def test_every_prefix_has_a_gloss_and_a_morph_class(self):
        for code, (gloss, morph) in L.PREFIXES.items():
            self.assertTrue(gloss and morph, code)

    def test_aug_key_rules(self):
        with_senses = {122, 1254}
        self.assertEqual(L.aug_key(L.parse("122"), with_senses), "122a")      # bare number -> sense a
        self.assertEqual(L.aug_key(L.parse("122 b"), with_senses), "122b")
        self.assertEqual(L.aug_key(L.parse("430"), with_senses), "430")       # no variants: plain
        self.assertEqual(L.aug_key(L.parse("c/430"), with_senses), "430")     # prefixes do not matter
        self.assertIsNone(L.aug_key(L.parse("c/l"), with_senses))


@unittest.skipUnless(HAVE_DATA, "OSHB / Hebrew Lexicon downloads not present under sources/")
class VerseTest(unittest.TestCase):
    """Real verses, expectations read from the OSHB files."""

    @classmethod
    def setUpClass(cls):
        cls.aug = L.AugIndex.load(AUG)

    def parsed(self, book, ref):
        return [L.parse(x) for x in verse_lemmas(book, ref)]

    def test_genesis_1_1(self):
        p = self.parsed("Gen", "Gen.1.1")
        self.assertEqual([x.strongs for x in p],
                         ["H7225", "H1254", "H430", "H853", "H8064", "H853", "H776"])
        self.assertEqual([x.prefixes for x in p],
                         [["b"], [], [], [], ["d"], ["c"], ["d"]])
        self.assertEqual([x.sense for x in p], [None, "a", None, None, None, None, None])

    def test_genesis_1_1_resolves_to_lexicon_entries(self):
        ids = [self.aug.resolve(x) for x in verse_lemmas("Gen", "Gen.1.1")]
        self.assertTrue(all(ids), ids)
        self.assertEqual(ids[2], "arn")               # 430, elohim: LexicalIndex entry "arn"

    def test_psalm_51_title_and_confession(self):
        # OSHB numbers the title as verse 1 (KJV puts it before verse 1)
        title = self.parsed("Ps", "Ps.51.1")
        self.assertEqual([x.strongs for x in title], ["H5329", "H4210", "H1732"])
        self.assertEqual([x.prefixes for x in title], [["l"], [], ["l"]])
        v3 = self.parsed("Ps", "Ps.51.3")                 # KJV 51:1
        self.assertEqual([(x.strongs, x.sense) for x in v3][:3],
                         [("H2603", "a"), ("H430", None), ("H2617", "a")])
        self.assertEqual(v3[4].sense, "b")                # 7356 b
        self.assertEqual(v3[2].prefixes, ["k"])

    def test_joel_3_is_kjv_joel_2_28(self):
        p = self.parsed("Joel", "Joel.3.1")
        self.assertEqual(p[0].prefixes, ["c"])
        self.assertEqual([x.strongs for x in p][:3], ["H1961", "H310", "H3651"])
        self.assertEqual([x.sense for x in p][1:3], ["a", "c"])      # 310 a, 3651 c
        self.assertTrue(all(self.aug.resolve(x) for x in verse_lemmas("Joel", "Joel.3.1")))

    def test_sense_letter_picks_the_right_index_entry(self):
        self.assertEqual(self.aug.resolve("1254 a"), self.aug.key_to_id["1254a"])
        self.assertEqual(self.aug.resolve("7356 b"), self.aug.key_to_id["7356b"])

    def test_bare_number_with_variants_resolves_to_sense_a(self):
        n = next(iter(sorted(self.aug.with_senses)))
        self.assertEqual(self.aug.resolve(str(n)), self.aug.key_to_id["%da" % n])

    def test_number_of_numbers_with_senses_matches_the_lexicon_app(self):
        self.assertEqual(len(self.aug.with_senses), 540)    # the list in Lexicon.js

    def test_prefix_only_lemma_resolves_to_nothing(self):
        self.assertIsNone(self.aug.resolve("c/l"))

    def test_every_oshb_lemma_parses_and_resolves(self):
        bad = []
        for f in sorted(os.listdir(WLC)):
            if not f.endswith(".xml") or f == "VerseMap.xml":
                continue
            for w in ET.parse(os.path.join(WLC, f)).iter(L.OSIS + "w"):
                lemma = w.get("lemma")
                try:
                    lem = L.parse(lemma)
                except ValueError:
                    bad.append(lemma)
                    continue
                if lem.number is not None and not self.aug.resolve(lemma):
                    bad.append(lemma)
        self.assertEqual(bad[:10], [])


if __name__ == "__main__":
    unittest.main()
