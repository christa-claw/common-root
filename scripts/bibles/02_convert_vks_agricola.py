#!/usr/bin/env python3
"""Convert the Kotus VKS Agricola corpus (saved kaino.kotus.fi HTML pages)
into the flat-JSON shape accepted by 03_import_json_bible.py --format flat.

INPUT: a directory of pages saved (e.g. with SingleFile) from
  https://kaino.kotus.fi/korpus/vks/meta/agricola/agricola_coll_rdf.xml
The scripture carriers are four pages: Evankeliumit ja Apostolien teot,
Uuden testamentin kirjeet ja Ilmestyskirja, Psalttari, and Weisut ja
Ennustoxet / Ne Prophetat / Mutomat Lughut Mosen Ramatuista. Other pages in
the directory (Abckiria, Rukouskirja, Käsikirja/Messu/Piina, the reun-keyed
marginal-note pages, and any Biblia 1642 saves sharing the folder) carry no
Agricola VERSE markers and fall through as "no canonical verses" — mixing
them in the input dir is harmless by construction.

Marker format (differs from the Biblia's [B1-…] scheme):
  [A-<vol>-<page>-[<book>-<chapter>:<verse>]]
e.g. [A-III-215-[Ps-1:1]] — vol II is Se Wsi Testamenti (1548), vol III the
Psalttari (1551) and Weisut/Prophetat (1551-52). As in the Biblia corpus, the
marker FOLLOWS the text it closes, the verse token may carry a caret (65^),
and the same address repeated concatenates a page/column-split verse.

Skipped as front matter: chapter token 'e' (esipuhe), verse 0 (headings and
summaries), the UT-e-* pseudo-books (OT passages quoted inside the NT
preface — they are preface, not an OT text witness), and every <b>…</b>
block (verified against all four pages: bold is only site chrome, titles/
colophons and Agricola's chapter arguments — never verse text — and the
arguments are NOT always :0-marked, so tag-level stripping is the only
reliable cut). Bare page-break markers ([A-III-466] with no verse address)
are stripped from inside verse text.

A repeated address CONSECUTIVELY is a page/column-split verse and is
concatenated (as in the Biblia corpus). A repeated address later in the
file with different text is an ANOMALY — Agricola's own chapter divisions
drift from the modern versification the corpus addresses use (his Jes 56
runs into modern 57; such verses carry a caret, e.g. [Jon-1:1^]) — and is
kept-first + counted, never concatenated. The kaino coding notes
(korpus/vks/viite/agricola_koodaus.php) are the authority on the caret if
this ever needs refining.

LITURGICAL GAP-FILL (--gapfill, on by default; 2026-08-15, on Tanja
Toropainen's tip that "Mikael Agricolan kieli", SKS 1988, lists Bible passages
Agricola rendered outside the scripture volumes). His Rukouskirja (1544),
Käsikirja and Messu (1549) and even the Abc-kiria carry marked Bible text —
some of it continuous, notably "Genesist mutomat Lughut", chapters of Genesis
printed inside the Käsikirja/Messu volume.

Two rules keep that honest:
  * FILL ONLY GAPS. A liturgical reading never overwrites a verse already
    present from a scripture volume — the translation always wins over its
    liturgical use, and the duplicate problem disappears by construction.
  * RUNS ONLY. A gap is filled only from a run of >= MIN_RUN consecutive
    verses in one chapter. Continuous scripture comes in runs; a verse quoted
    inside a prayer or a doctrinal paragraph stands alone. At the default of 3
    this admits 685 verses in 58 runs and rejects 56 isolated quotations.
The filled addresses are listed in the manifest written beside the output, so
every borrowed verse can be traced to the work it came from.

Cross-FILE conflicts (4): NT benedictions Agricola re-quoted in the Weisut
in variant renderings (Lk 21:28, Mt 21:43, 2Th 3:16, 2Cor 13:12). Merge is
keep-first in sorted-filename order, which parses the dedicated NT volumes
before the Weisut — the primary witness wins. If the input files are ever
renamed so Weisut sorts first, these four verses silently swap rendering;
the per-file CONFLICTING count in the report is the tripwire.

Book codes are the same VKS codes as the Biblia 1642, so BOOK_MAP is
imported from 02_convert_vks_biblia1642 rather than copied. Coverage is
Agricola's, i.e. deliberately partial: complete NT and Psalter, prophet
selections, and scattered Pentateuch chapters. Gaps are the historical
record — import with --allow-partial and let missing books render as the
empty columns the 2026-08-05 decision asked for.

Example:
  python3 scripts/bibles/02_convert_vks_agricola.py \
      --input sources/Agricola --output sources/Agricola/agricola_flat.json
  python3 scripts/bibles/03_import_json_bible.py \
      --input sources/Agricola/agricola_flat.json \
      --format flat --id bible-fi-1548 --abbr AGR1548 \
      --translation "Agricola 1548" --lang fi --iso3 fin --year 1548 \
      --license "Public Domain" --region Protestant \
      --source "https://kaino.kotus.fi/korpus/vks/meta/agricola/agricola_coll_rdf.xml" \
      --allow-partial --dry-run
"""
import argparse, collections, glob, html as htmlmod, importlib, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# Same VKS book codes as the 1642 — one map, one source of truth. Numbered
# sibling, so importlib (plain import can't take a leading digit).
BOOK_MAP = importlib.import_module("02_convert_vks_biblia1642").BOOK_MAP

