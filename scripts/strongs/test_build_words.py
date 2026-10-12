# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Tests for build_words_oshb.py. Run:  python3 scripts/strongs/test_build_words.py -v

The fixture tests need nothing. The data tests read the OSHB download (sources/, gitignored)
and are skipped when it is absent. None of them needs BaseX."""
import os, subprocess, sys, unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_words_oshb as B
import oshb_lemma as L

WD = "{%s}" % B.NS
OSIS = '<verse xmlns="http://www.bibletechnologies.net/2003/OSIS/namespace" osisID="%s">%s</verse>'
HEADER = {"id": "words-oshb", "edition": "bible-he-wlc", "source": "https://example.org/x",
          "license": "CC BY 4.0", "credit": "credit", "changes": "changes"}


def w(lemma, text, wid):
    return ET.fromstring('<w xmlns="http://www.bibletechnologies.net/2003/OSIS/namespace" '
                         'lemma="%s" morph="HX" id="%s">%s</w>' % (lemma, wid, text))


def rebuild(verse):
    """Join text + after per word, a space between words unless `after` holds a maqqef."""
    out, ws = [], verse.findall(WD + "w")
    for i, w in enumerate(ws):
        after = w.get("after", "")
        out.append(w.text + after)
        if i < len(ws) - 1 and "\u05be" not in after:
            out.append(" ")
    return "".join(out).strip()


def doc(verses, align=lambda key: "exact"):
    xml, stats = B.build(verses, align, HEADER)
    return ET.fromstring(xml.encode("utf-8")), stats, xml


class WordElementTest(unittest.TestCase):
    def test_number_sense_and_prefix_stripped_of_morpheme_marks(self):
        self.assertEqual(B.word_element(w("b/7225", "בְּ/רֵאשִׁ֖ית", "01xeN")),
                         '<w id="01xeN" strongs="H7225">בְּרֵאשִׁ֖ית</w>')
        self.assertEqual(B.word_element(w("1254 a", "בָּרָ֣א", "01Nvk")),
                         '<w id="01Nvk" strongs="H1254" sense="a">בָּרָ֣א</w>')

    def test_plus_flag_is_kept(self):
        self.assertIn('plus="true"', B.word_element(w("1008+", "x", "i1")))

    def test_prefix_only_word_has_no_strongs(self):
        self.assertEqual(B.word_element(w("c/l", "וְ/לְ", "i2")), '<w id="i2">וְלְ</w>')

    def test_marks_after_a_word_are_recorded(self):
        el = w("5921 a", "עַל", "m1")
        el.set("x-after", "\u05be")
        self.assertEqual(B.word_element(el), '<w id="m1" strongs="H5921" sense="a" after="\u05be">עַל</w>')

    def test_empty_word_is_dropped_and_text_is_escaped(self):
        self.assertIsNone(B.word_element(w("430", " ", "i3")))
        self.assertIn("&lt;&amp;", B.word_element(w("430", "&lt;&amp;", "i4")))


class BuildTest(unittest.TestCase):
    def verses(self):
        return {
            ("GEN", 1, 2): ("b", [w("430", "b", "a2")]),
            ("GEN", 1, 1): ("a", [w("b/7225", "a", "a1"), w("430", "c", "a3")]),
            ("EXO", 2, 1): ("d", [w("776", "d", "e1")]),
            ("GEN", 2, 1): ("e", [w("776", "e", "g1")]),
        }

    def test_document_is_ordered_by_canon_chapter_and_verse(self):
        root, stats, _ = doc(self.verses())
        books = [b.get("code") for b in root.findall(WD + "book")]
        self.assertEqual(books, ["GEN", "EXO"])
        gen = root.find(WD + "book")
        self.assertEqual([c.get("number") for c in gen.findall(WD + "chapter")], ["1", "2"])
        self.assertEqual([v.get("number") for v in gen.find(WD + "chapter").findall(WD + "verse")], ["1", "2"])

    def test_counts_in_header_and_stats(self):
        root, stats, _ = doc(self.verses())
        self.assertEqual((root.get("totalVerses"), root.get("totalWords")), ("4", "5"))
        self.assertEqual((stats["verses"], stats["words"], stats["with_strongs"]), (4, 5, 5))

    def test_alignment_status_is_recorded_per_verse(self):
        root, stats, _ = doc(self.verses(), lambda key: "exact" if key[0] == "GEN" else "mismatch")
        statuses = {v.get("align") for v in root.iter(WD + "verse")}
        self.assertEqual(statuses, {"exact", "mismatch"})
        self.assertEqual(stats["verse_mismatch"], 1)

    def test_credit_and_changes_are_in_the_document(self):
        root, _, _ = doc(self.verses())
        self.assertEqual((root.get("credit"), root.get("changes")), ("credit", "changes"))

    def test_verse_without_printed_words_is_skipped(self):
        root, stats, _ = doc({("GEN", 1, 1): ("", [w("430", " ", "z")])})
        self.assertEqual(stats["verses"], 0)


WLC = L._default("sources", "oshb", "src", "wlc")


@unittest.skipUnless(os.path.isdir(WLC), "OSHB download not present under sources/")
class DataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.verses = B.check.oshb_verses(WLC, "GEN")
        cls.root, cls.stats, cls.xml = doc(cls.verses)

    def test_genesis_1_1_words(self):
        v = self.root.find(".//%sverse[@number='1']" % WD)
        ws = v.findall(WD + "w")
        self.assertEqual([x.get("strongs") for x in ws],
                         ["H7225", "H1254", "H430", "H853", "H8064", "H853", "H776"])
        self.assertEqual(ws[1].get("sense"), "a")
        # the stored form, exactly, minus OSHB's "/" mark. (Compared against the source, not a
        # typed literal: combining marks have more than one valid order and OSHB asks that none
        # be normalised — a retyped string would silently differ.)
        source = "".join(self.verses[("GEN", 1, 1)][1][0].itertext())
        self.assertIn("/", source)
        self.assertEqual(ws[0].text, source.replace("/", ""))
        self.assertEqual(ws[0].get("id"), "01xeN")

    def test_genesis_counts_match_the_oshb(self):
        self.assertEqual(self.stats["verses"], 1533)
        self.assertEqual(self.stats["words"], 20612)
        self.assertGreater(self.stats["with_strongs"], 20000)

    def test_rebuilt_verse_equals_the_printed_text_for_every_genesis_verse(self):
        """The reader rebuilds a rung line from words; it must read exactly like the corpus line."""
        for (code, ch, vs), (surface, _) in self.verses.items():
            v = self.root.find(".//%sbook/%schapter[@number='%d']/%sverse[@number='%d']" % (WD, WD, ch, WD, vs))
            self.assertEqual(rebuild(v), surface, (code, ch, vs))

    def test_no_slash_remains_in_any_word(self):
        self.assertFalse([w.text for w in self.root.iter(WD + "w") if "/" in (w.text or "")])

    def test_every_word_id_is_unique(self):
        ids = [w.get("id") for w in self.root.iter(WD + "w")]
        self.assertEqual(len(ids), len(set(ids)))

    @unittest.skipUnless(subprocess.call(["which", "xmllint"], stdout=subprocess.DEVNULL) == 0, "no xmllint")
    def test_output_validates_against_the_schema(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".xml", encoding="utf-8", delete=False) as f:
            f.write(self.xml)
        xsd = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "schema", "words.xsd")
        try:
            r = subprocess.run(["xmllint", "--noout", "--schema", xsd, f.name], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
        finally:
            os.unlink(f.name)


if __name__ == "__main__":
    unittest.main()
