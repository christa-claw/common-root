#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
tts_build.py — chapter audio with per-verse timings, generated a nibble at a time.

WHY PER CHAPTER, NOT PER VERSE. A verse synthesized on its own gets a terminal
falling contour and a fresh pitch reset, because the engine cannot know the
sentence continues. The 1776 Finnish runs sentences across verse divisions
constantly, so chained per-verse audio reads as a list of separate pronouncements
— audibly the proof-texting fragmentation this project exists to argue against.
Chapter-level synthesis keeps the prose intact.

HOW THE SYNC SURVIVES ANYWAY. Each verse is preceded by an SSML <bookmark/>; the
Speech SDK reports the audio offset where each one is reached, so every chapter
ships as <chapter>.mp3 plus <chapter>.json giving a millisecond offset per verse.
That is what the reading line (issue #2) needs to highlight and advance, with
natural prosody, at identical cost per character.

LONG CHAPTERS. The endpoint caps a request at ~10 minutes of audio. Only 12 of
the 1,189 chapters in bible-fi-1776 exceed the threshold (max 13,176 chars), so
those few are synthesized in segments split at verse boundaries and joined with
ffmpeg into ONE mp3 with ONE offsets file — a segmented chapter is
indistinguishable from any other in the output. Joins land where a verse break
already invites a pause, and later segments' offsets are shifted by the joined
file's true measured duration.

WHY A LEDGER AND A BUDGET. Azure's free grant is 500,000 characters a month and
the paid rate is per character, so an unattended loop with a bug is a bill. Every
run reads the ledger, refuses to exceed the month's budget, and records what it
spent. Nothing is ever regenerated: a chapter already on disk and in the ledger is
skipped, so re-running costs nothing.

WHY THE VOICE IS IN THE PATH. The files are served immutable with a one-year
cache; a voice change must produce new URLs, not stale hits.

WHY NOT IN THE REPO. Output defaults outside the working tree. Audio is ~1.7 GB
per Bible-sized edition, and the weekly crons abort on a dirty tree.

Credentials come from the environment (never the repo):
    AZURE_SPEECH_KEY, AZURE_SPEECH_REGION

Needs the Speech SDK; run with the venv interpreter:
    ~/.commonroot/venv-tts/bin/python

Usage:
    # what would it cost? spends nothing, calls nothing:
    .../python scripts/audio/tts_build.py --text-id bible-fi-1776 --book JHN --dry-run

    # generate, staying inside this month's budget:
    .../python scripts/audio/tts_build.py --text-id bible-fi-1776 --book JHN
"""
import argparse, base64, datetime, json, os, pathlib, re, subprocess, sys, tempfile, time
import urllib.parse, urllib.request

BASEX_URL = "http://localhost:8984/rest/religioustext"
BASEX_AUTH = base64.b64encode(b"admin:admin").decode()
NS = "http://religioustext.org/schema/1.0"

DEFAULT_OUT = os.environ.get("COMMONROOT_AUDIO_DIR", "./audio")
DEFAULT_ENV_FILE = os.environ.get("COMMONROOT_TTS_ENV_FILE", "tts.env")
DEFAULT_LEDGER = str(pathlib.Path(__file__).with_name("tts_ledger.json"))

# Azure's free grant is 500k characters/month. Sit below it so a miscount, a
# retry, or a manual run during the month cannot tip the account into billing.
DEFAULT_MONTHLY_BUDGET = 480_000

# Per-run ceiling as well: one night should never spend the whole month, so a
# mistake noticed in the morning has cost at most one night.
DEFAULT_RUN_LIMIT = 25_000
# Two Azure resources, deliberately. The F0 one is free forever but capped at
# ~500k characters a month; the S0 one draws on paid credit. Free is always
# spent first and the paid key is untouched unless --paid-cap says otherwise,
# so the default behaviour of this script can never cost money. The paid
# ceiling is a lifetime one, tracked in the ledger: a monthly cap would reset
# and protect nothing.
DEFAULT_PAID_CAP = 0

# Split a chapter into segments above this many characters. ~8.5k is roughly ten
# minutes of speech, the per-request ceiling; 12 of 1,189 chapters need it.
SEGMENT_CHARS = 7_500

# ...but only for Latin-script prose. The 600s ceiling is on AUDIO, and
# characters per second varies hugely by script: Finnish measures ~14.3
# (3,247 chars -> 219s), Mandarin at most 5.2 (3,131 chars overran 600s and
# was cut off). A dense script therefore needs a far smaller segment. These
# are deliberately pessimistic — an extra join costs a moment of ffmpeg, an
# overrun costs the whole run and the characters are billed anyway.
SEGMENT_CHARS_BY_LANG = {
    "zh": 2_000,
    "ja": 2_500,
    "he": 2_400,
    "ar": 2_800,
    "ru": 5_000,
    "de": 6_800,
    "es": 6_800,
}


def segment_chars_for(lang):
    """Max characters per request for a language tag like 'zh-CN'."""
    return SEGMENT_CHARS_BY_LANG.get((lang or "").split("-")[0].lower(),
                                     SEGMENT_CHARS)

# Speaking rate per voice, applied as SSML <prosody rate="...">. A voice absent
# here runs at its own factory default, which is what every language did until
# now. Defaults differ a lot between voices and are NOT comparable: measured
# words per minute over generated chapters came out at fi 118, ar 113 — but
# he-IL-AvriNeural at 180, a news-reader pace against everyone else's unhurried
# one. Rate is set per VOICE, not per language, because it is the voice that
# varies; add an entry only after measuring, and re-measure after changing one,
# since existing chapters keep whatever rate they were made at.
#
# 2026-09-21: he-IL-AvriNeural -20% (maintainer's call) => ~145 wpm. The 97
# chapters made before this were discarded and regenerated.
VOICE_RATE = {
    "he-IL-AvriNeural": "-20%",
}

AUDIO_FORMAT = "Audio24Khz48KBitRateMonoMp3"
# Chapter numbers are zero-padded so a directory listing sorts the way a reader
# expects — 1, 2, 10 rather than 1, 10, 2. Three digits because Psalms has 150;
# nothing in the corpus reaches four. The book code is repeated in the file name
# even though the directory already carries it, so a single downloaded file
# still says what it is.
CHAPTER_DIGITS = 3


def chapter_stem(aChapter, aBook):
    return f"{aChapter:0{CHAPTER_DIGITS}d}_{aBook}"


# Book folders carry their canonical position so a directory listing sorts in
# reading order rather than alphabetically (01_GEN, 02_EXO, ... not 1SA, DEU,
# EXO, GEN). Two digits is enough for every edition in the corpus; the widest
# is DRA at 72 books.
BOOK_DIGITS = 2


def book_dir(aBook, aOrder):
    """Folder name for one book, e.g. "01_GEN".

    The number is the book's position in THIS edition's own order as the
    corpus returns it, never a shared 66-book table: the editions genuinely
    differ - DRA carries 72 books with the deuterocanonicals appended after
    Revelation - so a fixed table would mis-number them. An unknown book
    keeps its bare code rather than being given a wrong number.
    """
    try:
        n = aOrder.index(aBook) + 1
    except ValueError:
        return aBook
    return f"{n:0{BOOK_DIGITS}d}_{aBook}"


def split_book_dir(aName):
    """("01_GEN") -> ("GEN", "01_GEN"); tolerates an unnumbered legacy name."""
    m = re.fullmatch(r"(\d+)_(.+)", aName)
    return (m.group(2), aName) if m else (aName, aName)


def xquery(query):
    url = f"{BASEX_URL}?{urllib.parse.urlencode({'query': query})}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {BASEX_AUTH}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8").strip()


def fetch_chapters(text_id, book, chapters=None):
    """{chapter: [(verse, text)]} in reading order for one book of one edition."""
    pred = ""
    if chapters:
        lo, hi = chapters
        pred = f"[number(@number) ge {lo} and number(@number) le {hi}]"
    rows = xquery(
        f"declare namespace rt='{NS}';"
        f" string-join("
        f"  for $c in db:open('religioustext')//rt:text[@id='{text_id}']"
        f"           /rt:book[@code='{book}']/rt:chapter{pred}"
        f"  for $v in $c/rt:verse"
        f"  return string-join((string($c/@number), string($v/@number),"
        f"                      normalize-space(string($v))), '&#9;'), '&#10;')"
    )
    out = {}
    for row in rows.splitlines():
        p = row.split("\t")
        if len(p) == 3 and p[0].isdigit() and p[1].isdigit() and p[2]:
            out.setdefault(int(p[0]), []).append((int(p[1]), p[2]))
    return dict(sorted(out.items()))


def fetch_books(text_id):
    """USFM codes of one edition, in document (canonical) order."""
    rows = xquery(
        f"declare namespace rt='{NS}';"
        f" string-join(db:open('religioustext')//rt:text[@id='{text_id}']"
        f"             /rt:book/@code, '&#10;')")
    return [r.strip() for r in rows.splitlines() if r.strip()]


def fetch_index(text_id):
    """[(book, chapter, chars)] for a whole edition, in canonical order.

    One query per edition instead of one per book, and it carries the
    character count so the run's budget can be planned without pulling any
    verse text. A nightly run needs a handful of chapters; enumerating
    eleven Bibles' worth of verses to find them was the wrong shape.
    """
    rows = xquery(
        f"declare namespace rt='{NS}';"
        f" string-join("
        f"  for $b in db:open('religioustext')//rt:text[@id='{text_id}']/rt:book"
        f"  for $c in $b/rt:chapter"
        f"  return string-join((string($b/@code), string($c/@number),"
        f"    string(sum(for $v in $c/rt:verse"
        f"               return string-length(normalize-space(string($v)))))),"
        f"   '&#9;'), '&#10;')")
    out = []
    for row in rows.splitlines():
        f = row.split("\t")
        if len(f) == 3 and f[1].isdigit() and f[2].isdigit():
            out.append((f[0], int(f[1]), int(f[2])))
    return out


def write_manifest(out_root):
    """Rebuild <out>/index.json from what is actually on disk.

    Scanned rather than derived from the ledger on purpose: the reader uses
    this to decide whether to offer audio, and must never advertise a chapter
    whose mp3 is missing, empty or half-written. Disk is the only honest
    source for that. Cheap enough to redo in full after every run.
    """
    root = pathlib.Path(out_root)
    texts, total = {}, 0
    for js in sorted(root.glob("*/*/*/*.json")):
        rel = js.relative_to(root).parts
        if len(rel) != 4:
            continue
        tid, voice, bookdir, name = rel
        book, bookdir = split_book_dir(bookdir)
        # "007_GEN.json" -> chapter 7. Tolerates an unpadded legacy name too.
        num = name[:-5].split("_", 1)[0]
        if not num.isdigit():
            continue
        mp3 = js.with_suffix(".mp3")
        if not mp3.is_file() or mp3.stat().st_size == 0:
            continue
        try:
            meta = json.loads(js.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        t = texts.setdefault(tid, {"voice": voice, "books": {}, "durationMs": 0})
        # Keyed by book code - the id the reader has - with the folder
        # carried alongside, so the reader never has to know how directories
        # are named either. Same contract as "file" below.
        b = t["books"].setdefault(book, {"dir": bookdir, "chapters": {},
                                         "durationMs": 0})
        d = int(meta.get("durationMs") or 0)
        # The manifest carries the actual file name, so the reader never has to
        # know the naming convention — if it changes again, only this file does.
        b["chapters"][str(int(num))] = {"file": mp3.name, "durationMs": d,
                                        "bytes": mp3.stat().st_size,
                                        "verses": len(meta.get("verses") or {})}
        b["durationMs"] += d
        t["durationMs"] += d
        total += 1
    out = {"generated": datetime.datetime.now().isoformat(timespec="seconds"),
           "pathPattern": "/audio/{text}/{voice}/{book}/{file}",
           "chapters": total, "texts": texts}
    dest = root / "index.json"
    tmp = dest.with_suffix(".json.tmp")
    root.mkdir(parents=True, exist_ok=True)
    tmp.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(dest)
    return total


def esc(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace('"', "&quot;"))


# Some editions are stored with a space between every CJK character — the CUV
# in this corpus is 49% spaces. Sent as-is the engine reads them one character
# at a time at a flat ~300ms each: John 1 came out at 901s instead of ~250s,
# and sounded like a list, not a sentence. Strip a space only when BOTH
# neighbours are CJK, so Latin words, digits and cross-references keep their
# spacing, and so this is a no-op for every non-CJK edition — and a no-op
# again if the corpus itself is ever cleaned.
_CJK = r"\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\u3000-\u303F\uFF01-\uFF60"
_CJK_SPACE = re.compile(f"(?<=[{_CJK}])[ \t]+(?=[{_CJK}])")


def normalize_for_speech(text):
    """Text as it should be SPOKEN, which is not always how it is stored."""
    return _CJK_SPACE.sub("", text)


def build_ssml(verses, voice, lang):
    """One <speak> for a run of verses, each preceded by its bookmark. The verses
       are joined as plain running text so the engine phrases across the verse
       divisions instead of stopping at each one."""
    body = "".join(f'<bookmark mark="v{n}"/>{esc(t)} ' for n, t in verses)
    # <bookmark/> offsets are still reported from inside <prosody>, so the
    # per-verse sync survives the wrapper unchanged.
    rate = VOICE_RATE.get(voice)
    if rate:
        body = f"<prosody rate='{rate}'>{body}</prosody>"
    return (f"<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
            f"xml:lang='{lang}'><voice name='{voice}'>{body}</voice></speak>")


def segments_for(verses, limit=None):
    """Split a chapter at verse boundaries so no request exceeds the audio cap."""
    limit = limit or SEGMENT_CHARS
    total = sum(len(t) for _, t in verses)
    if total <= limit:
        return [verses]
    out, cur, cur_len = [], [], 0
    for v in verses:
        if cur and cur_len + len(v[1]) > limit:
            out.append(cur)
            cur, cur_len = [], 0
        cur.append(v)
        cur_len += len(v[1])
    if cur:
        out.append(cur)
    return out


def mp3_duration_ms(path):
    """True stored duration of the file, from ffprobe.

       NOT the SDK's reported audio_duration: that is the length of the SPEECH,
       while concatenation appends whole mp3 frames including each encoder's
       delay and padding. Offsets for a later segment must be shifted by what the
       joined file actually contains, or every verse after a join drifts."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        check=True, capture_output=True, text=True).stdout.strip()
    return int(float(out) * 1000)


def synth_segment(speechsdk, cfg, ssml_text, path):
    """Synthesize one segment to an mp3. Returns {mark: offset_ms} and duration_ms."""
    audio_cfg = speechsdk.audio.AudioOutputConfig(filename=str(path))
    synth = speechsdk.SpeechSynthesizer(speech_config=cfg, audio_config=audio_cfg)
    marks = {}
    # audio_offset is in ticks of 100ns; the mark is what we wrote in the SSML.
    synth.bookmark_reached.connect(
        lambda e: marks.__setitem__(e.text, int(e.audio_offset / 10_000)))
    result = synth.speak_ssml_async(ssml_text).get()
    reason = result.reason
    if reason != speechsdk.ResultReason.SynthesizingAudioCompleted:
        detail = ""
        if reason == speechsdk.ResultReason.Canceled:
            c = result.cancellation_details
            detail = f"{c.reason}: {c.error_details}"
        raise RuntimeError(f"synthesis failed ({reason}) {detail}")
    return marks, mp3_duration_ms(path)


def concat(parts, target):
    """Join segment mp3s. Copies streams — no re-encode, no quality loss."""
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        for p in parts:
            f.write(f"file '{p}'\n")
        listfile = f.name
    try:
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "concat", "-safe", "0", "-i", listfile,
                        "-c", "copy", str(target)], check=True)
    finally:
        os.unlink(listfile)
        for p in parts:
            pathlib.Path(p).unlink(missing_ok=True)


