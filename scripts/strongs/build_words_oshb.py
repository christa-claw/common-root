#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
build_words_oshb.py — generate words-oshb.xml, the Hebrew word layer, from the OSHB download.

Milestone 2 of docs/strongs-design.md. One <w> per printed word, in verse order, carrying the
word's surface form (OSHB's morpheme "/" marks removed, so it reads as the corpus prints it), the
marks printed after it (`after`: maqqef, paseq, sof pasuq, paragraph marks — so the verse can be
rebuilt exactly as the corpus prints it: join each word's text + `after`, with a space between
words unless `after` holds a maqqef), the OSHB word id, and the Strong's number and sense letter parsed by oshb_lemma.py. A word that is
only a prefix particle has no number. The corpus's verse text is never touched.

Each <verse> records how OSHB's text compared with the corpus's `bible-he-wlc` text
(scripts/bibles/check_oshb_alignment.py: exact / letters / words / mismatch). The reader shows
numbers only on `exact` verses, so a tag never lands on text that is not on the page.

Read-only: reads OSHB from sources/ and the corpus from BaseX; writes only the output file.
Validate with:  xmllint --noout --schema schema/words.xsd out/strongs/words-oshb.xml

Usage:
    python3 scripts/strongs/build_words_oshb.py [--out out/strongs/words-oshb.xml] [--book GEN] [--no-corpus]
  --no-corpus skips BaseX (every verse is marked `exact`); for tests and offline checks only.
"""
import argparse, collections, datetime, glob, importlib, os, re, sys
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "bibles"))
import oshb_lemma as L
check = importlib.import_module("check_oshb_alignment")

NS = "http://religioustext.org/schema/words/1.0"
CREDIT = ("Original work of the Open Scriptures Hebrew Bible available at "
          "https://github.com/openscriptures/morphhb")
CHANGES = ("Lemmas normalised to H#### with a sense letter and parsed prefix particles; word text "
           "unchanged except that OSHB's morpheme marks (/) are removed; only printed words kept "
           "(alternative-reading notes omitted).")
BOOK_ORDER = list(check.OSIS_TO_USFM.values())


def word_element(w):
    """One OSHB <w> as an XML string, or None for an empty word."""
    text = "".join(w.itertext()).replace("/", "")
    if not text.strip():
        return None
    lem = L.parse(w.get("lemma"))
    attrs = ['id=%s' % quoteattr(w.get("id", ""))]
    if lem.strongs:
        attrs.append('strongs="%s"' % lem.strongs)
    if lem.sense:
        attrs.append('sense="%s"' % lem.sense)
    if lem.plus:
        attrs.append('plus="true"')
    after = w.get("x-after", "")        # printed marks after this word (set by check_oshb_alignment)
    if after:
        attrs.append('after=%s' % quoteattr(after))
    return "<w %s>%s</w>" % (" ".join(attrs), escape(text))


def build(verses, align, header_attrs):
    """verses: {(code, chapter, verse): (surface, [w elements])}; align(key) -> status string.
    Returns (xml_text, stats)."""
    stats = collections.Counter()
    by_book = collections.defaultdict(lambda: collections.defaultdict(list))
    for key in verses:
        by_book[key[0]][key[1]].append(key[2])
    out = []
    for code in sorted(by_book, key=BOOK_ORDER.index):
        out.append('  <book code="%s">' % code)
        for ch in sorted(by_book[code]):
            out.append('    <chapter number="%d">' % ch)
            for vs in sorted(by_book[code][ch]):
                words = [x for x in (word_element(w) for w in verses[(code, ch, vs)][1]) if x]
                if not words:
                    continue
                status = align((code, ch, vs))
                stats["verses"] += 1
                stats["verse_" + status] += 1
                stats["words"] += len(words)
                stats["with_strongs"] += sum(1 for x in words if "strongs=" in x.split(">", 1)[0])
                out.append('      <verse number="%d" align="%s">' % (vs, status))
                out.extend("        " + x for x in words)
                out.append("      </verse>")
            out.append("    </chapter>")
        out.append("  </book>")
    head = ['<?xml version="1.0" encoding="UTF-8"?>', '<words xmlns="%s"' % NS]
    attrs = dict(header_attrs, totalVerses=stats["verses"], totalWords=stats["words"])
    head += ["       %s=%s" % (k, quoteattr(str(v))) for k, v in attrs.items()]
    head[-1] += ">"
    return "\n".join(head + out + ["</words>", ""]), stats


def rebuild_surface(word_elements):
    """The verse as the reader will rebuild it from the <w> elements: text + marks, a space
    between words unless the marks hold a maqqef. Must equal the corpus's own verse text."""
    out = []
    for i, w in enumerate(word_elements):
        text = "".join(w.itertext()).replace("/", "")
        if not text.strip():
            continue
        after = w.get("x-after", "")
        out.append(text + after)
        if i < len(word_elements) - 1 and check.MAQQEF not in after:
            out.append(" ")
    return "".join(out).strip()


def source_commit(oshb_dir):
    m = [re.search(r"morphhb-([0-9a-f]{40})", f) for f in glob.glob(os.path.join(oshb_dir, "..", "..", "*.tar.gz"))]
    m = [x for x in m if x]
    return m[0].group(1) if m else ""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--oshb", help="path to OSHB's wlc/ folder")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "out", "strongs", "words-oshb.xml"))
    ap.add_argument("--book", help="one USFM book code (default: all 39)")
    ap.add_argument("--no-corpus", action="store_true", help="skip BaseX; mark every verse exact")
    a = ap.parse_args()

    wlc = check.find_oshb_dir(a.oshb)
    verses = check.oshb_verses(wlc, a.book)
    if a.no_corpus:
        align = lambda key: "exact"
    else:
        corpus = check.corpus_verses()
        if not corpus:
            sys.exit("No verses for bible-he-wlc in BaseX (is it running and imported?). Use --no-corpus to skip.")
        align = lambda key: "only_in_source" if key not in corpus else check.classify(verses[key][0], corpus[key])
    header = collections.OrderedDict([
        ("id", "words-oshb"), ("edition", check.EDITION),
        ("source", "https://github.com/openscriptures/morphhb"), ("commit", source_commit(wlc)),
        ("license", "CC BY 4.0"), ("credit", CREDIT), ("changes", CHANGES),
        ("generatedAt", datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))])
    xml, stats = build(verses, align, header)
    if not a.no_corpus:
        # Safety net for the reader: on every `exact` verse the rebuilt line must equal the corpus line.
        bad = [k for k in verses if align(k) == "exact" and rebuild_surface(verses[k][1]) != corpus[k]]
        if bad:
            sys.exit("%d exact verses do not rebuild to the corpus text, e.g. %s" % (len(bad), bad[:5]))
        print("rebuild check: all %d exact verses rebuild to the corpus text" % stats["verse_exact"])
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote %s  (%d bytes)" % (os.path.abspath(a.out), len(xml.encode("utf-8"))))
    print("  verses %d, words %d, with a Strong's number %d" % (stats["verses"], stats["words"], stats["with_strongs"]))
    for k in sorted(k for k in stats if k.startswith("verse_")):
        print("  %-22s %6d" % (k.replace("verse_", "align="), stats[k]))


if __name__ == "__main__":
    main()
