#!/usr/bin/env python3
"""
Local (Ollama) variant of extract_arguments.py.

Same arguments.json output contract — DataSeeder consumes it unchanged — but the
per-transcript analysis runs on a LOCAL model via Ollama instead of the Anthropic
API. Zero marginal cost; runs on the Mac's GPU. Designed to grind in the
background for days/weeks; resumable and incremental.

Reuses all the matching/mapping logic from extract_arguments.py (Bible book
codes, LDS name->code table, D&C pattern, filename verse detection, vtt cleanup,
channel traditions, skip filter), so the two stay in lock-step. The only
difference is the model call and that the model returns book NAMES (more reliable
for a local model than USFM codes) which we map to codes in Python.

Requires: Ollama running locally with the model pulled (default gemma4:latest).
  ollama serve            # usually already running
  ollama pull gemma4:latest

Usage:
  python3 extract_arguments_ollama.py                                  # all channels, resumable
  python3 extract_arguments_ollama.py --channels "Vlad Savchuk,Apologia Studios"
  python3 extract_arguments_ollama.py --limit 5                        # smoke test
  OLLAMA_MODEL=llama3.2:latest python3 extract_arguments_ollama.py     # faster, rougher

Output: transcripts/arguments.json (same file/shape as the API extractor).
"""
import argparse
import glob
import json
import os
import re
import signal
import time
import urllib.request

# Reuse the validated maps + helpers (its `import anthropic` is harmless here —
# the same interpreter that runs extract_arguments.py has the package; we never
# call its API function). Single source of truth for book codes etc.
import importlib
ea = importlib.import_module("02_extract_arguments")
from comment_id import mint_comment_id

OLLAMA_URL   = os.environ.get("OLLAMA_URL", "http://localhost:11434/api/chat")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "gemma4:latest")

TRANSCRIPTS_DIR = ea.TRANSCRIPTS_DIR
OUTPUT_FILE     = ea.OUTPUT_FILE
VIDEO_ID_RE     = re.compile(r"\[([A-Za-z0-9_-]{11})\]")

# Graceful pause. extract_ctl.sh stop sends SIGTERM; the main loop notices this
# flag at the next item boundary, saves, and exits cleanly — so pausing never
# loses more than the one in-flight video and never corrupts arguments.json.
_STOP = False
def _request_stop(signum, frame):
    global _STOP
    _STOP = True
signal.signal(signal.SIGTERM, _request_stop)
signal.signal(signal.SIGINT, _request_stop)


def video_url_from(path):
    """https://www.youtube.com/watch?v=<id> from the [id] in the filename."""
    m = VIDEO_ID_RE.findall(os.path.basename(path))
    return "https://www.youtube.com/watch?v=" + m[-1] if m else None


def build_prompt(text, channel, tradition, verse_ref):
    hint = ""
    if verse_ref:
        if verse_ref["type"] == "bible":
            hint = f"The video title references {verse_ref['code']} {verse_ref['chapter']}:{verse_ref['verse']}. "
        elif verse_ref["type"] == "lds":
            hint = (f"The video title references {verse_ref['code']} "
                    f"{verse_ref['chapter']}:{verse_ref['verse']} (Latter-day Saint scripture). ")
        elif verse_ref["type"] == "quran":
            hint = f"The video title references Qur'an {verse_ref['surah']}:{verse_ref['ayah']}. "
    return f"""You are analysing a transcript from the YouTube channel "{channel}" ({tradition} perspective).
{hint}
Extract structured argument data for a comparative religious-texts study platform.

TRANSCRIPT (first 3000 chars):
{text[:3000]}

Return ONLY a JSON object with exactly these fields:
{{
  "verse_refs": [
    {{"type": "bible|quran|lds", "book": "book NAME e.g. John, 1 Corinthians, 1 Nephi, Doctrine and Covenants", "chapter": 0, "verse": 0}}
  ],
  "argument_summary": "2-3 sentence neutral summary of the specific scriptural argument",
  "argument_type": "contextual|translation|prophecy|theological|comparative|historical",
  "useful": true
}}

Rules:
- The summary must describe the SPEAKER'S OWN argument. Speakers often quote a verse, an opposing view, or another tradition's claim in order to respond to it — attribute such material as quoted/contested ("the speaker argues against the claim that…"), NEVER as the speaker's own assertion. Sanity check: the speaker argues from a {tradition} perspective.
  Example: a transcript quotes "according to the Quran, Christians call Jesus the son of God because they imitate the pagans" and the speaker then rebuts it with Gospel witnesses.
  WRONG summary: "The speaker argues that Christians call Jesus the son of God because they are imitating pagans."
  RIGHT summary: "Responding to Surah 9:30's charge that Christians imitate pagans, the speaker argues Jesus is called the Son of God because Gospel witnesses identified him as such."
- Always give the book NAME, never a code.
- type "lds" for Latter-day Saint scripture: Book of Mormon (1 Nephi, 2 Nephi, Jacob, Enos, Jarom, Omni, Words of Mormon, Mosiah, Alma, Helaman, 3 Nephi, 4 Nephi, Mormon, Ether, Moroni), the Doctrine and Covenants (book="Doctrine and Covenants", chapter=the section number), and the Pearl of Great Price (Moses, Abraham, Joseph Smith-Matthew, Joseph Smith-History, Articles of Faith).
- type "quran" for the Qur'an: put the surah number in "chapter" and the ayah in "verse".
- Set useful=false for reaction videos, news, testimonies, worship sets, or anything not making a specific argument about specific verses."""


