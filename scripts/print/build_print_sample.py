#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
build_print_sample.py — a printer's sample: one text, set three ways.

WHY THIS EXISTS
---------------
Custom-Bible shops are spec-driven. "Would you be interested in a chronological
Bible" gets ignored; a 20-page sample with a trim size on it gets a quote. This
builds that sample from the live corpus, so the artefact is the actual product
rather than a mock-up.

WHAT IT SHOWS, AND WHY THESE PASSAGES
-------------------------------------
1. THE CHRONOLOGICAL SEAM. A Genesis sample would demonstrate nothing: Genesis
   1-11 is the first chronological block and sits in canonical order, so it looks
   identical to every Bible ever printed. The reordering only becomes visible at
   a seam — and the first one is the best one. The plan runs Genesis 1-11, then
   the whole of Job, then Genesis 12. So Job opens between Genesis 11 and 12,
   which is immediately legible on the page as "this is not the order you know".

2. SCRIPTIO CONTINUA. The same text as the earliest manuscripts carried it: no
   spaces, no lower case, no punctuation, no chapter or verse numbers. A search
   in July 2026 found no edition of it for sale — worth something, but not
   proof, so do not write "nobody sells this" on anything that goes out. It is
   in any case the most visually striking page in the sample.

   NOTE ON THE COVER (2026-07-30). It read "three Bibles you cannot buy" until
   Christa questioned it. Two of the three are sold: chronological Bibles are a
   retail CATEGORY (Lifeway, who are on our own target list, have a shop section
   for them; Thomas Nelson and Tyndale both publish one), and reader's editions
   without chapter and verse numbers are a mainstream line (Crossway's ESV
   Reader's Bible, six volumes). The recipient of this sample could disprove the
   claim in one search, on a subject where they are the expert. What is ours is
   the COMBINATION and the licence position, not the novelty of any one setting.

3. CONTINUOUS, SIMPLIFIED. The middle option — modern readable text with the
   chapter and verse numbering removed, so a book reads as the argument or
   narrative its author wrote rather than a grid of quotable cells.

OUTPUT
------
HTML with CSS Paged Media, deliberately not PDF. Print it from Chrome
(File > Print > Save as PDF, margins "None", background graphics ON) and you
have something to attach tonight. A real PDF/X-1a for production needs
WeasyPrint or Paged.js plus Ghostscript, which is worth building only once a
printer has said yes.

Usage:
    python3 scripts/print/build_print_sample.py                    # KJV, default 5.5x8.5in
    python3 scripts/print/build_print_sample.py --edition bible-web
    python3 scripts/print/build_print_sample.py --trim 6x9
"""

import argparse
import html
import re
import sys
import urllib.parse
import urllib.request
import base64

BASEX = "http://localhost:8984/rest/religioustext"
AUTH = base64.b64encode(b"admin:admin").decode()
NS = "declare namespace rt='http://religioustext.org/schema/1.0'; "

TRIMS = {"5.5x8.5": ("5.5in", "8.5in"), "6x9": ("6in", "9in"), "a5": ("148mm", "210mm")}


def xq(query):
    url = f"{BASEX}?{urllib.parse.urlencode({'query': query})}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}",
                                               "Accept": "text/plain"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def verses(edition, code, chapter, first=None, last=None):
    """Verses of one chapter, in document order, as (number, text)."""
    pred = ""
    if first is not None:
        pred = f"[xs:integer(@number) >= {first}]"
        if last is not None:
            pred = f"[xs:integer(@number) >= {first} and xs:integer(@number) <= {last}]"
    q = (NS + f"for $v in db:open('religioustext', '{edition}.xml')"
              f"/rt:text/rt:book[@code='{code}']/rt:chapter[@number='{chapter}']"
              f"/rt:verse{pred} "
              "return concat(string($v/@number), '\t', normalize-space(string($v)))")
    out = []
    for line in xq(q).split("\n"):
        if "\t" in line:
            n, t = line.split("\t", 1)
            out.append((n.strip(), clean(t)))
    return out


# Leaked translator footnotes. The wldeh source JSONs embed notes inline, keyed by
# "<chapter>.<verse> <lemma>: <note>", and the ingest kept them — see the INLINE
# FOOTNOTE LEAK backlog note (2026-07-24). KJV and WEB are both affected. Stripping
# here is a sample-side WORKAROUND so a printer is not sent broken text; the corpus
# itself still carries the corruption and still needs fixing at ingest.
FOOTNOTE_TAIL = re.compile(r"\s*\b\d+\.\d+\s+\S[^:]{0,40}:.*$")


# The KJV's own paragraph marks, carried through from 1611. Removed from the
# sample on Christa's call (2026-07-31): "nothing in the text is altered" is a
# claim about the WORDS, and a pilcrow is editorial marking, not text. They also
# stop dead at Acts 20:36 — the 1611 markup was never finished — so in a full run
# they peter out, which reads as a data fault to anyone who does not know the
# story. Stripped here for the sample only; the corpus keeps them, and a printer
# setting a real edition should be asked whether they want them.
PILCROW = re.compile(r"\s*¶\s*")


def clean(text):
    text = FOOTNOTE_TAIL.sub("", text)
    text = PILCROW.sub(" ", text)
    return " ".join(text.split()).strip()


def scriptio(text):
    """Strip the scribes' later help: spacing, case and punctuation."""
    return re.sub(r"[^A-Za-zͰ-Ͽ֐-׿]", "", text).upper()


# ── rendering ─────────────────────────────────────────────────────────

def _verses_html(rows, numbered=True):
    parts = []
    for n, t in rows:
        num = f'<span class="vn">{html.escape(n)}</span>' if numbered else ""
        parts.append(f"{num}{html.escape(t)}")
    return " ".join(parts)


def col_block(rows, numbered=True):
    """Verses run together into one justified block, the way a Bible is actually
    set. One verse per line is the 19th-century style and eats the page — at 9pt
    in a 5.5in trim it roughly doubles the extent, which a printer costs by the
    signature."""
    return "<p class='v'>" + _verses_html(rows, numbered) + "</p>"


def chapter(name, rows, numbered=True):
    """A chapter heading that cannot be orphaned at a column or page break.

    Chrome ignores `break-after: avoid` on a heading inside multicol — it will
    leave the heading alone at the foot of a column. Two fixes were tried and
    rejected before this one:

      * `break-inside: avoid` on a wrapper holding heading + first verse. Works,
        but splits the chapter into two blocks, and the first block ends in a
        short ragged line mid-page. `text-align-last: justify` hides the seam by
        stretching that line, which in a psalm ("place in all generations.")
        looks like a typesetting fault.
      * `break-inside: avoid` on the whole chapter. Pushes entire chapters to the
        next page whenever they do not fit — this is what left a blank page 4 and,
        later, a page holding nothing but the note.

    So: the heading is a full-width inline-block INSIDE the single verse
    paragraph. One block, so nothing can be split off; the heading forms its own
    line box, and `orphans: 3` on p.v guarantees it is never the last line in a
    column. Verified by rendering, not by reasoning."""
    if not rows:
        return ""
    return ('<div class="bk">'
            "<p class='v'>"
            f'<span class="bkh">{html.escape(name)}</span>'
            + _verses_html(rows, numbered)
            + "</p></div>")


def run_head(text):
    return f'<div class="rh">{html.escape(text)}</div>'


def build(edition, trim, label):
    w, h = TRIMS[trim]

    # A real seam, with text genuinely different on both sides of it. Psalm 90 is
    # the only psalm ascribed to Moses; the plan places 90-91 between Deuteronomy
    # and Joshua, so the psalm is preceded by the death of Moses and followed by
    # Joshua taking command. Canonically its neighbours are Psalms 89 and 92.
    # Four complete chapters, sized to run across a two-page spread.
    deu34 = verses(edition, "DEU", 34)
    psa90 = verses(edition, "PSA", 90)
    psa91 = verses(edition, "PSA", 91)          # 90-91 are one block in the plan
    jos1 = verses(edition, "JOS", 1)
    jhn1 = verses(edition, "JHN", 1, 1, 14)

    wanted = {"DEU 34": deu34, "PSA 90": psa90, "PSA 91": psa91,
              "JOS 1": jos1, "JHN 1": jhn1}
    empty = [k for k, v in wanted.items() if not v]
    if empty:
        sys.exit(f"Corpus query returned nothing for: {', '.join(empty)}. "
                 f"Is BaseX running, and does '{edition}' carry them? "
                 "Try: python3 scripts/bibles/inspect_basex.py")

    flow = " ".join(t for _, t in jhn1)

    return f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Common Root — printer's sample</title>
<style>
  @page {{ size: {w} {h}; margin: 14mm 12mm 16mm 12mm; }}
  @page {{ @bottom-center {{ content: counter(page); font: 8pt Georgia, serif; color:#555; }} }}
  html, body {{ margin:0; padding:0; }}
  body {{ font: 9pt/1.32 Georgia, "Times New Roman", serif; color:#000; }}
  .sheet + .sheet {{ break-before: page; }}
  h1 {{ font-size: 17pt; margin: 0 0 4mm; letter-spacing:.2px; }}
  h2 {{ font-size: 10pt; text-transform: uppercase; letter-spacing:1.1px;
        margin: 0 0 3mm; color:#333; border-bottom:.4pt solid #999; padding-bottom:1.5mm; }}
  .lede {{ font-size: 9pt; color:#222; margin:0 0 5mm; max-width: 92%; }}
  .cols {{ column-count: 2; column-gap: 6mm; column-rule: .3pt solid #ddd;
           column-fill: balance; }}
  .rh {{ font-size: 7.5pt; letter-spacing:1px; text-transform:uppercase;
         color:#444; border-bottom:.3pt solid #bbb; padding-bottom:1mm; margin:0 0 2.5mm; }}
  .bk {{ margin: 0 0 3.5mm; }}
  .bkh {{ display:inline-block; width:100%; font-size:12pt; font-weight:700;
          line-height:1.5; text-align:left; }}
  p.v {{ margin:0 0 2mm; text-align: justify; hyphens:auto; text-indent:0;
         orphans: 3; widows: 2; }}
  .vn {{ font-size:6pt; vertical-align:.4em; color:#666; margin-right:.5mm;
         margin-left:.6mm; font-weight:600; }}
  .sc {{ font: 10.5pt/1.55 Georgia, serif; letter-spacing:.4px; text-align:justify;
         word-break: break-all; }}
  .cont {{ font: 9.5pt/1.45 Georgia, serif; text-align:justify; hyphens:auto; }}
  .note {{ font-size:7.5pt; color:#555; margin-top:5mm; border-top:.3pt solid #ccc;
           padding-top:2mm; break-inside: avoid; }}
  .note.top {{ margin: 0 0 4.5mm; border-top:none; border-bottom:.3pt solid #ccc;
               padding: 0 0 2mm; }}
  .spec {{ font: 7.5pt/1.5 ui-monospace, Menlo, monospace; color:#333; }}
</style></head><body>

<div class="sheet">
  <h1>Common Root — set to order</h1>
  <p class="lede">Every page here is set from public-domain text ({label}), rendered
  straight from the corpus at common-root.org. No rights holder, no per-copy
  licence, no permissions correspondence.</p>
  <p class="lede">Chronological Bibles and reader's editions are published, each locked to
  one licensed translation and one setting. These are the same settings applied to any of
  thirty public-domain editions and generated on demand — so a combination a customer asks
  for is a file, not a print run somebody has to commission first.</p>
  <h2>What follows</h2>
  <p class="lede"><b>1 — Chronological order.</b> Events in the sequence scholars
  reconstruct rather than the order the books were bound in — so a psalm can stand
  beside the episode that produced it, and Chronicles beside the Samuel and Kings
  it retells. The spread overleaf shows one such seam, with the text that now precedes
  and follows it.</p>
  <p class="lede"><b>2 — Scriptio continua.</b> The text as the earliest manuscripts
  carried it: no spaces, no lower case, no punctuation, no numbers.</p>
  <p class="lede"><b>3 — Continuous, unnumbered.</b> Modern readable text with the chapter
  and verse numbers taken out, so a passage reads as the argument it was written as.</p>
  <p class="spec">Sample trim {trim} · two-column · 9pt · text 100% K · interior only, no bleed</p>
</div>

<div class="sheet">
  {run_head("Chronological order — Deuteronomy 34 · Psalms 90, 91 · Joshua 1")}
  <h2>1 — Before and after</h2>
  <p class="lede">Psalm 90 is titled “A Prayer of Moses the man of God” — the only psalm
  ascribed to him. In a printed Bible it sits in the middle of the Psalter, five hundred
  pages from anything Moses did, between Psalms 89 and 92. Here it falls where its author
  stands: after the death of Moses that closes Deuteronomy, before Joshua takes up the
  command. Read the four chapters in a row and the psalm stops being an anthology piece
  and becomes what it says it is. The reordering moves blocks, not verses — Psalms 90 and
  91 are one block and travel together, and elsewhere whole runs such as the Hallel psalms
  do not move at all. Nothing in the text is altered, ever; only the sequence.</p>
  <p class="note top">Four complete chapters, consecutive, nothing omitted. Sequence from
  <span class="spec">@globalChronologicalSeq</span>, stamped verse by verse across every
  edition in the corpus, so the same ordering prints in any translation.</p>
  <div class="cols">
    {chapter("Deuteronomy 34", deu34)}
    {chapter("Psalm 90", psa90)}
    {chapter("Psalm 91", psa91)}
    {chapter("Joshua 1", jos1)}
  </div>
</div>

<div class="sheet">
  {run_head("Scriptio continua — John 1")}
  <h2>2 — As it was first written</h2>
  <p class="lede">Word spacing, lower case, punctuation, chapter and verse numbers are
  all later additions. Removed, the text becomes what a first-century reader met.</p>
  <div class="sc">{html.escape(scriptio(flow))}</div>
  <p class="note">John 1:1–14. The same passage appears overleaf in readable form.</p>
</div>

<div class="sheet">
  {run_head("Continuous, unnumbered — John 1")}
  <h2>3 — Readable, without the numbers</h2>
  <p class="lede">Spacing and punctuation restored — the scribes' genuine gift — but the
  chapter and verse numbers left out. Chapters were added in the 13th century and
  verses in the 16th; they are what invites quoting a sentence away from its argument.</p>
  <div class="cont">{html.escape(flow)}</div>
  <p class="note">Set from {label}. Print via Chrome: File &gt; Print &gt; Save as PDF,
  paper size 5.5x8.5, margins None, background graphics on, headers and footers OFF.</p>
</div>

</body></html>"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--edition", default="bible-kjv-1611")
    ap.add_argument("--label", default="King James Version, 1611")
    ap.add_argument("--trim", default="5.5x8.5", choices=sorted(TRIMS))
    ap.add_argument("--out", default="print-sample.html")
    a = ap.parse_args()
    open(a.out, "w", encoding="utf-8").write(build(a.edition, a.trim, a.label))
    print(f"wrote {a.out}  ({a.edition}, trim {a.trim})")
    print("Open it in Chrome and print to PDF: margins None, background graphics ON.")


if __name__ == "__main__":
    main()
