#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
oshb_lemma.py — turn an Open Scriptures Hebrew Bible (OSHB) `lemma` attribute into
something the word layer and the lexicon can use.

Milestone 2 of docs/strongs-design.md. The OSHB writes a word's lemma as an AUGMENTED
Strong's number:

    430          a plain Strong's number                          -> H430
    1254 a       number + sense letter (homographs)               -> H1254, sense a
    b/7225       prefix particle + number ("in" + H7225)          -> prefix b, H7225
    c/d/776      two prefixes ("and" + "the") + number            -> prefixes c, d; H776
    c/l/4723 c   prefixes + number + sense letter
    1008+        number with a trailing plus (see PLUS below)     -> H1008, plus flag
    b, c/l       prefix-only word: no number at all               -> no Strong's number

This module parses those, and resolves them to the Hebrew Lexicon's own entry ids through
its `AugIndex.xml`, following the lookup OpenScriptures' lexicon app uses
(HebrewLexicon/HomeFiles/Script/Lexicon.js): take the part after the last "/", drop the
space, and where a plain number has lettered variants default to sense "a".

Nothing here changes the stored text or the OSHB data; it only reads and normalises.

PLUS. A trailing "+" appears on 256 distinct numbers in the OSHB (1008+, 1035+, 884+ …) and
is not explained in the files we downloaded. OSHB's own JSON converter treats it like a sense
letter and strips it ("removeLemmaTypes"), and the lexicon app does no lookup for it. We do the
same for the number — H1008 — and keep `plus=True` so nothing is lost and a later reading of
what it means can use it.

Usage (CLI check over the whole OSHB):
    python3 scripts/strongs/oshb_lemma.py [--oshb DIR/wlc] [--aug DIR/AugIndex.xml]
"""
import argparse, collections, os, re, sys
import xml.etree.ElementTree as ET
from typing import Dict, List, NamedTuple, Optional

OSIS = "{http://www.bibletechnologies.net/2003/OSIS/namespace}"

# Prefix particle codes as the OSHB writes them, with the morphology class that tags each
# in `morph` (HebrewMorphologyCodes). The glosses are ours; the morph string is authoritative.
PREFIXES = {
    "b": ("in, with", "R"),       # inseparable preposition
    "k": ("like, as", "R"),
    "l": ("to, for", "R"),
    "m": ("from", "R"),
    "c": ("and", "C"),            # conjunction
    "d": ("the", "Td"),           # definite article
    "i": ("interrogative", "Ti"), # he-interrogative
    "s": ("that, which", "Tr"),   # relative particle
}

_LEMMA = re.compile(r"^(?P<number>\d+)(?:\s?(?P<sense>[a-f]))?(?P<plus>\+)?$")


class Lemma(NamedTuple):
    prefixes: List[str]          # particle codes in order, e.g. ["c", "d"]
    number: Optional[int]        # Strong's number, or None for a prefix-only word
    sense: Optional[str]         # "a".."f", or None
    plus: bool                   # a trailing "+" was present

    @property
    def strongs(self) -> Optional[str]:
        """The plain Strong's id, e.g. "H1254" (the sense letter is separate)."""
        return None if self.number is None else "H%d" % self.number


def parse(lemma: Optional[str]) -> Lemma:
    """Parse one OSHB lemma attribute. Raises ValueError on a shape we have not seen."""
    if lemma is None or not lemma.strip():
        return Lemma([], None, None, False)
    parts = lemma.strip().split("/")
    last = parts[-1]
    m = _LEMMA.match(last)
    if m:
        prefixes, number = parts[:-1], int(m.group("number"))
        sense, plus = m.group("sense"), bool(m.group("plus"))
    else:
        # A prefix-only word ("b", "c/l"): every part is a particle.
        prefixes, number, sense, plus = parts, None, None, False
    for p in prefixes:
        if p not in PREFIXES:
            raise ValueError("unknown prefix %r in lemma %r" % (p, lemma))
    return Lemma(prefixes, number, sense, plus)


