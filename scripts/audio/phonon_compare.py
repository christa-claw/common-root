#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
phonon_compare.py — put Ollama's Phonon next to the Azure chapters already on disk.

A listening test, not a pipeline: it spends nothing, writes nothing the site
serves, and touches neither the ledger nor the manifest. For each language it
takes the SAME chapter text the Azure run used (from BaseX, via tts_build),
synthesises it with Phonon in ONE request (chapter-level, for the same prosody
reason tts_build.py gives), and writes a page with both players side by side
plus the numbers that decide whether Phonon is a candidate: speaking pace,
generation speed, and whether it survives the whole chapter.

What it cannot tell you is verse timing. Azure gives per-verse offsets through
SSML bookmarks; Ollama's /v1/audio/speech returns a bare WAV. That gap is the
real cost of switching, and it is not measured here.

Output: out/phonon-test/index.html (+ the audio), gitignored.

Prereqs (the maintainer's machine, not this script):
    Ollama new enough for speech models (blog post: 0.40.11), then
    ollama pull phonon phonon:fr phonon:de phonon:es     # 104-123 MB each

Usage:
    python3 scripts/audio/phonon_compare.py                 # default chapters, all four languages
    python3 scripts/audio/phonon_compare.py --lang en,fr    # a subset
    python3 scripts/audio/phonon_compare.py --chapters PSA.23,JHN.1
    python3 scripts/audio/phonon_compare.py --voice Zoey    # a voice from `ollama show phonon`
"""
import argparse, html, json, os, pathlib, shutil, subprocess, sys, time
import urllib.request, wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tts_build as tb   # fetch_chapters, normalize_for_speech, BASEX_*

REPO = pathlib.Path(__file__).resolve().parents[2]
AUDIO = pathlib.Path(os.environ.get("COMMONROOT_AUDIO_DIR", REPO.parent / "audio"))
OUT = REPO / "out" / "phonon-test"
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")

# language -> (corpus text id, Azure voice dir, Ollama model, licence of that model)
LANGS = {
    "en": ("bible-lsv",          "en-GB-AlfieNeural",     "phonon",    "Apache 2.0"),
    "fr": ("bible-fr-darby",     "fr-FR-AlainNeural",     "phonon:fr", "CC BY-NC 4.0"),
    "de": ("bible-lut1912-1912", "de-DE-ChristophNeural", "phonon:de", "CC BY-NC 4.0"),
    "es": ("bible-rvr09-1909",   "es-ES-AlvaroNeural",    "phonon:es", "CC BY-NC 4.0"),
}


def book_folder(text_id, voice, book):
    """The '19_PSA' directory, found rather than computed."""
    base = AUDIO / text_id / voice
    hits = sorted(base.glob(f"*_{book}"))
    return hits[0] if hits else None


def wav_seconds(path):
    with wave.open(str(path)) as w:
        return w.getnframes() / float(w.getframerate())


def mp3_seconds(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "default=nw=1:nk=1", str(path)],
                         capture_output=True, text=True)
    return float(out.stdout.strip()) if out.returncode == 0 and out.stdout.strip() else None


def synth(model, text, voice, dest):
    body = {"model": model, "input": text, "response_format": "wav"}
    if voice:
        body["voice"] = voice
    req = urllib.request.Request(f"{OLLAMA}/v1/audio/speech", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=900) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    return time.time() - t0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--lang", default="en,fr,de,es")
    ap.add_argument("--chapters", default="PSA.23,JHN.1", help="BOOK.chapter, comma-separated")
    ap.add_argument("--voice", default=None, help="a Phonon voice; omit for the model's default")
    args = ap.parse_args()

    try:
        urllib.request.urlopen(f"{OLLAMA}/api/version", timeout=5)
    except OSError as e:
        sys.exit(f"Ollama not reachable at {OLLAMA}: {e}")

    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for lang in [l.strip() for l in args.lang.split(",") if l.strip()]:
        text_id, az_voice, model, lic = LANGS[lang]
        for spec in args.chapters.split(","):
            book, ch = spec.strip().split(".")
            ch = int(ch)
            verses = tb.fetch_chapters(text_id, book, (ch, ch)).get(ch)
            if not verses:
                print(f"[{lang}] {spec}: no text in BaseX for {text_id}"); continue
            text = tb.normalize_for_speech(" ".join(v for _, v in verses))
            words = len(text.split())

            stem = f"{lang}_{book}{ch:03d}"
            wav = OUT / f"{stem}_phonon.wav"
            try:
                took = synth(model, text, args.voice, wav)
                p_sec = wav_seconds(wav)
            except Exception as e:           # a model not pulled, a cap, a crash: record it
                print(f"[{lang}] {spec}: Phonon failed — {e}")
                rows.append(dict(lang=lang, spec=spec, model=model, lic=lic, error=str(e)))
                continue

            az_src = None
            folder = book_folder(text_id, az_voice, book)
            if folder and (folder / f"{ch:03d}_{book}.mp3").is_file():
                az_src = folder / f"{ch:03d}_{book}.mp3"
                shutil.copyfile(az_src, OUT / f"{stem}_azure.mp3")
            a_sec = mp3_seconds(az_src) if az_src else None

            row = dict(lang=lang, spec=spec, model=model, lic=lic, words=words,
                       phonon_s=p_sec, phonon_wpm=words / (p_sec / 60), gen_s=took,
                       speed=p_sec / took if took else None,
                       azure=bool(az_src), azure_s=a_sec,
                       azure_wpm=(words / (a_sec / 60)) if a_sec else None,
                       stem=stem, azure_voice=az_voice)
            rows.append(row)
            print(f"[{lang}] {spec}: {words} words, Phonon {p_sec:.0f}s "
                  f"({row['phonon_wpm']:.0f} wpm, {row['speed']:.0f}x realtime)"
                  + (f", Azure {a_sec:.0f}s ({row['azure_wpm']:.0f} wpm)" if a_sec else ""))

    (OUT / "results.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    write_page(rows, args.voice)
    print(f"\nopen {OUT / 'index.html'}")


def write_page(rows, voice):
    def n(v, fmt="{:.0f}"):
        return fmt.format(v) if isinstance(v, (int, float)) else "–"
    cards = []
    for r in rows:
        if "error" in r:
            cards.append(f"<section><h2>{r['lang']} {html.escape(r['spec'])}</h2>"
                         f"<p class=err>Phonon ({html.escape(r['model'])}) failed: "
                         f"{html.escape(r['error'])}</p></section>")
            continue
        az = (f"<audio controls src='{r['stem']}_azure.mp3'></audio>" if r["azure"]
              else "<em>no Azure chapter on disk</em>")
        cards.append(
            f"<section><h2>{r['lang']} · {html.escape(r['spec'])}</h2>"
            f"<div class=cols><div><h3>Phonon <small>{html.escape(r['model'])} · {r['lic']}</small></h3>"
            f"<audio controls src='{r['stem']}_phonon.wav'></audio>"
            f"<p>{n(r['phonon_s'])} s · {n(r['phonon_wpm'])} wpm · made in {n(r['gen_s'], '{:.1f}')} s "
            f"({n(r['speed'])}× real time)</p></div>"
            f"<div><h3>Azure <small>{html.escape(r['azure_voice'])}</small></h3>{az}"
            f"<p>{n(r['azure_s'])} s · {n(r['azure_wpm'])} wpm</p></div></div></section>")
    page = f"""<!doctype html><meta charset=utf-8><title>Phonon vs Azure</title>
<style>body{{font:16px system-ui;max-width:900px;margin:2rem auto;padding:0 1rem}}
.cols{{display:grid;grid-template-columns:1fr 1fr;gap:1.5rem}}audio{{width:100%}}
small{{color:#666;font-weight:400}}.err{{color:#b00}}section{{border-top:1px solid #ccc;padding:.5rem 0}}</style>
<h1>Phonon vs Azure</h1><p>Voice: {html.escape(voice or 'model default')}. Same chapter text, one request
per chapter. Verse timing is not compared: Phonon returns a bare WAV.</p>{''.join(cards)}"""
    (OUT / "index.html").write_text(page, encoding="utf-8")


if __name__ == "__main__":
    main()