# [A-III-215-[Ps-1:1]] -> vol, page, "Ps-1", verse. The inner book-chapter
# token is split afterwards, because book codes themselves contain hyphens
# and digits (1Kor, 5Ms, UT-e-Rom).
MARKER = re.compile(r"\[A-(I{1,3})-(\d+[ab]?)-\[([^\]:]+):(\d+)\^?\]\]")
BOOK_CH = re.compile(r"(.+)-(\d+|e)$")
PAGE_BREAK = re.compile(r"\[A-I{1,3}-\d+[ab]?\]")   # bare page marker, no verse


BOLD_OPEN, BOLD_CLOSE = "⟦", "⟧"   # ⟦…⟧ sentinels around <b> content
BOLD_SPAN = re.compile(r"⟦[^⟧]*⟧")


def page_text(path):
    raw = open(path, encoding="utf-8", errors="replace").read()
    txt = re.sub(r"<script.*?</script>|<style.*?</style>", " ", raw, flags=re.S)
    # <b> blocks are chrome/titles/chapter arguments — with ONE exception, a
    # verse whose entire text the corpus set bold (Ps 72:20's "Loppu Dauidin
    # isain poian rucouxist"). So bold content is SENTINEL-wrapped here and
    # resolved per segment in parse_file: dropped when the segment has plain
    # text of its own, kept when it IS the verse.
    txt = re.sub(r"<b>(.*?)</b>", BOLD_OPEN + r"\1" + BOLD_CLOSE, txt, flags=re.S | re.I)
    txt = re.sub(r"<[^>]+>", " ", txt)
    txt = htmlmod.unescape(htmlmod.unescape(txt))   # some entities double-escaped
    txt = re.sub(r"\s+", " ", txt)
    # Site chrome ends at the [Selaukseen] browse link; the page proper follows.
    cut = txt.find("[Selaukseen]")
    if cut >= 0:
        txt = txt[cut + len("[Selaukseen]"):]
    return txt


def resolve_bold(seg):
    """Resolve a segment that may contain ⟦bold⟧ spans (headings, rubrics,
    chapter arguments) into the verse text the closing marker refers to.

    Bold acts as a SEPARATOR between units, so when plain text follows the last
    bold span, that tail is the verse and anything before the bold belongs to
    the previous unit — in the liturgical works that leading matter is typically
    a response like "Jumalan olcon Kijtos" closing the previous reading, which
    would otherwise be glued onto the front of the next verse (it was: Gen 1:1
    came out as "Jumalan olcon Kijtos . Alghusa Jumala Loij Taiuaan ia Maan .").
    With no tail, the bold ends the segment as the next unit's heading and the
    verse is the plain text before it. With no plain text at all, the bold IS
    the verse (exactly one case: Ps 72:20).

    Verified: no segment in the four SCRIPTURE volumes has plain text on both
    sides of a bold span, so this rule cannot alter their 12,931 verses; all 43
    such segments are in the liturgical works.
    """
    spans = list(BOLD_SPAN.finditer(seg))
    if spans:
        tail = re.sub(r"\s+", " ", seg[spans[-1].end():]).strip()
        if tail:
            return tail
    plain = re.sub(r"\s+", " ", BOLD_SPAN.sub(" ", seg)).strip()
    if plain:
        return plain
    return re.sub(r"\s+", " ", seg.replace(BOLD_OPEN, " ").replace(BOLD_CLOSE, " ")).strip()