def aug_key(lem: Lemma, plain_numbers_with_senses) -> Optional[str]:
    """The AugIndex key for a parsed lemma: number+letter ("1254a"), or the plain number —
    except that a plain number which has lettered variants means sense "a" (the lexicon app's
    rule). None for a prefix-only word."""
    if lem.number is None:
        return None
    if lem.sense:
        return "%d%s" % (lem.number, lem.sense)
    if lem.number in plain_numbers_with_senses:
        return "%da" % lem.number
    return str(lem.number)


class AugIndex:
    """AugIndex.xml: augmented Strong's key -> Hebrew Lexicon LexicalIndex entry id."""

    def __init__(self, key_to_id: Dict[str, str]):
        self.key_to_id = key_to_id
        # numbers that exist ONLY in lettered form ("122a", "122b"): a bare 122 means 122a.
        self.with_senses = {int(re.match(r"\d+", k).group()) for k in key_to_id
                            if re.match(r"\d+[a-f]$", k)}
        self.with_senses -= {int(k) for k in key_to_id if k.isdigit()}

    @classmethod
    def load(cls, path: str) -> "AugIndex":
        ns = "{http://openscriptures.github.com/morphhb/namespace}"
        root = ET.parse(path).getroot()
        return cls({w.get("aug"): (w.text or "").strip() for w in root.iter(ns + "w") if w.get("aug")})

    def resolve(self, lemma: Optional[str]) -> Optional[str]:
        """The lexicon entry id for an OSHB lemma, or None (prefix-only, or not in the index)."""
        key = aug_key(parse(lemma), self.with_senses)
        return self.key_to_id.get(key) if key else None


# ── CLI: check every lemma in the OSHB ───────────────────────────────────

def _default(*rel):
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    for base in (root, os.path.join(os.path.dirname(root), "religious-texts")):
        p = os.path.join(base, *rel)
        if os.path.exists(p):
            return p
    return os.path.join(root, *rel)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--oshb", default=_default("sources", "oshb", "src", "wlc"))
    ap.add_argument("--aug", default=_default("sources", "hebrew-lexicon", "src", "AugIndex.xml"))
    ap.add_argument("--show", type=int, default=15)
    args = ap.parse_args()

    aug = AugIndex.load(args.aug)
    total = collections.Counter()
    unresolved = collections.Counter()
    shapes_bad = collections.Counter()
    for fname in sorted(os.listdir(args.oshb)):
        if not fname.endswith(".xml") or fname == "VerseMap.xml":
            continue
        for w in ET.parse(os.path.join(args.oshb, fname)).iter(OSIS + "w"):
            lemma = w.get("lemma")
            total["words"] += 1
            try:
                lem = parse(lemma)
            except ValueError as e:
                shapes_bad[str(e)] += 1
                continue
            if lem.number is None:
                total["prefix_only" if lemma else "no_lemma"] += 1
            elif aug.resolve(lemma):
                total["resolved"] += 1
                total["plus"] += lem.plus
            else:
                total["unresolved"] += 1
                unresolved[lemma] += 1
    print("OSHB lemmas vs AugIndex (%d index keys, %d numbers with senses)\n" % (len(aug.key_to_id), len(aug.with_senses)))
    for k in ("words", "resolved", "prefix_only", "no_lemma", "unresolved"):
        print("  %-12s %7d" % (k, total[k]))
    print("  %-12s %7d  (of the resolved: carried a '+')" % ("plus", total["plus"]))
    if shapes_bad:
        print("\nunparseable shapes:", dict(shapes_bad))
    if unresolved:
        print("\nunresolved lemmas (most frequent first):")
        for lemma, n in unresolved.most_common(args.show):
            print("  %-14s %5d" % (lemma, n))
    sys.exit(1 if shapes_bad else 0)


if __name__ == "__main__":
    main()
