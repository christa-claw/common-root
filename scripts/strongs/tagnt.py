#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
tagnt.py — read STEPBible's TAGNT (Translators Amalgamated Greek New Testament) and pick out the
Textus Receptus reading, for the Greek half of the Strong's word layer (docs/strongs-design.md).

TAGNT lists EVERY word found in any of the major Greek editions, one tab-separated row per word:

    Mat.1.1#01=NKO <TAB> Βίβλος (Biblos) <TAB> [The] book <TAB> G0976=N-NSF <TAB> βίβλος=book
        <TAB> NA28+NA27+Tyn+SBL+WH+Treg+TR+Byz <TAB> meaning variants <TAB> spelling variants ...

so the TR text is the words whose editions column holds the token `TR`. TAGNT's "Traditional" text is
Scrivener 1894, the TR the corpus's bible-grc-tr comes from. Three details matter:

  * Spelling. The Greek shown is NA28's spelling; where the TR spells a word differently the
    "Spelling variants" column says so ("Tyn+WH: Δαυεὶδ ; +TR: Δαβὶδ ;"). A TR word's forms are therefore
    the displayed form AND its TR spelling.
  * Numbering. References are NRSV's; the KJV's differs in places and is given in square brackets
    (Act.2.11[2.10]). The corpus follows the KJV, so the bracketed number wins. Braces and parentheses
    name other editions' numbering and are ignored.
  * Order. An edition whose word stands elsewhere is marked "moved »2: Treg" in the editions column.

Strong's: `G2424G` is the extended (disambiguated) number: the digits are the plain Strong's number, the
letter distinguishes senses (Jesus G2424G, a different Jesus G2424H ...). Numbers are written without
padding (G976, not G0976), as the Hebrew ids are.

Nothing here modifies TAGNT. Licence: CC BY 4.0, credit STEP Bible (www.STEPBible.org), data from
Tyndale House, Cambridge.
"""
import glob, os, re, unicodedata
from typing import Dict, List, NamedTuple, Optional, Tuple

# The TR token, optionally with a displacement: TR, TR«3 (moved 3 earlier), TR»1 (moved 1 later).
TR_TOKEN = re.compile(r"^TR([\u00ab\u00bb]\d+)?$")
# A TR alternative recorded in the "Meaning variants" column:
#   ἐπηρώτων (t=epērōtōn) were asking - G1905=V-IAI-3P in: TR+Byz     (a phrase may carry two numbers)
ALT = re.compile(r"(?P<greek>[^;|\t]*?)\s*\(t=[^)]*\)\s*(?P<eng>[^;|\t]*?)\s+-\s+"
                 r"(?P<ds>G\d{4}[A-Z]?=\S+(?:\s+\+\s+G\d{4}[A-Z]?=\S+)*)\s+in:\s*(?P<eds>[^;|\t]*)")
ROW = re.compile(r"^([1-3A-Za-z]{3})\.(\d+)\.(\d+)(\[[^\]]*\])?([({][^)}]*[)}])?#(\d+)=(.*)$")
DSTRONG = re.compile(r"^(G\d{4})([A-Z]?)$")
KJV_REF = re.compile(r"^\[(\d+)\.(\d+)\]$")


class Word(NamedTuple):
    book: str                # USFM, e.g. "MAT"
    chapter: int             # KJV numbering
    verse: int
    seq: int                 # the "#NN" position among all editions' words in the verse
    kind: str                # the word-type code: NKO, K, k(o) ...
    greek: str               # as displayed (NA28 spelling), transliteration removed
    strongs: Optional[str]   # plain "G2424" (unpadded), None when TAGNT gives none
    sense: Optional[str]     # the disambiguating letter ("G"), or None
    forms: Tuple[str, ...]   # every spelling this word has in the TR: displayed + TR variant
    moved_in_tr: bool        # the TR has the word in a different position


def normalise(s: str) -> str:
    """Comparison key: no accents, breathings or case, final sigma = sigma, letters only."""
    d = unicodedata.normalize("NFD", s)
    d = "".join(c for c in d if not unicodedata.category(c).startswith("M"))
    d = d.lower().replace("ς", "σ")
    return "".join(c for c in d if c.isalpha())


def tr_spelling(variants: str) -> Optional[str]:
    """The TR's own spelling from the 'Spelling variants' column, e.g. '+TR: Δαβὶδ ;' -> 'Δαβὶδ'."""
    for seg in variants.split(";"):
        if ":" not in seg:
            continue
        sigla, form = seg.split(":", 1)
        if "TR" in [t.strip() for t in sigla.replace("+", " ").split()]:
            f = form.strip()
            if f:
                return f
    return None


def _strongs(token: str):
    ds = DSTRONG.match(token.split("=")[0].strip())
    # unpadded, like the Hebrew ids ("H430"): G0976 -> G976
    return ("G%d" % int(ds.group(1)[1:]), ds.group(2) or None) if ds else (None, None)


