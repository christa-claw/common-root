#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
check_lexicon_translations.py — coverage and the verification gate for the Strong's lexicon
translations (docs/lexicon-translation-job.md; docs/strongs-design.md).

The English base is app/src/main/resources/i18n/lexicon/hebrew.properties. Each other interface
language has hebrew_<lang>.properties holding ONLY `H####.gloss` and `H####.def` keys; a key a
locale does not have falls back to English in the app. This script

  * reports, per locale, how many glosses and definitions are translated;
  * `--next N` prints the next batch of entries to translate (TSV), most frequent words first
    (docs/lexicon-frequency-hebrew.tsv). A batch is done when EVERY locale has its keys;
  * checks the files (the gate: exit 1 on any failure, the job must not commit then):
      - only .gloss / .def keys, each for a number the English file has; no duplicates;
      - no empty values; one line each; raw UTF-8 (no \\uXXXX escapes);
      - every H#### reference in the English definition survives in the translation.

Locales come from LocaleUtil.java (the authority); English is the base and is not translated.

Usage:
    python3 scripts/strongs/check_lexicon_translations.py                  # check + coverage
    python3 scripts/strongs/check_lexicon_translations.py --coverage       # coverage only
    python3 scripts/strongs/check_lexicon_translations.py --next 400       # next batch (auto phase)
    python3 scripts/strongs/check_lexicon_translations.py --next 100 --phase definitions
    python3 scripts/strongs/check_lexicon_translations.py --quiet          # only failures
"""
import argparse, collections, os, re, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
LEX = os.path.join(REPO, "app", "src", "main", "resources", "i18n", "lexicon")
FREQ = os.path.join(REPO, "docs", "lexicon-frequency-hebrew.tsv")
LOCALE_UTIL = os.path.join(REPO, "app", "src", "main", "java", "org", "religioustext", "app", "i18n", "LocaleUtil.java")

KEY = re.compile(r"^(H\d+)\.(lemma|xlit|gloss|def)$")
REF = re.compile(r"\bH\d+\b")
ESC = re.compile(r"\\u[0-9a-fA-F]{4}")


def locales():
    """Non-English language tags from LocaleUtil.LOCALES, in its order."""
    src = open(LOCALE_UTIL, encoding="utf-8").read()
    consts = dict(re.findall(r'Locale (\w+)\s*=\s*Locale\.forLanguageTag\("([a-zA-Z-]+)"\)', src))
    names = re.search(r"LOCALES\s*=\s*List\.of\(([^)]*)\)", src).group(1)
    tags = [consts.get(n.strip(), "en" if n.strip() == "EN" else None) for n in names.split(",")]
    return [t for t in tags if t and t != "en"]


def read_props(path):
    """(dict, duplicate keys, malformed lines) from a .properties file — raw UTF-8, `key=value`."""
    out, dups, bad = {}, [], []
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        if "=" not in line:
            bad.append(n)
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        if k in out:
            dups.append(k)
        out[k] = v
    return out, dups, bad


def check_locale(tag, english, loc, dups, bad):
    """Failures for one locale file, as strings."""
    fails = ["%s: duplicate key %s" % (tag, k) for k in dups] + ["%s: line %d is not key=value" % (tag, n) for n in bad]
    for k, v in loc.items():
        m = KEY.match(k)
        if not m or m.group(2) not in ("gloss", "def"):
            fails.append("%s: %s — only H####.gloss and H####.def belong in a locale file" % (tag, k))
            continue
        if k not in english:
            fails.append("%s: %s has no English original" % (tag, k))
            continue
        if not v.strip():
            fails.append("%s: %s is empty (omit the key to fall back to English)" % (tag, k))
        if ESC.search(v):
            fails.append("%s: %s uses a \\uXXXX escape; write raw UTF-8" % (tag, k))
        if k.endswith(".def"):
            missing = sorted(set(REF.findall(english[k])) - set(REF.findall(v)))
            if missing:
                fails.append("%s: %s lost the reference(s) %s" % (tag, k, ", ".join(missing)))
    return fails


def frequency_order(english):
    ids = []
    if os.path.exists(FREQ):
        for line in open(FREQ, encoding="utf-8"):
            if line.startswith("#") or not line.strip():
                continue
            sid = line.split("\t", 1)[0]
            if sid + ".gloss" in english:
                ids.append(sid)
    seen = set(ids)
    rest = sorted((k[:-6] for k in english if k.endswith(".gloss") and k[:-6] not in seen), key=lambda s: int(s[1:]))
    return ids + rest


def pending(order, locs, kind):
    """Ids, in order, for which at least one locale lacks <id>.<kind>."""
    return [sid for sid in order if any((sid + "." + kind) not in loc for loc in locs.values())]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--coverage", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--next", type=int, metavar="N", help="print the next N entries to translate (TSV) and exit")
    ap.add_argument("--phase", choices=("auto", "glosses", "definitions"), default="auto")
    a = ap.parse_args()

    english, _, _ = read_props(os.path.join(LEX, "hebrew.properties"))
    ids = [k[:-6] for k in english if k.endswith(".gloss")]
    tags = locales()
    locs, fails = {}, []
    for tag in tags:
        path = os.path.join(LEX, "hebrew_%s.properties" % tag)
        if os.path.exists(path):
            loc, dups, bad = read_props(path)
            fails += check_locale(tag, english, loc, dups, bad)
        else:
            loc = {}
        locs[tag] = loc

    if a.next:
        order = frequency_order(english)
        phase = a.phase
        if phase == "auto":
            phase = "glosses" if pending(order, locs, "gloss") else "definitions"
        kind = "gloss" if phase == "glosses" else "def"
        batch = pending(order, locs, kind)[:a.next]
        print("# phase=%s kind=%s entries=%d  columns: id, english, locales still missing it" % (phase, kind, len(batch)))
        for sid in batch:
            missing = ",".join(t for t in tags if (sid + "." + kind) not in locs[t])
            print("%s\t%s\t%s" % (sid, english.get(sid + "." + kind, ""), missing))
        return

    if not a.quiet or fails:
        if not a.quiet:
            n = len(ids)
            print("Hebrew lexicon: %d entries; %d locales (%s)\n" % (n, len(tags), " ".join(tags)))
            print("  %-6s %9s %12s" % ("locale", "glosses", "definitions"))
            for tag in tags:
                g = sum(1 for i in ids if i + ".gloss" in locs[tag])
                d = sum(1 for i in ids if i + ".def" in locs[tag])
                print("  %-6s %5d/%-4d %7d/%-4d" % (tag, g, n, d, n))
            gl, df = len(pending(ids, locs, "gloss")), len(pending(ids, locs, "def"))
            print("\n  entries still missing a gloss in some locale: %d; a definition: %d" % (gl, df))
        for f in fails:
            print("FAIL " + f)
        if not fails and not a.quiet:
            print("\n0 failures.")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
