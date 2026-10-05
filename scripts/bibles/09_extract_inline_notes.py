#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Take leaked translator footnotes OUT of an edition's verse text and put them
in a notes ledger, using the edition's USFM as the witness.

THE LEAK (CLAUDE_NOTES, "INLINE FOOTNOTE LEAK", 2026-07-24)
-----------------------------------------------------------
Several editions were ingested from sources that had already flattened their
footnotes into the verse:

    In the beginning, God1:1 The Hebrew word rendered “God” is “אֱלֹהִ֑ים”
    (Elohim). created the heavens and the earth.

The corpus keeps no markup around the note, and a note that sits mid-verse has
no detectable END — "(Elohim). created" is indistinguishable from a sentence
break in scripture. So the corpus alone cannot be cleaned safely. The USFM
can: there the same note is `\\f + \\fr 1:1 \\ft …\\f*`, delimited exactly.

WHAT THIS DOES
--------------
For every verse it builds two strings from the USFM — the CLEAN text, and the
LEAKED form (each note replaced by "<origin ref> <note text>", which is how
the flattening source wrote them) — and compares both with the corpus verse,
ignoring whitespace:

    corpus == clean    nothing to do
    corpus == leaked   PROVEN leak: the verse becomes the clean text and the
                       notes go to the ledger
    neither            left alone and reported. Either the edition has moved
                       on since ingest (text drift), or the leak has a shape
                       this script does not recognise.

By default a verse is only rewritten when the result is proven identical to
the USFM's own clean text. Nothing is guessed.

--refresh goes further, and exists because of what the first run on WEB found:
a quarter of its verses (9,491, Psalms almost entirely) were TRUNCATED at the
first paragraph or poetry-line break — "He makes me lie down in green
pastures." and nothing after it. With --refresh every verse that differs from
the USFM takes the USFM's clean text, and the ledger carries the edition's
whole apparatus rather than only the notes that happened to leak. The verse
is still matched by book code, chapter and number; no verse is added or
removed, and a verse the USFM leaves empty (one the edition relegates to a
footnote) is left as it is.

The ledger has the shape NotesSeeder reads (see transcripts/notes-lut1912.json
and 02_convert_osis_flat.py --notes): the notes come back in the reader as
edition-bound comments under that edition's column. The edition needs an
entry in NotesSeeder.EDITIONS or the ledger is skipped at boot.

USAGE
    curl -L -o /tmp/eng-web_usfm.zip https://ebible.org/Scriptures/eng-web_usfm.zip
    # dry run — reports, writes nothing:
    python3 scripts/bibles/09_extract_inline_notes.py --id bible-web --abbr WEB \\
        --usfm /tmp/eng-web_usfm.zip
    # for real — backs the document up, rewrites the verses, writes the ledger:
    python3 scripts/bibles/09_extract_inline_notes.py --id bible-web --abbr WEB \\
        --usfm /tmp/eng-web_usfm.zip --ledger transcripts/notes-web.json \\
        --refresh --apply

