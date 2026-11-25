# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
read_sword.py — a CrossWire SWORD commentary module (the rawzip the nightly
fetch keeps in sources/commentaries/crosswire/<slug>/) -> commentary_lib.Commentary.

There are no SWORD tools on the Mac and no SWORD library in the repo, so this
reads the compressed-commentary format (ModDrv zCom / zCom4) directly. It is
the same on-disk layout as a zText Bible:

  {ot,nt}.bzs  block index   12 bytes per block: offset, size, uncompressed size (u32 LE)
  {ot,nt}.bzv  verse index   per entry: block u32, offset in block u32, size u16 (zCom)
                             or size u32 (zCom4)
  {ot,nt}.bzz  zlib blocks
  (older modules name them .czs/.czv/.czz)

Entry numbering follows the module's versification (data/sword_versification.json):
per testament, 0 = module heading, 1 = testament heading, then for every book a
book heading, and for every chapter a chapter heading followed by its verses.
Headings carry intros: a book heading becomes a book <intro>, a chapter heading
a chapter <intro>.

SWORD expresses "this note covers verses 3-5" by pointing 3, 4 and 5 at the
same bytes. Consecutive entries with an identical (block, offset, size) are
therefore one comment with @toVerse, not three copies.
"""
from __future__ import annotations

import re
import struct
import zipfile
import zlib
from pathlib import Path

from commentary_lib import Comment, Commentary, OSIS_TO_USFM, to_paragraphs, versification


def parse_conf(text: str) -> dict:
    conf, name = {}, None
    for line in text.splitlines():
        line = line.rstrip()
        if line.startswith("[") and line.endswith("]"):
            name = line[1:-1]
            continue
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            conf.setdefault(k.strip(), v.strip())
    conf["_name"] = name
    return conf


def _files(zf: zipfile.ZipFile, testament: str) -> tuple[bytes, bytes, bytes] | None:
    names = zf.namelist()
    for ext in ("b", "c"):
        want = [n for n in names for s in ("s", "v", "z") if n.endswith(f"/{testament}.{ext}z{s}")]
        got = {n[-1]: n for n in want}
        if len(got) == 3:
            return zf.read(got["s"]), zf.read(got["v"]), zf.read(got["z"])
    return None


def _decode(raw: bytes, encoding: str) -> str:
    """Entry by entry: a conf that says UTF-8 is not proof (DutKant declares
    UTF-8 and stores Dutch diacritics as Latin-1), and one that says nothing
    often holds UTF-8. Strict UTF-8 first, then Windows-1252."""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp1252", "replace")


def _layout(books: list) -> list[tuple[int, int, int]]:
    """Entry index -> (book index, chapter, verse); chapter 0 = book heading, verse 0 = chapter heading."""
    out = [(-1, 0, 0), (-1, 0, 0)]               # module heading, testament heading
    for bi, (_name, _osis, lens) in enumerate(books):
        out.append((bi, 0, 0))
        for ci, n in enumerate(lens, start=1):
            out.append((bi, ci, 0))
            out.extend((bi, ci, v) for v in range(1, n + 1))
    return out


def read(work_dir: Path) -> tuple[Commentary, dict]:
    zpath = next(iter(sorted(work_dir.glob("*.zip"))), None)
    if not zpath:
        raise ValueError(f"no SWORD zip in {work_dir}")
    zf = zipfile.ZipFile(zpath)
    conf_name = next((n for n in zf.namelist() if n.startswith("mods.d/") and n.endswith(".conf")), None)
    conf = parse_conf(zf.read(conf_name).decode("utf-8", "replace")) if conf_name else {}
    v11n = (conf.get("Versification") or "KJV").lower()
    drv = (conf.get("ModDrv") or "zCom").lower()
    enc = conf.get("Encoding") or "Latin-1"
    lang = conf.get("Lang") or "en"
    out = Commentary(id="", abbr="", title="", lang=lang, iso3="")
    facts = {"module": conf.get("_name"), "versification": v11n, "driver": drv, "sourceType": conf.get("SourceType"),
             "licences": [conf.get("DistributionLicense", "")], "description": conf.get("Description", ""),
             "entries": 0, "linked": 0}

    for testament in ("ot", "nt"):
        files = _files(zf, testament)
        if not files:
            continue
        bzs, bzv, bzz = files
        books = versification(v11n)[testament]
        layout = _layout(books)
        rec = 12 if drv == "zcom4" else 10
        if len(bzv) // rec != len(layout) and len(bzv) // (22 - rec) == len(layout):
            rec = 22 - rec                       # conf and data disagree: trust the data
        if len(bzv) // rec != len(layout):
            out.drop(f"{testament}: index has {len(bzv) // rec} entries, {v11n} expects {len(layout)}")
        fmt = "<IIH" if rec == 10 else "<III"
        cache: dict[int, bytes] = {}

        def block(n: int) -> bytes:
            if n not in cache:
                off, size, _uc = struct.unpack_from("<III", bzs, n * 12)
                cache.clear() if len(cache) > 8 else None
                cache[n] = zlib.decompress(bzz[off:off + size])
            return cache[n]

        prev_key, prev_comment, prev_pos = None, None, None
        for idx in range(min(len(bzv) // rec, len(layout))):
            b, start, size = struct.unpack_from(fmt, bzv, idx * rec)
            bi, ch, v = layout[idx]
            if size == 0 or bi < 0:
                prev_key = None
                continue
            key = (b, start, size)
            if key == prev_key and v and prev_pos and prev_pos[0] == bi and prev_comment is not None:
                # linked entry: same bytes as the previous verse -> extend the range
                if ch != prev_pos[1]:
                    prev_comment.to_chapter = ch
                prev_comment.to_verse = v
                facts["linked"] += 1
                continue
            try:
                raw = block(b)[start:start + size]
            except (zlib.error, struct.error) as e:
                out.drop(f"unreadable block ({e.__class__.__name__})")
                prev_key = None
                continue
            text = _decode(raw, enc).strip("\x00 \r\n")
            facts["entries"] += 1
            code = OSIS_TO_USFM.get(books[bi][1])
            if not code:
                out.drop(f"book {books[bi][1]} has no USFM code")
                prev_key = None
                continue
            paras = to_paragraphs(text)
            if not paras:
                prev_key = None
                continue
            c = Comment(paras=paras)
            if ch == 0:
                out.add_intro(code, None, c)
            elif v == 0:
                out.add_intro(code, ch, c)
            else:
                out.add(code, ch, v, c)
            prev_key, prev_comment, prev_pos = key, c, (bi, ch, v)
    return out, facts
