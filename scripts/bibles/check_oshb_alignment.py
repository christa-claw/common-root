#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
check_oshb_alignment.py — does the Open Scriptures Hebrew Bible (OSHB) line up with
the corpus's `bible-he-wlc`, verse by verse and word by word?

Milestone 1 of docs/strongs-design.md. The word layer will attach Strong's numbers
and morphology from OSHB to the corpus's Hebrew text, so the two must be the same text.
This script is READ-ONLY: it reads the corpus from BaseX (same query shape as
compare_chapter_lengths.py) and OSHB from the local download, and reports. It changes
nothing and writes nothing unless --tsv is given.

How a verse is compared
  OSHB writes a verse as <w> words (morpheme boundaries marked "/") and <seg> marks
  (x-maqqef, x-paseq, x-sof-pasuq, and the paragraph marks x-pe / x-samekh). The
  script rebuilds the surface verse the way the corpus prints it — words separated by
  spaces, a maqqef joining two words with no space — and compares at three levels:

    exact       identical strings
    canonical   identical after Unicode canonical reordering of the combining marks
                (NFD). OSHB asks that NFC never be applied to ITS text; this is a
                comparison key only, nothing is ever rewritten.
    letters     identical once spaces and the marks maqqef/paseq/sof pasuq are removed
    words       same number of words (a weaker check, for the report)

  Anything else is a mismatch and is listed with the first point of difference.

Usage:
    python3 scripts/bibles/check_oshb_alignment.py [--oshb DIR] [--book GEN] [--show 10] [--tsv out.tsv]
