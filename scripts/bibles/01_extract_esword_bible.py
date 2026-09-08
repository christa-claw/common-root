#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
extract_esword_bible.py — pull verse text out of an e-Sword Bible module
(.bblx / .bbli, which are SQLite databases) into flat import JSON.

Used to check whether the Emphatic Diaglott e-Sword module carries Wilson's
readable TRANSLATION ("...the LOGOS was God") or the interlinear gloss
("...a god was the Word"), and to ingest it cleanly if it's the translation.

Usage:
    python3 scripts/bibles/01_extract_esword_bible.py \\
        --input ~/Downloads/<module>.bblx \\
        --output ~/Downloads/diaglott-esword.json
"""
import argparse, json, re, sqlite3, sys

TAG = re.compile(r"<[^>]+>")        # e-Sword inline tags: <FR>..<Fr> red letter, <WG3056> Strong's, <FI>..<Fi> italics
BRACE = re.compile(r"\{[^}]*\}")    # occasional brace markup
WS = re.compile(r"\s+")


def clean(t):
    if t is None:
        return ""
    t = TAG.sub(" ", t)
    t = BRACE.sub(" ", t)
    for a, b in (("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&"),
                 ("&quot;", '"'), ("&#39;", "'"), ("&nbsp;", " ")):
        t = t.replace(a, b)
    return WS.sub(" ", t).strip()


def find_verse_table(con):
    for (name,) in con.execute("SELECT name FROM sqlite_master WHERE type='table'"):
        cols = [c[1].lower() for c in con.execute(f"PRAGMA table_info('{name}')")]
        if {"book", "chapter", "verse"} <= set(cols):
            textcol = next((c for c in ("scripture", "text", "verse_text") if c in cols), None)
            if textcol is None:
                textcol = next((c for c in cols if c not in ("book", "chapter", "verse", "id")), cols[-1])
            return name, textcol
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    a = ap.parse_args()

    con = sqlite3.connect(a.input)
    tables = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")]
    print("tables:", ", ".join(tables))

    for meta in ("Details", "details", "info"):
        if meta in tables:
            cols = [c[1] for c in con.execute(f"PRAGMA table_info('{meta}')")]
            row = con.execute(f"SELECT * FROM {meta} LIMIT 1").fetchone()
            if row:
                d = dict(zip(cols, row))
                # don't dump huge 'Information' blobs whole
                for k in list(d):
                    if isinstance(d[k], str) and len(d[k]) > 200:
                        d[k] = d[k][:200] + "…"
                print(f"{meta}: {d}")
            break

    table, textcol = find_verse_table(con)
    if not table:
        sys.exit("No Book/Chapter/Verse table found — inspect the schema above.")
    print(f"verse table: {table}  (text column: {textcol})")

    rows = list(con.execute(
        f"SELECT Book, Chapter, Verse, {textcol} FROM {table} ORDER BY Book, Chapter, Verse"))
    books = sorted({r[0] for r in rows})
    offset = 39 if books and max(books) <= 27 else 0   # some NT-only modules number books 1-27

    verses = []
    for b, c, v, s in rows:
        text = clean(s)
        if text:
            verses.append({"book": b + offset, "chapter": c, "verse": v, "text": text})

    with open(a.output, "w", encoding="utf-8") as f:
        json.dump({"verses": verses}, f, ensure_ascii=False)

    print(f"\nWrote {len(verses)} verses to {a.output}")
    print(f"books present (after offset {offset}): {[b + offset for b in books]}")
    for bk, ch, vs in [(40, 1, 1), (43, 1, 1), (43, 1, 14)]:   # Matt 1:1, John 1:1, John 1:14
        raw = next((r[3] for r in rows if r[0] + offset == bk and r[1] == ch and r[2] == vs), None)
        if raw is not None:
            print(f"\n--- {bk} {ch}:{vs} RAW  : {raw[:300]}")
            print(f"--- {bk} {ch}:{vs} CLEAN: {clean(raw)[:300]}")


if __name__ == "__main__":
    main()
