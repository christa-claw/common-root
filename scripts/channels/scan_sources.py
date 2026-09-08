#!/usr/bin/env python3
"""
scan_sources.py
===============
Inventory which hadith collections, Qur'an translations / readings, and major
tafsir / sira works the transcripts cite — to guide what to ingest next.

Pure text scan, no API. Designed to run in the BACKGROUND (see run_scan.sh);
prints progress as it goes and writes transcripts/mentioned_sources.md at the end.

Fast strategy (avoids the 44-full-passes slowness that hung earlier):
  Stage 1 — ONE combined `grep -rliE` over ~22k .vtt files to collect the small
            subset that mentions ANY tracked source (early-exits per file).
  Stage 2 — read only those candidate files once each in Python and classify by
            per-source regex (file-level counts, since auto-captions repeat lines).

Matching is conservative: ambiguous common words (Muslim, Malik, Ali, Ahmad)
require a distinctive multi-word form.

    python3 scripts/channels/scan_sources.py            # foreground
    ./run_scan.sh                      # background (recommended)
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TRANSCRIPTS = os.path.join(ROOT, "transcripts")

HADITH = {
    "Sahih al-Bukhari":       r"bukhari",
    "Sahih Muslim":           r"sahih muslim|saheeh muslim",
    "Sunan Abu Dawud":        r"abu daw(o|u)?d|abu dawood",
    "Jami at-Tirmidhi":       r"tirmidhi|tirmizi",
    "Sunan an-Nasa'i":        r"nasa'?i",
    "Sunan Ibn Majah":        r"ibn maj(ah|a)",
    "Muwatta Malik":          r"muwatta",
    "Musnad Ahmad":           r"musnad|ibn hanbal",
    "Mishkat al-Masabih":     r"mishkat",
    "Riyad as-Salihin":       r"riyad(h)? as[- ]?salih|riyad us[- ]?salih",
    "Bulugh al-Maram":        r"bulugh al[- ]?maram",
    "Sunan al-Bayhaqi":       r"bayhaqi",
    "Al-Adab al-Mufrad":      r"adab al[- ]?mufrad",
    "Shamail Muhammadiyah":   r"shama'?il",
    "al-Tabarani":            r"tabarani",
    "Sunan al-Darimi":        r"darimi",
}

QURAN = {
    "Pickthall":              r"pickthall",
    "Yusuf Ali":              r"yusuf ali|yousuf ali|abdullah yusuf",
    "Sahih International":     r"sahih international|saheeh international",
    "Shakir":                 r"shakir",
    "Hilali-Khan":            r"hilali|muhsin khan",
    "Arberry":                r"arberry",
    "Muhammad Asad":          r"muhammad asad",
    "Maududi":                r"maududi|maudoodi|mawdudi",
    "Daryabadi":              r"daryabadi",
    "Clear Qur'an (Khattab)": r"clear qur'?an|khattab",
    "Abdel Haleem":           r"abdel haleem|abdul haleem",
    "Taqi Usmani":            r"usmani",
    "Qarai":                  r"qarai",
    "Ghali":                  r"ghali",
    "Hafs (an Asim)":         r"\bhafs\b",
    "Warsh (an Nafi)":        r"\bwarsh\b",
    "Uthmani script":         r"uthmani",
    "Qira'at (readings)":     r"qira'?at|qiraat|variant reading",
}

TAFSIR_SIRA = {
    "Tafsir Ibn Kathir":      r"ibn kathir",
    "Tafsir al-Tabari":       r"\btabari\b",
    "Tafsir al-Qurtubi":      r"qurtubi",
    "Tafsir al-Jalalayn":     r"jalalayn",
    "Maariful Qur'an":        r"maariful",
    "Sira (Ibn Ishaq)":       r"ibn ishaq",
    "Sira (Ibn Hisham)":      r"ibn hisham",
    "Asbab al-Nuzul":         r"asbab a[ln][- ]?nuzul",
}

GROUPS = [("Hadith collections", HADITH),
          ("Qur'an translations & readings", QURAN),
          ("Tafsir & sira (commentary / biography)", TAFSIR_SIRA)]

TAG_RE = re.compile(r"<[^>]*>")


def log(msg):
    print(msg, flush=True)


def find_candidates():
    """Stage 1: one combined grep pass -> files mentioning ANY tracked source."""
    combined = "|".join(p for _, g in GROUPS for p in g.values())
    log("Stage 1: combined grep over transcripts (one pass)...")
    try:
        r = subprocess.run(
            ["grep", "-rliE", "--include=*.vtt", combined, TRANSCRIPTS],
            capture_output=True, text=True, timeout=1800)
        files = [ln for ln in r.stdout.splitlines() if ln.strip()]
    except Exception as e:
        log(f"grep failed: {e}")
        files = []
    log(f"Stage 1: {len(files)} candidate transcripts.")
    return files


def classify(files):
    """Stage 2: read each candidate once, record which sources it mentions."""
    compiled = {label: re.compile(pat, re.IGNORECASE)
                for _, g in GROUPS for label, pat in g.items()}
    hits = {label: {"files": set(), "example": None} for label in compiled}
    total = len(files)
    for i, path in enumerate(files, 1):
        if i % 200 == 0 or i == total:
            log(f"Stage 2: {i}/{total} files classified...")
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                text = TAG_RE.sub("", f.read())
        except Exception:
            continue
        for label, rx in compiled.items():
            m = rx.search(text)
            if m:
                hits[label]["files"].add(path)
                if hits[label]["example"] is None:
                    s = max(0, m.start() - 30)
                    snippet = " ".join(text[s:m.end() + 30].split())
                    hits[label]["example"] = snippet
    return hits


def render(hits, n_candidates):
    out = ["# Texts Mentioned in Transcripts", "",
           f"Generated by `scan_sources.py` (file-level counts over "
           f"{n_candidates} candidate transcripts found among ~22k). This is an "
           "ingestion to-do list, not a precise concordance — verify before "
           "acting, especially short-name matches.", ""]
    for title, group in GROUPS:
        out += [f"## {title}", "",
                "| Source | Transcripts | Example mention |", "|---|---:|---|"]
        rows = [(len(hits[l]["files"]), l, hits[l]["example"] or "")
                for l in group if hits[l]["files"]]
        rows.sort(key=lambda r: (-r[0], r[1]))
        if not rows:
            out.append("| _(none found)_ | | |")
        for cnt, label, ex in rows:
            ex = ex.replace("|", "\\|")[:80]
            out.append(f"| {label} | {cnt} | …{ex}… |")
        out.append("")
    return "\n".join(out)


def main():
    files = find_candidates()
    hits = classify(files)
    dest = os.path.join(TRANSCRIPTS, "mentioned_sources.md")
    with open(dest, "w", encoding="utf-8") as f:
        f.write(render(hits, len(files)))
    log(f"DONE. Wrote {dest}")
    # console summary
    for title, group in GROUPS:
        found = sorted(((len(hits[l]["files"]), l) for l in group if hits[l]["files"]),
                       reverse=True)
        log(f"{title}: " + (", ".join(f"{l} ({c})" for c, l in found) if found else "(none)"))


if __name__ == "__main__":
    main()
