# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""verse_scan.py — find EVERY scripture citation spoken anywhere in a transcript.

The inverse of 03_enrich_arguments.find_timecode(). That function answers
"where in this video is ref X mentioned?" for a ref we already know. This one
answers "which refs are mentioned at all, and when?" — with no ref known in
advance and no truncation of the transcript.

Why it exists: the extractors only ever saw text[:3000], so on a long debate the
model was reasoning about the intro. Scanning the whole transcript cheaply (pure
regex, no model) and then sending only the windows AROUND each citation gives
full-transcript coverage at a small fraction of the model calls a blind chunked
sweep would need.

Reuses 03_enrich_arguments' cue parser and spoken-name alias tables so the two
stay in lock-step — if a book alias is added there, it is found here too.

Stdlib only (project rule: no pip deps, no xml.etree).
"""

import importlib
import re

en = importlib.import_module("03_enrich_arguments")

_TIMESTAMP_RE = en.TIMESTAMP_RE
_TAG_RE = en.TAG_RE


def parse_vtt(path):
    """[(start_seconds, lowercased text)] with rolling captions deduplicated.

    03_enrich.parse_vtt keeps every caption line, which is right for its job
    (find the EARLIEST mention of a known ref — duplicates are harmless there).
    It is wrong here. YouTube auto-captions roll: each cue repeats the previous
    one or two lines and adds one new line, so the raw text comes out roughly
    tripled AND interleaved:

        "...have a written roughly have a written roughly have a guess right
         there are four in the gospel guess right there are four in the gospel..."

    That interleaving is not just noise — it splits "john chapter 3 verse 16"
    across a roll boundary and the citation regex never sees it. Deduplicating
    per LINE (keeping each line's first appearance and its cue's start time) is
    what 02_extract_arguments.vtt_to_text already does for the flat text; this
    does the same while preserving timing.
    """
    cues, seen = [], set()
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return cues
    i, n = 0, len(lines)
    while i < n:
        m = _TIMESTAMP_RE.match(lines[i])
        if not m:
            i += 1
            continue
        start = en.hhmmss_to_s(*m.groups())
        i += 1
        fresh = []
        while i < n and lines[i].strip() and not _TIMESTAMP_RE.match(lines[i]):
            text = _TAG_RE.sub("", lines[i]).strip().lower()
            if text and text not in seen:
                seen.add(text)
                fresh.append(text)
            i += 1
        if fresh:
            cues.append((start, " ".join(fresh)))
    return cues

# ---------------------------------------------------------------------------
# Name -> code tables, built by INVERTING the alias tables in 03_enrich.
# ---------------------------------------------------------------------------

# Spoken ordinals, as auto-captions render them.
ORDINAL_WORDS = {
    "1": 1, "first": 1, "1st": 1, "i": 1,
    "2": 2, "second": 2, "2nd": 2, "ii": 2,
    "3": 3, "third": 3, "3rd": 3, "iii": 3,
    "4": 4, "fourth": 4, "4th": 4, "iv": 4,
}

# Books whose spoken name is shared by several canonical books, disambiguated by
# a preceding ordinal. `default` is the code for the BARE name: "john" alone is
# overwhelmingly the Gospel, while a bare "corinthians" is genuinely ambiguous
# and is dropped rather than guessed.
NUMBERED = {
    "samuel":       ({1: "1SA", 2: "2SA"}, None,  "bible"),
    "kings":        ({1: "1KI", 2: "2KI"}, None,  "bible"),
    "chronicles":   ({1: "1CH", 2: "2CH"}, None,  "bible"),
    "corinthians":  ({1: "1CO", 2: "2CO"}, None,  "bible"),
    "thessalonians": ({1: "1TH", 2: "2TH"}, None, "bible"),
    "timothy":      ({1: "1TI", 2: "2TI"}, None,  "bible"),
    "peter":        ({1: "1PE", 2: "2PE"}, None,  "bible"),
    "john":         ({1: "1JN", 2: "2JN", 3: "3JN"}, "JHN", "bible"),
    "nephi":        ({1: "1NE", 2: "2NE", 3: "3NE", 4: "4NE"}, None, "lds"),
}


def _build_simple():
    """Unambiguous spoken name -> (code, type), inverted from 03_enrich's tables.

    Names that NUMBERED already owns are excluded, as are names claimed by more
    than one code (those are exactly the numbered families).
    """
    counts = {}
    for table in (en.BOOK_ALIASES, en.LDS_ALIASES):
        for code, names in table.items():
            for n in names:
                counts.setdefault(n, set()).add(code)

    simple = {}
    for kind, table in (("bible", en.BOOK_ALIASES), ("lds", en.LDS_ALIASES)):
        for code, names in table.items():
            for n in names:
                if n in NUMBERED or len(counts[n]) > 1:
                    continue
                simple[n] = (code, kind)
    # "section" (a D&C alias) is far too common a word in ordinary speech to
    # treat as a citation trigger; D&C is matched by its full name only.
    simple.pop("section", None)
    return simple


SIMPLE = _build_simple()

# Surah spoken name -> number, inverted from 03_enrich's SURAH_SPOKEN.
SURAH_BY_NAME = {n: int(num) for num, names in en.SURAH_SPOKEN.items() for n in names}

# ---------------------------------------------------------------------------
# Patterns
# ---------------------------------------------------------------------------

_ALL_NAMES = sorted(set(SIMPLE) | set(NUMBERED), key=len, reverse=True)
_NAME_ALT = "|".join(re.escape(n) for n in _ALL_NAMES)
_ORD_ALT = "|".join(sorted(ORDINAL_WORDS, key=len, reverse=True))

# "(first) corinthians" — ordinal optional, captured when present.
BOOK_RE = re.compile(rf"\b(?:({_ORD_ALT})\s+)?({_NAME_ALT})\b")

# The chapter[:verse] tail that may follow a book name. Tolerates the spoken
# filler auto-captions preserve: "chapter number 14 verse number 16".
#
# No leading "^": these are used with .match(text, pos), which already anchors
# at pos, whereas "^" would anchor at the start of the whole string and so match
# nothing but the very first citation in a transcript.
#
# The verse separator is optional because auto-captions routinely drop the colon
# — "romans 9 22" and "first corinthians 15 3" are how these actually appear.
# The bare-space form is why the verse is capped at 176 (Psalm 119, the longest
# chapter in scripture): without a cap, a stray "psalm 23 1000 times" would
# scan as a verse citation.
_NUM = r"(?:number|numbers|no\.?)?\W*"
TAIL_RE = re.compile(
    rf"\W{{0,12}}(?:chapter\W*{_NUM})?(\d{{1,3}})\b"
    rf"(?:\W{{0,18}}(?:verse|verses|vs|v|:|\.)?\W*{_NUM}(\d{{1,3}})\b)?"
)

# "surah 4 verse 157", "surah al-nisa verse 157", "quran 2:255".
SURAH_RE = re.compile(
    rf"\b(?:surahs?|surat|sura|qur'?an|koran)\W{{0,4}}(?:al[\s-]*)?"
    rf"(?:(\d{{1,3}})|({'|'.join(re.escape(n) for n in sorted(SURAH_BY_NAME, key=len, reverse=True))}))"
    rf"\b(?:\W{{0,18}}(?:ayah?|ayat|verse|vs|v|:|\.)?\W*{_NUM}(\d{{1,3}})\b)?"
)

# Doctrine and Covenants: the "&" defeats the generic book regex.
DC_RE = re.compile(
    rf"\b(?:d\s*&\s*c|d\s+and\s+c|doctrine\s+(?:and|&)\s+covenants)"
    rf"\W{{0,12}}(?:section\W*{_NUM})?(\d{{1,3}})\b"
    rf"(?:\W{{0,18}}(?:verse|vs|v|:|\.)\W*{_NUM}(\d{{1,3}})\b)?"
)


# Chapter counts per book. A citation naming a chapter the book does not have
# is not a citation — it is the regex latching onto a nearby number. Caught in
# the pilot: "Polycarp's epistle to the Philippians, Chapter 6" scanned as
# PHP 6, but Philippians has four chapters.
CHAPTERS = {
    "GEN": 50, "EXO": 40, "LEV": 27, "NUM": 36, "DEU": 34, "JOS": 24, "JDG": 21,
    "RUT": 4, "1SA": 31, "2SA": 24, "1KI": 22, "2KI": 25, "1CH": 29, "2CH": 36,
    "EZR": 10, "NEH": 13, "EST": 10, "JOB": 42, "PSA": 150, "PRO": 31, "ECC": 12,
    "SNG": 8, "ISA": 66, "JER": 52, "LAM": 5, "EZK": 48, "DAN": 12, "HOS": 14,
    "JOL": 3, "AMO": 9, "OBA": 1, "JON": 4, "MIC": 7, "NAM": 3, "HAB": 3,
    "ZEP": 3, "HAG": 2, "ZEC": 14, "MAL": 4,
    "MAT": 28, "MRK": 16, "LUK": 24, "JHN": 21, "ACT": 28, "ROM": 16,
    "1CO": 16, "2CO": 13, "GAL": 6, "EPH": 6, "PHP": 4, "COL": 4,
    "1TH": 5, "2TH": 3, "1TI": 6, "2TI": 4, "TIT": 3, "PHM": 1, "HEB": 13,
    "JAS": 5, "1PE": 5, "2PE": 3, "1JN": 5, "2JN": 1, "3JN": 1, "JUD": 1,
    "REV": 22,
    # LDS standard works
    "1NE": 22, "2NE": 33, "JAC": 7, "ENO": 1, "JAR": 1, "OMN": 1, "WOM": 1,
    "MOS": 29, "ALM": 63, "HEL": 16, "3NE": 30, "4NE": 1, "MRM": 9, "ETH": 15,
    "MNI": 10, "DC": 138, "MOSE": 8, "ABR": 5, "JSM": 1, "JSH": 1, "AOF": 1,
}

# Apologetics channels discuss the Apostolic Fathers constantly, and those works
# are named for the SAME congregations as the canonical epistles — Polycarp to
# the Philippians, 1 Clement to the Corinthians, Ignatius to the Ephesians and
# Romans. "Polycarp, chapter 6" is not Philippians. When one of these names
# appears shortly before a book name, the citation is patristic, not canonical,
# and is dropped rather than misfiled.
PATRISTIC_RE = re.compile(
    r"\b(polycarp|clement|ignatius|barnabas|hermas|didache|papias|irenaeus|"
    r"tertullian|origen|eusebius|chrysostom|augustine|josephus)\b"
)
PATRISTIC_LOOKBACK = 60


def _ref_key(ref):
    """Identity of a ref, for dedup within a video."""
    if ref["type"] == "quran":
        return ("quran", ref["surah"], ref.get("ayah", 0))
    return (ref["type"], ref["code"], ref["chapter"], ref.get("verse", 0))


def scan_text(text):
    """All scripture citations in one blob of lowercased text.

    Returns [(char_offset, ref)] in order of appearance. A ref matches the shape
    the rest of the pipeline uses: bible/lds -> {type, code, chapter, verse},
    quran -> {type, surah, ayah}. A citation naming only a chapter yields
    verse/ayah 0, exactly as an unresolved verse does elsewhere.
    """
    out = []

    for m in DC_RE.finditer(text):
        out.append((m.start(), {"type": "lds", "code": "DC",
                                "chapter": int(m.group(1)),
                                "verse": int(m.group(2) or 0)}))

    for m in SURAH_RE.finditer(text):
        num, name, ayah = m.group(1), m.group(2), m.group(3)
        surah = int(num) if num else SURAH_BY_NAME.get(name)
        if not surah or surah > 114:
            continue
        out.append((m.start(), {"type": "quran", "surah": surah,
                                "ayah": int(ayah or 0)}))

    for m in BOOK_RE.finditer(text):
        ordinal, name = m.group(1), m.group(2)
        tail = TAIL_RE.match(text, m.end())
        if not tail:
            # A bare book name with no chapter number is not a citation — it is
            # just someone saying "Paul" or "John". Requiring the number is what
            # keeps this scan precise enough to anchor on.
            continue
        if name in NUMBERED:
            codes, default, kind = NUMBERED[name]
            n = ORDINAL_WORDS.get(ordinal) if ordinal else None
            code = codes.get(n) if n else default
            if not code:
                continue
        else:
            code, kind = SIMPLE[name]
        chapter = int(tail.group(1))
        verse = int(tail.group(2) or 0)
        if chapter == 0 or verse > 176 or chapter > CHAPTERS.get(code, 150):
            continue
        if PATRISTIC_RE.search(text[max(0, m.start() - PATRISTIC_LOOKBACK):m.start()]):
            continue
        out.append((m.start(), {"type": kind, "code": code,
                                "chapter": chapter, "verse": verse}))

    out.sort(key=lambda p: p[0])
    return out


def scan_cues(cues, window_chars=1500, max_windows=40):
    """Scan timestamped cues and cluster the hits into model-sized windows.

    Returns [{"start_s", "refs", "text"}] — one entry per cluster of nearby
    citations, each carrying the transcript text around it and the timestamp of
    its first citation (for a youtube &t= deep link).

    Clustering matters: a speaker exegeting Romans 9 cites eight verses in two
    minutes. Those are one argument and belong in one window, not eight.
    """
    if not cues:
        return []

    # Flat text plus an offset->cue index, so a char hit maps back to a time.
    parts, offsets, pos = [], [], 0
    for start, text in cues:
        parts.append(text)
        offsets.append((pos, start))
        pos += len(text) + 1
    flat = " ".join(parts)

    hits = scan_text(flat)
    if not hits:
        return []

    def time_at(off):
        lo, hi = 0, len(offsets) - 1
        best = offsets[0][1]
        while lo <= hi:
            mid = (lo + hi) // 2
            if offsets[mid][0] <= off:
                best = offsets[mid][1]
                lo = mid + 1
            else:
                hi = mid - 1
        return best

    # Greedy left-to-right clustering: a hit joins the open cluster while it
    # still fits inside one window's worth of characters.
    clusters = []
    cur = [hits[0]]
    for off, ref in hits[1:]:
        if off - cur[0][0] <= window_chars:
            cur.append((off, ref))
        else:
            clusters.append(cur)
            cur = [(off, ref)]
    clusters.append(cur)

    windows = []
    for cl in clusters[:max_windows]:
        first, last = cl[0][0], cl[-1][0]
        lo = max(0, first - window_chars // 3)
        hi = min(len(flat), last + window_chars)
        seen, refs = set(), []
        for _, ref in cl:
            k = _ref_key(ref)
            if k not in seen:
                seen.add(k)
                refs.append(ref)
        windows.append({
            "start_s": time_at(first),
            "refs": refs,
            "text": flat[lo:hi],
        })
    return windows


def scan_vtt(path, **kw):
    """Convenience: parse a .vtt and return its citation windows."""
    return scan_cues(parse_vtt(path), **kw)
