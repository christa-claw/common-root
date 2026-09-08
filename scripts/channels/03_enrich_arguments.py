#!/usr/bin/env python3
"""
enrich_arguments.py — add video_id / video_url / per-verse-ref timecodes to
transcripts/arguments.json, in place (with a .bak backup).

Per entry:
  video_id   <- "[XXXXXXXXXXX]" token in the source_file name, else the sibling
                .info.json's "id" (yt-dlp writes both files from one template,
                but title sanitization can differ, so the .info.json is matched
                by the [id] token or, failing that, by date+title prefix).
  video_url  <- https://www.youtube.com/watch?v={id}
  per verse_ref:
      timecode_s <- start (whole seconds) of the EARLIEST .vtt caption that
                    mentions the reference (book-name alias near chapter[:.,/ ]
                    verse, or "chapter N ... verse M" phrasing). Heuristic over
                    auto-captions; absent when no mention is found.
      video_link <- video_url + "&t={timecode_s}s" (or no t= when no timecode).

Idempotent: re-running recomputes the same fields. Stdlib only (the project's
Python-3.14/libexpat rule: no xml.etree, no pip deps).

Usage:
    python3 enrich_arguments.py            # enrich in place
    python3 enrich_arguments.py --dry-run  # report only, write nothing
"""
import difflib
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "transcripts")
ARGS_JSON = os.path.join(ROOT, "arguments.json")


def _fold(s):
    """Normalize a filename for fuzzy comparison: strip a [videoid] token, then
    keep ONLY ascii alphanumerics, lowercased. Kills every punctuation/space/
    unicode-symbol variance yt-dlp's sanitization has produced between fetches
    (fullwidth quotes/colons, \uFDFA ligature, underscores-for-colons, ellipses…)."""
    s = re.sub(r"\[[A-Za-z0-9_-]{11}\]", "", s)
    return re.sub(r"[^a-z0-9]", "", s.lower())


def _stem(name):
    """Filename without the trailing subtitle/info extension."""
    return re.sub(r"(\.[a-z]{2}(?:[-.][A-Za-z]+)*)?\.(vtt|info\.json)$", "", name)


def _stem_match(want, have):
    """True when two folded stems refer to the same video. Both start with the
    8-digit upload date; require equal dates, then accept an exact title match,
    containment (truncated/prefixed titles), or a high similarity ratio (small
    wording drift like \"Jehovah Witnesses\" vs \"Jehovah's Witnesses\")."""
    if want == have:
        return True
    if want[:8] != have[:8] or not want[:8].isdigit():
        return False
    a, b = want[8:], have[8:]
    if len(a) >= 18 and len(b) >= 18 and (a in b or b in a):
        return True
    return difflib.SequenceMatcher(None, a, b).ratio() >= 0.85


# Known channel-folder typos in older arguments.json entries.
DIR_FIXES = {"Shamounian Extends": "Shamounian Explains"}


def resolve_vtt(source_file):
    """Actual on-disk .vtt path for a stored source_file, tolerating the
    quote-mark / [id]-token / channel-typo drift between fetches. Prefers an
    [id]-bracketed copy (it pins the video id). Returns (path or None)."""
    for bad, good in DIR_FIXES.items():
        if source_file.startswith(bad + "/"):
            source_file = good + source_file[len(bad):]
            break
    full = os.path.join(ROOT, source_file)
    d = os.path.dirname(full)
    base = os.path.basename(source_file)
    want = _fold(_stem(base))
    best = None
    if os.path.isfile(full):
        best = full
    if os.path.isdir(d):
        for f in os.listdir(d):
            if f.endswith(".vtt") and _stem_match(want, _fold(_stem(f))):
                p = os.path.join(d, f)
                if VIDEO_ID_RE.search(f):
                    return p          # bracketed copy wins (carries the id)
                if best is None:
                    best = p
    return best

VIDEO_ID_RE = re.compile(r"\[([A-Za-z0-9_-]{11})\](?=\.[a-z]{2}(?:[-.][A-Za-z]+)*\.vtt$|\.)")
TIMESTAMP_RE = re.compile(r"^(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+-->")
TAG_RE = re.compile(r"<[^>]+>")

