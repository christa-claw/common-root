#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
classify_versification.py — which verse-numbering scheme does each Bible edition use?

Cross-references and comments are anchored in the canonical (KJV) numbering, so
every edition must say how ITS numbers map onto that. This script is the first
step: it reads per-chapter verse counts from BaseX (read-only, the same query
compare_chapter_lengths.py uses), compares each Bible edition with the reference
edition over the 66 Protestant books they share, and writes a guess plus the
evidence to docs/versification-ledger.tsv for a human to review.

It only ever GUESSES from verse counts. The ledger's `scheme` column is the
reviewed answer; re-running preserves any scheme a reviewer has written
(`reviewed` = yes) and refreshes only the evidence columns.

Fingerprints (all against the KJV count):
  hebrew-chapters — Joel has 4 chapters (KJV 3) and/or Malachi 3 (KJV 4).
  psalm-titles    — Psalm titles are counted as verse 1, so Ps 9 has 21 verses (KJV 20).
  lxx-psalter     — Greek/Latin Psalter numbering: Ps 9 runs on through what the KJV
                    calls Ps 9-10, so Ps 9 has about twice the KJV's verses.
  kjv             — every shared chapter has the KJV's verse count.
  kjv-like        — no signal above, but a few chapters differ (usually omitted or merged
                    verses, not a numbering shift); look at diff_books.
  unclassified    — no signal above yet 60+ chapters differ: a real numbering shift this
                    script cannot name (the Russian Synodal is one). Needs a human.
Tags combine with "+" (the 1545 Luther: hebrew-chapters+psalm-titles).

Usage:
    python3 scripts/bibles/classify_versification.py [--reference bible-kjv-1611] [--out docs/versification-ledger.tsv]
"""
import argparse, base64, collections, csv, os, sys, urllib.parse, urllib.request

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH = base64.b64encode(b"admin:admin").decode()
NS = "http://religioustext.org/schema/1.0"

CANON = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG "
         "ISA JER LAM EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT "
         "ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split()
HEADER = ["edition", "books_shared", "diff_chapters", "net_verses", "joel_chapters", "mal_chapters",
          "ps9_verses", "ps51_verses", "guess", "scheme", "reviewed", "diff_books"]


def xquery(q):
    url = f"{BASEX_URL}?{urllib.parse.urlencode({'query': q})}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read().decode("utf-8").strip()


def all_counts():
    """{edition_id: {(book, chapter): verse_count}} for every Bible edition, in ONE query."""
    rows = xquery(
        f"declare namespace rt='{NS}';"
        f" for $t in //rt:text[starts-with(@id,'bible-')] for $b in $t/rt:book for $c in $b/rt:chapter"
        f" return string-join((string($t/@id), string($b/@code), string($c/@number),"
        f"   string(count($c//rt:verse))), '&#9;')")
    out = collections.defaultdict(dict)
    for row in rows.splitlines():
        p = row.split("\t")
        if len(p) == 4 and p[2].isdigit() and p[3].isdigit() and p[1] in CANON:
            out[p[0]][(p[1], int(p[2]))] = int(p[3])
    return out


def classify(ed, ref):
    shared = {b for b, _ in ed} & {b for b, _ in ref}
    diffs = [(b, c, ed[(b, c)], ref[(b, c)]) for (b, c) in sorted(ref, key=lambda k: (CANON.index(k[0]), k[1]))
             if b in shared and (b, c) in ed and ed[(b, c)] != ref[(b, c)]]
    chapters = lambda book, src: len([1 for (b, _) in src if b == book])
    joel, mal = chapters("JOL", ed), chapters("MAL", ed)
    ps9, ps51 = ed.get(("PSA", 9)), ed.get(("PSA", 51))
    # Independent signals, reported as tags: an edition can divide Joel/Malachi the
    # Hebrew way yet number Psalm titles like the KJV, and the other way round.
    tags = []
    if (("JOL" in shared and joel == 4 and ref_ch(ref, "JOL") == 3)
            or ("MAL" in shared and mal == 3 and ref_ch(ref, "MAL") == 4)):
        tags.append("hebrew-chapters")
    r9 = ref.get(("PSA", 9))
    if "PSA" in shared and ps9 and r9:
        if ps9 >= 1.5 * r9:
            tags.append("lxx-psalter")
        elif ps9 == r9 + 1:
            tags.append("psalm-titles")
    # No signal and no differences: KJV numbering. No signal but differences: almost
    # always omitted/merged verses (NIV, Synodal, Tischendorf), not a numbering shift —
    # but only a reviewer can say, so it is flagged rather than assumed.
    if tags:
        guess = "+".join(tags)
    elif not diffs:
        guess = "kjv"
    else:
        guess = "unclassified" if len(diffs) >= 60 else "kjv-like"   # many differing chapters = a real shift
    per_book = collections.Counter()
    for b, _, a, r in diffs:
        per_book[b] += a - r
    return {
        "books_shared": len(shared), "diff_chapters": len(diffs),
        "net_verses": sum(a - r for _, _, a, r in diffs),
        "joel_chapters": joel or "", "mal_chapters": mal or "",
        "ps9_verses": ps9 or "", "ps51_verses": ps51 or "", "guess": guess,
        "diff_books": ",".join(f"{b}{d:+d}" for b, d in sorted(per_book.items(), key=lambda kv: CANON.index(kv[0]))),
    }


def ref_ch(ref, book):
    return len([1 for (b, _) in ref if b == book])


def read_reviewed(path):
    """{edition: (scheme, reviewed)} from an existing ledger, so a re-run never clobbers a review."""
    kept = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for row in csv.DictReader((l for l in f if not l.startswith("#")), delimiter="\t"):
                if row.get("reviewed") == "yes":
                    kept[row["edition"]] = row["scheme"]
    return kept


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reference", default="bible-kjv-1611")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "..", "docs", "versification-ledger.tsv"))
    args = ap.parse_args()

    counts = all_counts()
    ref = counts.pop(args.reference, None)
    if not ref:
        sys.exit(f"No chapters found for reference {args.reference}.")
    kept = read_reviewed(args.out)
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write(f"# versification ledger — verse-numbering scheme per Bible edition, vs {args.reference}\n"
                "# guess = from verse counts (scripts/bibles/classify_versification.py); scheme = the reviewed answer.\n"
                "# Edit `scheme` and set reviewed=yes; re-runs keep reviewed rows' scheme.\n")
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(HEADER)
        for ed in sorted(counts):
            r = classify(counts[ed], ref)
            scheme, reviewed = (kept[ed], "yes") if ed in kept else (r["guess"] if r["guess"] == "kjv" else "", "no")
            w.writerow([ed] + [r[k] for k in HEADER[1:8]] + [r["guess"], scheme, reviewed, r["diff_books"]])
    tally = collections.Counter(classify(counts[e], ref)["guess"] for e in counts)
    print(f"{len(counts)} editions -> {args.out}  ", dict(tally))


if __name__ == "__main__":
    main()
