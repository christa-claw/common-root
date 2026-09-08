#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
show_verses.py — print a verse range from one or more editions, for eyeballing
alignment questions (omission vs merge vs renumbering).

Usage:
    python3 scripts/bibles/show_verses.py <BOOK> <chapter> <from> <to> [id ...]
      e.g. python3 scripts/bibles/show_verses.py ACT 8 35 39 \
             bible-diaglott-il-1864 bible-kjv-1611
Defaults to the Diaglott interlinear + KJV if no ids are given.
"""
import sys, urllib.request, urllib.parse, base64

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH      = base64.b64encode(b"admin:admin").decode()
NS        = "http://religioustext.org/schema/1.0"

def xquery(q):
    url = f"{BASEX_URL}?{urllib.parse.urlencode({'query': q})}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req) as r:
        return r.read().decode("utf-8")

def show(tid, code, ch, frm, to):
    q = (f"declare namespace rt='{NS}';"
         f" for $v in db:open('religioustext')//rt:text[@id='{tid}']"
         f"/rt:book[@code='{code}']/rt:chapter[@number='{ch}']"
         f"//rt:verse[number(@number) ge {frm} and number(@number) le {to}]"
         f" order by number($v/@number)"
         f" return concat($v/@number, '|', normalize-space($v))")
    print(f"--- {tid} ---")
    out = xquery(q).strip()
    if not out:
        print("  (nothing — id, book code or chapter wrong?)")
    for line in out.splitlines():
        n, _, txt = line.partition("|")
        print(f"  {n:>3}  {txt[:160]}")

def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    code, ch, frm, to = sys.argv[1].upper(), int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    ids = sys.argv[5:] or ["bible-diaglott-il-1864", "bible-kjv-1611"]
    print(f"{code} {ch}:{frm}-{to}")
    for tid in ids:
        show(tid, code, ch, frm, to)

if __name__ == "__main__":
    main()
