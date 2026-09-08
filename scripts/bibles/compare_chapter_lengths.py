#!/usr/bin/env python3
"""
compare_chapter_lengths.py — diff two editions' per-chapter verse counts.

Catches the failure a contiguity check CANNOT see: a verse missing from the END
of a chapter (or two verses merged into one), which leaves numbering unbroken
and so looks healthy. That is exactly how the Emphatic Diaglott interlinear
arrived with Acts 28:30 and 28:31 merged into a single verse.

Compares only the books present in BOTH editions, so an NT-only edition can be
checked against a whole-Bible one without noise.

Usage:
    python3 scripts/bibles/compare_chapter_lengths.py <edition-id> [reference-id]
      e.g. python3 scripts/bibles/compare_chapter_lengths.py bible-diaglott-il-1864 bible-kjv

A difference is not automatically a defect — editions genuinely differ (Griesbach
omits verses the KJV prints). It is a list of places to LOOK.
"""
import sys, urllib.request, urllib.parse, base64, collections

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH      = base64.b64encode(b"admin:admin").decode()
NS        = "http://religioustext.org/schema/1.0"


def xquery(q):
    url = f"{BASEX_URL}?{urllib.parse.urlencode({'query': q})}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req) as r:
        return r.read().decode("utf-8").strip()


def chapter_counts(tid):
    """{(book_code, chapter_number): verse_count} for one edition."""
    rows = xquery(
        f"declare namespace rt='{NS}';"
        f" for $b in db:open('religioustext')//rt:text[@id='{tid}']/rt:book"
        f" for $c in $b/rt:chapter"
        f" return string-join((string($b/@code), string($c/@number),"
        f"   string(count($c//rt:verse))), '&#9;')"
    )
    out = {}
    for row in rows.splitlines():
        p = row.split('\t')
        if len(p) == 3 and p[1].isdigit() and p[2].isdigit():
            out[(p[0], int(p[1]))] = int(p[2])
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    tid = sys.argv[1]
    ref = sys.argv[2] if len(sys.argv) > 2 else "bible-kjv"

    a, b = chapter_counts(tid), chapter_counts(ref)
    if not a:
        sys.exit(f"No chapters found for {tid} — is the id right, and is it imported?")
    if not b:
        sys.exit(f"No chapters found for reference {ref} — pass a different reference id.")

    shared_books = {code for code, _ in a} & {code for code, _ in b}
    print(f"{tid}  vs  {ref}")
    print(f"books in both: {len(shared_books)}")

    diffs, missing = [], []
    for key in sorted(b):
        code, ch = key
        if code not in shared_books:
            continue
        if key not in a:
            missing.append(key)
        elif a[key] != b[key]:
            diffs.append((code, ch, a[key], b[key]))

    if missing:
        print(f"\nCHAPTERS ABSENT from {tid} ({len(missing)}):")
        for code, ch in missing:
            print(f"  {code} {ch}")

    if not diffs:
        print("\nEvery shared chapter matches the reference. Nothing to look at.")
        return

    total = sum(bb - aa for _, _, aa, bb in diffs)
    print(f"\nCHAPTERS DIFFERING ({len(diffs)}, net {total:+d} verses vs reference):")
    print(f"  {'book':<6}{'ch':>4}{'this':>7}{'ref':>7}{'delta':>7}")
    for code, ch, aa, bb in diffs:
        print(f"  {code:<6}{ch:>4}{aa:>7}{bb:>7}{aa-bb:>+7}")
    per_book = collections.Counter()
    for code, _, aa, bb in diffs:
        per_book[code] += aa - bb
    print("\n  by book:", ", ".join(f"{c}{d:+d}" for c, d in sorted(per_book.items())))
    print("\nCheck each against the printed edition: a genuine textual omission is fine"
          "\n(record it in the About note); a merged or truncated verse should be split"
          "\nbefore the edition is used as a parallel column.")


if __name__ == "__main__":
    sys.exit(main())
