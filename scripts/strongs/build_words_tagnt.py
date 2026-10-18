#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
build_words_tagnt.py — generate words-tagnt.xml, the Greek word layer for bible-grc-tr, from STEPBible's TAGNT.

The Greek counterpart of build_words_oshb.py (docs/strongs-design.md). Unlike the Hebrew layer, whose
text is OSHB's, THIS layer's text is the corpus's own: each <w> is a word of bible-grc-tr exactly as it is
stored (lower-case, unaccented), and carries the Strong's number of the TAGNT Textus Receptus word it
matches (scripts/strongs/tagnt.py: matched by normalised form, in order, then unambiguous leftovers). So
the line the reader rebuilds from the words always equals the corpus line, and a word TAGNT does not list
simply has no number. Each <verse> records how well it matched:

    exact      every word matched, in order        reordered   every word matched, some out of order
    partial    some words unnumbered (TAGNT records only TR differences that affect translation)

Read-only: the corpus from BaseX, TAGNT from the local download. Validate with
    xmllint --noout --schema schema/words.xsd out/strongs/words-tagnt.xml

Usage:  python3 scripts/strongs/build_words_tagnt.py [--out out/strongs/words-tagnt.xml] [--tagnt DIR]
"""
import argparse, collections, datetime, os, sys
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tagnt as T
import check_tagnt_alignment as C

NS = "http://religioustext.org/schema/words/1.0"
EDITION = "bible-grc-tr"
CREDIT = ("Data created by www.STEPBible.org based on work at Tyndale House Cambridge (CC BY 4.0); "
          "https://github.com/STEPBible/STEPBible-Data")
CHANGES = ("The words are the corpus's own Textus Receptus text, unchanged. Each carries the Strong's number "
           "(with TAGNT's disambiguating letter as 'sense') of the matching TAGNT Textus Receptus word, matched by "
           "normalised form; words TAGNT does not list have no number.")


def verse_xml(book, ch, vs, tokens, assignment, status, words):
    lines = ['      <verse number="%d" align="%s">' % (vs, status)]
    for n, (tok, a) in enumerate(zip(tokens, assignment), 1):
        attrs = ['id="%s.%d.%d.%d"' % (book, ch, vs, n)]
        if a is not None and words[a].strongs:
            attrs.append('strongs="%s"' % words[a].strongs)
            if words[a].sense:
                attrs.append('sense="%s"' % words[a].sense)
        lines.append("        <w %s>%s</w>" % (" ".join(attrs), escape(tok)))
    lines.append("      </verse>")
    return lines


def build(corpus, tr, header):
    """corpus: {(book, ch, vs): text}; tr: TAGNT TR words by verse. Returns (xml_text, stats)."""
    stats = collections.Counter()
    by_book = collections.defaultdict(lambda: collections.defaultdict(list))
    for key in corpus:
        by_book[key[0]][key[1]].append(key[2])
    body = []
    for book in sorted(by_book, key=lambda b: C.NT.index(b) if b in C.NT else 99):
        body.append('  <book code="%s">' % book)
        for ch in sorted(by_book[book]):
            body.append('    <chapter number="%d">' % ch)
            for vs in sorted(by_book[book][ch]):
                tokens = [t for t in corpus[(book, ch, vs)].split() if T.normalise(t)]
                if not tokens:
                    continue
                words = tr.get((book, ch, vs), [])
                assignment, status = T.align(tokens, words)
                stats["verses"] += 1
                stats["verse_" + status] += 1
                stats["words"] += len(tokens)
                stats["numbered"] += sum(1 for a in assignment if a is not None and words[a].strongs)
                body.extend(verse_xml(book, ch, vs, tokens, assignment, status, words))
            body.append("    </chapter>")
        body.append("  </book>")
    attrs = dict(header, totalVerses=stats["verses"], totalWords=stats["words"])
    head = ['<?xml version="1.0" encoding="UTF-8"?>', '<words xmlns="%s"' % NS]
    head += ["       %s=%s" % (k, quoteattr(str(v))) for k, v in attrs.items()]
    head[-1] += ">"
    return "\n".join(head + body + ["</words>", ""]), stats


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tagnt", default=None, help="folder holding the TAGNT .txt files")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "out", "strongs", "words-tagnt.xml"))
    a = ap.parse_args()
    folder = a.tagnt or C.default_tagnt_dir()
    tr = T.read_tr(folder)
    corpus = C.corpus_verses(EDITION)
    if not corpus:
        sys.exit("No verses for %s in BaseX (is it running and imported?)." % EDITION)
    commit = ""
    if os.path.exists(os.path.join(folder, "COMMIT")):
        commit = open(os.path.join(folder, "COMMIT")).read().strip()
    header = collections.OrderedDict([
        ("id", "words-tagnt"), ("edition", EDITION),
        ("source", "https://github.com/STEPBible/STEPBible-Data"), ("commit", commit),
        ("license", "CC BY 4.0"), ("credit", CREDIT), ("changes", CHANGES),
        ("generatedAt", datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))])
    xml, stats = build(corpus, tr, header)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote %s  (%d bytes)" % (os.path.abspath(a.out), len(xml.encode("utf-8"))))
    print("  verses %d, words %d, numbered %d (%.2f%%)" % (
        stats["verses"], stats["words"], stats["numbered"], 100.0 * stats["numbered"] / stats["words"]))
    for k in sorted(k for k in stats if k.startswith("verse_")):
        print("  %-22s %6d" % (k.replace("verse_", "align="), stats[k]))


if __name__ == "__main__":
    main()
