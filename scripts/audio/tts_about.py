#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
tts_about.py -- narrated audio for the About (front) page's essay sections.

Sibling to tts_build.py, which synthesizes Bible CHAPTERS verse-by-verse from
BaseX with per-verse bookmarks. This script narrates the About page's prose
instead -- sourced from the i18n UI bundles (app/src/main/resources/i18n/
translations[_xx].properties), one audio file per (locale, section). No
verse structure, so no bookmarks and no per-verse offsets file.

SHARES THE SAME LEDGER as tts_build.py (scripts/audio/tts_ledger.json) and the
same monthly free-tier / lifetime-paid budget accounting, so the two jobs can
never together spend past the real Azure Speech account limits. Ledger keys
are namespaced "about/<locale>/<section>" so they can never collide with a
chapter key ("<text-id>/<voice>/<book>/<chapter>").

Credentials: the same tts.env as tts_build.py (AZURE_SPEECH_KEY/_REGION,
optionally the _PAID pair). Never printed or logged.

Output: <out>/about/<locale>/<section>.mp3, plus <out>/about-index.json (a
flat manifest consumed by AboutAudioIndexService.java). <out> defaults to the
same directory tts_build.py writes chapters into, so the existing /audio/*
servlet (AudioStaticConfig) and the existing nightly rsync to prod pick these
files up with no further changes.

Usage:
    # what would it cost? spends nothing, calls nothing:
    .../python scripts/audio/tts_about.py --dry-run

    # generate every (locale, section) not already on disk+ledger:
    .../python scripts/audio/tts_about.py
"""
import argparse, datetime, json, os, pathlib, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tts_build as cb  # reuse esc(), normalize_for_speech(), (load|save)_ledger, VOICE_RATE, AUDIO_FORMAT, mp3_duration_ms, concat

REPO = pathlib.Path(__file__).resolve().parents[2]
I18N_DIR = REPO / "app" / "src" / "main" / "resources" / "i18n"

DEFAULT_OUT = cb.DEFAULT_OUT
DEFAULT_ENV_FILE = cb.DEFAULT_ENV_FILE
DEFAULT_LEDGER = cb.DEFAULT_LEDGER
DEFAULT_MONTHLY_BUDGET = cb.DEFAULT_MONTHLY_BUDGET
DEFAULT_PAID_CAP = cb.DEFAULT_PAID_CAP
# One run should comfortably cover the whole page once; there is no nightly
# ladder for this job (it is a one-off, run by hand), so the per-run ceiling
# is generous rather than rationed like the chapter job's 25,000.
DEFAULT_RUN_LIMIT = 300_000

VOICE_FOR_LOCALE = {
    "en": "en-US-AndrewNeural",
    "ar": "ar-SA-HamedNeural",
    "de": "de-DE-ChristophNeural",
    "es": "es-ES-AlvaroNeural",
    "fi": "fi-FI-HarriNeural",
    "fr": "fr-FR-AlainNeural",
    "he": "he-IL-AvriNeural",
    "hi": "hi-IN-MadhurNeural",
    "it": "it-IT-BenignoNeural",
    "ja": "ja-JP-KeitaNeural",
    "ru": "ru-RU-DmitryNeural",
    "sv": "sv-SE-MattiasNeural",
    "tr": "tr-TR-AhmetNeural",
    "zh": "zh-CN-YunxiNeural",
}
LANG_TAG = {
    "en": "en-US", "ar": "ar-SA", "de": "de-DE", "es": "es-ES", "fi": "fi-FI",
    "fr": "fr-FR", "he": "he-IL", "hi": "hi-IN", "it": "it-IT", "ja": "ja-JP",
    "ru": "ru-RU", "sv": "sv-SE", "tr": "tr-TR", "zh": "zh-CN",
}
LOCALES = sorted(VOICE_FOR_LOCALE)

# Keys per section, in the exact order AboutView.java renders them. Kept here
# (not derived from the view) because that is the only way to be sure of
# reading order without parsing Java; if AboutView's sections change shape,
# this map needs a matching edit -- the same trade-off CLAUDE_NOTES already
# accepts for the lineage chain list in AboutView itself.
def _rows(prefix, items, fields):
    return [f"about.{prefix}.{item}.{f}" for item in items for f in fields]

SECTION_KEYS = {
    "hero": ["about.hero.title", "about.hero.subtitle"],
    "howto": ["about.howto.title", "about.howto.intro"]
        + _rows("howto", [f"step{i}" for i in range(1, 8)], ["title", "body"]),
    "modes": ["about.modes.title", "about.modes.intro"]
        + _rows("modes", ["original", "simplified", "chapters", "verses", "titles"],
                ["label", "subtitle", "p1", "p2"]),
    "orders": ["about.orders.title", "about.orders.intro"]
        + _rows("orders", ["canonical", "events", "writing", "tanakh"],
                ["label", "subtitle", "p1", "p2"]),
    "problems": ["about.problems.title", "about.problems.intro"]
        + _rows("problems", ["isaiah", "romans", "philippians", "jeremiah", "john"],
                ["ref", "title", "p1", "p2"])
        + ["about.problems.callout.title", "about.problems.callout.p1", "about.problems.callout.p2"],
    "quran": ["about.quran.title", "about.quran.intro"]
        + _rows("quran", ["original", "standardization", "orders", "meccaMedina", "translations"],
                ["title", "p1", "p2"])
        + ["about.quran.callout.title", "about.quran.callout.p1", "about.quran.callout.p2"],
    "about": ["about.about.title", "about.about.p1", "about.about.p2", "about.about.p3"],
    "search": ["about.search.title", "about.search.body"],
}
SECTION_ORDER = ["hero", "howto", "modes", "orders", "problems", "quran", "about", "search"]


def load_properties(path):
    """Tolerant .properties reader: direct UTF-8, no \\uXXXX escapes in this
       repo's bundles (confirmed by inspection), key=value with optional
       whitespace around '='. Comments ('#' or '!') and blanks are skipped."""
    props = {}
    if not path.exists():
        return props
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip("\n")
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        if "=" not in line:
            continue
        key, _, val = line.partition("=")
        props[key.strip()] = val.strip()
    return props


def bundle_for(locale):
    base = load_properties(I18N_DIR / "translations.properties")
    if locale == "en":
        return base
    over = load_properties(I18N_DIR / f"translations_{locale}.properties")
    merged = dict(base)
    merged.update({k: v for k, v in over.items() if v})
    return merged


def section_parts(props, section):
    return [props[k].strip() for k in SECTION_KEYS[section] if props.get(k, "").strip()]


def build_ssml(parts, voice, lang):
    body = '<break time="650ms"/>'.join(cb.esc(cb.normalize_for_speech(p)) for p in parts)
    rate = cb.VOICE_RATE.get(voice)
    if rate:
        body = f"<prosody rate='{rate}'>{body}</prosody>"
    return (f"<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
            f"xml:lang='{lang}'><voice name='{voice}'>{body}</voice></speak>")


def chunks_for(parts, limit):
    """Split a section's parts into runs that fit the per-request character cap,
       never splitting a single part (a part is a paragraph/heading, atomic)."""
    out, cur, cur_len = [], [], 0
    for p in parts:
        if cur and cur_len + len(p) > limit:
            out.append(cur)
            cur, cur_len = [], 0
        cur.append(p)
        cur_len += len(p)
    if cur:
        out.append(cur)
    return out or [[]]


def synth_one(speechsdk, cfg, parts, voice, lang, path):
    ssml = build_ssml(parts, voice, lang)
    audio_cfg = speechsdk.audio.AudioOutputConfig(filename=str(path))
    synth = speechsdk.SpeechSynthesizer(speech_config=cfg, audio_config=audio_cfg)
    result = synth.speak_ssml_async(ssml).get()
    if result.reason != speechsdk.ResultReason.SynthesizingAudioCompleted:
        detail = ""
        if result.reason == speechsdk.ResultReason.Canceled:
            c = result.cancellation_details
            detail = f"{c.reason}: {c.error_details}"
        raise RuntimeError(f"synthesis failed ({result.reason}) {detail}")
    return cb.mp3_duration_ms(path)


def synth_section(speechsdk, cfg, parts, voice, lang, path):
    limit = cb.segment_chars_for(lang)
    total = sum(len(p) for p in parts)
    if total <= limit:
        return synth_one(speechsdk, cfg, parts, voice, lang, path)
    groups = chunks_for(parts, limit)
    seg_paths = [path.with_suffix(f".part{i}.mp3") for i in range(len(groups))]
    try:
        for gp, sp in zip(groups, seg_paths):
            synth_one(speechsdk, cfg, gp, voice, lang, sp)
        cb.concat([str(p) for p in seg_paths], path)  # concat() removes the parts itself
    finally:
        for sp in seg_paths:
            sp.unlink(missing_ok=True)
    return cb.mp3_duration_ms(path)


def write_index(out_root, done_map):
    """Rebuild about-index.json from disk (not trusted from the ledger), same
       discipline as tts_build.write_manifest: a file only appears here when
       its mp3 genuinely exists."""
    about_root = pathlib.Path(out_root) / "about"
    sections = {}
    voices = {}
    for locale_dir in sorted(about_root.glob("*")) if about_root.is_dir() else []:
        if not locale_dir.is_dir():
            continue
        locale = locale_dir.name
        by_section = {}
        for mp3 in sorted(locale_dir.glob("*.mp3")):
            by_section[mp3.stem] = {"file": mp3.name}
        if by_section:
            sections[locale] = by_section
            voices[locale] = VOICE_FOR_LOCALE.get(locale, "")
    manifest = {
        "generated": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "voices": voices,
        "sections": sections,
    }
    idx_path = pathlib.Path(out_root) / "about-index.json"
    tmp = idx_path.with_suffix(".tmp")
    tmp.write_text(json.dumps(manifest, ensure_ascii=False, indent=1, sort_keys=True),
                   encoding="utf-8")
    tmp.replace(idx_path)
    n = sum(len(v) for v in sections.values())
    return idx_path, n


def main():
    ap = argparse.ArgumentParser(description="Generate About-page narration audio, rationed.")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--ledger", default=DEFAULT_LEDGER)
    ap.add_argument("--env-file", default=DEFAULT_ENV_FILE)
    ap.add_argument("--monthly-budget", type=int, default=DEFAULT_MONTHLY_BUDGET)
    ap.add_argument("--run-limit", type=int, default=DEFAULT_RUN_LIMIT)
    ap.add_argument("--paid-cap", type=int, default=DEFAULT_PAID_CAP)
    ap.add_argument("--locales", help="comma-separated subset, default all 14")
    ap.add_argument("--sections", help="comma-separated subset, default all 8")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--manifest-only", action="store_true")
    args = ap.parse_args()

    out_root = pathlib.Path(args.out).resolve()

    if args.manifest_only:
        path, n = write_index(out_root, {})
        print(f"manifest: {n} (locale, section) pair(s) listed in {path}")
        return 0

    locales = [l.strip() for l in args.locales.split(",")] if args.locales else LOCALES
    sections = [s.strip() for s in args.sections.split(",")] if args.sections else SECTION_ORDER
    unknown_l = [l for l in locales if l not in VOICE_FOR_LOCALE]
    unknown_s = [s for s in sections if s not in SECTION_KEYS]
    if unknown_l or unknown_s:
        print(f"unknown locale(s) {unknown_l} or section(s) {unknown_s}", file=sys.stderr)
        return 2

    ledger = cb.load_ledger(args.ledger)
    month = datetime.date.today().strftime("%Y-%m")
    free_month = ledger["months"].get(month, 0)
    free_ever = ledger.get("lifetime", sum(ledger["months"].values()))
    paid_ever = ledger.get("paidLifetime", 0)
    free_left = max(0, args.monthly_budget - free_month)
    paid_left = max(0, args.paid_cap - paid_ever)
    budget = min(free_left + paid_left, args.run_limit)

    pending = []  # (locale, section, parts, chars, key, path)
    done_already = 0
    for locale in locales:
        props = bundle_for(locale)
        for section in sections:
            parts = section_parts(props, section)
            if not parts:
                print(f"  {locale}/{section}: no narratable text found -- skipped", file=sys.stderr)
                continue
            key = f"about/{locale}/{section}"
            path = out_root / "about" / locale / f"{section}.mp3"
            chars = sum(len(p) for p in parts)
            if key in ledger["done"] and path.exists():
                done_already += 1
                continue
            pending.append((locale, section, parts, chars, key, path))

    total_chars = sum(e[3] for e in pending)
    print(f"about-page narration: {len(locales)} locale(s) x {len(sections)} section(s), "
          f"{done_already} already done, {len(pending)} pending ({total_chars:,} chars)")
    print(f"free  {month}: {free_month:,} used, {free_left:,} left of {args.monthly_budget:,}/month")
    print(f"paid  lifetime: {paid_ever:,} used, {paid_left:,} left of {args.paid_cap:,} cap"
          + ("  (paid disabled)" if not args.paid_cap else ""))
    print(f"this run may spend {budget:,}")

    if args.dry_run:
        print("dry run -- nothing called.")
        return 0

    if not pending:
        print("nothing pending.")
        return 0

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
              f"{args.env_file} -- refusing to run", file=sys.stderr)
        return 3
    if paid_left and not paid_key:
        print("--paid-cap given but AZURE_SPEECH_KEY_PAID is not set -- free tier only this run",
              file=sys.stderr)
        paid_left = 0
        budget = min(free_left, args.run_limit)
    if budget <= 0:
        print("nothing left to spend this month -- free allowance used and paid is capped or disabled")
        return 0

    import azure.cognitiveservices.speech as speechsdk

    def make_cfg(k, r):
        c = speechsdk.SpeechConfig(subscription=k, region=r)
        c.set_speech_synthesis_output_format(
            getattr(speechsdk.SpeechSynthesisOutputFormat, cb.AUDIO_FORMAT))
        return c

    cfg_free = make_cfg(free_key, free_region)
    cfg_paid = make_cfg(paid_key, paid_region) if (paid_left and paid_key) else None

    free_spent = paid_spent = made = 0
    try:
        for locale, section, parts, n, key, path in pending:
            spent = free_spent + paid_spent
            if spent + n > budget:
                print(f"budget reached after {made} section(s) -- stopping cleanly")
                break
            if free_spent + n <= free_left:
                tier, cfg = "free", cfg_free
            elif cfg_paid and paid_spent + n <= paid_left:
                tier, cfg = "paid", cfg_paid
            else:
                print(f"free allowance used and no paid room -- stopping after {made} section(s)")
                break
            voice = VOICE_FOR_LOCALE[locale]
            lang = LANG_TAG[locale]
            path.parent.mkdir(parents=True, exist_ok=True)
            try:
                dur_ms = synth_section(speechsdk, cfg, parts, voice, lang, path)
            except Exception as exc:
                print(f"  {locale}/{section}: FAILED -- {exc}", file=sys.stderr)
                ledger.setdefault("failed", {})[key] = str(exc)
                cb.save_ledger(args.ledger, ledger)
                continue
            if tier == "free":
                free_spent += n
            else:
                paid_spent += n
            made += 1
            ledger["done"][key] = {"chars": n, "durationMs": dur_ms, "tier": tier}
            ledger["months"][month] = ledger["months"].get(month, 0) + (n if tier == "free" else 0)
            ledger["lifetime"] = ledger.get("lifetime", 0) + (n if tier == "free" else 0)
            if tier == "paid":
                ledger["paidLifetime"] = ledger.get("paidLifetime", 0) + n
            cb.save_ledger(args.ledger, ledger)  # atomic per-section, same as chapters
            print(f"  {locale}/{section}: {n:,} chars ({tier}), {dur_ms/1000:.1f}s")
    finally:
        idx_path, n = write_index(out_root, {})
        print(f"manifest: {n} (locale, section) pair(s) listed in {idx_path}")

    print(f"done: {made} section(s) -- {free_spent:,} free + {paid_spent:,} paid. "
          f"Free {ledger['months'].get(month,0):,}/{args.monthly_budget:,} this month; "
          f"paid {ledger.get('paidLifetime',0):,}/{args.paid_cap:,} lifetime")
    return 0


if __name__ == "__main__":
    sys.exit(main())
