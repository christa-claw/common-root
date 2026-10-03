#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Fetch the 1919 Chinese Union Version ("Shen" edition) from Chinese Wikisource
and convert it into the flat JSON that 03_import_json_bible.py --format flat takes.

WHY THIS EXISTS (docs/print-rights-plan-zh-ar-he.md)
----------------------------------------------------
The corpus's `bible-zh-cuv` is the later, punctuated and modernised text (the
1988 "New Punctuation" family): modern character forms, modern punctuation and a
few wording changes. Statements that the CUV is public domain are about the 1919
text, so they do not cover it. Chinese Wikisource carries a careful transcription
of the 1919 text (聖經 (和合本)): 1919 orthography (爲 着 裏 衆 眞 麼 ...), the
1919 punctuation, the translators' dotted words, margin notes and cross
references, and every page is tagged public domain ({{PD/1923}}). Its index page
documents the editors' method; read it before relying on the text.

WHAT IT DOES
------------
  * fetches the index and the 66 book pages through the MediaWiki API, politely,
    and caches the raw wikitext (default: ./.cache/cuv1919) so a re-run is free
  * keeps each page's revision id and timestamp in the output metadata, so the
    exact text that was printed can be re-found
  * reads {{verse|C|V}} markers into verses; strips layout (chapter headings,
    "back to top" links, indent divs, anchors)
  * keeps {{udots|x}} (translator-supplied words) as plain text, {{-|x}}
    (psalm titles and "Selah") as text
  * removes the ideographic space that precedes 神 in the "Shen" edition (the
    printed honorific gap); it would otherwise break lines in the wrong place
  * takes the translators' margin notes {{*|...}} OUT of the verse and writes them
    to a notes ledger in the shape NotesSeeder reads, as 09_extract_inline_notes.py
    does for the WEB
  * drops the cross-reference markers (<sup>〡</sup>) and the cross-reference lists
    at the foot of each chapter
  * checks the result: 66 books, 31,102 verses, no verse empty, and reports
    anything it did not understand instead of guessing

USAGE
    python3 scripts/bibles/01_fetch_wikisource_cuv.py \\
        --output /tmp/cuv1919-flat.json --notes transcripts/notes-cuv1919.json

then (writes to local BaseX):
    python3 scripts/bibles/03_import_json_bible.py --input /tmp/cuv1919-flat.json \\
        --format flat --id bible-zh-cuv1919 --abbr CUV1919 --lang zh --iso3 zho \\
        --translation "和合本 (1919)" --year 1919 --region Protestant \\
        --license "Public Domain (1919 text); transcription: Chinese Wikisource" \\
        --source "https://zh.wikisource.org/wiki/聖經_(和合本)"
    # then stamp the reading orders:
    #   python3 scripts/bibles/04_stamp_canonical.py --id bible-zh-cuv1919      (note: --id)
    #   python3 scripts/bibles/05_stamp_chronological.py --translation bible-zh-cuv1919
    #   python3 scripts/bibles/06_stamp_tanakh.py --translation bible-zh-cuv1919
    #   python3 scripts/bibles/06b_stamp_writing.py --translation bible-zh-cuv1919

Stdlib only.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "channels"))
from comment_id import mint_note_id  # noqa: E402

API = "https://zh.wikisource.org/w/api.php"
INDEX_TITLE = "聖經 (和合本)"
UA = {"User-Agent": "common-root-cuv-ingest/1.0 (christa.claw@proton.me)"}
EDITION = "CUV1919"

# Canonical book number -> (USFM code, OSIS-style code used as the note id key)
USFM = ["GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI",
        "2KI", "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG",
        "ISA", "JER", "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC",
        "NAM", "HAB", "ZEP", "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT",
        "ROM", "1CO", "2CO", "GAL", "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI",
        "TIT", "PHM", "HEB", "JAS", "1PE", "2PE", "1JN", "2JN", "3JN", "JUD", "REV"]


