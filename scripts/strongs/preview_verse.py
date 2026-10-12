#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
preview_verse.py — print an OSHB verse word by word, joined to the Hebrew Lexicon.

A throwaway look at what the Strong's word layer (docs/strongs-design.md) would show in the
verse panel: for each word the Hebrew, its prefix particles, Strong's number and sense, the
lexicon's pointed lemma and short gloss, Strong's own definition, and the OSHB morphology
code. Read-only; uses oshb_lemma.py for the parsing and the AugIndex lookup.

Usage:
    python3 scripts/strongs/preview_verse.py [REF ...] [--html out.html]
REF is OSIS-style, as in OSHB (Gen.1.1, Ps.51.3, Joel.3.1). OSHB's numbering, not the KJV's.
Default refs: Gen.1.1 Ps.51.1 Ps.51.3 Joel.3.1.
"""
import argparse, html, os, sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oshb_lemma as L

NS = "{http://openscriptures.github.com/morphhb/namespace}"


def flat(el):
    return " ".join("".join(el.itertext()).split())


def load_strong(path):
    out = {}
    for e in ET.parse(path).getroot().iter(NS + "entry"):
        w = e.find(NS + "w")
        meaning = e.find(NS + "meaning")
        source = e.find(NS + "source")
        out[e.get("id")] = {
            "source": flat(source) if source is not None else "",
            "lemma": (w.text or "") if w is not None else "",
            "xlit": w.get("xlit", "") if w is not None else "",
            "pos": w.get("pos", "") if w is not None else "",
            "glosses": [flat(d) for d in meaning.iter(NS + "def")] if meaning is not None else [],
            "meaning": flat(meaning) if meaning is not None else "",
        }
    return out


def load_lexical_index(path):
    out = {}
    for e in ET.parse(path).getroot().iter(NS + "entry"):
        w, d, p = e.find(NS + "w"), e.find(NS + "def"), e.find(NS + "pos")
        out[e.get("id")] = {"lemma": (w.text or "") if w is not None else "",
                            "xlit": w.get("xlit", "") if w is not None else "",
                            "def": flat(d) if d is not None else "",
                            "pos": (p.text or "") if p is not None else ""}
    return out


def verse_words(wlc_dir, ref):
    book = ref.split(".")[0]
    for v in ET.parse(os.path.join(wlc_dir, book + ".xml")).iter(L.OSIS + "verse"):
        if v.get("osisID") == ref:
            return v.findall(L.OSIS + "w")
    raise SystemExit("No verse %s in the OSHB." % ref)


def rows(ref, wlc_dir, aug, strong, lex):
    out = []
    for n, w in enumerate(verse_words(wlc_dir, ref), 1):
        lemma = w.get("lemma")
        lem = L.parse(lemma)
        entry_id = aug.resolve(lemma)
        li = lex.get(entry_id, {}) if entry_id else {}
        st = strong.get(lem.strongs, {}) if lem.strongs else {}
        out.append({
            "n": n,
            "hebrew": "".join(w.itertext()),
            "prefixes": ", ".join("%s (%s)" % (p, L.PREFIXES[p][0]) for p in lem.prefixes),
            "strongs": (lem.strongs or "—") + (" " + lem.sense if lem.sense else "") + ("+" if lem.plus else ""),
            "entry": ("%s  %s" % (li.get("lemma", ""), li.get("xlit", ""))).strip() or "—",
            "gloss": li.get("def") or "; ".join(st.get("glosses", [])[:3]) or "—",
            "strong_def": st.get("meaning", ""),
            "morph": w.get("morph", ""),
        })
    return out


def markdown(ref, rs):
    lines = ["### %s" % ref, "",
             "| # | Hebrew | Prefix | Strong's | Lexicon entry | Gloss | Morph |", "|---|---|---|---|---|---|---|"]
    for r in rs:
        lines.append("| %d | %s | %s | %s | %s | %s | `%s` |" % (
            r["n"], r["hebrew"], r["prefixes"] or "", r["strongs"], r["entry"], r["gloss"], r["morph"]))
    return "\n".join(lines)


CSS = """
:root{--bg:#fff;--fg:#1f2328;--mut:#656d76;--line:#d0d7de;--head:#f6f8fa;--acc:#0b5cab}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--mut:#8b949e;--line:#30363d;--head:#161b22;--acc:#58a6ff}}
body{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif;margin:0;padding:24px 16px;max-width:1100px;margin-inline:auto}
h1{font-size:20px;margin:0 0 4px}p.sub{color:var(--mut);margin:0 0 24px}
h2{font-size:16px;margin:28px 0 8px}
.wrap{overflow-x:auto;border:1px solid var(--line);border-radius:8px}
table{border-collapse:collapse;width:100%;min-width:760px}
th,td{padding:7px 10px;text-align:start;vertical-align:top;border-bottom:1px solid var(--line)}
th{background:var(--head);font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:var(--mut)}
tr:last-child td{border-bottom:0}
.he{font:22px/1.4 "SBL Hebrew","Ezra SIL","Times New Roman",serif;direction:rtl;text-align:right;white-space:nowrap}
.sn{font-weight:600;color:var(--acc);white-space:nowrap}.m{font:12px ui-monospace,monospace;color:var(--mut)}
.def{color:var(--mut);font-size:13px;margin-top:2px}
"""


def page(blocks):
    parts = ["<!doctype html><html lang=en><head><meta charset=utf-8>",
             "<meta name=viewport content='width=device-width,initial-scale=1'>",
             "<title>Word study preview</title><style>%s</style></head><body>" % CSS,
             "<h1>Word study preview</h1>",
             "<p class=sub>OSHB words joined to the Hebrew Lexicon. A throwaway preview of the verse panel; "
             "OSHB verse numbering. Sources: Open Scriptures Hebrew Bible and Hebrew Lexicon, CC BY 4.0.</p>"]
    for ref, rs in blocks:
        parts.append("<h2>%s</h2><div class=wrap><table><tr><th>#<th>Hebrew<th>Prefix<th>Strong's"
                     "<th>Lexicon entry<th>Gloss and Strong's definition<th>Morph</tr>" % html.escape(ref))
        for r in rs:
            parts.append(
                "<tr><td>%d<td class=he lang=he>%s<td>%s<td class=sn>%s<td class=he lang=he style='font-size:18px'>%s"
                "<td>%s<div class=def>%s</div><td class=m>%s</tr>" % (
                    r["n"], html.escape(r["hebrew"]), html.escape(r["prefixes"]), html.escape(r["strongs"]),
                    html.escape(r["entry"]), html.escape(r["gloss"]),
                    html.escape(r["strong_def"][:220] + ("…" if len(r["strong_def"]) > 220 else "")),
                    html.escape(r["morph"])))
        parts.append("</table></div>")
    parts.append("</body></html>")
    return "".join(parts)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("refs", nargs="*", default=["Gen.1.1", "Ps.51.1", "Ps.51.3", "Joel.3.1"])
    ap.add_argument("--html", help="write a standalone HTML page instead of printing markdown")
    ap.add_argument("--oshb", default=L._default("sources", "oshb", "src", "wlc"))
    ap.add_argument("--lexicon", default=os.path.dirname(L._default("sources", "hebrew-lexicon", "src", "AugIndex.xml")))
    a = ap.parse_args()
    aug = L.AugIndex.load(os.path.join(a.lexicon, "AugIndex.xml"))
    strong = load_strong(os.path.join(a.lexicon, "HebrewStrong.xml"))
    lex = load_lexical_index(os.path.join(a.lexicon, "LexicalIndex.xml"))
    blocks = [(r, rows(r, a.oshb, aug, strong, lex)) for r in a.refs]
    if a.html:
        with open(a.html, "w", encoding="utf-8") as f:
            f.write(page(blocks))
        print("wrote", a.html)
    else:
        print("\n\n".join(markdown(r, rs) for r, rs in blocks))


if __name__ == "__main__":
    main()