AFTERWARDS: the verse TEXT changed, so prod needs the BaseX content image and
a search reindex (COMMANDS.md). Sequence stamps are attributes and survive.
"""
import argparse
import importlib
import io
import json
import os
import re
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "channels"))
sc = importlib.import_module("05_stamp_chronological")     # BaseX helpers
usfm = importlib.import_module("02_convert_usfm_flat")     # marker stripping
osis = importlib.import_module("02_convert_osis_flat")     # OSIS book codes
from comment_id import mint_note_id                        # noqa: E402

NS = f"declare namespace rt='{sc.NS}'; "
OSIS_BY_USFM = {osis.USFM_BY_NUM[n]: o for o, n in osis.OSIS_BOOKS.items()
                if n in osis.USFM_BY_NUM}

RE_NOTE = re.compile(r"\\(f|fe|x)\s(.*?)\\\1\*", re.S)
RE_ORIGIN = re.compile(r"\\(?:fr|xo)\s+([^\\]*)")
BATCH = 150
ANCHOR_WORDS = 5


def nows(s):
    return re.sub(r"\s+", "", s)


def inner(text):
    """USFM character markup off, text kept — clean() minus the note removal."""
    text = usfm.RE_FIG.sub("", text)
    text = usfm.RE_WORD.sub(r"\1", text)
    text = usfm.RE_CHAR.sub(" ", text)
    return usfm.RE_SPACE.sub(" ", text.replace("~", " ")).strip()


def read_usfm(path):
    """{(code, chapter, verse): raw verse string}, notes still in place."""
    texts = []
    if os.path.isdir(path):
        for n in sorted(os.listdir(path)):
            if n.lower().endswith((".usfm", ".sfm")):
                with io.open(os.path.join(path, n), encoding="utf-8-sig") as f:
                    texts.append(f.read())
    else:
        with zipfile.ZipFile(path) as z:
            for n in sorted(z.namelist()):
                if n.lower().endswith((".usfm", ".sfm")):
                    texts.append(z.read(n).decode("utf-8-sig"))
    raw = {}
    for text in texts:
        m = re.search(r"^\\id\s+([A-Z0-9]{3})", text, re.M)
        if not m:
            continue
        code, chapter, verse, buf = m.group(1), 0, 0, []

        def flush():
            if chapter and verse and buf:
                raw.setdefault((code, chapter, verse), " ".join(buf))

        # The same line discipline as 02_convert_usfm_flat.parse_usfm, so the
        # clean text built here is the text that converter would ingest.
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            if line.startswith("\\c "):
                flush()
                verse, buf = 0, []
                cm = re.match(r"\\c\s+(\d+)", line)
                chapter = int(cm.group(1)) if cm else 0
                continue
            vm = re.match(r"\\v\s+(\d+)(?:[-\u2013,]\d+)?\s*(.*)$", line)
            if vm:
                flush()
                verse, buf = int(vm.group(1)), [vm.group(2)]
                continue
            cm = usfm.CONTINUATION.match(line)
            if cm:
                if verse and cm.group(2):
                    buf.append(cm.group(2))
                continue
            if usfm.DISCARD.match(line):
                continue
            if verse:
                buf.append(line)
        flush()
    return raw


def notes_of(raw, kinds):
    """[(origin ref, note text, anchor)] for the notes of the given kinds."""
    out = []
    for m in RE_NOTE.finditer(raw):
        if m.group(1) not in kinds:
            continue
        body = m.group(2)
        om = RE_ORIGIN.search(body)
        origin = om.group(1).strip() if om else ""
        text = inner(RE_ORIGIN.sub("", re.sub(r"^\s*\S\s", "", body, count=1)))
        # Stripping a character marker leaves a space where it stood; inside
        # quotation marks that reads as a typo.
        text = re.sub(r"\s+([”’,.;:)])", r"\1", re.sub(r"([“‘(])\s+", r"\1", text))
        before = usfm.clean(raw[:m.start()]).split()
        out.append((origin, text, " ".join(before[-ANCHOR_WORDS:])))
    return out


def leaked_form(raw, kinds):
    """The verse as a footnote-flattening source would have written it."""
    def sub(m):
        if m.group(1) not in kinds:
            return ""
        body = m.group(2)
        om = RE_ORIGIN.search(body)
        origin = om.group(1).strip() if om else ""
        text = RE_ORIGIN.sub("", re.sub(r"^\s*\S\s", "", body, count=1))
        return f"{origin} {text}"
    return inner(RE_NOTE.sub(sub, raw))


def legacy_clean(raw):
    """The verse as the converter produced it BEFORE 2026-10-04, when every
    character marker (\\nd, \\add ...) became a space: "the \\nd LORD\\nd*." came
    out "the LORD .". Kept only so --respace can recognise that exact output."""
    t = usfm.RE_NOTE.sub("", raw)
    t = usfm.RE_FIG.sub("", t)
    t = usfm.RE_WORD.sub(r"\1", t)
    t = usfm.RE_CHAR.sub(" ", t).replace("~", " ")
    return usfm.RE_SPACE.sub(" ", t).strip()


def respace(a, raw, verses):
    """Put right the spacing a first run of this script left behind.

    The first KJV repair wrote the USFM's clean text, and the converter then put
    a space where every character marker had been: 915 verses read "the LORD ."
    (the pre-repair backup had none). A verse is rewritten ONLY when it holds
    exactly that old output, so nothing anyone has edited since is touched, and the
    ledger's anchors (which quote the words before each note) are recomputed
    from the corrected text. Note ids and note text do not change, so the
    seeder updates the existing comments in place."""
    fixes, kept = [], 0
    for code, _bn, ch, vs, text in verses:
        r = raw.get((code, ch, vs))
        if r is None:
            continue
        new = usfm.clean(r)
        if text == new:
            kept += 1
            continue
        if nows(text) == nows(new) and text == legacy_clean(r):
            fixes.append((code, ch, vs, new))
    print(f"  already correct                  {kept}")
    print(f"  holding the old spacing (REWRITE) {len(fixes)}")
    for code, ch, vs, new in fixes[:a.show]:
        print(f"    {code} {ch}:{vs}  ...{new[-60:]}")

    ledger_fixed = 0
    ledger = None
    if a.ledger and os.path.exists(a.ledger):
        with io.open(a.ledger, encoding="utf-8") as f:
            ledger = json.load(f)
        for n in ledger["notes"]:
            ref = n["verse_refs"][0]
            r = raw.get((ref["code"], ref["chapter"], ref["verse"]))
            if r is None:
                continue
            for kinds in (("f", "fe"), ("f", "fe", "x")):
                found = notes_of(r, kinds)
                i = n["note_index"]
                if i < len(found) and found[i][1] == n["text"]:
                    if found[i][2] != n["anchor"]:
                        ledger_fixed += 1
                        n["anchor"] = found[i][2]
                    break
        print(f"  ledger anchors to correct         {ledger_fixed}")

    if not a.apply:
        print("\nDry run - nothing written. Re-run with --apply.")
        return
    if fixes:
        os.makedirs(a.backup_dir, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        backup = os.path.join(a.backup_dir, f"{a.id}-before-respace-{stamp}.xml")
        with io.open(backup, "w", encoding="utf-8") as f:
            f.write(sc.xquery(f"serialize(db:open('religioustext','{a.id}.xml'))"))
        print(f"\nBacked up {a.id} to {backup} ({os.path.getsize(backup)} bytes)")
        apply_updates(a.id, fixes)
    if ledger is not None and ledger_fixed:
        with io.open(a.ledger, "w", encoding="utf-8") as f:
            json.dump(ledger, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print(f"Rewrote {a.ledger} ({ledger_fixed} anchors corrected)")


def corpus_verses(doc):
    q = (NS + f"for $b in db:open('religioustext','{doc}.xml')//rt:book "
         f"for $v in $b//rt:verse "
         f"return string-join((string($b/@code), string($b/@canonicalOrder), "
         f"string($v/@chapterNumber), string($v/@number), "
         f"normalize-space(string($v))), '&#9;')")
    out = []
    for ln in sc.xquery(q).split("\n"):
        p = ln.split("\t", 4)
        if len(p) == 5 and p[2].isdigit() and p[3].isdigit():
            out.append((p[0], int(p[1] or 0), int(p[2]), int(p[3]), p[4]))
    return out


def xq_str(s):
    return '"' + s.replace("&", "&amp;").replace('"', '""') + '"'


def apply_updates(doc, fixes):
    for i in range(0, len(fixes), BATCH):
        chunk = fixes[i:i + BATCH]
        rows = ", ".join(
            f"map{{'b':{xq_str(c)},'c':'{ch}','v':'{vs}','t':{xq_str(t)}}}"
            for c, ch, vs, t in chunk)
        sc.xquery_update(
            NS + f"let $d := db:open('religioustext','{doc}.xml') "
            f"for $r in ({rows}) "
            f"let $v := $d//rt:book[@code = $r?b]//rt:verse"
            f"[@chapterNumber = $r?c][@number = $r?v] "
            f"return replace value of node $v with $r?t")
        print(f"  rewrote {min(i + BATCH, len(fixes))}/{len(fixes)}")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--id", required=True, help="corpus document id, e.g. bible-web")
    ap.add_argument("--abbr", required=True, help="edition abbreviation, e.g. WEB")
    ap.add_argument("--usfm", required=True, help="ebible.org USFM zip or directory")
    ap.add_argument("--ledger", help="notes ledger to write (with --apply)")
    ap.add_argument("--backup-dir", default="backups",
                    help="where the pre-change document is saved (with --apply)")
    ap.add_argument("--apply", action="store_true",
                    help="rewrite the corpus and write the ledger; default is a dry run")
    ap.add_argument("--refresh", action="store_true",
                    help="also rewrite verses that differ from the USFM for any "
                         "other reason (truncation, revision), and ledger every "
                         "note in the USFM, not just the leaked ones")
    ap.add_argument("--respace", action="store_true",
                    help="only repair the spacing an earlier run left (see respace()); "
                         "with --ledger, also corrects that ledger's anchors in place")
    ap.add_argument("--show", type=int, default=8,
                    help="how many unresolved verses to print")
    a = ap.parse_args()
    if a.apply and not a.ledger and not a.respace:
        ap.error("--apply needs --ledger: the notes must land somewhere")

    raw = read_usfm(a.usfm)
    verses = corpus_verses(a.id)
    print(f"{a.id}: {len(verses)} corpus verses, {len(raw)} USFM verses")

    if a.respace:
        respace(a, raw, verses)
        return

    own_marker = lambda ch, vs: re.compile(rf"(?<!\d){ch}[:.]{vs}\.?\s")
    kinds_all = ("f", "fe", "x")

    def ledger_notes(code, booknum, ch, vs, r, kinds):
        ref = f"{OSIS_BY_USFM.get(code, code)}.{ch}.{vs}"
        for i, (_origin, note, anchor) in enumerate(notes_of(r, kinds)):
            ledger.append({
                "id": mint_note_id(a.abbr, ref, i, note),
                "edition": a.abbr,
                "source": a.abbr.lower(),
                "verse_refs": [{"ref": ref, "code": code, "book": booknum,
                                "chapter": ch, "verse": vs}],
                "note_index": i,
                "anchor": anchor,
                "text": note,
            })

    fixes, ledger, unresolved = [], [], []
    clean_n = proven = truncated = revised = absent = empty = 0
    for code, booknum, ch, vs, text in verses:
        r = raw.get((code, ch, vs))
        if r is None:
            absent += 1
            continue
        c = nows(text)
        clean = usfm.clean(r)
        if a.refresh:
            ledger_notes(code, booknum, ch, vs, r, kinds_all)
        if c == nows(clean):
            clean_n += 1
            continue
        for kinds in (("f", "fe"), kinds_all):
            if c == nows(leaked_form(r, kinds)):
                proven += 1
                fixes.append((code, ch, vs, clean))
                if not a.refresh:
                    ledger_notes(code, booknum, ch, vs, r, kinds)
                break
        else:
            if not clean:
                empty += 1
            elif a.refresh:
                fixes.append((code, ch, vs, clean))
                if nows(clean).startswith(c):
                    truncated += 1
                else:
                    revised += 1
            elif own_marker(ch, vs).search(text):
                unresolved.append((code, ch, vs, text, clean))
            elif nows(clean).startswith(c):
                truncated += 1
            else:
                revised += 1

    will = "REWRITE" if a.refresh else "untouched"
    print(f"  already clean                         {clean_n}")
    print(f"  proven footnote leaks (REWRITE)       {proven}")
    print(f"  truncated in the corpus ({will})    {truncated}")
    print(f"  differs otherwise ({will})          {revised}")
    print(f"  leak-shaped but unproven (untouched)  {len(unresolved)}")
    print(f"  empty in the USFM (untouched)         {empty}")
    print(f"  not in the USFM (untouched)           {absent}")
    print(f"  verses to rewrite {len(fixes)} · notes for the ledger {len(ledger)}")
    for code, ch, vs, text, clean in unresolved[:a.show]:
        print(f"\n  ? {code} {ch}:{vs}\n    corpus: {text[:260]}\n    usfm  : {clean[:260]}")

    if not a.apply:
        print("\nDry run — nothing written. Re-run with --ledger … --apply.")
        return
    if not fixes:
        print("\nNothing to fix.")
        return

    os.makedirs(a.backup_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup = os.path.join(a.backup_dir, f"{a.id}-before-notes-{stamp}.xml")
    with io.open(backup, "w", encoding="utf-8") as f:
        f.write(sc.xquery(f"serialize(db:open('religioustext','{a.id}.xml'))"))
    print(f"\nBacked up {a.id} to {backup} ({os.path.getsize(backup)} bytes)")

    apply_updates(a.id, fixes)
    with io.open(a.ledger, "w", encoding="utf-8") as f:
        json.dump({"metadata": {"edition": a.abbr,
                                "source": os.path.basename(a.usfm.rstrip("/")),
                                "count": len(ledger)},
                   "notes": ledger}, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"Wrote {a.ledger} ({len(ledger)} notes)")


if __name__ == "__main__":
    main()