def parse_file(path):
    """-> {(bookNum, chapter, verse): text} for this file; plus stats."""
    parts = MARKER.split(page_text(path))
    verses, skipped_codes = {}, collections.Counter()
    front_segs = variant_repeats = 0
    prev_key = None
    # parts = [pre, vol, page, book-ch, vs, pre, vol, ...]
    for i in range(0, len(parts) - 5, 5):
        seg = parts[i]
        if i == 0:
            # The file's opening runs title-page material (e.g. the Weisut's
            # "Ellei Me Somalaiset szaa…" rhyme) into the first marked verse's
            # segment, with no verse marker of its own. The corpus separates
            # them with bare page-break markers, and the verse text proper is
            # what follows the LAST one — keep only that tail.
            last = None
            for m in PAGE_BREAK.finditer(seg):
                last = m
            if last:
                seg = seg[last.end():]
        seg = PAGE_BREAK.sub(" ", seg)
        seg = resolve_bold(seg)
        bc  = parts[i + 3]
        vs  = int(parts[i + 4])
        m = BOOK_CH.fullmatch(bc)
        if not m:
            skipped_codes[bc] += 1
            continue
        code, ch = m.group(1), m.group(2)
        if ch == "e" or vs == 0 or code.startswith("UT-e"):
            front_segs += 1               # prefaces, headings, NT-preface quotes
            prev_key = None
            continue
        if code not in BOOK_MAP:
            skipped_codes[code] += 1
            continue
        key = (BOOK_MAP[code], int(ch), vs)
        if key in verses:
            if key == prev_key:
                # Same address twice IN A ROW = page/column-split verse.
                verses[key] = (verses[key] + " " + seg).strip()
            elif verses[key] != seg:
                variant_repeats += 1      # versification anomaly — keep first
        else:
            verses[key] = seg
        prev_key = key
    return verses, skipped_codes, front_segs, variant_repeats


# Which saved page is what. Matched against the page's own title line; a file
# matching neither is reported and skipped (the 1642 saves and the reun- pages
# land there, and they carry no [A-…] verse markers anyway).
SCRIPTURE_TITLES  = ("Uuden testamentin", "Evankeliumit", "Psalttari", "Weisut")
LITURGICAL_TITLES = ("Rukouskirja", "ABC-kirja", "Abc-kiria", "Käsikirja", "Messu")


def classify(path):
    """-> 'scripture' | 'liturgical' | None, from the saved page's title."""
    head = page_text(path)[:400]
    base = os.path.basename(path)
    for hay in (base, head):
        if any(k in hay for k in LITURGICAL_TITLES):
            return "liturgical"
    for hay in (base, head):
        if any(k in hay for k in SCRIPTURE_TITLES):
            return "scripture"
    return None