def _has_tr(editions: str):
    """(True, moved) when the editions string includes the TR (with or without a displacement)."""
    for t in re.split(r"[+ ]", editions):
        m = TR_TOKEN.match(t.strip())
        if m:
            return True, bool(m.group(1))
    return False, False


def parse_row(line: str) -> List[Word]:
    """The TR words one TAGNT row contributes: the row's own word when the TR has it, and any word the
    TR has INSTEAD (recorded in the meaning-variants column). Usually zero or one; a phrase can give two."""
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 6:
        return []
    m = ROW.match(parts[0])
    if not m:
        return []
    book, ch, vs, kjv, _other, seq, kind = m.groups()
    chapter, verse = int(ch), int(vs)
    if kjv:
        k = KJV_REF.match(kjv)
        if k:
            chapter, verse = int(k.group(1)), int(k.group(2))
    out: List[Word] = []
    has, moved = _has_tr(parts[5])
    if has:
        greek = re.sub(r"\s*\([^)]*\)\s*$", "", parts[1]).strip()   # drop the "(Biblos)" transliteration
        strongs, sense = _strongs(parts[3])
        forms = [greek]
        alt = tr_spelling(parts[7]) if len(parts) > 7 else None
        if alt:
            forms.append(alt)
        out.append(Word(book.upper(), chapter, verse, int(seq), kind, greek, strongs, sense, tuple(forms), moved))
    if len(parts) > 6 and "TR" in parts[6]:
        for a in ALT.finditer(parts[6]):
            ok, alt_moved = _has_tr(a.group("eds"))
            if not ok:
                continue
            tokens = a.group("greek").split()
            nums = [d.strip() for d in a.group("ds").split("+")]
            if len(tokens) == len(nums):
                pairs = list(zip(tokens, nums))
            else:                                   # cannot pair words with numbers: keep the phrase, first number
                pairs = [(" ".join(tokens), nums[0])]
            for tok, ds in pairs:
                strongs, sense = _strongs(ds)
                out.append(Word(book.upper(), chapter, verse, int(seq), kind, tok, strongs, sense, (tok,), alt_moved))
    return out


def read_tr(tagnt_dir: str) -> Dict[Tuple[str, int, int], List[Word]]:
    """{(book, chapter, verse): [TR words in TAGNT order]} from every TAGNT file in the folder."""
    out: Dict[Tuple[str, int, int], List[Word]] = {}
    for path in sorted(glob.glob(os.path.join(tagnt_dir, "TAGNT*.txt"))):
        for line in open(path, encoding="utf-8-sig"):
            if not line[:1].isalnum():
                continue
            for w in parse_row(line):
                out.setdefault((w.book, w.chapter, w.verse), []).append(w)
    return out


# ── Aligning TAGNT's TR words to the corpus's own words ─────────────────────────────────────────

def align(tokens: List[str], words: List[Word]):
    """Match each corpus token to a TAGNT TR word. Returns (assignment, status) where assignment[i] is the
    index into `words` of the word token i is, or None, and status is one of:

        exact      every token matched, in order, and no TAGNT word is left over
        reordered  every token matched but some out of order (the TR orders the verse differently)
        partial    some tokens (or TAGNT words) are unmatched: TAGNT omits TR words that do not change a
                   translation, so those tokens stay unnumbered rather than guessed

    In-order matches come from a longest-common-subsequence over normalised words. Out-of-order matches
    are accepted only when unambiguous: exactly one unmatched token and exactly one unmatched TAGNT word
    share the form. Homographs are real here (the corpus has no accents: ἡ / ἢ / ἦ all read "η"), so
    an ambiguous leftover is left unnumbered, never assigned by luck."""
    n, m = len(tokens), len(words)
    keys = [normalise(t) for t in tokens]
    wkeys = [frozenset(normalise(f) for f in w.forms) for w in words]
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            dp[i][j] = dp[i + 1][j + 1] + 1 if keys[i] in wkeys[j] else max(dp[i + 1][j], dp[i][j + 1])
    assign: List[Optional[int]] = [None] * n
    i = j = 0
    while i < n and j < m:
        if keys[i] in wkeys[j] and dp[i][j] == dp[i + 1][j + 1] + 1:
            assign[i] = j
            i += 1
            j += 1
        elif dp[i + 1][j] >= dp[i][j + 1]:
            i += 1
        else:
            j += 1
    in_order = sum(1 for a in assign if a is not None)
    used = {a for a in assign if a is not None}
    free_tok = [t for t in range(n) if assign[t] is None]
    free_word = [w for w in range(m) if w not in used]
    for t in free_tok:
        cands = [w for w in free_word if keys[t] in wkeys[w]]
        same_tok = [u for u in free_tok if keys[u] == keys[t]]
        same_word = [w for w in free_word if keys[t] in wkeys[w]]
        if len(cands) == 1 and len(same_tok) == 1 and len(same_word) == 1:
            assign[t] = cands[0]
    matched = sum(1 for a in assign if a is not None)
    leftover = m - len({a for a in assign if a is not None})
    if matched == n and leftover == 0:
        status = "exact" if in_order == n else "reordered"
    else:
        status = "partial"
    return assign, status
