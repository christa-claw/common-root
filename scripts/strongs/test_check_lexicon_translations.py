# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Tests for check_lexicon_translations.py. Run:  python3 scripts/strongs/test_check_lexicon_translations.py -v"""
import os, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_lexicon_translations as C

EN = {
    "H1.lemma": "אָב", "H1.xlit": "ʾāb", "H1.gloss": "father", "H1.def": "father, in a literal sense",
    "H2.lemma": "אַב", "H2.xlit": "ʾab", "H2.gloss": "father", "H2.def": "(Aramaic) corresponding to H1",
    "H3.lemma": "x", "H3.xlit": "x", "H3.gloss": "three", "H3.def": "third",
}


class CheckTest(unittest.TestCase):
    def fails(self, loc, dups=(), bad=()):
        return C.check_locale("fi", EN, loc, list(dups), list(bad))

    def test_a_good_file_passes(self):
        self.assertEqual(self.fails({"H1.gloss": "isä", "H2.def": "(aramea) vastaa sanaa H1"}), [])

    def test_only_gloss_and_def_belong_in_a_locale_file(self):
        self.assertTrue(any("only H####.gloss" in f for f in self.fails({"H1.lemma": "x"})))
        self.assertTrue(any("only H####.gloss" in f for f in self.fails({"H1.xlit": "x"})))
        self.assertTrue(any("only H####.gloss" in f for f in self.fails({"junk": "x"})))

    def test_a_key_with_no_english_original_fails(self):
        self.assertTrue(any("no English original" in f for f in self.fails({"H99.gloss": "x"})))

    def test_empty_value_fails(self):
        self.assertTrue(any("is empty" in f for f in self.fails({"H1.gloss": "  "})))

    def test_unicode_escapes_fail(self):
        self.assertTrue(any("escape" in f for f in self.fails({"H1.gloss": "is\\u00e4"})))

    def test_a_definition_must_keep_its_strongs_references(self):
        self.assertTrue(any("lost the reference" in f and "H1" in f
                            for f in self.fails({"H2.def": "(aramea) vastaa sanaa yksi"})))
        self.assertEqual(self.fails({"H1.def": "isä kirjaimellisesti"}), [])   # English has no reference

    def test_duplicates_and_malformed_lines_are_reported(self):
        self.assertTrue(any("duplicate key" in f for f in self.fails({}, dups=["H1.gloss"])))
        self.assertTrue(any("not key=value" in f for f in self.fails({}, bad=[7])))


class ReadAndOrderTest(unittest.TestCase):
    def test_read_props_skips_comments_and_keeps_equals_in_values(self):
        with tempfile.NamedTemporaryFile("w", suffix=".properties", encoding="utf-8", delete=False) as f:
            f.write("# c\n\nH1.gloss=a=b\nH1.gloss=again\nbroken line\n")
        try:
            d, dups, bad = C.read_props(f.name)
        finally:
            os.unlink(f.name)
        self.assertEqual(d["H1.gloss"], "again")
        self.assertEqual(dups, ["H1.gloss"])
        self.assertEqual(bad, [5])

    def test_pending_is_in_order_and_needs_every_locale(self):
        locs = {"fi": {"H1.gloss": "isä", "H3.gloss": "kolme"}, "de": {"H1.gloss": "Vater"}}
        self.assertEqual(C.pending(["H3", "H1", "H2"], locs, "gloss"), ["H3", "H2"])   # H3 lacks de, H2 lacks both

    def test_the_locale_list_matches_the_apps(self):
        tags = C.locales()
        self.assertNotIn("en", tags)
        self.assertEqual(len(tags), 13)
        self.assertIn("ja", tags)

    def test_the_shipped_english_file_and_frequency_table_agree(self):
        english, _, _ = C.read_props(os.path.join(C.LEX, "hebrew.properties"))
        order = C.frequency_order(english)
        ids = [k[:-6] for k in english if k.endswith(".gloss")]
        self.assertEqual(sorted(order), sorted(ids))           # every entry exactly once
        self.assertEqual(order[0], "H853")                      # the most frequent word first


if __name__ == "__main__":
    unittest.main()