# Book-name aliases as SPOKEN (lowercased match against caption text). USFM code
# -> list of name variants. Numbered books match both "1 john" and "first john".
ORDINALS = {"1": ["1", "first"], "2": ["2", "second"], "3": ["3", "third"], "4": ["4", "fourth"]}
BOOK_ALIASES = {
    "GEN": ["genesis"], "EXO": ["exodus"], "LEV": ["leviticus"], "NUM": ["numbers"],
    "DEU": ["deuteronomy"], "JOS": ["joshua"], "JDG": ["judges"], "RUT": ["ruth"],
    "1SA": ["samuel"], "2SA": ["samuel"], "1KI": ["kings"], "2KI": ["kings"],
    "1CH": ["chronicles"], "2CH": ["chronicles"], "EZR": ["ezra"], "NEH": ["nehemiah"],
    "EST": ["esther"], "JOB": ["job"], "PSA": ["psalm", "psalms"], "PRO": ["proverbs"],
    "ECC": ["ecclesiastes"], "SNG": ["song of solomon", "song of songs"],
    "ISA": ["isaiah"], "JER": ["jeremiah"], "LAM": ["lamentations"],
    "EZK": ["ezekiel"], "DAN": ["daniel"], "HOS": ["hosea"], "JOL": ["joel"],
    "AMO": ["amos"], "OBA": ["obadiah"], "JON": ["jonah"], "MIC": ["micah"],
    "NAM": ["nahum"], "HAB": ["habakkuk"], "ZEP": ["zephaniah"], "HAG": ["haggai"],
    "ZEC": ["zechariah"], "MAL": ["malachi"],
    "MAT": ["matthew"], "MRK": ["mark"], "LUK": ["luke"], "JHN": ["john"],
    "ACT": ["acts"], "ROM": ["romans"], "1CO": ["corinthians"], "2CO": ["corinthians"],
    "GAL": ["galatians"], "EPH": ["ephesians"], "PHP": ["philippians"],
    "COL": ["colossians"], "1TH": ["thessalonians"], "2TH": ["thessalonians"],
    "1TI": ["timothy"], "2TI": ["timothy"], "TIT": ["titus"], "PHM": ["philemon"],
    "HEB": ["hebrews"], "JAS": ["james"], "1PE": ["peter"], "2PE": ["peter"],
    "JHN1": [], "1JN": ["john"], "2JN": ["john"], "3JN": ["john"],
    "JUD": ["jude"], "REV": ["revelation", "revelations"],
}
# Surah-number -> spoken names for Qur'an refs (type "quran", code = surah number).
SURAH_SPOKEN = {
    "1": ["fatihah", "fatiha"], "2": ["baqarah", "baqara"], "3": ["imran"],
    "4": ["nisa"], "5": ["ma'idah", "maidah", "maida"], "9": ["tawbah", "taubah"],
    "19": ["maryam"], "112": ["ikhlas"],
}

# LDS standard-works code -> spoken names (lowercased, matched against caption
# text). Codes match ingest_bom.py / extract_arguments.py. Numbered Nephi books
# store only the base name; ORDINALS prepends "first"/"1" etc., exactly like the
# numbered Bible books in BOOK_ALIASES.
LDS_ALIASES = {
    "1NE": ["nephi"], "2NE": ["nephi"], "3NE": ["nephi"], "4NE": ["nephi"],
    "JAC": ["jacob"], "ENO": ["enos"], "JAR": ["jarom"], "OMN": ["omni"],
    "WOM": ["words of mormon"], "MOS": ["mosiah"], "ALM": ["alma"],
    "HEL": ["helaman"], "MRM": ["mormon"], "ETH": ["ether"], "MNI": ["moroni"],
    "DC": ["doctrine and covenants", "d and c", "section"],
    "MOSE": ["moses"], "ABR": ["abraham"],
    "JSM": ["joseph smith matthew", "joseph smith"],
    "JSH": ["joseph smith history", "joseph smith"],
    "AOF": ["articles of faith", "article of faith"],
}


def hhmmss_to_s(h, m, s, ms):
    return int(h) * 3600 + int(m) * 60 + int(s) + (1 if int(ms) >= 500 else 0)


def parse_vtt(path):
    """[(start_seconds, lowercased caption text)] — one entry per caption block,
    inline word-timing tags stripped, consecutive lines of a block joined."""
    cues = []
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return cues
    i, n = 0, len(lines)
    while i < n:
        m = TIMESTAMP_RE.match(lines[i])
        if not m:
            i += 1
            continue
        start = hhmmss_to_s(*m.groups())
        i += 1
        text = []
        while i < n and lines[i].strip() and not TIMESTAMP_RE.match(lines[i]):
            text.append(TAG_RE.sub("", lines[i]))
            i += 1
        joined = " ".join(t.strip() for t in text if t.strip()).lower()
        if joined:
            cues.append((start, joined))
    return cues


def spoken_names(ref):
    """Spoken-name variants for a verse_ref (handles 1/2/3/4-prefixed books)."""
    if ref.get("type") == "quran":
        names = list(SURAH_SPOKEN.get(str(ref.get("code", "")), []))
        names.append("surah " + str(ref.get("chapter", "")))
        return names
    code = str(ref.get("code", ""))
    if ref.get("type") == "lds":
        base = LDS_ALIASES.get(code, [])
    else:
        base = BOOK_ALIASES.get(code, [])
    if code[:1] in ORDINALS and base:
        return [f"{o} {b}" for o in ORDINALS[code[0]] for b in base]
    return list(base)


