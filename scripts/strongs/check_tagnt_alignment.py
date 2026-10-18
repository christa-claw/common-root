#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
check_tagnt_alignment.py — does STEPBible's TAGNT line up with the corpus's Greek TR (bible-grc-tr)?

The Greek counterpart of check_oshb_alignment.py, milestone G1 of docs/strongs-design.md. Read-only: the
corpus from BaseX, TAGNT from the local download. The corpus's TR is lower-case and unaccented, so words
are compared through tagnt.normalise (no accents, breathings or case; final sigma = sigma), and a TAGNT
word matches if EITHER its displayed form OR its TR spelling does.

Per verse:
    exact       same number of words and every word matches, in order
    reordered   the same words in a different order (the TR orders a few verses differently)
    different   anything else (a missing or extra word, a different reading)
    only_in_corpus / only_in_tagnt   a verse one side lacks

Usage:  python3 scripts/strongs/check_tagnt_alignment.py [--tagnt DIR] [--show 12] [--edition bible-grc-tr] [--tsv out.tsv]
"""
import glob, argparse, collections, os, sys, urllib.parse, urllib.request, base64

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tagnt as T

BASEX = "http://localhost:8984/rest/religioustext"
AUTH = base64.b64encode(b"admin:admin").decode()
NS = "http://religioustext.org/schema/1.0"
NT = "MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV".split()


def default_tagnt_dir():
    root = os.path.abspath(os.path.join(HERE, "..", ".."))
    for base in (root, os.path.join(os.path.dirname(root), "religious-texts")):   # a worktree finds the main checkout's downloads
        p = os.path.join(base, "sources", "stepbible")
        if glob.glob(os.path.join(p, "TAGNT*.txt")):
            return p
    sys.exit("TAGNT download not found under sources/stepbible (see docs/strongs-design.md).")


def corpus_verses(edition):
    q = ("declare namespace rt='%s'; for $b in //rt:text[@id='%s']/rt:book for $c in $b/rt:chapter for $v in $c/rt:verse "
         "return string-join((string($b/@code), string($c/@number), string($v/@number), normalize-space(string($v))), '&#9;')"
         % (NS, edition))
    req = urllib.request.Request(BASEX + "?" + urllib.parse.urlencode({"query": q}),
                                 headers={"Authorization": "Basic " + AUTH})
    out = {}
    for row in urllib.request.urlopen(req, timeout=300).read().decode("utf-8").splitlines():
        p = row.split("\t", 3)
        if len(p) == 4 and p[1].isdigit() and p[2].isdigit():
            out[(p[0], int(p[1]), int(p[2]))] = p[3]
    return out


def matches(corpus_tok, word):
    k = T.normalise(corpus_tok)
    return any(T.normalise(f) == k for f in word.forms)


def classify(corpus_text, words):
    toks = [t for t in corpus_text.split() if T.normalise(t)]
    if len(toks) == len(words) and all(matches(t, w) for t, w in zip(toks, words)):
        return "exact"
    # same bag of words, different order
    pool = list(words)
    for t in toks:
        hit = next((w for w in pool if matches(t, w)), None)
        if hit is None:
            return "different"
        pool.remove(hit)
    return "reordered" if not pool else "different"


def first_difference(corpus_text, words):
    toks = [t for t in corpus_text.split() if T.normalise(t)]
    for i, (t, w) in enumerate(zip(toks, words)):
        if not matches(t, w):
            return "word %d: corpus %r vs TAGNT %r" % (i + 1, t, "/".join(w.forms))
    return "corpus has %d words, TAGNT %d" % (len(toks), len(words))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tagnt", default=None)
    ap.add_argument("--edition", default="bible-grc-tr")
    ap.add_argument("--show", type=int, default=12)
    ap.add_argument("--tsv")
    a = ap.parse_args()

    tr = T.read_tr(a.tagnt or default_tagnt_dir())
    corpus = corpus_verses(a.edition)
    if not corpus:
        sys.exit("No verses for %s in BaseX." % a.edition)
    per_book = collections.defaultdict(collections.Counter)
    samples = collections.defaultdict(list)
    rows = []
    for key in sorted(set(corpus) | set(tr), key=lambda k: (NT.index(k[0]) if k[0] in NT else 99, k[1], k[2])):
        if key not in tr:
            st = "only_in_corpus"
        elif key not in corpus:
            st = "only_in_tagnt"
        else:
            st = classify(corpus[key], tr[key])
            if st in ("different", "reordered") and len(samples[st]) < a.show:
                samples[st].append((key, first_difference(corpus[key], tr[key])))
        if st in ("only_in_corpus", "only_in_tagnt") and len(samples[st]) < a.show:
            samples[st].append((key, ""))
        per_book[key[0]][st] += 1
        rows.append((key, st, len(tr.get(key, []))))
    total = collections.Counter()
    for c in per_book.values():
        total.update(c)
    n = sum(total.values())
    words = sum(len(v) for v in tr.values())
    print("%s vs TAGNT (TR reading) — %d verses, %d TR words in TAGNT\n" % (a.edition, n, words))
    order = ["exact", "reordered", "different", "only_in_corpus", "only_in_tagnt"]
    for s in order:
        print("  %-15s %6d  %6.2f%%" % (s, total[s], 100.0 * total[s] / n))
    print("\nper book (exact / reordered / different / only_corpus / only_tagnt):")
    for b in NT:
        c = per_book.get(b)
        if c:
            print("  %s  " % b + " ".join("%5d" % c[s] for s in order))
    for s in ("reordered", "different", "only_in_corpus", "only_in_tagnt"):
        if samples[s]:
            print("\nfirst %d %s:" % (len(samples[s]), s))
            for (b, ch, vs), note in samples[s]:
                print("  %s %d:%d  %s" % (b, ch, vs, note))
    if a.tsv:
        with open(a.tsv, "w", encoding="utf-8", newline="") as f:
            f.write("book\tchapter\tverse\tstatus\ttagnt_words\n")
            for (b, ch, vs), st, nw in rows:
                f.write("%s\t%d\t%d\t%s\t%d\n" % (b, ch, vs, st, nw))


if __name__ == "__main__":
    main()