def load_ledger(path):
    p = pathlib.Path(path)
    if not p.exists():
        return {"months": {}, "done": {}}
    led = json.loads(p.read_text(encoding="utf-8"))
    led.setdefault("months", {})
    led.setdefault("done", {})
    return led


def save_ledger(path, ledger):
    p = pathlib.Path(path)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(ledger, ensure_ascii=False, indent=1, sort_keys=True),
                   encoding="utf-8")
    tmp.replace(p)      # atomic: a killed run never leaves a half-written ledger


def main():
    ap = argparse.ArgumentParser(description="Generate chapter audio, rationed.")
    ap.add_argument("--text-id", help="e.g. bible-fi-1933 (omit with --queue)")
    ap.add_argument("--book",
                    help="USFM code, a comma-separated list worked in order "
                         "(MAT,MRK,LUK,JHN), or ALL for the whole edition")
    ap.add_argument("--queue",
                    help="TSV of 'text-id voice lang books' lines, worked "
                         "strictly top to bottom; ALL means the whole edition")
    ap.add_argument("--manifest-only", action="store_true",
                    help="rebuild <out>/index.json from disk and exit")
    ap.add_argument("--chapters", help="range, e.g. 1-3 (default: whole book)")
    ap.add_argument("--voice", default="fi-FI-HarriNeural")
    ap.add_argument("--lang", default="fi-FI")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--ledger", default=DEFAULT_LEDGER)
    ap.add_argument("--env-file", default=DEFAULT_ENV_FILE,
                    help="where to find AZURE_SPEECH_* if not in the environment")
    ap.add_argument("--monthly-budget", type=int, default=DEFAULT_MONTHLY_BUDGET)
    ap.add_argument("--run-limit", type=int, default=DEFAULT_RUN_LIMIT)
    ap.add_argument("--paid-cap", type=int, default=DEFAULT_PAID_CAP,
                    help="lifetime ceiling on PAID characters. 0 (the default) "
                         "means the paid key is never used at all")
    ap.add_argument("--dry-run", action="store_true",
                    help="report what would be spent; call nothing, write nothing")
    args = ap.parse_args()

    if args.manifest_only:
        print(f"manifest: {write_manifest(args.out)} chapters listed in "
              f"{args.out}/index.json")
        return 0

    def parse_books(spec):
        return (None if spec.upper() == "ALL"
                else [b.strip().upper() for b in spec.split(",") if b.strip()])

    entries = []
    if args.queue:
        for ln in pathlib.Path(args.queue).read_text(encoding="utf-8").splitlines():
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            f = ln.split()
            if len(f) != 4:
                print(f"queue line ignored (want 4 fields): {ln}", file=sys.stderr)
                continue
            entries.append((f[0], f[1], f[2], parse_books(f[3])))
        if not entries:
            print(f"no usable entries in {args.queue}", file=sys.stderr)
            return 2
    else:
        if not args.text_id or not args.book:
            print("need --text-id and --book, or --queue", file=sys.stderr)
            return 2
        entries = [(args.text_id, args.voice, args.lang, parse_books(args.book))]

    rng = None
    if args.chapters:
        if len(entries) > 1 or not entries[0][3] or len(entries[0][3]) > 1:
            print("--chapters only makes sense with a single book",
                  file=sys.stderr)
            return 2
        lo, _, hi = args.chapters.partition("-")
        rng = (int(lo), int(hi or lo))

    ledger = load_ledger(args.ledger)
    month = datetime.date.today().strftime("%Y-%m")
    free_month = ledger["months"].get(month, 0)
    free_ever = ledger.get("lifetime", sum(ledger["months"].values()))
    paid_ever = ledger.get("paidLifetime", 0)
    free_left = max(0, args.monthly_budget - free_month)
    paid_left = max(0, args.paid_cap - paid_ever)
    budget = min(free_left + paid_left, args.run_limit)

    # Entries, then books within an entry, are worked strictly in the order
    # given, so the queue drains front to back across nights and finishing a
    # language needs no edit anywhere.
    pending, done_already, found, truncated, scanned = [], 0, 0, False, []
    for tid, voice, lang, books in entries:
        if truncated:
            break
        by_book = {}
        for bk, ch, chars in fetch_index(tid):
            by_book.setdefault(bk, []).append((ch, chars))
        if not by_book:
            print(f"  {tid}: nothing found — check the id", file=sys.stderr)
            continue
        scanned.append(tid)
        # Canonical order for this edition, for numbering the book folders.
        order = list(by_book)
        for bk in (books if books else list(by_book)):
            if bk not in by_book:
                print(f"  {tid} {bk}: no such book", file=sys.stderr)
                continue
            root = pathlib.Path(args.out) / tid / voice / book_dir(bk, order)
            for ch, chars in by_book[bk]:
                if rng and not (rng[0] <= ch <= rng[1]):
                    continue
                found += 1
                key = f"{tid}/{voice}/{bk}/{ch}"
                mp3 = root / (chapter_stem(ch, bk) + ".mp3")
                if key in ledger["done"] and mp3.exists():
                    done_already += 1
                    continue
                pending.append((tid, voice, lang, bk, ch, chars, key, mp3))
                # Enough queued to fill this run — stop walking the corpus.
                if not args.dry_run and sum(e[5] for e in pending) >= budget:
                    truncated = True
                    break
            if truncated:
                break
        # A dry run reports one edition's full scope, not all eleven.
        if args.dry_run and pending:
            truncated = True

    if not found:
        print("nothing found for any queue entry — check the ids",
              file=sys.stderr)
        return 2

    total_chars = sum(e[5] for e in pending)
    print(f"{'+'.join(scanned)}: {found} chapters scanned, {done_already} "
          f"already done, {len(pending)} pending ({total_chars:,} chars)"
          + (f"; {len(entries) - len(scanned)} later queue entries not scanned"
             if len(scanned) < len(entries) else ""))
    print(f"free  {month}: {free_month:,} used, {free_left:,} left of "
          f"{args.monthly_budget:,}/month")
    print(f"paid  lifetime: {paid_ever:,} used, {paid_left:,} left of "
          f"{args.paid_cap:,} cap" + ("  (paid disabled)" if not args.paid_cap else ""))
    print(f"this run may spend {budget:,}")

    if args.dry_run:
        runs = (total_chars // max(1, args.run_limit)) + 1
        long_ones = [f"{e[3]} {e[4]}" for e in pending
                     if e[5] > SEGMENT_CHARS][:12]
        print(f"dry run — nothing called. About {runs} more run(s) at "
              f"{args.run_limit:,}/run."
              + (f" Segmented chapters: {long_ones}" if long_ones else ""))
        return 0

    # The launchd wrapper sources the env file; a hand-run shell does not.
    # Read it here too, so testing by hand doesn't need a ritual.
    env_file = pathlib.Path(args.env_file).expanduser()
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                os.environ.setdefault(k.strip(), v.strip())

    free_key = os.environ.get("AZURE_SPEECH_KEY")
    free_region = os.environ.get("AZURE_SPEECH_REGION")
    paid_key = os.environ.get("AZURE_SPEECH_KEY_PAID")
    paid_region = os.environ.get("AZURE_SPEECH_REGION_PAID") or free_region
    if not free_key or not free_region:
        print(f"AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set, and not in "
              f"{args.env_file} — refusing to run", file=sys.stderr)
        return 3
    if paid_left and not paid_key:
        print(f"--paid-cap given but AZURE_SPEECH_KEY_PAID is not set — "
              f"free tier only this run", file=sys.stderr)
        paid_left = 0
        budget = min(free_left, args.run_limit)
    if budget <= 0:
        print("nothing left to spend this month — free allowance used and "
              "paid is capped or disabled")
        return 0

    import azure.cognitiveservices.speech as speechsdk

    def make_cfg(k, r):
        c = speechsdk.SpeechConfig(subscription=k, region=r)
        c.set_speech_synthesis_output_format(
            getattr(speechsdk.SpeechSynthesisOutputFormat, AUDIO_FORMAT))
        return c

    cfg_free = make_cfg(free_key, free_region)
    cfg_paid = make_cfg(paid_key, paid_region) if (paid_left and paid_key) else None

    free_spent = paid_spent = made = skipped = 0
    try:
        for tid, voice, lang, bk, ch, n, lkey, mp3 in pending:
            spent = free_spent + paid_spent
            if spent + n > budget:
                print(f"budget reached after {made} chapters — stopping cleanly")
                break
            # Free allowance first, always; the paid key is a fallback and only
            # when a cap was explicitly given.
            if free_spent + n <= free_left:
                tier, cfg = "free", cfg_free
            elif cfg_paid and paid_spent + n <= paid_left:
                tier, cfg = "paid", cfg_paid
            else:
                print(f"free allowance used and no paid room — stopping after "
                      f"{made} chapters")
                break
            # Verse text is pulled only for chapters actually being made.
            verses = fetch_chapters(tid, bk, (ch, ch)).get(ch)
            if not verses:
                print(f"  {tid} {bk} {ch}: no verses returned — skipped",
                      file=sys.stderr)
                continue
            # Azure bills what is actually sent, so spend is recorded against
            # the normalized text; n (from the index) stays the planning
            # estimate and is the larger of the two, which keeps the budget
            # check conservative.
            verses = [(vn, normalize_for_speech(txt)) for vn, txt in verses]
            n_sent = sum(len(txt) for _, txt in verses)
            mp3.parent.mkdir(parents=True, exist_ok=True)

            offsets, elapsed, parts = {}, 0, []
            try:
                for i, seg in enumerate(
                        segments_for(verses, segment_chars_for(lang))):
                    part = mp3.with_suffix(f".part{i}.mp3")
                    marks, dur = synth_segment(
                        speechsdk, cfg, build_ssml(seg, voice, lang), part)
                    # Shift this segment's marks past everything already made.
                    for mark, off in marks.items():
                        offsets[mark[1:]] = elapsed + off
                    elapsed += dur
                    parts.append(str(part))
            except Exception as exc:
                # One bad chapter must not end the run. Before this, any
                # synthesis error propagated out of main(): every later chapter
                # was lost and the manifest was never rebuilt, so the queue
                # stalled on that chapter for good. Azure has already been
                # billed for whatever it processed, so the characters are
                # charged here too — over-recording is safe, under-recording is
                # the error that costs real money.
                if tier == "free":
                    free_spent += n_sent
                else:
                    paid_spent += n_sent
                for stale in parts + [str(mp3.with_suffix(f".part{len(parts)}.mp3"))]:
                    pathlib.Path(stale).unlink(missing_ok=True)
                prev = ledger.setdefault("failed", {}).get(lkey, {})
                ledger["failed"][lkey] = {
                    "count": prev.get("count", 0) + 1,
                    "chars": n_sent, "tier": tier, "error": str(exc)[:300],
                    "at": datetime.datetime.now().isoformat(timespec="seconds")}
                skipped += 1
                print(f"  {tid} {bk} {ch}: FAILED — {n:,} chars [{tier}] "
                      f"charged, moving on: {exc}", file=sys.stderr)
                continue

            # Count the spend the moment Azure has been billed — BEFORE the join.
            # If ffmpeg then fails, the chapter is retried next run (it never
            # reaches the ledger's done map), but the characters already sent must
            # not vanish from the month's total: under-recording spend is the one
            # error that silently walks the account into billing.
            if tier == "free":
                free_spent += n_sent
            else:
                paid_spent += n_sent

            if len(parts) == 1:
                pathlib.Path(parts[0]).replace(mp3)
            else:
                concat(parts, mp3)

            mp3.with_suffix(".json").write_text(json.dumps({
                "text": tid, "book": bk, "chapter": ch,
                "voice": voice, "durationMs": elapsed,
                "verses": {k: offsets[k] for k in sorted(offsets, key=int)},
            }, ensure_ascii=False, indent=1), encoding="utf-8")

            made += 1
            ledger["done"][lkey] = {
                "chars": n_sent, "tier": tier,
                "bytes": mp3.stat().st_size, "durationMs": elapsed,
                "verses": len(offsets), "segments": len(parts),
                "at": datetime.datetime.now().isoformat(timespec="seconds")}
            print(f"  {tid} {bk} {ch}: {len(offsets)}/{len(verses)} marks, "
                  f"{elapsed//1000}s, {n_sent:,} chars [{tier}]"
                  + (f" (normalized from {n:,})" if n_sent != n else ""),
                  flush=True)
            time.sleep(0.3)     # gentle on the endpoint; nothing here is urgent
    finally:
        # Record spend even if interrupted — an unrecorded charge is the one
        # bookkeeping error that costs real money next run.
        ledger["months"][month] = free_month + free_spent
        ledger["lifetime"] = free_ever + free_spent
        ledger["paidLifetime"] = paid_ever + paid_spent
        ledger.setdefault("paidMonths", {})[month] = (
            ledger.get("paidMonths", {}).get(month, 0) + paid_spent)
        save_ledger(args.ledger, ledger)

    listed = write_manifest(args.out)
    print(f"done: {made} chapters"
          + (f" ({skipped} FAILED)" if skipped else "")
          + f" — {free_spent:,} free + {paid_spent:,} paid. "
          f"Free {ledger['months'][month]:,}/{args.monthly_budget:,} this month; "
          f"paid {ledger['paidLifetime']:,}/{args.paid_cap:,} lifetime; "
          f"manifest lists {listed} chapters")
    return 0


if __name__ == "__main__":
    sys.exit(main())