"""
import argparse, base64, collections, os, subprocess, sys, unicodedata
import urllib.parse, urllib.request
import xml.etree.ElementTree as ET

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH = base64.b64encode(b"admin:admin").decode()
NS = "http://religioustext.org/schema/1.0"
OSIS = "{http://www.bibletechnologies.net/2003/OSIS/namespace}"
EDITION = "bible-he-wlc"

MAQQEF, PASEQ, SOF_PASUQ = "־", "׀", "׃"

# OSHB's OSIS book ids -> the corpus's USFM codes (the same 39 as the OT of the Protestant canon).
OSIS_TO_USFM = dict(zip(
    "Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov "
    "Eccl Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal".split(),
    "GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO "
    "ECC SNG ISA JER LAM EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL".split()))


def find_oshb_dir(given):
    """The wlc/ folder: --oshb, else sources/oshb/src/wlc in this checkout or in the main worktree
    (sources/ is gitignored, so a feature worktree does not have its own copy)."""
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    candidates = [given] if given else [os.path.join(root, "sources", "oshb", "src", "wlc")]
    if not given:
        try:
            common = subprocess.check_output(["git", "-C", root, "rev-parse", "--git-common-dir"],
                                             text=True).strip()
            main_root = os.path.dirname(os.path.abspath(os.path.join(root, common)))
            candidates.append(os.path.join(main_root, "sources", "oshb", "src", "wlc"))
        except (OSError, subprocess.CalledProcessError):
            pass
    for c in candidates:
        if c and os.path.isdir(c):
            return c
    sys.exit("OSHB not found. Download it (see docs/strongs-design.md) or pass --oshb DIR/wlc.")


def xquery(q):
    url = f"{BASEX_URL}?{urllib.parse.urlencode({'query': q})}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read().decode("utf-8")


def corpus_verses():
    """{(code, chapter, verse): text} for bible-he-wlc."""
    rows = xquery(
        f"declare namespace rt='{NS}';"
        f" for $b in //rt:text[@id='{EDITION}']/rt:book for $c in $b/rt:chapter for $v in $c/rt:verse"
        f" return string-join((string($b/@code), string($c/@number), string($v/@number),"
        f"   normalize-space(string($v))), '&#9;')")
    out = {}
    for row in rows.splitlines():
        p = row.split("\t", 3)
        if len(p) == 4 and p[1].isdigit() and p[2].isdigit():
            out[(p[0], int(p[1]), int(p[2]))] = p[3]
    return out


def oshb_verses(wlc_dir, only_book=None):
    """{(code, chapter, verse): (surface_text, [word elements])} from OSHB.

    Words directly under <verse> only: the <w> inside a <note>/<rdg> are alternative
    readings, not part of the printed verse."""
    out = {}
    for fname in sorted(os.listdir(wlc_dir)):
        osis_book = fname[:-4]
        if not fname.endswith(".xml") or osis_book not in OSIS_TO_USFM:
            continue
        code = OSIS_TO_USFM[osis_book]
        if only_book and code != only_book:
            continue
        tree = ET.parse(os.path.join(wlc_dir, fname))
        for verse in tree.iter(OSIS + "verse"):
            ref = verse.get("osisID", "").split(".")
            if len(ref) != 3 or not ref[1].isdigit() or not ref[2].isdigit():
                continue
            parts, words, glue = [], [], False
            marks = []          # the printed marks that follow the current word, exactly as appended
            def flush():        # record them on that word: a word's text + these = what the corpus prints
                if words:
                    words[-1].set("x-after", "".join(marks))
                del marks[:]
            for el in verse:
                tag = el.tag.replace(OSIS, "")
                if tag == "w":
                    flush()
                    text = "".join(el.itertext()).replace("/", "")
                    words.append(el)
                    parts.append(("" if glue else " ") + text if parts else text)
                    glue = False
                elif tag == "seg":
                    kind = el.get("type", "")
                    mark = "".join(el.itertext())
                    if kind == "x-maqqef":
                        parts.append(mark); marks.append(mark)
                        glue = True
                    elif kind == "x-paseq":
                        parts.append(" " + mark); marks.append(" " + mark)
                    elif kind == "x-sof-pasuq":
                        parts.append(mark); marks.append(mark)
                    elif kind in ("x-pe", "x-samekh"):
                        # open / closed paragraph marks: printed in the corpus
                        parts.append(" " + mark); marks.append(" " + mark)
                    # x-accent: an accent note, not printed text
            flush()
            out[(code, int(ref[1]), int(ref[2]))] = ("".join(parts).strip(), words)
    return out


def letters(s):
    return "".join(ch for ch in s if ch not in (" ", MAQQEF, PASEQ, SOF_PASUQ))


def word_count(s):
    return len(s.replace(MAQQEF, " ").replace(PASEQ, " ").split())


def classify(oshb, corpus):
    if oshb == corpus:
        return "exact"
    if unicodedata.normalize("NFD", oshb) == unicodedata.normalize("NFD", corpus):
        return "canonical"
    if letters(unicodedata.normalize("NFD", oshb)) == letters(unicodedata.normalize("NFD", corpus)):
        return "letters"
    if word_count(oshb) == word_count(corpus):
        return "words"
    return "mismatch"


def first_difference(a, b):
    i = next((k for k, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
    lo = max(0, i - 12)
    return f"@{i}: oshb …{a[lo:i + 12]}…  corpus …{b[lo:i + 12]}…"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--oshb", help="path to OSHB's wlc/ folder")
    ap.add_argument("--book", help="one USFM book code (default: all 39)")
    ap.add_argument("--show", type=int, default=8, help="mismatches to print per category (default 8)")
    ap.add_argument("--tsv", help="write one row per verse to this file")
    args = ap.parse_args()

    oshb = oshb_verses(find_oshb_dir(args.oshb), args.book)
    corpus = {k: v for k, v in corpus_verses().items() if not args.book or k[0] == args.book}
    if not corpus:
        sys.exit(f"No verses for {EDITION} in BaseX (is it running and imported?).")

    per_book = collections.defaultdict(collections.Counter)
    samples = collections.defaultdict(list)
    rows = []
    for key in sorted(set(oshb) | set(corpus), key=lambda k: (list(OSIS_TO_USFM.values()).index(k[0]), k[1], k[2])):
        if key not in corpus:
            status = "only_in_oshb"
        elif key not in oshb:
            status = "only_in_corpus"
        else:
            status = classify(oshb[key][0], corpus[key])
            if status in ("letters", "words", "mismatch") and len(samples[status]) < args.show:
                samples[status].append((key, first_difference(oshb[key][0], corpus[key])))
            elif status in ("only_in_oshb", "only_in_corpus"):
                pass
        if status in ("only_in_oshb", "only_in_corpus") and len(samples[status]) < args.show:
            samples[status].append((key, ""))
        per_book[key[0]][status] += 1
        rows.append((key, status, len(oshb[key][1]) if key in oshb else 0))

    total = collections.Counter()
    for c in per_book.values():
        total.update(c)
    n = sum(total.values())
    words = sum(r[2] for r in rows)
    print(f"{EDITION} vs OSHB — {n} verses, {words} OSHB words\n")
    order = ["exact", "canonical", "letters", "words", "mismatch", "only_in_oshb", "only_in_corpus"]
    for s in order:
        print(f"  {s:<15}{total[s]:>7}  {100.0 * total[s] / n:6.2f}%")
    aligned = total["exact"] + total["canonical"] + total["letters"]
    print(f"\n  alignable by text (exact + canonical + letters): {aligned}/{n} = {100.0 * aligned / n:.2f}%")

    print("\nper book (exact / canonical / letters / words / mismatch / only_oshb / only_corpus):")
    for code in OSIS_TO_USFM.values():
        c = per_book.get(code)
        if c:
            print(f"  {code}  " + " ".join(f"{c[s]:>5}" for s in order))

    for status in ("letters", "words", "mismatch", "only_in_oshb", "only_in_corpus"):
        if samples[status]:
            print(f"\nfirst {len(samples[status])} {status}:")
            for (code, ch, vs), note in samples[status]:
                print(f"  {code} {ch}:{vs}  {note}")

    if args.tsv:
        with open(args.tsv, "w", encoding="utf-8", newline="") as f:
            f.write("book\tchapter\tverse\tstatus\toshb_words\n")
            for (code, ch, vs), status, nw in rows:
                f.write(f"{code}\t{ch}\t{vs}\t{status}\t{nw}\n")


if __name__ == "__main__":
    main()
