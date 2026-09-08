#!/usr/bin/env python3
"""
build_links.py
==============
Turn the scripture references already extracted into transcripts/arguments.json
into shareable reader links (the format documented in docs/link-format.md), and
write them back into arguments.json.

This is the Python mirror of the Java ReaderLink codec: a reference dict
  {"type":"bible","code":"JHN","chapter":8,"verse":58}   -> "JHN.8.58"
  {"type":"quran","surah":2,"ayah":255}                  -> "Q.2.255"
becomes a single-column deep link
  /reader?cols=1&c1.src=<default-edition>&c1.ref=<ref>

References are edition-independent; the link just needs *some* src token, so we
default Bible -> kjv (public domain, always available) and Qur'an -> q-en
(Pickthall English). The reader lets the user switch edition or add columns.

No API calls — pure transform over the existing JSON. Re-runnable / idempotent.

    python3 scripts/channels/04_build_links.py            # enrich transcripts/arguments.json in place
    python3 scripts/channels/04_build_links.py --dry-run  # print a sample, write nothing
"""

import json
import os
import sys

ARGS = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "transcripts", "arguments.json")

DEFAULT_SRC = {"bible": "kjv", "quran": "q-en"}


def ref_string(r):
    """Canonical reference string for a verse_ref dict, or None if malformed."""
    t = r.get("type")
    if t == "bible":
        code = r.get("code")
        chap = r.get("chapter")
        if not code or not chap:
            return None
        s = f"{code}.{chap}"
        if r.get("verse"):
            s += f".{r['verse']}"
        return s
    if t == "quran":
        surah = r.get("surah")
        if not surah:
            return None
        s = f"Q.{surah}"
        if r.get("ayah"):
            s += f".{r['ayah']}"
        return s
    return None


def link_for(r):
    """Relative reader deep link for a verse_ref, or None if unmappable."""
    ref = ref_string(r)
    if ref is None:
        return None
    src = DEFAULT_SRC.get(r.get("type"))
    if not src:
        return None
    return f"/reader?cols=1&c1.src={src}&c1.ref={ref}"


def enrich(entries):
    n_refs = n_links = 0
    for e in entries:
        primary = None
        for r in e.get("verse_refs", []):
            ref = ref_string(r)
            link = link_for(r)
            if ref:
                r["ref"] = ref
                n_refs += 1
            if link:
                r["link"] = link
                n_links += 1
                if primary is None:
                    primary = link
        if primary:
            e["primary_link"] = primary
    return n_refs, n_links


def main():
    dry = "--dry-run" in sys.argv
    with open(ARGS, encoding="utf-8") as f:
        entries = json.load(f)

    n_refs, n_links = enrich(entries)

    # Show a few samples
    shown = 0
    for e in entries:
        if e.get("verse_refs") and shown < 4:
            print(f"- {os.path.basename(e['source_file'])}")
            for r in e["verse_refs"]:
                print(f"    {r.get('ref','?'):<12} {r.get('link','(unmappable)')}")
            shown += 1

    print(f"\n{len(entries)} entries, {n_refs} refs, {n_links} links generated.")

    if dry:
        print("(dry run — arguments.json not modified)")
        return
    with open(ARGS, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=2)
    print(f"Wrote {ARGS}")


if __name__ == "__main__":
    main()