def api_get(title):
    q = urllib.parse.urlencode({
        "action": "query", "prop": "revisions", "rvprop": "content|ids|timestamp",
        "rvslots": "main", "titles": title, "format": "json", "redirects": 1})
    req = urllib.request.Request(API + "?" + q, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.load(r)
    page = list(d["query"]["pages"].values())[0]
    rev = page["revisions"][0]
    return rev["slots"]["main"]["*"], rev["revid"], rev["timestamp"]


def fetch(title, cache_dir, key):
    wiki = os.path.join(cache_dir, key + ".wiki")
    meta = os.path.join(cache_dir, key + ".json")
    if os.path.exists(wiki) and os.path.exists(meta):
        with open(wiki, encoding="utf-8") as f, open(meta, encoding="utf-8") as m:
            return f.read(), json.load(m)
    text, revid, ts = api_get(title)
    with open(wiki, "w", encoding="utf-8") as f:
        f.write(text)
    info = {"revid": revid, "timestamp": ts, "title": title}
    with open(meta, "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False)
    time.sleep(0.4)                       # be polite to a volunteer-run site
    return text, info


# ── wikitext -> verses ────────────────────────────────────────────────────────

VERSE = re.compile(r"\{\{\s*verse\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\}\}", re.I)
SKIP_LINE = re.compile(
    r"^\s*(\{\{\s*(gototop|anchor|header2?|footer|pd/\d+|檢索|textquality)\b|==|</?div|:|\[\[|__NOTOC__)",
    re.I)
NOTE_OPEN = re.compile(r"\{\{\s*\*\s*\|")
PLAIN = [
    (re.compile(r"\{\{\s*udots\s*\|(.*?)\}\}", re.S), r"\1"),   # translator-supplied words
    (re.compile(r"\{\{\s*-\s*\|(.*?)\}\}", re.S), r"\1"),       # psalm titles, "Selah"
    (re.compile(r"\{\{\s*\+\s*\|(.*?)\}\}", re.S), r"\1"),       # words set apart in print
    (re.compile(r"<sup>.*?</sup>", re.S), ""),                  # cross-reference markers
    (re.compile(r"-\{(.*?)\}-", re.S), r"\1"),                  # language-converter guards
    (re.compile(r"</?span[^>]*>"), ""),
    # {{!|rare-character|fallback}}: a character Unicode lacked, with the plain
    # characters Wikisource shows in its place. Print uses the fallback.
    (re.compile(r"\{\{\s*!\s*\|[^|}]*\|([^}]*)\}\}"), r"\1"),
]
LEFTOVER = re.compile(r"\{\{|\}\}|<[a-zA-Z/]|\[\[")


def split_notes(raw):
    """Take the {{*|...}} margin notes out of a verse, matching braces: a note can
    contain another template (a rare-character gloss), so a non-greedy regex
    would stop at the wrong }}. Returns (verse without notes, [note text])."""
    notes, out, pos = [], [], 0
    while True:
        m = NOTE_OPEN.search(raw, pos)
        if not m:
            out.append(raw[pos:])
            break
        out.append(raw[pos:m.start()])
        depth, i = 1, m.end()
        while i < len(raw) and depth:
            if raw.startswith("{{", i):
                depth, i = depth + 1, i + 2
            elif raw.startswith("}}", i):
                depth, i = depth - 1, i + 2
            else:
                i += 1
        notes.append(raw[m.end():i - 2] if depth == 0 else raw[m.end():])
        pos = i
    return "".join(out), notes


def parse_book(wikitext, book_no, problems):
    """-> ([(chapter, verse, text, [notes])], ...)"""
    verses, cur = [], None
    for raw in wikitext.splitlines():
        if SKIP_LINE.match(raw) and not VERSE.search(raw):
            continue
        pos = 0
        parts = list(VERSE.finditer(raw))
        if not parts:
            if cur is not None and raw.strip():
                cur["raw"] += raw.strip()
            continue
        if cur is not None and raw[:parts[0].start()].strip():
            cur["raw"] += raw[:parts[0].start()].strip()
        for k, m in enumerate(parts):
            if cur is not None:
                verses.append(cur)
            end = parts[k + 1].start() if k + 1 < len(parts) else len(raw)
            cur = {"book": book_no, "chapter": int(m.group(1)), "verse": int(m.group(2)),
                   "raw": raw[m.end():end].strip()}
    if cur is not None:
        verses.append(cur)
    # Bridged verses. The 1919 text prints some consecutive verses as one passage
    # ("{{verse|1|20}} {{verse|1|21}}text"): the marker for the first is followed
    # by the marker for the next with nothing between. The text belongs to the
    # FIRST verse of the bridge (as the corpus's other CUV does), and the rest are
    # kept as empty verses so the numbering stays the standard 31,102.
    for i in range(len(verses) - 1, -1, -1):
        if verses[i]["raw"] == "" and i + 1 < len(verses) \
                and verses[i + 1]["chapter"] == verses[i]["chapter"]:
            verses[i]["raw"], verses[i + 1]["raw"] = verses[i + 1]["raw"], ""
            verses[i]["bridged"] = True
    for i, v in enumerate(verses):                 # chain heads already moved; tails stay empty
        v.setdefault("bridged", False)
    out = []
    for v in verses:
        raw = v["raw"]
        raw, found = split_notes(raw)
        notes = []
        for n in found:
            for rx, rep_ in PLAIN:
                n = rx.sub(rep_, n)
            notes.append(re.sub(r"\s+", "", n.replace("\u3000", "")))
        for rx, rep_ in PLAIN:
            raw = rx.sub(rep_, raw)
        raw = raw.replace("　", "")                    # the "Shen" honorific gap
        text = re.sub(r"\s+", "", raw)
        if LEFTOVER.search(text):
            problems.append(f"markup left in {USFM[book_no-1]} {v['chapter']}:{v['verse']}: {text[:60]}")
        out.append((v["chapter"], v["verse"], text, notes, v["bridged"]))
    return out


def bridged_tail_ok(verses, book, chapter, verse):
    """True when the previous verse of this chapter exists (this verse is the tail
    of a bridge whose text sits on the first verse of the group)."""
    return bool(verses) and verses[-1]["book"] == book \
        and verses[-1]["chapter"] == chapter and verses[-1]["verse"] == verse - 1


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--output", required=True, help="flat JSON for 03_import_json_bible.py")
    ap.add_argument("--notes", help="notes ledger to write (the translators' margin notes)")
    ap.add_argument("--cache", default=os.path.join(".cache", "cuv1919"),
                    help="where raw wikitext is kept between runs")
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)

    index, idx_info = fetch(INDEX_TITLE, a.cache, "index")
    titles = re.findall(r"\*\[\[/([^|\]]+)\|", index)
    if len(titles) != 66:
        sys.exit(f"expected 66 books in the index, found {len(titles)} — the page layout changed")

    problems, verses, notes, revs = [], [], [], {}
    for n, title in enumerate(titles, 1):
        text, info = fetch(f"{INDEX_TITLE}/{title}", a.cache, f"{n:02d}")
        revs[title] = {"revid": info["revid"], "timestamp": info["timestamp"]}
        if not re.search(r"\{\{\s*pd/\d+", text, re.I):
            problems.append(f"{title}: no public-domain tag on the page")
        prev_nonempty = True
        for ch, vs, t, ns, bridged in parse_book(text, n, problems):
            # An empty verse is legitimate only as the tail of a bridge.
            if not t and prev_nonempty is False:
                pass
            elif not t and not bridged_tail_ok(verses, n, ch, vs):
                problems.append(f"empty verse {USFM[n-1]} {ch}:{vs}")
            verses.append({"book": n, "chapter": ch, "verse": vs, "text": t})
            code = USFM[n - 1]
            for i, note in enumerate(ns):
                ref = f"{code}.{ch}.{vs}"
                notes.append({
                    "id": mint_note_id(EDITION, ref, i, note),
                    "edition": EDITION, "source": EDITION.lower(),
                    "verse_refs": [{"ref": ref, "code": code, "book": n,
                                    "chapter": ch, "verse": vs}],
                    "note_index": i, "anchor": "", "text": note})

    seen = {}
    for v in verses:
        k = (v["book"], v["chapter"], v["verse"])
        if k in seen:
            problems.append(f"duplicate verse {USFM[k[0]-1]} {k[1]}:{k[2]}")
        seen[k] = 1
    print(f"{len(titles)} books, {len(verses)} verses, {len(notes)} margin notes")
    if len(verses) != 31102:
        problems.append(f"expected 31,102 verses, got {len(verses)}")

    with open(a.output, "w", encoding="utf-8") as f:
        json.dump({"metadata": {
            "converter": "01_fetch_wikisource_cuv.py",
            "source": "https://zh.wikisource.org/wiki/聖經_(和合本)",
            "retrieved": time.strftime("%Y-%m-%d"),
            "index_revision": idx_info["revid"],
            "page_revisions": revs,
            "text_sha256": hashlib.sha256("".join(v["text"] for v in verses).encode()).hexdigest(),
        }, "verses": verses}, f, ensure_ascii=False)
    print("Wrote", a.output)
    if a.notes:
        with open(a.notes, "w", encoding="utf-8") as f:
            json.dump({"metadata": {"edition": EDITION,
                                    "source": "zh.wikisource.org 聖經 (和合本)",
                                    "count": len(notes)}, "notes": notes},
                      f, ensure_ascii=False, indent=1)
            f.write("\n")
        print("Wrote", a.notes)
    if problems:
        print(f"\n{len(problems)} thing(s) to look at:")
        for p in problems[:40]:
            print("  -", p)
        sys.exit(1)
    print("Checks passed.")


if __name__ == "__main__":
    main()
