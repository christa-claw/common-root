#!/usr/bin/env python3
"""
check_class_size.py — enforce the standing class-size rule.

The rule is the project's class-size limit (adopted
2026-06-28). One functionality per class is the organizing principle; the line
thresholds are guardrails on top of it:

    ideal        100-300 lines
    >= 1000      split candidate — plan an extraction
    >  1500      split, not optional
    >  2000      hard ceiling, never to be exceeded

Written because the rule was recorded and enforced by nothing, which is how
EditionInfo.java reached 13,545 lines — more than six times a ceiling described
as never to be exceeded — while every other check in the project stayed green.
A rule nothing checks is a rule that gets skipped by whoever has not read that
particular file today.

BASELINE, not a gate. Two files are over the thresholds right now and neither
can be fixed by this script. Listing them in KNOWN keeps the exit code
meaningful: 0 means nothing NEW crossed a line and nothing already over it grew.
A file that shrinks below its baseline is reported as STALE so the numbers get
ratcheted down rather than quietly rotting upward — the same idiom
check_edition_info.py uses for its acknowledged findings.

Usage:
    python3 scripts/site/check_class_size.py            # report + exit code
    python3 scripts/site/check_class_size.py --quiet    # only problems
    python3 scripts/site/check_class_size.py --all      # every file, largest first

Run from the repo root. Stdlib only.
"""

import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ROOTS = [os.path.join(REPO, "app", "src", "main", "java")]

CANDIDATE = 1000
MUST_SPLIT = 1500
CEILING = 2000

# Known, accepted-for-now sizes: path relative to the repo -> (lines, why).
# The number is a CAP, not a note — exceeding it fails. Lower it when the file
# shrinks; delete the entry when it drops under CANDIDATE.
KNOWN = {
    "app/src/main/java/org/religioustext/app/ui/views/ReaderView.java": (
        3237,
        "over the hard ceiling; its split is mandatory. "
        "First extraction: SourceCatalog + SourceRow (kills the row[6] magic-index "
        "decoding), then WindowRenderer, the comments/notes UI, the column-controls "
        "builder. Was ~3000 when the notes were written, so it is still growing.",
    ),
    "app/src/main/java/org/religioustext/app/ui/views/AboutView.java": (
        1309,
        "split candidate. The translation grids (addTextCard and the roster) are the "
        "obvious extraction.",
    ),
}


def java_files():
    for root in ROOTS:
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in sorted(filenames):
                if name.endswith(".java"):
                    full = os.path.join(dirpath, name)
                    yield os.path.relpath(full, REPO), full


def line_count(path):
    # Raw file lines, per the rule: comments and embedded JS strings included.
    with open(path, encoding="utf-8") as handle:
        return sum(1 for _ in handle)


def main():
    quiet = "--quiet" in sys.argv
    show_all = "--all" in sys.argv

    sizes = {rel: line_count(full) for rel, full in java_files()}

    failures, notes, stale = [], [], []

    for rel, lines in sorted(sizes.items(), key=lambda kv: -kv[1]):
        capped = KNOWN.get(rel)
        if capped:
            cap, why = capped
            if lines > cap:
                failures.append(
                    (rel, lines, "GREW past its baseline of %d (+%d)" % (cap, lines - cap))
                )
            elif lines < cap:
                stale.append((rel, lines, cap))
            else:
                notes.append((rel, lines, why))
            continue
        if lines > CEILING:
            failures.append((rel, lines, "OVER THE HARD CEILING of %d" % CEILING))
        elif lines > MUST_SPLIT:
            failures.append((rel, lines, "over %d — split is not optional" % MUST_SPLIT))
        elif lines >= CANDIDATE:
            failures.append((rel, lines, "split candidate (>= %d)" % CANDIDATE))

    print("CLASS SIZE — %d java files, thresholds %d / %d / %d"
          % (len(sizes), CANDIDATE, MUST_SPLIT, CEILING))

    for rel, lines, why in failures:
        print("  FAIL  %5d  %s\n               %s" % (lines, rel, why))

    if not quiet:
        for rel, lines, why in notes:
            print("  note  %5d  %s\n               %s" % (lines, rel, why))

    for rel, lines, cap in stale:
        print("  STALE %5d  %s\n               below its baseline of %d — lower the"
              " KNOWN entry to %d (or drop it if under %d)"
              % (lines, rel, cap, lines, CANDIDATE))

    if show_all:
        print("\n  all files, largest first:")
        for rel, lines in sorted(sizes.items(), key=lambda kv: -kv[1]):
            print("    %5d  %s" % (lines, rel))

    biggest = max(sizes.values()) if sizes else 0
    over = sum(1 for n in sizes.values() if n >= CANDIDATE)
    print("\n  largest %d lines; %d file(s) at or over %d; %d accepted baseline(s)"
          % (biggest, over, CANDIDATE, len(notes) + len(stale)))

    if failures:
        print("  %d NEW or GROWN violation(s)." % len(failures))
    elif stale:
        print("  no new violations, but %d baseline(s) are stale." % len(stale))
    else:
        print("  all clear (%d accepted, 0 new)" % len(notes))

    sys.exit(1 if (failures or stale) else 0)


if __name__ == "__main__":
    main()
