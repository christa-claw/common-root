#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
mock_rung.py — a static MOCK-UP of the Strong's rung line (docs/strongs-design.md), for review.

Not the reader: a standalone HTML page that imitates a KJV column with its original-language
rung under each verse, the ⓝ toggle that shows/hides the numbers, and an optional hover tooltip.
It exists so the hover idea can be judged before anything is built. Read-only: KJV text from
BaseX, Hebrew words and numbers from the OSHB download, definitions from the Hebrew Lexicon.

Usage: python3 scripts/strongs/mock_rung.py out/strongs-rung-mock.html
"""
import base64, html, os, sys, urllib.parse, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oshb_lemma as L
import preview_verse as P

NS = "http://religioustext.org/schema/1.0"
# (KJV book, chapter, verse) shown in the column  ->  OSHB ref of the same verse (OSHB numbers differ)
VERSES = [(("GEN", 1, 1), "Gen.1.1"), (("PSA", 51, 1), "Ps.51.3"), (("JOL", 2, 28), "Joel.3.1")]


def kjv(code, ch, vs):
    q = ("declare namespace rt='%s'; string((//rt:text[@id='bible-kjv-1611']/rt:book[@code='%s']"
         "/rt:chapter[@number='%d']/rt:verse[@number='%d'])[1])" % (NS, code, ch, vs))
    req = urllib.request.Request("http://localhost:8984/rest/religioustext?" + urllib.parse.urlencode({"query": q}),
                                 headers={"Authorization": "Basic " + base64.b64encode(b"admin:admin").decode()})
    return urllib.request.urlopen(req, timeout=60).read().decode().strip()


def short(text, n=150):
    return text if len(text) <= n else text[:n].rsplit(" ", 1)[0] + "…"


def rung_line(ref, wlc, aug, strong, lex):
    parts = []
    for w in P.verse_words(wlc, ref):
        lemma = w.get("lemma")
        lem = L.parse(lemma)
        surface = html.escape("".join(w.itertext()).replace("/", ""))
        if lem.number is None:
            parts.append('<span class=w>%s</span>' % surface)
            continue
        st = strong.get(lem.strongs, {})
        li = lex.get(aug.resolve(lemma) or "", {})
        gloss = "; ".join(st.get("glosses", [])[:3]) or li.get("def", "")
        tip = ('<span class=tip><b>%s</b> <i>%s</i><br><span class=g>%s</span><br>'
               '<span class=d>%s</span></span>') % (
            html.escape(li.get("lemma") or st.get("lemma", "")), html.escape(li.get("xlit") or st.get("xlit", "")),
            html.escape(gloss), html.escape(short(st.get("meaning", ""))))
        label = "%s%s" % (lem.strongs, lem.sense or "")
        parts.append('<span class=w>%s<sup class=n>%s%s</sup></span>' % (surface, label, tip))
    return " ".join(parts)


CSS = """
:root{--bg:#fff;--fg:#1f2328;--mut:#6b7280;--line:#d8dee4;--card:#fafbfc;--acc:#0b5cab;--pill:#eef1f4}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--mut:#9aa4af;--line:#30363d;--card:#12171e;--acc:#58a6ff;--pill:#1d242d}}
*{box-sizing:border-box}body{background:var(--bg);color:var(--fg);font:16px/1.6 Georgia,serif;margin:0;padding:24px 16px}
.page{max-width:760px;margin-inline:auto}h1{font:600 18px system-ui;margin:0 0 4px}.sub{font:14px system-ui;color:var(--mut);margin:0 0 18px}
.col{border:1px solid var(--line);border-radius:10px;background:var(--card);overflow:visible}
.head{display:flex;gap:10px;align-items:center;padding:10px 14px;border-bottom:1px solid var(--line);font:13px system-ui;color:var(--mut)}
.head b{color:var(--fg)}.sp{flex:1}
.tg{display:inline-flex;align-items:center;justify-content:center;height:20px;min-width:22px;padding:0 6px;border-radius:10px;
border:1px solid var(--line);font:13px system-ui;color:var(--mut);background:transparent;cursor:pointer}
.tg.on{background:var(--mut);color:var(--bg);border-color:var(--mut)}
.body{padding:14px 18px 6px}.v{margin:0 0 18px}.vn{font:10px system-ui;color:var(--mut);vertical-align:super;margin-inline-end:3px}
.rung{margin-top:6px;padding-inline-start:10px;border-inline-start:2px solid var(--line);direction:rtl;text-align:right;
color:var(--mut);font:20px/2 "SBL Hebrew","Ezra SIL","Times New Roman",serif}
.chip{direction:ltr;display:inline-block;font:9px system-ui;border:1px dashed var(--mut);border-radius:3px;padding:0 4px;margin-inline-start:8px;vertical-align:middle}
.w{position:relative;white-space:nowrap}
sup.n{display:none;font:600 8px system-ui;color:var(--acc);margin-inline-start:2px;cursor:help;direction:ltr;unicode-bidi:isolate}
body.nums sup.n{display:inline}
.tip{display:none;position:absolute;z-index:5;inset-inline-start:0;top:100%;min-width:230px;max-width:330px;white-space:normal;direction:ltr;text-align:left;
background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:8px 10px;font:13px/1.45 system-ui;box-shadow:0 6px 18px rgba(0,0,0,.18);opacity:1}
.tip b{font:18px "SBL Hebrew",serif;margin-inline-end:6px}.tip .g{font-weight:600}.tip .d{color:var(--mut)}
body.hover.nums sup.n:hover .tip{display:block}
.ctl{font:13px system-ui;color:var(--mut);margin:14px 0}.ctl label{margin-inline-end:16px;cursor:pointer}
.credit{font:11px system-ui;color:var(--mut);margin-top:14px}
"""

JS = """
const b=document.body,t=document.getElementById('n'),h=document.getElementById('h');
t.onclick=()=>{b.classList.toggle('nums');t.classList.toggle('on',b.classList.contains('nums'))};
h.onchange=()=>b.classList.toggle('hover',h.checked);
"""


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "out/strongs-rung-mock.html"
    wlc = L._default("sources", "oshb", "src", "wlc")
    lexdir = os.path.dirname(L._default("sources", "hebrew-lexicon", "src", "AugIndex.xml"))
    aug = L.AugIndex.load(os.path.join(lexdir, "AugIndex.xml"))
    strong = P.load_strong(os.path.join(lexdir, "HebrewStrong.xml"))
    lex = P.load_lexical_index(os.path.join(lexdir, "LexicalIndex.xml"))
    body = []
    for (code, ch, vs), ref in VERSES:
        body.append('<div class=v><span class=vn>%d</span>%s<div class=rung lang=he><span class=chip>≈ WLC</span> %s</div></div>'
                    % (vs, html.escape(kjv(code, ch, vs)), rung_line(ref, wlc, aug, strong, lex)))
    page = ("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>Strong's rung mock-up</title><style>%s</style></head><body class='nums hover'><div class=page>"
            "<h1>Strong's numbers on the original-language rung — mock-up</h1>"
            "<p class=sub>Not the reader. KJV column with its Hebrew rung (the muted line under each verse). "
            "Click ⓝ to show or hide the numbers; hover a number for the tooltip.</p>"
            "<div class=col><div class=head><b>KJV</b><span>King James Version</span><span class=sp></span>"
            "<span class=tg title='Show comment markers in the text'>💬</span>"
            "<span class=tg title='Show cross-reference markers in the text'>✝︎</span>"
            "<button class='tg on' id=n title='Show Strong’s numbers'>ⓝ</button></div>"
            "<div class=body>%s</div></div>"
            "<div class=ctl><label><input type=checkbox id=h checked> hover tooltips</label>"
            "<span>(compare: untick to see numbers only)</span></div>"
            "<p class=credit>Original work of the Open Scriptures Hebrew Bible available at "
            "https://github.com/openscriptures/morphhb · Hebrew Lexicon: Open Scriptures Hebrew Bible Project · CC BY 4.0. "
            "Lemmas normalised to H#### + sense letter; word text unchanged.</p>"
            "</div><script>%s</script></body></html>") % (CSS, "".join(body), JS)
    with open(out, "w", encoding="utf-8") as f:
        f.write(page)
    print("wrote", out)


if __name__ == "__main__":
    main()
