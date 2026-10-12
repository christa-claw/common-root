#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
import_words.py — load words-oshb.xml into a NEW local BaseX database, `words`, and check it.

Milestone 2 of docs/strongs-design.md. Writes use `curl`, never Spring/RestTemplate (the project
rule: it corrupts the XML prolog). The corpus database (`religioustext`) is never touched: the
word layer lives in its own database, which `scripts/publish/build_basex_image.sh` ships with the
editions because it snapshots BaseX's whole data directory.

Refuses to overwrite an existing `words` database unless --replace is given.

After the import it builds an ATTRIBUTE index (the concordance lookups are `w[@strongs=...]`),
then verifies against the file: verse and word counts, a chapter fetch, a Strong's lookup.

Usage:
    python3 scripts/strongs/import_words.py [--file out/strongs/words-oshb.xml] [--db words] [--replace]
"""
import argparse, os, re, subprocess, sys, time, urllib.parse, xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "http://localhost:8984/rest"
AUTH = "admin:admin"          # local dev credentials (COMMANDS.md); prod never takes writes from here
NSDECL = "declare default element namespace 'http://religioustext.org/schema/words/1.0';"


def curl(*args):
    r = subprocess.run(["curl", "-s", "-S", "-u", AUTH, "--fail-with-body"] + list(args),
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("curl failed (%d): %s%s" % (r.returncode, r.stderr.strip(), r.stdout[:300]))
    return r.stdout


def xquery(q):
    return curl("-G", BASE, "--data-urlencode", "query=" + q).strip()


def databases():
    return re.findall(r">([^<]+)</rest:database>", curl(BASE))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", default=os.path.join(HERE, "..", "..", "out", "strongs", "words-oshb.xml"))
    ap.add_argument("--db", default="words")
    ap.add_argument("--replace", action="store_true", help="drop and re-create the database if it exists")
    a = ap.parse_args()
    path = os.path.abspath(a.file)
    if not os.path.exists(path):
        sys.exit("No %s — run scripts/strongs/build_words_oshb.py first." % path)
    if a.db == "religioustext":
        sys.exit("Refusing: that is the corpus database.")

    head = ET.iterparse(path, events=("start",))
    root = next(head)[1]
    want_verses, want_words = int(root.get("totalVerses")), int(root.get("totalWords"))
    res = os.path.basename(path)

    if a.db in databases():
        if not a.replace:
            sys.exit("Database %r already exists. Use --replace to drop and re-create it." % a.db)
        xquery("db:drop('%s')" % a.db)
        print("dropped existing %r" % a.db)

    xquery("db:create('%s')" % a.db)       # BaseX 9.6 will not create a database on PUT
    t = time.time()
    curl("-X", "PUT", "-H", "Content-Type: application/xml", "--data-binary", "@" + path,
         "%s/%s/%s" % (BASE, a.db, urllib.parse.quote(res)))
    print("imported %s into %r in %.1fs" % (res, a.db, time.time() - t))

    # attribute index for the concordance; no text/token index needed.
    xquery("db:optimize('%s', true(), map{'attrindex': true(), 'textindex': false(), 'tokenindex': false()})" % a.db)
    print("optimised with an attribute index")

    doc = "db:open('%s', '%s')" % (a.db, res)
    verses = int(xquery(NSDECL + "count(%s//verse)" % doc))
    words = int(xquery(NSDECL + "count(%s//w)" % doc))
    ok = (verses, words) == (want_verses, want_words)
    print("verses %d (file %d), words %d (file %d)  %s" % (verses, want_verses, words, want_words, "OK" if ok else "MISMATCH"))

    t = time.time()
    gen1 = xquery(NSDECL + "string-join(%s/words/book[@code='GEN']/chapter[@number='1']/verse[@number='1']/w/@strongs, ' ')" % doc)
    print("Gen 1:1 numbers: %s   (%.0f ms)" % (gen1, 1000 * (time.time() - t)))
    t = time.time()
    n = xquery(NSDECL + "count(%s//w[@strongs='H430'])" % doc)
    print("words with H430: %s   (%.0f ms)" % (n, 1000 * (time.time() - t)))
    t = time.time()
    chap = xquery(NSDECL + "count(%s/words/book[@code='PSA']/chapter[@number='51']//w)" % doc)
    print("Ps 51 words: %s   (%.0f ms)" % (chap, 1000 * (time.time() - t)))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