def find_timecode(cues, ref):
    """Earliest caption start mentioning the ref. Strategy per caption window
    (caption + the next one, since auto-captions split mid-sentence):
      1. name + chapter + verse near each other  (john ... 8 ... 58)
      2. 'chapter C' + 'verse V' phrasing
      3. fallback: name + chapter only (first occurrence)."""
    names = [n for n in spoken_names(ref) if n]
    if not names:
        return None
    ch, vs = str(ref.get("chapter", "")), str(ref.get("verse", ""))
    # "number"/"no." between the keyword and the digits is common spoken style:
    # "gospel of John chapter number 14 verse number 16".
    num = r"(?:number|no)?\W*"
    full_re = [re.compile(
        rf"\b{re.escape(n)}\b\W+(?:chapter\W*{num})?{ch}\b\W{{0,16}}(?:verse\W*{num}|:|\.)?\W*{vs}\b")
        for n in names] if vs and vs != "0" else []
    chap_re = [re.compile(rf"\b{re.escape(n)}\b\W+(?:chapter\W*{num})?{ch}\b") for n in names]
    cv_re = re.compile(
        rf"\bchapter\W*{num}{ch}\b.{{0,40}}\bverse\W*{num}{vs}\b") if vs and vs != "0" else None

    chap_hit = None
    for i, (start, text) in enumerate(cues):
        window = text if i + 1 >= len(cues) else text + " " + cues[i + 1][1]
        for rx in full_re:
            if rx.search(window):
                return start
        if cv_re and cv_re.search(window):
            # require the book name somewhere within the previous ~6 captions
            ctx = " ".join(t for _, t in cues[max(0, i - 6):i + 1])
            if any(re.search(rf"\b{re.escape(n)}\b", ctx) for n in names):
                return start
        if chap_hit is None:
            for rx in chap_re:
                if rx.search(window):
                    chap_hit = start
                    break
    return chap_hit


def resolve_video_id(source_file, vtt_path=None):
    """[id] token in the resolved filename, else the sibling .info.json (matched
    by folded stem, then by date+title prefix)."""
    if vtt_path:
        m = VIDEO_ID_RE.search(os.path.basename(vtt_path))
        if m:
            return m.group(1)
    m = VIDEO_ID_RE.search(source_file)
    if m:
        return m.group(1)
    full = vtt_path or os.path.join(ROOT, source_file)
    d = os.path.dirname(full)
    base = os.path.basename(full)
    want = _fold(_stem(base))
    if not os.path.isdir(d):
        return None
    candidates = [f for f in os.listdir(d) if f.endswith(".info.json")]
    for f in candidates:
        if _stem_match(want, _fold(_stem(f))):
            return _id_from_info(os.path.join(d, f))
    return None


def _id_from_info(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return json.load(f).get("id")
    except (OSError, ValueError):
        return None


def main():
    dry = "--dry-run" in sys.argv
    with open(ARGS_JSON, encoding="utf-8") as f:
        entries = json.load(f)

    n_id = n_tc = n_refs = n_novtt = 0
    for e in entries:
        src = e.get("source_file", "")
        vtt = resolve_vtt(src)
        vid = resolve_video_id(src, vtt)
        if vid:
            n_id += 1
            e["video_id"] = vid
            e["video_url"] = f"https://www.youtube.com/watch?v={vid}"
        else:
            e.pop("video_id", None)
            e.pop("video_url", None)

        cues = parse_vtt(vtt) if vtt else []
        if not cues:
            n_novtt += 1
        for ref in e.get("verse_refs", []):
            n_refs += 1
            tc = find_timecode(cues, ref) if cues else None
            if tc is not None:
                n_tc += 1
                ref["timecode_s"] = tc
                if vid:
                    ref["video_link"] = f"https://www.youtube.com/watch?v={vid}&t={tc}s"
            else:
                ref.pop("timecode_s", None)
                if vid:
                    ref["video_link"] = f"https://www.youtube.com/watch?v={vid}"
                else:
                    ref.pop("video_link", None)

    print(f"entries: {len(entries)}  video_id resolved: {n_id}/{len(entries)}  "
          f"vtt missing/empty: {n_novtt}")
    print(f"verse_refs: {n_refs}  timecodes found: {n_tc}")

    if dry:
        print("(dry run — nothing written)")
        return
    bak = ARGS_JSON + ".bak"
    if not os.path.exists(bak):
        os.replace(ARGS_JSON, bak)
    else:
        os.remove(ARGS_JSON)
    with open(ARGS_JSON, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"written: {ARGS_JSON}  (backup: {bak})")


if __name__ == "__main__":
    main()