def resolve_ref(ref):
    """Model ref (type+book+chapter+verse) -> arguments.json ref shape with a
    stable code. Returns None if the book can't be mapped (dropped)."""
    if not isinstance(ref, dict):
        # gemma4 occasionally emits bare strings ("John 3:16") in verse_refs
        # despite the schema in the prompt; drop them like unmappable books.
        return None
    t = ref.get("type")
    if t == "quran":
        surah = _int(ref.get("surah") or ref.get("chapter"))
        ayah  = _int(ref.get("ayah") or ref.get("verse"))
        if surah == 0 or ayah == 0:
            return None
        return {"type": "quran", "surah": surah, "ayah": ayah}
    book = ea._norm(ref.get("book", ""))
    chapter, verse = _int(ref.get("chapter")), _int(ref.get("verse"))
    if t == "lds":
        code = ea.LDS_NAME_TO_CODE.get(book)
    else:
        code = ea.BOOK_CODES.get(book)
        t = "bible"
    if not code:
        return None
    return {"type": t, "code": code, "chapter": chapter, "verse": verse}


def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def ollama_extract(text, channel, tradition, verse_ref, model, timeout=300):
    """Call the local model, force JSON, parse, normalise to the output contract.
    Returns a dict or None."""
    prompt = build_prompt(text, channel, tradition, verse_ref)
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "format": "json",
        "stream": False,
        # No num_predict cap: gemma4 is a reasoning model and spends tokens
        # thinking before emitting the (format-constrained) JSON; a low cap
        # exhausts the budget mid-thought and returns empty (done_reason=length).
        # num_ctx raised from Ollama's 4096 default: prompt (~3500 chars) +
        # reasoning + JSON output must all fit, else the JSON is truncated
        # mid-stream (parse errors) or generation drags into the timeout.
        "options": {"temperature": 0, "num_ctx": 8192},
    }).encode()

    for attempt in (1, 2):
        try:
            req = urllib.request.Request(OLLAMA_URL, data=body,
                                         headers={"Content-Type": "application/json"})
            resp = json.load(urllib.request.urlopen(req, timeout=timeout))
            raw = (resp.get("message") or {}).get("content", "").strip()
            if not raw:
                continue
            data = json.loads(raw)
        except Exception as e:
            if attempt == 2:
                print(f"    ollama/parse error: {e}")
                return None
            time.sleep(1)
            continue

        refs = []
        for vr in (data.get("verse_refs") or []):
            try:
                r = resolve_ref(vr)
            except Exception as e:
                print(f"    bad verse_ref dropped ({e}): {vr!r}")
                r = None
            if r:
                refs.append(r)
        return {
            "verse_refs": refs,
            "argument_summary": data.get("argument_summary", ""),
            "argument_type": data.get("argument_type", ""),
            "tradition": tradition,
            "channel": channel,
            "useful": bool(data.get("useful")),
        }
    return None


