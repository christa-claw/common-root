#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
build_docs.py — Living documentation pipeline for Common Root?
==============================================================

Two phases:

  1. REFRESH  — regenerate the AUTOGEN blocks inside docs/src/*.md from the
                current project state (pom.xml, channels.properties, BaseX).
  2. RENDER   — convert each markdown source to PDF, DOCX and PPTX.

The markdown files in docs/src/ are the SINGLE SOURCE OF TRUTH. Prose is
hand-written; tables/lists that drift with the project live between markers:

    <!-- AUTOGEN:translations -->
    ...generated table...
    <!-- /AUTOGEN:translations -->

Only the content between matching markers is touched; everything else is left
exactly as written.

Usage:
    python3 build_docs.py                 # refresh autogen + render all formats
    python3 build_docs.py --refresh-only  # update markdown autogen blocks, no render
    python3 build_docs.py --render-only    # render current markdown, skip refresh
    python3 build_docs.py --formats pdf    # restrict output formats (pdf,docx,pptx)

Requirements (run on a machine that has these — e.g. the Mac, not a sandbox):
    - pandoc                       (markdown -> docx, and markdown -> pptx)
    - LibreOffice (soffice)        (docx -> pdf)
    - requests (pip)               (only if --refresh hits BaseX; optional)

If a renderer is missing, that format is skipped with a warning rather than
failing the whole build.
"""

import argparse
import configparser
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import date

# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
SRC_DIR      = os.path.join(SCRIPT_DIR, "src")
OUT_DIR      = SCRIPT_DIR  # binaries land alongside the existing docs

POM_FILE      = os.path.join(PROJECT_ROOT, "pom.xml")
CHANNELS_FILE = os.path.join(PROJECT_ROOT, "channels.properties")

# Chapter-audio manifest. Written by scripts/audio/tts_build.py and kept OUTSIDE
# the repo on purpose (audio is ~1.7 GB per edition and the weekly crons abort on
# a dirty tree), so this is an absolute path rather than a repo-relative one, and
# overridable for a machine that mounts the volume elsewhere.
AUDIO_INDEX = os.environ.get(
    "COMMONROOT_AUDIO_INDEX", "audio/index.json")

APP_NAME = "Common Root?"

# BaseX connection (used only by the translations/ingestion autogen blocks)
BASEX_URL  = os.environ.get("BASEX_URL", "http://localhost:8984")
BASEX_DB   = os.environ.get("BASEX_DB", "religioustext")
BASEX_USER = os.environ.get("BASEX_USER", "admin")
BASEX_PASS = os.environ.get("BASEX_PASS", "admin")

# Channel folder -> tradition. Keys must match the channels.properties `.folder`
# value exactly (a mismatch silently falls through to "Unknown"). Christa's
# classifications (2026-07-22).
CHANNEL_TRADITION = {
    "Dr Zakir Naik": "Islamic", "Ali Dawah": "Islamic", "DUS Dawah": "Islamic",
    "Mohammed Hijab": "Islamic", "Let the Quran Speak": "Islamic",
    "DAWAH BRO'S PODCAST": "Islamic", "DawahWise": "Islamic",
    "Apologetics Roadshow": "Christian", "GodLogic Apologetics": "Christian",
    "Hatun Tash DCCI Ministries": "Christian", "Shamounian Explains": "Christian",
    "Israel Advocacy": "Christian", "The Crucible": "Christian",
    # A fully-neutral debate platform — arguments belong to the guests, not the host.
    "Modern Day Debate": "Neutral",
    "JihadWatchVideo": "Critical", "Raymond Ibrahim": "Critical",
}


# ── Phase 1: AUTOGEN refresh ───────────────────────────────────────────────────

def pom_version():
    """Read <version> from the parent pom.xml."""
    try:
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        tree = ET.parse(POM_FILE)
        root = tree.getroot()
        v = root.find("m:version", ns)
        return v.text.strip() if v is not None else "unknown"
    except Exception as e:
        print(f"  ! could not read pom version: {e}")
        return "unknown"


def gen_meta():
    """Project metadata block."""
    return (
        f"| Field | Value |\n"
        f"|---|---|\n"
        f"| Application | {APP_NAME} |\n"
        f"| Version | {pom_version()} |\n"
        f"| Generated | {date.today().isoformat()} |\n"
    )


def gen_channels():
    """Channel table from channels.properties."""
    cfg = configparser.ConfigParser()
    cfg.read(CHANNELS_FILE)
    if "channels" not in cfg:
        return "_No channels configured._\n"

    rows = []
    for key, value in cfg["channels"].items():
        if "." in key:
            continue
        folder = cfg["channels"].get(f"{key}.folder", key)
        types  = cfg["channels"].get(f"{key}.types", "videos,streams")
        trad   = CHANNEL_TRADITION.get(folder, "Unknown")
        rows.append((folder, trad, types))

    rows.sort(key=lambda r: (r[1], r[0]))
    out = ["| Channel | Tradition | Content |", "|---|---|---|"]
    out += [f"| {f} | {t} | {ty} |" for (f, t, ty) in rows]
    out.append("")
    out.append(f"_{len(rows)} channels configured._")
    return "\n".join(out) + "\n"


def gen_translations():
    """
    Translation/ingestion table from BaseX. Falls back to a static note if
    BaseX is unreachable (e.g. building docs offline).
    """
    try:
        import requests
        from requests.auth import HTTPBasicAuth
    except ImportError:
        return "_BaseX query skipped (install `requests` to enable live ingestion stats)._\n"

    query = (
        'declare namespace rt="http://religioustext.org/schema/1.0";'
        f"for $doc in db:open('{BASEX_DB}')/rt:text "
        "return string-join(("
        "  string($doc/@id), string($doc/@lang),"
        "  string(count($doc//rt:book)), string(count($doc//rt:verse))"
        "), '|')"
    )
    body = (f'<query xmlns="http://basex.org/rest"><text>'
            f'{query.replace("&", "&amp;").replace("<", "&lt;")}'
            f'</text></query>')
    try:
        import requests
        from requests.auth import HTTPBasicAuth
        r = requests.post(f"{BASEX_URL}/rest/{BASEX_DB}", data=body.encode("utf-8"),
                          headers={"Content-Type": "application/xml"},
                          auth=HTTPBasicAuth(BASEX_USER, BASEX_PASS), timeout=10)
        r.raise_for_status()
        lines = [l for l in r.text.strip().splitlines() if l.strip()]
    except Exception as e:
        return f"_BaseX unreachable at build time ({e}); ingestion table not refreshed._\n"

    if not lines:
        return "_No documents found in BaseX._\n"

    out = ["| Document | Language | Books | Verses |", "|---|---|---|---|"]
    total_v = 0
    for line in sorted(lines):
        parts = line.split("|")
        if len(parts) >= 4:
            doc, lang, books, verses = parts[0], parts[1], parts[2], parts[3]
            out.append(f"| `{doc}` | {lang} | {books} | {verses} |")
            try: total_v += int(verses)
            except ValueError: pass
    out.append("")
    out.append(f"_{len(lines)} translations, {total_v:,} verses total._")
    return "\n".join(out) + "\n"


def _chapter_ranges(numbers):
    """[1,2,3,5,9,10] -> "1-3, 5, 9-10". Chapters are made a few a night, so a
       book's list is long, mostly contiguous, and unreadable spelled out."""
    runs, start, prev = [], None, None
    for n in numbers:
        if start is None:
            start = prev = n
        elif n == prev + 1:
            prev = n
        else:
            runs.append((start, prev))
            start = prev = n
    if start is not None:
        runs.append((start, prev))
    return ", ".join(str(a) if a == b else f"{a}\u2013{b}" for a, b in runs)


def _listening_time(ms):
    """Milliseconds -> "1 h 43 min" / "34 min" / "48 s"."""
    total = int(round((ms or 0) / 1000.0))
    hours, rest = divmod(total, 3600)
    minutes, seconds = divmod(rest, 60)
    if hours:
        return f"{hours} h {minutes:02d} min"
    if minutes:
        return f"{minutes} min"
    return f"{seconds} s"


def gen_audio():
    """
    Chapter-audio coverage, read from the generator's manifest — the same file the
    reader itself uses to decide which chapters to offer, so this table cannot
    claim audio the site does not have. Falls back to a note when the manifest is
    absent (e.g. building the docs somewhere that does not mount the audio volume).
    """
    try:
        with open(AUDIO_INDEX, encoding="utf-8") as handle:
            index = json.load(handle)
    except FileNotFoundError:
        return (f"_No audio manifest at `{AUDIO_INDEX}`; coverage table not "
                "refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._\n")
    except (OSError, ValueError) as exc:
        return f"_Audio manifest unreadable ({exc}); coverage table not refreshed._\n"

    texts = index.get("texts") or {}
    rows, chapters_total, ms_total = [], 0, 0
    for text_id in sorted(texts):
        text = texts[text_id] or {}
        voice = text.get("voice") or "?"
        books = text.get("books") or {}
        for book in sorted(books):
            entry = books[book] or {}
            numbers = sorted(int(c) for c in (entry.get("chapters") or {})
                             if str(c).isdigit())
            if not numbers:
                continue
            duration = entry.get("durationMs") or 0
            rows.append(f"| `{text_id}` | {voice} | {book} | "
                        f"{_chapter_ranges(numbers)} ({len(numbers)}) | "
                        f"{_listening_time(duration)} |")
            chapters_total += len(numbers)
            ms_total += duration

    if not rows:
        return "_No chapter audio has been generated yet._\n"

    out = ["| Edition | Voice | Book | Chapters with audio | Listening time |",
           "|---|---|---|---|---|"] + rows + [""]
    editions = len(texts)
    out.append(f"_{chapters_total} chapters across {editions} "
               f"edition{'' if editions == 1 else 's'}, "
               f"{_listening_time(ms_total)} of audio. Manifest generated "
               f"{index.get('generated', 'unknown')}._")
    return "\n".join(out) + "\n"


GENERATORS = {
    "meta":         gen_meta,
    "channels":     gen_channels,
    "translations": gen_translations,
    "audio":        gen_audio,
}


def refresh_markdown(md_path):
    """Replace each AUTOGEN block in a markdown file with freshly generated content."""
    with open(md_path, encoding="utf-8") as f:
        text = f.read()

    def replace(match):
        name = match.group(1)
        gen  = GENERATORS.get(name)
        if not gen:
            print(f"  ! unknown AUTOGEN block '{name}' in {os.path.basename(md_path)} — left as-is")
            return match.group(0)
        print(f"  ✓ refreshed AUTOGEN:{name} in {os.path.basename(md_path)}")
        return f"<!-- AUTOGEN:{name} -->\n{gen()}<!-- /AUTOGEN:{name} -->"

    pattern = re.compile(
        r"<!-- AUTOGEN:(\w+) -->.*?<!-- /AUTOGEN:\1 -->", re.DOTALL)
    new_text = pattern.sub(replace, text)

    if new_text != text:
        with open(md_path, "w", encoding="utf-8") as f:
            f.write(new_text)


def refresh_all():
    if not os.path.isdir(SRC_DIR):
        print(f"  ! source dir missing: {SRC_DIR}")
        return
    for fn in sorted(os.listdir(SRC_DIR)):
        if fn.endswith(".md"):
            refresh_markdown(os.path.join(SRC_DIR, fn))


# ── Phase 2: RENDER ────────────────────────────────────────────────────────────

def have(cmd):
    return shutil.which(cmd) is not None


def render_docx(md_path, out_path):
    if not have("pandoc"):
        print("  ! pandoc not found — skipping DOCX")
        return False
    # No --toc: pandoc emits a docx TOC *field*, which headless LibreOffice
    # never updates when converting to PDF — so every PDF showed an empty
    # "Table of Contents" heading. The docs are short; skip the TOC entirely.
    subprocess.run(["pandoc", md_path, "-o", out_path], check=True)
    print(f"  ✓ {os.path.basename(out_path)}")
    return True


def render_pptx(md_path, out_path):
    if not have("pandoc"):
        print("  ! pandoc not found — skipping PPTX")
        return False
    # pandoc treats level-1/2 headers as slide boundaries
    subprocess.run(["pandoc", md_path, "-o", out_path,
                    "--slide-level=2"], check=True)
    print(f"  ✓ {os.path.basename(out_path)}")
    return True


def render_pdf(md_path, out_path, docx_path):
    """PDF via LibreOffice converting the DOCX (keeps styling consistent)."""
    soffice = shutil.which("soffice") or shutil.which("libreoffice")
    if not soffice:
        print("  ! LibreOffice not found — skipping PDF")
        return False
    if not os.path.exists(docx_path):
        if not render_docx(md_path, docx_path):
            return False
    subprocess.run([soffice, "--headless", "--convert-to", "pdf",
                    "--outdir", os.path.dirname(out_path), docx_path], check=True)
    print(f"  ✓ {os.path.basename(out_path)}")
    return True


# Maps a source markdown stem to the output basename (matching the existing files)
DOC_MAP = {
    "overview":  "religious-texts-overview",
    "technical": "religious-texts-technical",
}


def render_all(formats):
    for fn in sorted(os.listdir(SRC_DIR)):
        if not fn.endswith(".md"):
            continue
        stem = fn[:-3]
        # A language variant is named "<doc>.<lang>.md" e.g. "overview.fi.md".
        doc, _, lang = stem.partition(".")
        base = DOC_MAP.get(doc, doc) + (("." + lang) if lang else "")
        md_path  = os.path.join(SRC_DIR, fn)
        docx_path = os.path.join(OUT_DIR, base + ".docx")
        print(f"\nRendering {fn} -> {base}.*")
        if lang:
            # Translated variant: PDF only (docx is just the LibreOffice intermediate).
            render_pdf(md_path, os.path.join(OUT_DIR, base + ".pdf"), docx_path)
            if os.path.exists(docx_path):
                os.remove(docx_path)
            continue
        if "docx" in formats or "pdf" in formats:
            render_docx(md_path, docx_path)
        if "pptx" in formats:
            render_pptx(md_path, os.path.join(OUT_DIR, base + ".pptx"))
        if "pdf" in formats:
            render_pdf(md_path, os.path.join(OUT_DIR, base + ".pdf"), docx_path)
        if "docx" not in formats and os.path.exists(docx_path):
            # docx was only needed as a PDF intermediate
            os.remove(docx_path)


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="Build Common Root? documentation")
    ap.add_argument("--refresh-only", action="store_true", help="update markdown autogen blocks only")
    ap.add_argument("--render-only",  action="store_true", help="render current markdown only")
    ap.add_argument("--formats", default="pdf,docx,pptx", help="comma-separated: pdf,docx,pptx")
    args = ap.parse_args()

    formats = {f.strip() for f in args.formats.split(",") if f.strip()}

    if not args.render_only:
        print("Refreshing AUTOGEN blocks from project state...")
        refresh_all()

    if not args.refresh_only:
        print(f"\nRendering formats: {', '.join(sorted(formats))}")
        render_all(formats)

    print("\nDone.")


if __name__ == "__main__":
    main()
