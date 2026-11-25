# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
read_ccel.py — CCEL ThML commentaries (sources/commentaries/ccel/<slug>/*.xml)
-> commentary_lib.Commentary.

CCEL marks where commentary on a passage begins with an empty milestone:

    <scripCom type="Commentary" passage="Ge 1:1" parsed="|Gen|1|1|0|0" osisRef="Bible:Gen.1.1"/>

parsed = |book|chapter|verse|toChapter|toVerse, verse 0 meaning the chapter as a
whole. Everything from one milestone to the next is the commentary on that
passage, so the body is cut at the milestones and each piece is filed under the
milestone before it: a verse anchor becomes a <comment> (with @toVerse when the
milestone names a range), a chapter anchor a chapter <intro>. Text before a
volume's first milestone (title page, prefaces, "The Argument") becomes a book
<intro> on the first book that volume comments on, headed with the volume title.

Volumes are read in file-name order, so a commentary spread over 45 files
(Calvin) comes out as one document in canonical order.
"""
from __future__ import annotations

import re
from pathlib import Path

from commentary_lib import Comment, Commentary, book_code, plain, to_paragraphs

MILESTONE = re.compile(r"<scripCom\b([^>]*)/?>", re.I)
ATTR = re.compile(r'(\w+)="([^"]*)"')
BODY = re.compile(r"<ThML\.body[^>]*>(.*)</ThML\.body>", re.S | re.I)
TITLE = re.compile(r"<DC\.Title[^>]*>(.*?)</DC\.Title>", re.S | re.I)
RIGHTS = re.compile(r"<DC\.Rights[^>]*>(.*?)</DC\.Rights>", re.S | re.I)


def _anchor(attrs: str):
    a = dict(ATTR.findall(attrs))
    parts = (a.get("parsed") or "").strip("|").split("|")
    if len(parts) < 3:
        return None
    code = book_code(parts[0])
    try:
        ch, v = int(parts[1]), int(parts[2])
        to_ch = int(parts[3]) if len(parts) > 3 and parts[3] else 0
        to_v = int(parts[4]) if len(parts) > 4 and parts[4] else 0
    except ValueError:
        return None
    if not code or ch < 1:
        return None
    return code, ch, v, to_ch, to_v, a.get("passage", "")


def read(work_dir: Path) -> tuple[Commentary, dict]:
    out = Commentary(id="", abbr="", title="", lang="en", iso3="eng")
    licences, volumes = [], 0
    for f in sorted(work_dir.glob("*.xml")):
        raw = f.read_text(encoding="utf-8", errors="replace")
        m = BODY.search(raw)
        if not m:
            out.drop(f"{f.name}: no ThML.body")
            continue
        volumes += 1
        for lic in RIGHTS.findall(raw):
            lic = plain(lic)
            if lic and lic not in licences:
                licences.append(lic)
        vol_title = plain((TITLE.findall(raw) or [f.stem])[0])
        body = m.group(1)
        pieces = MILESTONE.split(body)          # [front, attrs1, text1, attrs2, text2, ...]
        front = pieces[0]
        first = next((_anchor(pieces[i]) for i in range(1, len(pieces), 2) if _anchor(pieces[i])), None)
        if first:
            paras = to_paragraphs(front)
            if paras:
                out.add_intro(first[0], None, Comment(paras=paras, heading=vol_title))
        for i in range(1, len(pieces), 2):
            anchor = _anchor(pieces[i])
            text = pieces[i + 1] if i + 1 < len(pieces) else ""
            if not anchor:
                out.drop("milestone without a usable parsed reference")
                continue
            code, ch, v, to_ch, to_v, passage = anchor
            paras = to_paragraphs(text)
            if not paras:
                continue
            c = Comment(paras=paras)
            if v == 0:
                out.add_intro(code, ch, c)
                continue
            if to_ch and to_ch != ch:
                c.to_chapter, c.to_verse = to_ch, (to_v or None)
            elif to_v and to_v != v:
                c.to_verse = to_v
            out.add(code, ch, v, c)
    return out, {"licences": licences, "volumes": volumes}