def main():
    ap = argparse.ArgumentParser(description="Extract verse arguments locally via Ollama.")
    ap.add_argument("--channels", help="comma-separated channel folder names (default: all)")
    ap.add_argument("--limit", type=int, default=0, help="stop after N model calls (smoke test)")
    ap.add_argument("--model", default=OLLAMA_MODEL, help=f"Ollama model (default {OLLAMA_MODEL})")
    args = ap.parse_args()

    only = {c.strip() for c in args.channels.split(",")} if args.channels else None

    results, existing = [], set()
    if os.path.exists(OUTPUT_FILE):
        with open(OUTPUT_FILE) as f:
            results = json.load(f)
        existing = {r["source_file"] for r in results if "source_file" in r}
        print(f"Resuming: {len(existing)} already processed")

    print(f"Model: {args.model}  |  channels: {args.channels or 'all'}")
    processed = skipped = errors = calls = 0

    for channel in sorted(os.listdir(TRANSCRIPTS_DIR)):
        cdir = os.path.join(TRANSCRIPTS_DIR, channel)
        if not os.path.isdir(cdir) or channel.startswith('.') or channel == "__pycache__":
            continue
        if only and channel not in only:
            continue
        tradition = ea.CHANNEL_TRADITION.get(channel, "Unknown")
        vtts = sorted(glob.glob(os.path.join(cdir, "**", "*.vtt"), recursive=True))
        # The same video often exists on disk twice — "Title [videoID].en.vtt"
        # and "Title.en.vtt" — because fetch output templates changed over time.
        # Keep only the [id] variant (it carries the id video_url_from() needs
        # for the YouTube link) and skip the bare twin, so each video yields
        # exactly one ledger entry and one comment. Bare files WITHOUT an [id]
        # twin are still processed normally.
        id_twin_bare = {re.sub(r"\s*\[[A-Za-z0-9_-]{11}\]", "", v)
                        for v in vtts if VIDEO_ID_RE.search(os.path.basename(v))}
        dupes = [v for v in vtts
                 if not VIDEO_ID_RE.search(os.path.basename(v)) and v in id_twin_bare]
        if dupes:
            vtts = [v for v in vtts if v not in set(dupes)]
            print(f"  ({len(dupes)} bare duplicates of [id]-named files skipped)")
        print(f"\n{channel} ({tradition}) — {len(vtts)} files")

        for vtt in vtts:
            rel = os.path.relpath(vtt, TRANSCRIPTS_DIR)
            if rel in existing:
                continue
            if any(k in os.path.basename(vtt).lower() for k in ea.SKIP_KEYWORDS):
                results.append({"source_file": rel, "useful": False})
                existing.add(rel)
                skipped += 1
                continue

            verse_ref = ea.extract_verse_from_filename(vtt)
            text = ea.vtt_to_text(vtt)
            if not text or len(text) < 200:
                skipped += 1
                continue

            print(f"  {os.path.basename(vtt)[:70]}")
            res = ollama_extract(text, channel, tradition, verse_ref, args.model)
            calls += 1

            if res:
                res["source_file"] = rel
                res["filename_verse_ref"] = verse_ref
                vu = video_url_from(vtt)
                if vu:
                    res["video_url"] = vu
                # Mint the permalink id at CREATION (comment_id.py), ref-guarded
                # like the backfill: ref-less entries can't seed, so they get no
                # id. Keyed on source_file here (video_id is an enrichment field
                # that doesn't exist yet at this point) — fine, because an id is
                # immutable once minted and is never re-derived.
                if res.get("verse_refs"):
                    res["id"] = mint_comment_id(res)
                results.append(res)
                existing.add(rel)
                if res.get("useful"):
                    processed += 1
                    print(f"    [{res.get('argument_type','?')}] refs={len(res['verse_refs'])} "
                          f"{res.get('argument_summary','')[:70]}")
                else:
                    skipped += 1
            else:
                # Ledger the failure so it isn't retried from scratch on every
                # restart (temperature-0 failures are deterministic and cost up
                # to 2x the timeout each). Same shape as a skip entry, plus an
                # error flag — strip "error" entries from arguments.json to
                # re-attempt them after a fix.
                results.append({"source_file": rel, "useful": False, "error": True})
                existing.add(rel)
                errors += 1

            if (processed + skipped + errors) % 10 == 0:
                _save(results)
            if _STOP:
                _save(results)
                print("\nPause requested — saved cleanly, exiting.")
                _summary(results, skipped, errors)
                return
            if args.limit and calls >= args.limit:
                print(f"\n--limit {args.limit} reached.")
                _save(results)
                _summary(results, skipped, errors)
                return

    _save(results)
    _summary(results, skipped, errors)


def _save(results):
    # Atomic write: never leave a half-written arguments.json if paused/killed
    # mid-save (it's the tracked seed the app image is built from).
    tmp = OUTPUT_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    os.replace(tmp, OUTPUT_FILE)


def _summary(results, skipped, errors):
    useful = sum(1 for r in results if r.get("useful"))
    print(f"\nDone. useful={useful}  skipped={skipped}  errors={errors}  total={len(results)}")
    print(f"Output: {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
