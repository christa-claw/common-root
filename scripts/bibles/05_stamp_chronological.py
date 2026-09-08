#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
stamp_chronological.py — stamps globalChronologicalSeq on every verse in BaseX
for all translations, following the order defined in orderings/chronological.yml.

Usage:
    python3 scripts/bibles/05_stamp_chronological.py [--dry-run] [--translation TRANSLATION_ID]

Options:
    --dry-run           Print what would be stamped without writing to BaseX
    --translation ID    Stamp a single translation only (e.g. bible-niv-2011)
                        Default: all translations in BaseX

No third-party dependencies — uses only stdlib (no PyYAML, no xml.etree).
YAML is parsed with a purpose-built state machine for our specific format.
"""

import sys
import urllib.request
import urllib.parse
import urllib.error
import base64
import os

BASEX_URL  = "http://localhost:8984/rest/religioustext"
AUTH       = base64.b64encode(b"admin:admin").decode()
NS         = "http://religioustext.org/schema/1.0"
SCRIPT_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN_PATH  = os.path.join(SCRIPT_DIR, "orderings", "chronological.yml")

# ── BaseX helpers ─────────────────────────────────────────────────────────────

def xquery(q):
    params = urllib.parse.urlencode({"query": q})
    url = f"{BASEX_URL}?{params}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req) as r:
        return r.read().decode("utf-8").strip()

def _escape_xml(s):
    """Escape a string for embedding in XML content (not CDATA)."""
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')

def xquery_update(q):
    """
    Execute an XQuery Update via BaseX REST API, using curl subprocess.
    Mirrors BaseXStore.postXQuery() exactly — XML-escaped body, curl POST.
    """
    import subprocess, tempfile, os
    wrapped = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<query xmlns="http://basex.org/rest">'
        '<text>' + _escape_xml(q) + '</text>'
        '</query>'
    )
    with tempfile.NamedTemporaryFile(mode='w', suffix='.xml',
                                     encoding='utf-8', delete=False) as f:
        f.write(wrapped)
        tmp = f.name
    try:
        resp_file = '/tmp/basex-stamp-response.txt'
        result = subprocess.run(
            [
                'curl', '-s',
                '-o', resp_file,
                '-w', '%{http_code}',
                '-u', 'admin:admin',
                '-X', 'POST',
                '-H', 'Content-Type: application/xml',
                '--data-binary', f'@{tmp}',
                BASEX_URL,
            ],
            capture_output=True, text=True, timeout=120
        )
        code = result.stdout.strip()
        if not code.startswith('2'):
            body = open(resp_file).read() if os.path.exists(resp_file) else ''
            raise RuntimeError(f'HTTP {code}: {body[:300]}')
    finally:
        os.unlink(tmp)

def get_all_translation_ids():
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" distinct-values(db:open('religioustext')//rt:text/@id)"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]

def get_verse_count(doc, book, chapter):
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" count(db:open('religioustext','{doc}')"
        f"/rt:text/rt:book[@name='{book}']/rt:chapter[@number='{chapter}']/rt:verse)"
    )
    try:
        return int(result.strip())
    except ValueError:
        return 0

def get_verse_numbers(doc, book, chapter):
    """Actual verse @number values in document order (as strings).

    Unlike a synthesized 1..count range, this respects versification gaps:
    chapters that omit verse numbers (e.g. NIV drops textual-variant verses
    like Matthew 17:21) or use non-contiguous genealogy numbering have a verse
    ELEMENT count lower than their highest verse NUMBER. The old code stamped
    range(1, count+1), which silently dropped the trailing verses, leaving them
    unstamped and — in chrono mode — colliding via the canonical fallback.
    """
    book_esc = book.replace("'", "&apos;")
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" for $v in db:open('religioustext','{doc}')"
        f"/rt:text/rt:book[@name='{book_esc}']/rt:chapter[@number='{chapter}']/rt:verse"
        f" return string($v/@number)"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]

def _as_int(s):
    """Leading-integer parse of a verse number ('12' -> 12, '12a' -> 12)."""
    import re
    m = re.match(r'\d+', str(s))
    return int(m.group()) if m else -1

def get_all_chapters(doc):
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" for $c in db:open('religioustext','{doc}')/rt:text/rt:book/rt:chapter"
        f" return concat($c/../@name, '|', $c/@number)"
    )
    pairs = set()
    for line in result.splitlines():
        line = line.strip()
        if '|' in line:
            b, c = line.split('|', 1)
            try:
                pairs.add((b, int(c)))
            except ValueError:
                pass
    return pairs

def get_book_names(doc):
    """Return list of actual book name strings stored in this translation."""
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" for $b in db:open('religioustext','{doc}')/rt:text/rt:book"
        f" return string($b/@name)"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]

def _normalise(name):
    """Normalise a book name for fuzzy matching: lowercase, remove spaces/punctuation.
    Decomposes Unicode accents first so e.g. \u00e9 (\xe9) → 'e', not stripped entirely.
    """
    import re
    import unicodedata
    # Decompose accented chars (e.g. \xe9 → e + combining acute), then drop non-ASCII
    nfd = unicodedata.normalize('NFD', name.lower())
    ascii_only = nfd.encode('ascii', 'ignore').decode('ascii')
    return re.sub(r'[^a-z0-9]', '', ascii_only)

def build_name_map(doc):
    """
    Build a dict mapping normalised plan name → actual name in this translation.
    e.g. 'psalm' → 'Psalms', 'songofsongsongs' → 'Song of Solomon'
    """
    actual_names = get_book_names(doc)
    return {_normalise(n): n for n in actual_names}

# Aliases: normalised plan name → normalised alternative to try in name_map.
# Handles cases where translations use a different title for the same book.
_ALIASES = {
    'songofsongsongs': ['songofsolomon', 'cantares', 'cantardeloscantares', 'canticleofcanticles'],
    'songofsongs':     ['songofsolomon', 'songofsongsongs', 'cantares', 'cantardeloscantares', 'canticleofcanticles'],
    'psalm':           ['psalms', 'salmos'],
    'psalms':          ['psalm', 'salmos'],
    'revelation':      ['apocalipsis', 'revelationofjohn'],
    'acts':            ['hechos', 'actsoftheapostles'],
    'james':           ['santiago'],
    'obadiah':         ['abdias', 'abdia'],
    # Gospel names WITHOUT the 'San' prefix (BES/PDDPT/VBL use 'Mateo' etc.,
    # unlike RVR09's 'San Mateo' which the ES map covers). Found 2026-07-03 by
    # the stamp_tanakh dry run — these were silently unresolved in the
    # CHRONOLOGICAL stamps for those three docs too (gospels fell back to the
    # end-of-order offset). Re-run stamp_chronological for bes/pddpt/vbl.
    'matthew':         ['sanmateo', 'mateo'],
    'mark':            ['sanmarcos', 'marcos'],
    'luke':            ['sanlucas', 'lucas'],
    'john':            ['sanjuan', 'juan'],
}

# Full English→Spanish name map for RVR09 (book names as stored in BaseX).
# Normalised English key → normalised Spanish key.
_ES_MAP = {
    'genesis':          'genesis',       # Génesis → genesis
    'exodus':           'exodo',         # Éxodo
    'leviticus':        'levitico',
    'numbers':          'numeros',
    'deuteronomy':      'deuteronomio',
    'joshua':           'josue',
    'judges':           'jueces',
    'ruth':             'rut',
    '1samuel':          '1samuel',
    '2samuel':          '2samuel',
    '1kings':           '1reyes',
    '2kings':           '2reyes',
    '1chronicles':      '1cronicas',
    '2chronicles':      '2cronicas',
    'ezra':             'esdras',
    'nehemiah':         'nehemias',
    'esther':           'ester',
    'job':              'job',
    'psalm':            'salmos',
    'psalms':           'salmos',
    'proverbs':         'proverbios',
    'ecclesiastes':     'eclesiastes',
    'songofsongsongs':  'cantares',
    'isaiah':           'isaias',
    'jeremiah':         'jeremias',
    'lamentations':     'lamentaciones',
    'ezekiel':          'ezequiel',
    'daniel':           'daniel',
    'hosea':            'oseas',
    'joel':             'joel',
    'amos':             'amos',
    'obadiah':          'abdias',
    'jonah':            'jonas',
    'micah':            'miqueas',
    'nahum':            'nahum',
    'habakkuk':         'habacuc',
    'zephaniah':        'sofonias',
    'haggai':           'hageo',
    'zechariah':        'zacarias',
    'malachi':          'malaquias',
    'matthew':          'sanmateo',
    'mark':             'sanmarcos',
    'luke':             'sanlucas',
    'john':             'sanjuan',
    'acts':             'hechos',
    'romans':           'romanos',
    '1corinthians':     '1corintios',
    '2corinthians':     '2corintios',
    'galatians':        'galatas',
    'ephesians':        'efesios',
    'philippians':      'filipenses',
    'colossians':       'colosenses',
    '1thessalonians':   '1tesalonicenses',
    '2thessalonians':   '2tesalonicenses',
    '1timothy':         '1timoteo',
    '2timothy':         '2timoteo',
    'titus':            'tito',
    'philemon':         'filemon',
    'hebrews':          'hebreos',
    'james':            'santiago',
    '1peter':           '1pedro',
    '2peter':           '2pedro',
    '1john':            '1juan',
    '2john':            '2juan',
    '3john':            '3juan',
    'jude':             'judas',
    'revelation':       'apocalipsis',
}

def resolve_book_name(plan_name, name_map):
    """
    Find the actual book name in a translation for a plan book name.
    Tries: exact normalised match, trailing-s variants, aliases, ES map.
    Returns actual name string, or None if not found.
    """
    key = _normalise(plan_name)

    # 1. Direct match
    if key in name_map:
        return name_map[key]

    # 2. Trailing-s variant (psalm/psalms)
    if key + 's' in name_map:
        return name_map[key + 's']
    if key.endswith('s') and key[:-1] in name_map:
        return name_map[key[:-1]]

    # 3. Known aliases (e.g. Song of Songs → Song of Solomon)
    for alt in _ALIASES.get(key, []):
        if alt in name_map:
            return name_map[alt]

    # 4. Spanish map (for RVR09 and future Spanish translations)
    es_key = _ES_MAP.get(key)
    if es_key and es_key in name_map:
        return name_map[es_key]

    return None

# ── YAML parser (no PyYAML) ───────────────────────────────────────────────────
#
# Handles exactly the structure of chronological.yml:
#
#   sequence:
#     - book: "Genesis"
#       chapters: "1-11"
#
#     - books:
#         - {book: "2 Samuel", chapters: "5"}
#         - {book: "1 Chronicles", chapters: "11-12"}
#
# Returns a flat list of dicts: {'book': str, 'chapters': str, 'verses': str|None}

def _unquote(s):
    """Strip surrounding quotes and whitespace from a YAML scalar."""
    s = s.strip()
    if (s.startswith('"') and s.endswith('"')) or \
       (s.startswith("'") and s.endswith("'")):
        s = s[1:-1]
    return s.strip()

def _parse_inline_dict(s):
    """
    Parse an inline YAML dict like: {book: "2 Samuel", chapters: "5"}
    Returns a plain Python dict with string values.
    """
    s = s.strip().lstrip('{').rstrip('}')
    result = {}
    # Split on ', ' but not inside quotes — simple enough for our format
    # since none of our values contain commas
    for part in s.split(','):
        part = part.strip()
        if ':' not in part:
            continue
        k, v = part.split(':', 1)
        result[k.strip()] = _unquote(v)
    return result

def _indent(line):
    """Return indentation level (number of leading spaces)."""
    return len(line) - len(line.lstrip(' '))

def load_plan(path):
    """
    Parse chronological.yml and return a flat list of:
      {'book': str, 'chapters': str, 'verses': str|None}
    in sequence order.
    """
    with open(path, encoding="utf-8") as f:
        raw_lines = f.readlines()

    # Strip comments and trailing whitespace, keep blank lines as sentinels
    lines = []
    for line in raw_lines:
        # Remove inline comments (but not # inside quoted strings — safe for our format)
        stripped = line.rstrip()
        if '#' in stripped:
            # Only strip if # is not inside quotes
            idx = stripped.find('#')
            before = stripped[:idx]
            # Check if we're inside a quoted string — simple heuristic
            if before.count('"') % 2 == 0:
                stripped = before.rstrip()
        lines.append(stripped)

    flat = []
    i = 0
    in_sequence = False

    while i < len(lines):
        line = lines[i]
        content = line.strip()

        # Enter sequence block
        if content == 'sequence:':
            in_sequence = True
            i += 1
            continue

        if not in_sequence or not content:
            i += 1
            continue

        ind = _indent(line)

        # ── Single-book entry: starts with "  - book:" at indent 2 ──────────
        if content.startswith('- book:'):
            book = _unquote(content[len('- book:'):])
            chapters = '1'
            verses   = None
            i += 1
            # Read following indented keys (chapters, verses)
            while i < len(lines):
                sub = lines[i]
                sub_content = sub.strip()
                if not sub_content:
                    i += 1
                    continue
                sub_ind = _indent(sub)
                # Stop when we return to the same or lower indent as the '-'
                if sub_ind <= ind and sub_content.startswith('-'):
                    break
                if sub_content.startswith('chapters:'):
                    chapters = _unquote(sub_content[len('chapters:'):])
                elif sub_content.startswith('verses:'):
                    verses = _unquote(sub_content[len('verses:'):])
                i += 1
            flat.append({'book': book, 'chapters': chapters, 'verses': verses})
            continue

        # ── Multi-book entry: starts with "  - books:" at indent 2 ──────────
        if content.startswith('- books:'):
            i += 1
            while i < len(lines):
                sub = lines[i]
                sub_content = sub.strip()
                if not sub_content:
                    i += 1
                    continue
                sub_ind = _indent(sub)
                # Stop when we return to the top-level sequence indent
                if sub_ind <= ind and sub_content.startswith('-') and \
                   not sub_content.startswith('- {'):
                    break

                # Inline dict: "      - {book: "X", chapters: "Y"}"
                if sub_content.startswith('- {'):
                    d = _parse_inline_dict(sub_content[1:].strip())
                    if 'book' in d:
                        flat.append({
                            'book':     d['book'],
                            'chapters': d.get('chapters', '1'),
                            'verses':   d.get('verses'),
                        })
                    i += 1
                    continue

                # Multi-line sub-entry: "      - book: ..." with nested keys
                if sub_content.startswith('- book:'):
                    book     = _unquote(sub_content[len('- book:'):])
                    chapters = '1'
                    verses   = None
                    entry_ind = sub_ind
                    i += 1
                    while i < len(lines):
                        kl = lines[i]
                        kl_content = kl.strip()
                        if not kl_content:
                            i += 1
                            continue
                        kl_ind = _indent(kl)
                        if kl_ind <= entry_ind:
                            break
                        if kl_content.startswith('chapters:'):
                            chapters = _unquote(kl_content[len('chapters:'):])
                        elif kl_content.startswith('verses:'):
                            verses = _unquote(kl_content[len('verses:'):])
                        i += 1
                    flat.append({'book': book, 'chapters': chapters, 'verses': verses})
                    continue

                i += 1
            continue

        i += 1

    return flat

# ── Range expansion ───────────────────────────────────────────────────────────

def expand_chapter_range(ch_str):
    ch_str = str(ch_str).strip()
    if '-' in ch_str:
        a, b = ch_str.split('-', 1)
        return list(range(int(a), int(b) + 1))
    return [int(ch_str)]

def expand_verse_range(vs_str, total_verses):
    if vs_str is None:
        return list(range(1, total_verses + 1))
    vs_str = str(vs_str).strip()
    if '-' in vs_str:
        a, b = vs_str.split('-', 1)
        return list(range(int(a), int(b) + 1))
    return [int(vs_str)]

# ── Validation ────────────────────────────────────────────────────────────────

def build_tuples(flat, ref_doc):
    """
    Expand plan entries into (book, chapter, verses_str|None, total_vc) tuples.
    Validates every reference against ref_doc. Returns (tuples, errors).
    """
    tuples = []
    errors = []

    for item in flat:
        book     = item['book']
        chapters = expand_chapter_range(item['chapters'])
        verses   = item['verses']

        if verses and len(chapters) > 1:
            errors.append(
                f"WARN: verse filter '{verses}' ignored for "
                f"multi-chapter {book} {item['chapters']}"
            )
            verses = None

        for ch in chapters:
            vc = get_verse_count(ref_doc, book, ch)
            if vc == 0:
                errors.append(f"NOT FOUND in {ref_doc}: '{book}' chapter {ch}")
                continue
            tuples.append((book, ch, verses if len(chapters) == 1 else None, vc))

    return tuples, errors

def stamp_chapter_batch(doc, book, ch, verse_seq_pairs, dry_run):
    """
    Stamp an entire chapter in two XQuery Update calls:
    1. Delete all existing globalChronologicalSeq attributes in the chapter
    2. Insert the correct seq value on each verse using position-based lookup
    Still ~1190 call-pairs total vs ~30000 individual verse calls.
    """
    if dry_run or not verse_seq_pairs:
        return

    book_esc = _escape_xq(book)

    # Step 1: delete all existing globalChronologicalSeq attrs in this chapter
    q_delete = (
        f"declare namespace rt='{NS}';"
        f" for $v in db:open('religioustext','{doc}')"
        f"/rt:text/rt:book[@name='{book_esc}']"
        f"/rt:chapter[string(@number)='{ch}']/rt:verse"
        f" where exists($v/@globalChronologicalSeq)"
        f" return delete node $v/@globalChronologicalSeq"
    )
    xquery_update(q_delete)

    # Step 2: insert seq on each verse individually — but batched as a
    # sequence of independent insert statements joined with a comma.
    # Each insert targets a distinct node so no XUDY0017.
    inserts = []
    for vnum, seq in verse_seq_pairs:
        inserts.append(
            f"let $v := db:open('religioustext','{doc}')"
            f"/rt:text/rt:book[@name='{book_esc}']"
            f"/rt:chapter[string(@number)='{ch}']"
            f"/rt:verse[string(@number)='{vnum}']"
            f" return if (exists($v)) then"
            f" insert node attribute globalChronologicalSeq {{'{seq}'}} into $v"
            f" else ()"
        )
    q_insert = (
        f"declare namespace rt='{NS}';"
        + ", ".join(inserts)
    )
    xquery_update(q_insert)

def _escape_xq(s):
    """Escape a string for use inside an XQuery string literal (single-quoted)."""
    return s.replace("'", "&apos;")

def stamp_translation(doc, tuples, dry_run):
    # Build name map: normalised plan name → actual name in this translation
    name_map = build_name_map(doc)

    # Remap tuples to use actual book names for this translation
    remapped = []
    unmappable = set()
    for (plan_book, ch, verses_str, total_vc) in tuples:
        actual = resolve_book_name(plan_book, name_map)
        if actual is None:
            unmappable.add(plan_book)
            continue
        remapped.append((actual, ch, verses_str, total_vc))

    if unmappable:
        print(f"    Books in plan not found in {doc} (expected for apocrypha): {sorted(unmappable)}")

    covered   = {(book, ch) for (book, ch, _, _) in remapped}
    all_chaps = get_all_chapters(doc)
    gaps      = sorted(
        [(b, c) for (b, c) in all_chaps if (b, c) not in covered],
        key=lambda x: (x[0], x[1])
    )

    seq     = 1
    stamped = 0
    skipped = 0

    print(f"\n  Stamping {doc} ...")
    for (book, ch, verses_str, total_vc) in remapped:
        # Stamp the ACTUAL verse numbers present in the chapter, in document
        # order — never a synthesized 1..count range (which dropped trailing
        # verses in chapters with versification gaps; see get_verse_numbers).
        actual_nums = get_verse_numbers(doc, book, ch)
        if not actual_nums:
            skipped += 1
            continue

        if verses_str:
            wanted = set(expand_verse_range(verses_str, 0))
            nums = [n for n in actual_nums if _as_int(n) in wanted]
        else:
            nums = actual_nums

        # Build (verse_number, seq) pairs for this chapter
        verse_seq_pairs = [(n, seq + i) for i, n in enumerate(nums)]
        try:
            stamp_chapter_batch(doc, book, ch, verse_seq_pairs, dry_run)
        except Exception as e:
            print(f"    ERROR: {book} ch.{ch}: {e}")
        # NOTE ON THE COUNT (verified 2026-07, seen again on NBLA 2026-08-03).
        # `stamped` runs ~91 HIGHER than the document's verse count — e.g. a
        # 31,090-verse NBLA reports 31,181. That is not double-stamping: the
        # plan covers a handful of chapters in two blocks (a psalm placed with
        # an episode and again in a psalm run), so those verses are visited
        # twice and the later visit wins. The delta is stable across editions,
        # which is the useful signal: a DIFFERENT delta means the plan changed,
        # a delta of zero-stamped means the --translation name matched nothing.
        seq     += len(nums)
        stamped += len(nums)

        if stamped % 5000 == 0 and stamped > 0:
            print(f"    ... {stamped} verses stamped (last: {book} ch.{ch})")

    return stamped, skipped, gaps

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    dry_run   = "--dry-run" in sys.argv
    target_id = None
    if "--translation" in sys.argv:
        idx       = sys.argv.index("--translation")
        target_id = sys.argv[idx + 1]

    if dry_run:
        print("DRY RUN — no changes written to BaseX\n")

    print(f"Loading plan: {PLAN_PATH}")
    flat = load_plan(PLAN_PATH)
    print(f"Plan entries after flattening: {len(flat)}")

    print("\nFirst 8 entries (sanity check):")
    for item in flat[:8]:
        print(f"  {item}")

    ref_doc = "bible-niv-2011.xml"
    print(f"\nValidating against {ref_doc} ...")
    tuples, errors = build_tuples(flat, ref_doc)

    if errors:
        print("\nVALIDATION ERRORS / WARNINGS:")
        for e in errors:
            print(f"  {e}")
        if any(e.startswith("NOT FOUND") for e in errors):
            print("\nFix NOT FOUND errors before stamping. Aborting.")
            sys.exit(1)

    print(f"Plan resolves to {len(tuples)} chapter-blocks, validation OK.")

    if target_id:
        all_ids = [target_id]
    else:
        all_ids = get_all_translation_ids()

    print(f"\nTranslations to stamp: {all_ids}")

    for tid in all_ids:
        doc = f"{tid}.xml"
        stamped, skipped, gaps = stamp_translation(doc, tuples, dry_run)
        action = "Would stamp" if dry_run else "Stamped"
        print(f"\n  {action} {stamped} verses in {doc} "
              f"({skipped} chapter-blocks not in this translation)")
        if gaps:
            print(f"  COVERAGE GAPS — {len(gaps)} chapters have no chronological seq:")
            by_book = {}
            for (b, c) in gaps:
                by_book.setdefault(b, []).append(c)
            for b, chs in sorted(by_book.items()):
                print(f"    {b}: ch. {', '.join(str(c) for c in sorted(chs))}")
        else:
            print("  Coverage: all chapters stamped ✓")

    print("\nDone.")

if __name__ == "__main__":
    main()
