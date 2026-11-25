# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
read_sefaria.py — Sefaria v3 JSON drops (sources/commentaries/sefaria/<slug>/*.json)
-> commentary_lib.Commentary, one per language.

Shapes met in the 33 works fetched so far (sectionNames, depth of version.text):
  Chapter/Verse/Comment|Paragraph, 3  -> comment k on chapter c, verse v   (most)
  Chapter/Passuk, 2                    -> one comment per verse            (Harchev Davar)
  Chapter/Paragraph/Comment, 3         -> chapter-level sections            (Alshich)
  Paragraph, 1                         -> the work's introduction           (book intro)
  Siman/Paragraph, 2                   -> keyed to Sifra sections, not to verses:
                                          cannot be anchored, dropped and counted
When a work carries two versions of one book in one language (a PD printing and
a vocalized/community edition), the version with more comments is primary and
the other only fills verses the primary leaves empty, tagged with @source.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from commentary_lib import ORDER, Comment, Commentary, to_paragraphs, _ALIAS, _norm

FOOTNOTE_MARKER = re.compile(r"<sup[^>]*class=\"footnote-marker\"[^>]*>.*?</sup>", re.S)
LEMMA = re.compile(r"^\s*<(b|strong)>(.*?)</\1>", re.S)


def _exact_book(name: str) -> str | None:
    return _ALIAS.get(_norm(name)) if name else None


def base_book(doc: dict) -> str | None:
    """'Abarbanel on Torah, Deuteronomy' / 'Ibn Ezra on Psalms' / 'Malbim on Leviticus, Achrei Mot' -> USFM."""
    ref = doc.get("ref") or doc.get("book") or ""
    parts = [p.strip() for p in ref.split(",")]
    for p in reversed(parts):
        if _exact_book(p):
            return _exact_book(p)
    for p in parts:
        if " on " in p and _exact_book(p.rsplit(" on ", 1)[1]):
            return _exact_book(p.rsplit(" on ", 1)[1])
    return None


def _segments(cell):
    """Flatten a cell (str or nested lists) to its string segments in order."""
    if cell is None:
        return []
    if isinstance(cell, str):
        return [cell] if cell.strip() else []
    out = []
    for c in cell:
        out.extend(_segments(c))
    return out


def _comment(seg: str, source: str | None = None) -> Comment | None:
    seg = FOOTNOTE_MARKER.sub("", seg)
    paras = to_paragraphs(seg)
    if not paras:
        return None
    c = Comment(paras=paras, source=source)
    m = LEMMA.match(seg)
    if m:
        lemma = re.sub(r"<[^>]+>", "", m.group(2)).strip(" .:-—")
        if 0 < len(lemma) <= 200:
            c.lemma = lemma
    return c


def _verse_map(doc: dict, version: dict, out: Commentary, code: str) -> dict:
    """-> {(chapter, verse): [segment, ...]} for verse-keyed shapes, or {} with
    intros added directly for the shapes that are not verse-keyed."""
    names = tuple(doc.get("sectionNames") or ())
    text = version.get("text") or []
    vt = version.get("versionTitle")
    if names == ("Paragraph",):
        paras = []
        for seg in _segments(text):
            paras += to_paragraphs(FOOTNOTE_MARKER.sub("", seg))
        title = doc.get("ref") or "Introduction"
        out.add_intro(code, None, Comment(paras=paras, heading=title.split(",")[-1].strip() or "Introduction",
                                          source=vt))
        return {}
    if names == ("Siman", "Paragraph"):
        out.drop(f"not verse-keyed ({doc.get('ref')})", len(_segments(text)))
        return {}
    if names[:2] == ("Chapter", "Paragraph"):          # chapter-level sections (Alshich)
        for ci, chap in enumerate(text, start=1):
            for section in (chap or []):
                paras = []
                for seg in _segments(section):
                    paras += to_paragraphs(FOOTNOTE_MARKER.sub("", seg))
                if paras:
                    out.add_intro(code, ci, Comment(paras=paras, source=vt))
        return {}
    vm = {}
    for ci, chap in enumerate(text, start=1):
        if not isinstance(chap, list):
            continue
        for vi, cell in enumerate(chap, start=1):
            segs = _segments(cell)
            if segs:
                vm[(ci, vi)] = segs
    return vm


def read(work_dir: Path, lang: str) -> tuple[Commentary, dict]:
    """All files of one language in a Sefaria work dir -> one Commentary (meta left
    for the caller) plus facts for the catalog/report."""
    out = Commentary(id="", abbr="", title="", lang=lang, iso3="")
    by_book: dict[str, list[tuple[dict, dict, Path]]] = {}
    intro_docs = []
    licences, sources = [], []
    for f in sorted(work_dir.glob("*.json")):
        doc = json.loads(f.read_text(encoding="utf-8"))
        for version in doc.get("versions") or []:
            if (version.get("actualLanguage") or version.get("language")) != lang:
                continue
            code = base_book(doc)
            if tuple(doc.get("sectionNames") or ()) == ("Paragraph",) or not code:
                intro_docs.append((doc, version, f))
            else:
                by_book.setdefault(code, []).append((doc, version, f))
    if not by_book and not intro_docs:
        raise ValueError(f"no {lang!r} versions in {work_dir}")

    for code, items in by_book.items():
        maps = []
        for doc, version, f in items:
            vm = _verse_map(doc, version, out, code)
            maps.append((sum(len(s) for s in vm.values()), vm, version))
            if not vm:   # a non-verse-keyed shape added intros (or was dropped) itself
                _credit(version, licences, sources)
        maps.sort(key=lambda m: -m[0])
        primary_vt = maps[0][2].get("versionTitle") if maps else None
        filled: set = set()
        for rank, (_n, vm, version) in enumerate(maps):
            vt = version.get("versionTitle")
            used = False
            for (ci, vi), segs in sorted(vm.items()):
                if (ci, vi) in filled:
                    if rank:
                        out.drop("verse already covered by the primary version", len(segs))
                    continue
                filled.add((ci, vi))
                used = True
                for seg in segs:
                    c = _comment(seg, vt if (rank and vt != primary_vt) else None)
                    if c:
                        out.add(code, ci, vi, c)
            if used:
                _credit(version, licences, sources)

    # A work-level introduction belongs to the first book the work covers.
    first = min(out.books, key=lambda c: ORDER[c]) if out.books else None
    for doc, version, f in intro_docs:
        code = base_book(doc) or first
        if not code:
            out.drop("introduction with no book to attach to")
            continue
        _verse_map(doc, version, out, code)
        _credit(version, licences, sources)
    return out, {"licences": licences, "sources": sources}


def _credit(version: dict, licences: list, sources: list):
    """Record the licence and source of a version that contributed text."""
    lic = version.get("license") or ""
    if lic and lic not in licences:
        licences.append(lic)
    src = version.get("versionSource") or ""
    if src and src not in sources:
        sources.append(src)
