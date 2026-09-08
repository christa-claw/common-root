#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
check_edition_info.py — verify EditionInfo.PAGES translations against their
English originals, and report coverage gaps.

Written for the recurring edition-info drafting task: a run that writes twelve
translations at once fails silently by truncating one of them, and the cheapest
way to catch that is to compare the things that must match REGARDLESS of
language — fact count, hero paragraph count, cross-link targets, reader href,
and every date carried over in some form.

Entries are read out of the COMPILED class by reflection, not parsed from
source, so what is checked is what the application actually holds. Run a
`mvn -o compile` first.

Usage:
    python3 scripts/site/check_edition_info.py              # check + coverage
    python3 scripts/site/check_edition_info.py --coverage   # coverage only
    python3 scripts/site/check_edition_info.py --quiet      # only failures; exit 1 if any

Run from the repo root. Stdlib only. Requires a JDK on PATH and
app/target/classes built.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
import collections

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLASSES = os.path.join(REPO, "app", "target", "classes")
VIEWS = os.path.join(REPO, "app", "src", "main", "java", "org", "religioustext", "app", "ui", "views")
LOCALE_UTIL = os.path.join(REPO, "app", "src", "main", "java", "org", "religioustext", "app", "i18n", "LocaleUtil.java")

DUMPER = r'''
import java.lang.reflect.*; import java.util.*;
public class Dump {
  static String esc(String s){ if(s==null) return "null"; StringBuilder b=new StringBuilder("\"");
    for(char c: s.toCharArray()){ switch(c){ case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
      case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
      default -> { if(c<0x20) b.append(String.format("\\u%04x",(int)c)); else b.append(c);} } }
    return b.append('"').toString(); }
  public static void main(String[] a) throws Exception {
    Class<?> k = Class.forName("org.religioustext.app.ui.views.EditionInfo");
    Map<?,?> pages = (Map<?,?>) k.getField("PAGES").get(null);
    StringBuilder o = new StringBuilder("{\n"); boolean first=true;
    for(Map.Entry<?,?> e : pages.entrySet()){
      if(!first) o.append(",\n"); first=false; Object v=e.getValue();
      o.append("  ").append(esc(e.getKey().toString())).append(": {");
      for(String f : new String[]{"abbr","lang","slug","title","description","heroHeading","heroBody","readerHref","thanks"}){
        Object val=k.getField(f).get(v);
        o.append(esc(f)).append(":").append(esc(val==null?null:val.toString())).append(","); }
      @SuppressWarnings("unchecked") Map<String,String> facts=(Map<String,String>) k.getField("facts").get(v);
      o.append("\"facts\":["); boolean f1=true;
      for(Map.Entry<String,String> fe : facts.entrySet()){ if(!f1) o.append(","); f1=false;
        o.append("[").append(esc(fe.getKey())).append(",").append(esc(fe.getValue())).append("]"); }
      o.append("]}"); }
    System.out.println(o.append("\n}\n")); } }
'''

# ── numeral normalisation ────────────────────────────────────────────────────
# A translation may write a year in its own numerals. These are CORRECT and
# must not be reported: 一六八三年, ١٨٧٧, १८७७. Only a date that appears in no
# form at all is a finding.
CJK = {"〇": "0", "零": "0", "一": "1", "二": "2", "三": "3", "四": "4",
       "五": "5", "六": "6", "七": "7", "八": "8", "九": "9"}
CJK_YEAR = re.compile("[" + "".join(CJK) + "]{3,4}(?=年|至|–|—)")
YEAR = re.compile(r"(?<!\d)(\d{3,4})(?!\d)")
LINK = re.compile(r"\[\[([A-Za-z0-9-]+)\|")

# Findings already read and confirmed good. A date a translation spells out in
# WORDS rather than digits is correct — better than correct, it is what a fluent
# writer does — but no amount of numeral normalisation will see it. Listing them
# keeps the exit code meaningful: 0 means nothing NEW drifted. Each is reported
# as a note, and one that stops firing is reported as STALE, so the list cannot
# quietly rot.
ACKNOWLEDGED = {
    ("AGR1548", "ar", "DATES ABSENT"):
        "1530 as ثلاثينيات "
        "القرن السادس "
        "عشر; 150 written out in words",
    ("AGR1548", "he", "DATES ABSENT"):
        "1530 as בשנות ה־30 "
        "של המאה ה־16",
    ("AGR1548", "it", "DATES ABSENT"):
        "1530 as 'negli anni Trenta del Cinquecento'",
    ("FB1642", "ar", "DATES ABSENT"):
        "1550 as خمسينيات "
        "القرن السادس "
        "عشر",
    ("FB1642", "he", "DATES ABSENT"):
        "1550 as בשנות ה־50 "
        "של המאה ה־16",
}


def normalise(s):
    s = CJK_YEAR.sub(lambda m: "".join(CJK[c] for c in m.group(0)), s)
    out = []
    for ch in s:
        if ch.isdigit() and not ("0" <= ch <= "9"):
            try:
                out.append(str(unicodedata.digit(ch)))
            except Exception:
                out.append(ch)
        else:
            out.append(ch)
    return "".join(out)


