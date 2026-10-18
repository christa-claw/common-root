# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Tests for tagnt.py and build_words_tagnt.py. Run:  python3 scripts/strongs/test_tagnt.py -v

The parser and matcher tests use inline rows (no data needed); the data tests read the TAGNT download
(sources/stepbible, gitignored) and are skipped when it is absent. None needs BaseX."""
import glob, os, sys, unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tagnt as T
import build_words_tagnt as B

TAB = "\t"


def row(ref, greek, ds, editions, meaning="", spelling="", kind="NKO"):
    cols = ["%s#%s=%s" % (ref[0], ref[1], kind), greek, "x", ds + "=N-NSF", "d=gloss", editions, meaning, spelling]
    return TAB.join(cols + [""] * 9)


class NormaliseTest(unittest.TestCase):
    def test_accents_breathings_case_and_final_sigma_are_ignored(self):
        self.assertEqual(T.normalise("Βίβλος"), T.normalise("βιβλοσ"))
        self.assertEqual(T.normalise("Ἰησοῦ"), "ιησου")
        self.assertEqual(T.normalise("μετ᾽"), "μετ")            # elision mark dropped
        self.assertEqual(T.normalise("ἡ"), T.normalise("ἢ"))     # homographs collide: the corpus has no accents


class ParseRowTest(unittest.TestCase):
    def test_a_word_in_the_tr_with_its_extended_strongs_number(self):
        (w,) = T.parse_row(row(("Mat.1.1", "03"), "Ἰησοῦ (Iēsou)", "G2424G", "NA28+TR+Byz"))
        self.assertEqual((w.book, w.chapter, w.verse, w.strongs, w.sense), ("MAT", 1, 1, "G2424", "G"))
        self.assertEqual(w.greek, "Ἰησοῦ")                        # transliteration removed

    def test_a_word_the_tr_lacks_is_not_returned(self):
        self.assertEqual(T.parse_row(row(("Mat.1.1", "01"), "Βίβλος (Biblos)", "G0976", "NA28+NA27+WH")), [])

    def test_tr_spelling_variant_is_a_second_form(self):
        (w,) = T.parse_row(row(("Mat.1.1", "06"), "Δαυὶδ (Dauid)", "G1138", "NA28+TR+Byz",
                               spelling="Tyn+WH: Δαυεὶδ ; +TR: Δαβὶδ ; "))
        self.assertEqual(w.forms, ("Δαυὶδ", "Δαβὶδ"))

    def test_a_displaced_tr_token_counts_as_the_tr_and_is_flagged(self):
        (w,) = T.parse_row(row(("Mat.2.3", "05"), "Ἡρῴδης (Hērōdēs)", "G2264G", "NA28+Treg+TR«3+Byz«3"))
        self.assertTrue(w.moved_in_tr)
        self.assertEqual(w.strongs, "G2264")

    def test_treg_is_not_the_tr(self):
        self.assertEqual(T.parse_row(row(("Mat.2.3", "05"), "Ἡρῴδης (Hērōdēs)", "G2264G", "NA28+Treg+WH")), [])

    def test_the_kjv_reference_in_brackets_wins(self):
        (w,) = T.parse_row(row(("Act.2.11[2.10]", "01"), "Ἰουδαῖοί (Ioudaioi)", "G2453", "NA28+TR"))
        self.assertEqual((w.chapter, w.verse), (2, 10))

    def test_other_editions_numbering_in_braces_is_ignored(self):
        (w,) = T.parse_row(row(("Rom.16.25{14.24}", "01"), "Τῷ (Tō)", "G3588", "NA28+TR"))
        self.assertEqual((w.chapter, w.verse), (16, 25))

    def test_a_tr_alternative_in_the_meaning_variants_column(self):
        ws = T.parse_row(row(("Mat.1.10", "10"), "Ἀμώς· (Amōs)", "G0301H", "NA28+NA27+WH",
                             meaning="Ἀμών (t=Amōn) Amon - G0300=N-ASM-P in: TR+Byz", kind="N(k)O"))
        self.assertEqual([(w.greek, w.strongs) for w in ws], [("Ἀμών", "G300")])

    def test_an_alternative_in_other_editions_only_is_not_the_tr(self):
        self.assertEqual(T.parse_row(row(("Mat.1.10", "10"), "Ἀμώς· (Amōs)", "G0301H", "NA28",
                                         meaning="Ἀμών (t=Amōn) Amon - G0300=N-ASM-P in: Byz")), [])

    def test_a_two_word_alternative_gets_one_number_each(self):
        ws = T.parse_row(row(("Act.16.12", "01"), "κεῖθεν (x)", "G1564", "NA28",
                             meaning="ἐκεῖθέν τε (t=ekeithen te) from there then - G1564=ADV + G5037=CONJ in: TR+Byz"))
        self.assertEqual([(w.greek, w.strongs) for w in ws], [("ἐκεῖθέν", "G1564"), ("τε", "G5037")])


class AlignTest(unittest.TestCase):
    @staticmethod
    def words(*specs):
        return [T.Word("MAT", 1, 1, i + 1, "NKO", g, s, None, (g,), False) for i, (g, s) in enumerate(specs)]

    def test_exact_in_order(self):
        toks = ["βιβλος", "γενεσεως", "ιησου"]
        a, st = T.align(toks, self.words(("Βίβλος", "G0976"), ("γενέσεως", "G1078"), ("Ἰησοῦ", "G2424")))
        self.assertEqual((a, st), ([0, 1, 2], "exact"))

    def test_reordered_when_the_tr_puts_a_word_elsewhere(self):
        toks = ["ακουσας", "δε", "ηρωδης", "ο", "βασιλευς"]
        ws = self.words(("Ἀκούσας", "G0191"), ("δὲ", "G1161"), ("ὁ", "G3588"), ("βασιλεὺς", "G0935"), ("Ἡρῴδης", "G2264"))
        a, st = T.align(toks, ws)
        self.assertEqual(st, "reordered")
        self.assertEqual([ws[i].strongs for i in a], ["G0191", "G1161", "G2264", "G3588", "G0935"])

    def test_a_word_tagnt_does_not_list_stays_unnumbered_and_the_verse_is_partial(self):
        toks = ["και", "ειπεν", "αυτω"]
        a, st = T.align(toks, self.words(("εἶπεν", "G3004"), ("αὐτῷ", "G0846")))
        self.assertEqual(st, "partial")
        self.assertEqual(a, [None, 0, 1])

    def test_a_homograph_the_tr_lists_twice_is_never_numbered_twice(self):
        # the corpus has no accents: ἡ (article G3588) and ἢ (or G2228) are both "η"; one token, two candidates
        toks = ["ο", "η"]
        ws = self.words(("ἡ", "G3588"), ("ἢ", "G2228"), ("ὁ", "G3588"))
        a, st = T.align(toks, ws)
        self.assertEqual(st, "partial")                         # one TAGNT word is left over
        self.assertEqual(len([x for x in a if x is not None]), len(set(x for x in a if x is not None)))

    def test_an_empty_verse_aligns_to_nothing(self):
        self.assertEqual(T.align([], []), ([], "exact"))


class BuildTest(unittest.TestCase):
    def test_the_words_are_the_corpus_words_with_numbers_attached(self):
        corpus = {("MAT", 1, 1): "βιβλος γενεσεως ιησου"}
        tr = {("MAT", 1, 1): [T.Word("MAT", 1, 1, 1, "NKO", "Βίβλος", "G0976", None, ("Βίβλος",), False),
                              T.Word("MAT", 1, 1, 3, "NKO", "Ἰησοῦ", "G2424", "G", ("Ἰησοῦ",), False)]}
        xml, stats = B.build(corpus, tr, {"id": "x", "edition": "bible-grc-tr", "source": "https://e", "license": "L",
                                          "credit": "c", "changes": "ch"})
        root = ET.fromstring(xml.encode("utf-8"))
        ws = root.findall(".//{%s}w" % B.NS)
        self.assertEqual([w.text for w in ws], ["βιβλος", "γενεσεως", "ιησου"])          # the corpus text, unchanged
        self.assertEqual([w.get("strongs") for w in ws], ["G0976", None, "G2424"])
        self.assertEqual(ws[2].get("sense"), "G")
        self.assertEqual(root.find(".//{%s}verse" % B.NS).get("align"), "partial")
        self.assertEqual((stats["words"], stats["numbered"]), (3, 2))


TAGNT_DIR = None
_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
for base in (_ROOT, os.path.join(os.path.dirname(_ROOT), "religious-texts")):
    p = os.path.join(base, "sources", "stepbible")
    if glob.glob(os.path.join(p, "TAGNT*.txt")):
        TAGNT_DIR = p
        break


@unittest.skipUnless(TAGNT_DIR, "TAGNT download not present under sources/stepbible")
class DataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tr = T.read_tr(TAGNT_DIR)

    def test_matthew_1_1_is_the_tr_text_with_the_kjv_spelling_of_david(self):
        ws = self.tr[("MAT", 1, 1)]
        self.assertEqual([w.strongs for w in ws], ["G976", "G1078", "G2424", "G5547", "G5207", "G1138", "G5207", "G11"])
        self.assertIn("Δαβὶδ", ws[5].forms)                     # the TR spelling, from the spelling-variants column

    def test_matthew_1_10_has_the_trs_amon_instead_of_the_nas_amos(self):
        greek = [w.greek for w in self.tr[("MAT", 1, 10)]]
        keys = [T.normalise(g) for g in greek]
        self.assertIn("αμων", keys)
        self.assertNotIn("αμωσ", keys)

    def test_every_nt_book_is_present_with_numbered_words(self):
        books = {k[0] for k in self.tr}
        self.assertEqual(len(books), 27)
        self.assertGreater(sum(1 for v in self.tr.values() for w in v if w.strongs), 135000)


if __name__ == "__main__":
    unittest.main()
