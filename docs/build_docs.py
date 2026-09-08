#!/usr/bin/env python3
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


GENERATORS = {
    "meta":         gen_meta,
    "channels":     gen_channels,
    "translations": gen_translations,
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