def load_pages():
    if not os.path.isdir(CLASSES):
        sys.exit("no compiled classes at %s — run `cd app && mvn -o compile` first" % CLASSES)
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "Dump.java")
        open(src, "w", encoding="utf-8").write(DUMPER)
        r = subprocess.run(["javac", "-cp", CLASSES, "-d", tmp, src],
                           capture_output=True, text=True)
        if r.returncode:
            sys.exit("javac failed:\n" + r.stderr)
        r = subprocess.run(["java", "-cp", CLASSES + os.pathsep + tmp, "Dump"],
                           capture_output=True, text=True)
        if r.returncode:
            sys.exit("java failed:\n" + r.stderr)
        return json.loads(r.stdout)


def locales():
    m = re.search(r"LOCALES\s*=\s*List\.of\(([^)]*)\)", open(LOCALE_UTIL, encoding="utf-8").read())
    if not m:
        sys.exit("could not read LOCALES from " + LOCALE_UTIL)
    return [t.strip().lower() for t in m.group(1).split(",") if t.strip()]


def roster():
    src = open(os.path.join(VIEWS, "AboutView.java"), encoding="utf-8").read()
    return re.findall(r'addTextCard\(\w+,\s*"([A-Za-z0-9-]+)"', src)


def blob(e):
    parts = [e["title"], e["description"], e["heroHeading"], e["heroBody"]]
    parts += [k + " " + v for k, v in e["facts"]]
    return normalise("\n".join(p for p in parts if p and p != "null"))


def paras(e):
    return len([p for p in e["heroBody"].split("\n\n") if p.strip()])


def links(e):
    return set(LINK.findall(" ".join(v for _, v in e["facts"])))


def main():
    quiet = "--quiet" in sys.argv
    pages = load_pages()
    locs = locales()
    cards = roster()

    by = collections.defaultdict(dict)
    for e in pages.values():
        by[e["abbr"]][e["lang"]] = e

    # ── integrity ────────────────────────────────────────────────────────────
    fails, notes, fired = [], [], set()
    for abbr, langs in sorted(by.items()):
        en = langs.get("en")
        if not en:
            fails.append((abbr, "-", "NO ENGLISH ORIGINAL", "translations exist without one"))
            continue
        for lang, e in sorted(langs.items()):
            if lang == "en":
                continue
            if len(e["facts"]) != len(en["facts"]):
                fails.append((abbr, lang, "FACT COUNT", "%d vs %d" % (len(e["facts"]), len(en["facts"]))))
            if paras(e) != paras(en):
                fails.append((abbr, lang, "HERO PARAGRAPHS", "%d vs %d" % (paras(e), paras(en))))
            if links(e) != links(en):
                fails.append((abbr, lang, "CROSS-LINKS", "%s vs %s" % (sorted(links(e)), sorted(links(en)))))
            if e["readerHref"] != en["readerHref"]:
                fails.append((abbr, lang, "READER HREF", "%s vs %s" % (e["readerHref"], en["readerHref"])))
            if not e["description"] or e["description"] == "null":
                fails.append((abbr, lang, "NO DESCRIPTION", "meta description empty"))
            wanted = YEAR.findall(blob(en))
            have = set(YEAR.findall(blob(e)))
            gone = sorted({y for y in wanted if y not in have})
            if gone:
                key = (abbr, lang, "DATES ABSENT")
                if key in ACKNOWLEDGED:
                    fired.add(key)
                    notes.append((abbr, lang, ACKNOWLEDGED[key]))
                else:
                    fails.append((abbr, lang, "DATES ABSENT",
                                  "in English, in no numeral form here: " + ", ".join(gone)))

    stale = sorted(set(ACKNOWLEDGED) - fired)

    if "--coverage" not in sys.argv:
        print("INTEGRITY — %d translations checked against their English originals"
              % sum(len(v) - 1 for v in by.values() if "en" in v))
        for f in fails:
            print("  FAIL  %-9s %-3s %-20s %s" % f)
        if not quiet:
            for n in notes:
                print("  note  %-9s %-3s %s" % n)
        for abbr, lang, kind in stale:
            print("  STALE %-9s %-3s acknowledged %s no longer fires — drop it from"
                  " ACKNOWLEDGED" % (abbr, lang, kind))
        if fails:
            print("\n  %d NEW failure(s) — read the entry before changing anything." % len(fails))
        elif stale:
            print("\n  no new failures, but %d acknowledgement(s) are stale." % len(stale))
        else:
            print("  all clear (%d acknowledged, 0 new)\n" % len(notes))

    if quiet:
        sys.exit(1 if (fails or stale) else 0)

    # ── coverage ─────────────────────────────────────────────────────────────
    print("COVERAGE — %d roster cards, %d locales, %d possible entries, %d written"
          % (len(cards), len(locs), len(cards) * len(locs), len(pages)))
    nopage = [a for a in cards if a not in by]
    partial = {a: sorted(set(locs) - set(by[a])) for a in by if set(locs) - set(by[a])}
    print("  complete (all %d locales): %s" % (len(locs), sorted(a for a in by if not set(locs) - set(by[a]))))
    print("  no page at all (%d): %s" % (len(nopage), " ".join(nopage)))
    print("  partial (%d):" % len(partial))
    for a in sorted(partial, key=lambda x: len(partial[x])):
        print("    %-9s missing %2d: %s" % (a, len(partial[a]), " ".join(partial[a])))
    orphans = [a for a in by if a not in cards]
    if orphans:
        print("  ORPHANS (page but no AboutView card): %s" % " ".join(orphans))

    sys.exit(1 if (fails or stale) else 0)


if __name__ == "__main__":
    main()
