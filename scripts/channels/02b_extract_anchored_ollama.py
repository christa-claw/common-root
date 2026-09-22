#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
02b_extract_anchored_ollama.py — verse-anchored, whole-transcript extraction.

The problem this fixes
----------------------
02_extract_arguments.py and its Ollama twin both send the model `text[:3000]`
— the first 3000 characters and nothing else. Median transcript is ~5k chars
but the mean is ~24k and p90 is ~74k, so on a long debate the model was
summarising the introduction. It shows in the ledger: 46% of "useful" entries
carry no verse ref at all, and the whole corpus yielded ~0.39 refs per video.

The approach
------------
Scan the WHOLE transcript with verse_scan (pure regex, no model), cluster the
citations into windows, and send only those windows to the model. Windows whose
citations are all chapter-only are dropped before the call (they cannot seed —
see verse_level), which removes 41% of them. Measured over a 500-video random
sample: ~1.24 model calls per video for ~5.5x the SEEDABLE verse refs. A blind
chunked sweep would cost ~8-10x for the same coverage.

Two further wins fall out of anchoring:
  * The scanner's refs are regex-verified, so they are used as the AUTHORITATIVE
    verse_refs. The model no longer has to map book names to codes (the step
    that silently dropped refs in the old path); it only writes the summary.
  * Every window carries a timestamp, so each argument gets a &t= deep link
    straight to the moment it is made.

Additive by construction
------------------------
Existing entries are never read, rewritten or re-minted. New entries carry
"pass": "anchored" and that marker is the resume key, so this can run alongside
the nightly job without touching a single existing permalink. mint_comment_id
keys on (video, refs, summary), so a new argument from an already-processed
video naturally gets its own id.

Usage:
  python3 02b_extract_anchored_ollama.py --limit 20        # smoke test
  python3 02b_extract_anchored_ollama.py --only-zero-ref   # the 8,951 backlog
  python3 02b_extract_anchored_ollama.py --channels "Apologia Studios"
  python3 02b_extract_anchored_ollama.py --dry-run         # scan only, no model
"""
import argparse
import glob
import importlib
import json
import os
import re
import signal
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

ea = importlib.import_module("02_extract_arguments")
oll = importlib.import_module("02_extract_arguments_ollama")
import verse_scan as vs
from comment_id import mint_comment_id

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434/api/chat")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "gemma4:latest")

TRANSCRIPTS_DIR = ea.TRANSCRIPTS_DIR
OUTPUT_FILE = ea.OUTPUT_FILE
VIDEO_ID_RE = re.compile(r"\[([A-Za-z0-9_-]{11})\]")

PASS_NAME = "anchored"

_STOP = False


def _request_stop(signum, frame):
    global _STOP
    _STOP = True


signal.signal(signal.SIGTERM, _request_stop)
signal.signal(signal.SIGINT, _request_stop)


def ref_label(ref):
    """Human-readable citation, for the prompt hint."""
    if ref["type"] == "quran":
        a = ref.get("ayah", 0)
        return f"Qur'an {ref['surah']}:{a}" if a else f"Qur'an surah {ref['surah']}"
    v = ref.get("verse", 0)
    base = f"{ref['code']} {ref['chapter']}"
    return f"{base}:{v}" if v else base


def build_window_prompt(window, channel, tradition):
    """Prompt for ONE window. The refs are already known and verified, so the
    model is asked for the argument, not for the citation."""
    cites = ", ".join(ref_label(r) for r in window["refs"])
    mm, ss = divmod(int(window["start_s"]), 60)
    return f"""You are analysing an excerpt from the YouTube channel "{channel}" ({tradition} perspective).

This excerpt begins at {mm}:{ss:02d} and is the part of the video where these scriptures are cited: {cites}

EXCERPT:
{window['text']}

Return ONLY a JSON object with exactly these fields:
{{
  "argument_summary": "2-3 sentence neutral summary of the specific scriptural argument made in THIS excerpt",
  "argument_type": "contextual|translation|prophecy|theological|comparative|historical",
  "useful": true
}}

Rules:
- Summarise ONLY the argument made in this excerpt, not the video as a whole.
- The summary must describe the SPEAKER'S OWN argument. Speakers often quote a verse, an opposing view, or another tradition's claim in order to respond to it — attribute such material as quoted/contested ("the speaker argues against the claim that…"), NEVER as the speaker's own assertion. Sanity check: the speaker argues from a {tradition} perspective.
  Example: a transcript quotes "according to the Quran, Christians call Jesus the son of God because they imitate the pagans" and the speaker then rebuts it with Gospel witnesses.
  WRONG summary: "The speaker argues that Christians call Jesus the son of God because they are imitating pagans."
  RIGHT summary: "Responding to Surah 9:30's charge that Christians imitate pagans, the speaker argues Jesus is called the Son of God because Gospel witnesses identified him as such."