def runs_of(addresses, min_run):
    """Keep only addresses inside a run of >= min_run consecutive verses
    within one (book, chapter). Returns the kept set."""
    by_chapter = collections.defaultdict(list)
    for book, chapter, verse in addresses:
        by_chapter[(book, chapter)].append(verse)
    kept = set()
    for (book, chapter), verses in by_chapter.items():
        verses.sort()
        start = prev = verses[0]
        for verse in verses[1:] + [None]:
            if verse == prev + 1:
                prev = verse
                continue
            if prev - start + 1 >= min_run:
                kept.update((book, chapter, v) for v in range(start, prev + 1))
            if verse is not None:
                start = prev = verse
    return kept


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="directory of saved VKS HTML pages")
    ap.add_argument("--output", required=True, help="flat JSON output path")
    ap.add_argument("--no-gapfill", action="store_true",
                    help="scripture volumes only — reproduces the pre-2026-08-15 output")
    ap.add_argument("--min-run", type=int, default=3,
                    help="a liturgical gap is filled only from a run of this many "
                         "consecutive verses (default 3)")
    ap.add_argument("--manifest", help="where to list the gap-filled addresses "
                                       "(default: <output>-gapfill.json)")
    a = ap.parse_args()

    merged, conflicts = {}, 0
    files = sorted(glob.glob(os.path.join(a.input, "*.html")))
    if not files:
        sys.exit(f"ERROR: no .html files in {a.input}")
    scripture = [f for f in files if classify(f) == "scripture"]
    liturgical = [f for f in files if classify(f) == "liturgical"]
    other = [f for f in files if classify(f) is None]
    if other:
        print(f"(not Agricola pages, ignored: {len(other)} file(s))")

    # ---- pass 1: the scripture volumes, which own every address they carry
    for f in scripture:
        verses, skipped, front, variants = parse_file(f)
        base = os.path.basename(f)[:48]
        if not verses:
            print(f"SKIP (no Agricola verses): {base}")
            continue
        dup_same = dup_diff = new = 0
        for k, txt in verses.items():
            if k in merged:
                if merged[k] == txt:
                    dup_same += 1        # duplicate save of the same page
                else:
                    dup_diff += 1        # conflicting text: keep first, warn below
            else:
                merged[k] = txt
                new += 1
        conflicts += dup_diff
        print(f"OK   {base}  +{new} verses "
              f"(dupes: {dup_same} identical, {dup_diff} CONFLICTING across files; "
              f"{variants} versification-anomaly repeats kept-first; "
              f"front-matter segments skipped: {front}; "
              f"non-canonical codes: {dict(skipped) or 'none'})")

    # ---- pass 2: liturgical works fill GAPS ONLY, and only from runs
    gapfilled = {}
    if not a.no_gapfill:
        for f in liturgical:
            verses, _, _, _ = parse_file(f)
            base = os.path.basename(f)[:48]
            fresh = {k: t for k, t in verses.items() if k not in merged and k not in gapfilled}
            keep = runs_of(set(fresh), a.min_run)
            dropped = len(fresh) - len(keep)
            for k in keep:
                merged[k] = fresh[k]
                gapfilled[k] = os.path.basename(f)
            print(f"GAP  {base}  +{len(keep)} verses filled "
                  f"({len(verses) - len(fresh)} already covered by a scripture volume; "
                  f"{dropped} isolated quotations below the {a.min_run}-verse run "
                  f"threshold, not used)")

    empties = [k for k, t in merged.items() if not t]
    per_book = collections.Counter(bn for bn, _, _ in merged)
    print(f"\nTOTAL: {len(merged)} verses across {len(per_book)} books; "
          f"{len(empties)} empty, {conflicts} conflicting duplicates")
    if empties:
        print("EMPTY:", sorted(empties)[:20], "…" if len(empties) > 20 else "")
    # Agricola is partial BY DESIGN — no missing-book nag. But the two halves
    # he did complete should be whole; a hole THERE means a save went wrong.
    nt = [n for n in range(40, 67) if n not in per_book]
    if nt:
        print(f"WARNING: NT books missing {nt} — Se Wsi Testamenti is complete "
              f"in the corpus; check the two NT page saves.")
    if 19 not in per_book:
        print("WARNING: no Psalms — check the Psalttari page save.")

    if gapfilled:
        per_work = collections.Counter(gapfilled.values())
        per_book = collections.Counter(b for b, _, _ in gapfilled)
        print(f"\nGAP-FILLED {len(gapfilled)} verses from the liturgical works "
              f"(runs of >= {a.min_run}); books touched: {sorted(per_book)}")
        for w, n in per_work.most_common():
            print(f"   {n:5}  {w[:60]}")
        manifest = a.manifest or (os.path.splitext(a.output)[0] + "-gapfill.json")
        with open(manifest, "w", encoding="utf-8") as fh:
            json.dump({"metadata": {"edition": "AGR1548", "min_run": a.min_run,
                                    "count": len(gapfilled),
                                    "note": "verses absent from Agricola's scripture "
                                            "volumes, supplied from the marked Bible text "
                                            "in his liturgical works"},
                       "verses": [{"book": b, "chapter": c, "verse": v, "from": w}
                                  for (b, c, v), w in sorted(gapfilled.items())]},
                      fh, ensure_ascii=False, indent=1)
        print(f"wrote {manifest}")

    out = {
        "metadata": {"name": "Agricola 1548", "language": "Finnish",
                     "source": "Kotus VKS corpus (kaino.kotus.fi), CC BY / PUB"},
        "verses": [{"book": bn, "chapter": ch, "verse": vs, "text": merged[(bn, ch, vs)]}
                   for bn, ch, vs in sorted(merged)],
    }
    with open(a.output, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False)
    print(f"wrote {a.output} ({os.path.getsize(a.output)} bytes)")


if __name__ == "__main__":
    main()