- Name the cited scripture in the summary where it reads naturally.
- Set useful=false if the excerpt merely mentions the reference in passing (a reading, a list of citations, an aside) without making an argument about it."""


def ollama_window(window, channel, tradition, model, timeout=300):
    """Call the local model for one window. Returns dict or None."""
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user",
                      "content": build_window_prompt(window, channel, tradition)}],
        "format": "json",
        "stream": False,
        "options": {"temperature": 0, "num_ctx": 8192},
    }).encode()

    for attempt in (1, 2):
        try:
            req = urllib.request.Request(
                OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
            resp = json.load(urllib.request.urlopen(req, timeout=timeout))
            raw = (resp.get("message") or {}).get("content", "").strip()
            if not raw:
                continue
            return json.loads(raw)
        except Exception as e:
            if attempt == 2:
                print(f"    ollama/parse error: {e}")
                return None
            time.sleep(1)
    return None


def verse_level(refs):
    """Only the refs that name an actual verse.

    A chapter-only citation ("Romans 9", no verse) is a fine ANCHOR — it tells
    us where in the transcript the discussion happens — but it cannot seed a
    comment: DataSeeder.addLedgerRefs drops any ref with verse == 0 (and any
    Qur'an ref with ayah == 0), so emitting them would create comments that
    attach to nothing. They locate the window; they do not leave it.
    """
    return [r for r in refs if (r.get("verse") or r.get("ayah") or 0) > 0]


def to_entry(window, res, rel, channel, tradition, video_url, index):
    """Build an arguments.json entry from a window + the model's summary.

    verse_refs come from the SCANNER, not the model: they were matched by regex
    against a known book table, so they are already valid codes. This is the
    step that used to lose refs when the model invented or misspelled a name.
    """
    link = f"{video_url}&t={window['start_s']}s" if video_url else None

    refs = []
    for r in verse_level(window["refs"]):
        if r["type"] == "quran":
            ref = {"type": "quran", "surah": r["surah"], "ayah": r["ayah"]}
        else:
            ref = {"type": r["type"], "code": r["code"],
                   "chapter": r["chapter"], "verse": r["verse"]}
        # timecode_s / video_link live on the REF, not the entry: that is the
        # shape 03_enrich_arguments writes and the only one DataSeeder reads
        # (it calls vr.get("video_link") per reference). Putting the link on
        # the entry alone would silently lose every deep link at seed time.
        ref["timecode_s"] = window["start_s"]
        if link:
            ref["video_link"] = link
        refs.append(ref)

    entry = {
        "source_file": rel,
        "pass": PASS_NAME,
        "window_index": index,
        "timestamp_s": window["start_s"],
        "verse_refs": refs,
        "argument_summary": (res.get("argument_summary") or "").strip(),
        "argument_type": res.get("argument_type", ""),
        "tradition": tradition,
        "channel": channel,
        "useful": bool(res.get("useful")),
    }
    if video_url:
        entry["video_url"] = video_url
        entry["video_link"] = link
    if refs and entry["argument_summary"]:
        entry["id"] = mint_comment_id(entry)
    return entry


def load_ledger_from(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return json.load(f)


def save_ledger(results):
    tmp = OUTPUT_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    os.replace(tmp, OUTPUT_FILE)


def main():
    ap = argparse.ArgumentParser(description="Verse-anchored whole-transcript extraction.")
    ap.add_argument("--channels", help="comma-separated channel folder names (default: all)")
    ap.add_argument("--limit", type=int, default=0, help="stop after N model calls")
    ap.add_argument("--model", default=OLLAMA_MODEL)
    ap.add_argument("--only-zero-ref", action="store_true",
                    help="only videos whose existing entry is useful but ref-less")
    ap.add_argument("--dry-run", action="store_true",
                    help="scan and report windows; make no model calls and write nothing")
    ap.add_argument("--output", help="write to this file instead of arguments.json "
                                     "(pilot runs, so the live ledger and the "
                                     "nightly job cannot collide)")
    ap.add_argument("--window-chars", type=int, default=1500)
    ap.add_argument("--max-windows", type=int, default=60,
                    help="per-video cap, so one 5-hour stream cannot eat a night. "
                         "40 clipped the marquee long-form debates (Brown vs Rabbi, "
                         "Wood vs O'Connor); 60 covers them.")
    ap.add_argument("--allow-chapter-only", action="store_true",
                    help="also process windows whose citations name no verse. They "
                         "cannot seed (DataSeeder drops verse==0), so this is for "
                         "diagnostics, not production runs.")
    args = ap.parse_args()

    only = {c.strip() for c in args.channels.split(",")} if args.channels else None

    # A pilot reads the real ledger (so resume and --only-zero-ref work against
    # real data) but writes somewhere else entirely.
    global OUTPUT_FILE
    read_from = OUTPUT_FILE
    if args.output:
        OUTPUT_FILE = os.path.abspath(args.output)
        print(f"Writing to {OUTPUT_FILE} (reading ledger from {read_from})")

    results = load_ledger_from(read_from)
    done = {r["source_file"] for r in results
            if r.get("pass") == PASS_NAME and "source_file" in r}
    print(f"Ledger: {len(results)} entries | already anchored: {len(done)} files")

    targets = None
    if args.only_zero_ref:
        targets = {r["source_file"] for r in results
                   if r.get("pass") != PASS_NAME
                   and r.get("useful") and not r.get("verse_refs")}
        print(f"--only-zero-ref: {len(targets)} candidate files")

    calls = new_entries = scanned = no_anchor = chapter_only = 0

    for channel in sorted(os.listdir(TRANSCRIPTS_DIR)):
        cdir = os.path.join(TRANSCRIPTS_DIR, channel)
        if not os.path.isdir(cdir) or channel.startswith(".") or channel == "__pycache__":
            continue
        if only and channel not in only:
            continue
        tradition = ea.CHANNEL_TRADITION.get(channel, "Unknown")
        vtts = sorted(glob.glob(os.path.join(cdir, "**", "*.vtt"), recursive=True))

        # Same bare/[id] duplicate rule as the Ollama extractor: keep the [id]
        # variant (it carries the id the YouTube link needs) and drop its twin.
        id_twin_bare = {re.sub(r"\s*\[[A-Za-z0-9_-]{11}\]", "", v)
                        for v in vtts if VIDEO_ID_RE.search(os.path.basename(v))}
        vtts = [v for v in vtts
                if VIDEO_ID_RE.search(os.path.basename(v)) or v not in id_twin_bare]

        printed = False
        for vtt in vtts:
            rel = os.path.relpath(vtt, TRANSCRIPTS_DIR)
            if rel in done:
                continue
            if targets is not None and rel not in targets:
                continue

            windows = vs.scan_vtt(vtt, window_chars=args.window_chars,
                                  max_windows=args.max_windows)
            scanned += 1
            if not windows:
                # Sentinel: a scanned file with no citations. Without this the
                # 60% of videos that cite nothing would be re-scanned on every
                # restart. Mirrors the skip-ledger style of the Ollama pass.
                no_anchor += 1
                if not args.dry_run:
                    results.append({"source_file": rel, "pass": PASS_NAME,
                                    "useful": False, "no_anchors": True})
                    done.add(rel)
                continue

            if not printed:
                print(f"\n{channel} ({tradition})")
                printed = True
            print(f"  {os.path.basename(vtt)[:66]} — {len(windows)} window(s)")

            if args.dry_run:
                for w in windows:
                    print(f"      t={w['start_s']:6d}s  "
                          f"{', '.join(ref_label(r) for r in w['refs'])[:88]}")
                continue

            video_url = oll.video_url_from(vtt)
            for i, w in enumerate(windows):
                # A window whose citations are all chapter-only cannot produce a
                # seedable entry, so it is not worth a model call. Measured on a
                # 500-video sample this skips 41% of windows.
                if not args.allow_chapter_only and not verse_level(w["refs"]):
                    chapter_only += 1
                    continue
                res = ollama_window(w, channel, tradition, args.model)
                calls += 1
                if not res:
                    continue
                entry = to_entry(w, res, rel, channel, tradition, video_url, i)
                if not entry["argument_summary"]:
                    continue
                results.append(entry)
                new_entries += 1
                if entry["useful"]:
                    print(f"    [{entry.get('argument_type','?')}] t={w['start_s']}s "
                          f"refs={len(entry['verse_refs'])} "
                          f"{entry['argument_summary'][:64]}")

                if _STOP or (args.limit and calls >= args.limit):
                    break

            done.add(rel)
            if new_entries and new_entries % 10 == 0:
                save_ledger(results)
            if _STOP:
                save_ledger(results)
                print("\nPause requested — saved cleanly.")
                return _summary(scanned, no_anchor, chapter_only, calls, new_entries, args.dry_run)
            if args.limit and calls >= args.limit:
                save_ledger(results)
                print(f"\n--limit {args.limit} reached.")
                return _summary(scanned, no_anchor, chapter_only, calls, new_entries, args.dry_run)

    if not args.dry_run:
        save_ledger(results)
    _summary(scanned, no_anchor, chapter_only, calls, new_entries, args.dry_run)


def _summary(scanned, no_anchor, chapter_only, calls, new_entries, dry_run=False):
    print(f"\nScanned {scanned} files | {no_anchor} with no citations | "
          f"{chapter_only} windows skipped (chapter-only) | "
          f"{calls} model calls | {new_entries} new entries")
    # Under --dry-run nothing is written at all, so naming an output file here
    # would claim a write that never happened.
    if dry_run:
        print("Dry run — nothing written.")
    else:
        print(f"Output: {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
