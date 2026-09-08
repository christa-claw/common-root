#!/usr/bin/env python3
"""
consolidate_basex.py
====================
Consolidates and cleans all translations in BaseX:

  1. Merges fragmented documents (NIV split across 2 docs)
  2. Fixes missing canonicalOrder (RVR09 — use book @code to assign)
  3. Sorts all books by canonicalOrder within each document
  4. Deduplicates books with the SAME canonicalOrder (keeps most verses)
     NOTE: order=9999 (apocrypha/unknown) are never deduplicated against
     each other — they are always all kept.
  5. Writes one clean {translation-id}.xml document per translation

Usage:
  python3 consolidate_basex.py [--dry-run] [--id bible-niv-2011]
"""

import sys
import urllib.request
import urllib.parse
import urllib.error
import base64
import xml.etree.ElementTree as ET
import xml.dom.minidom
from collections import defaultdict
from datetime import datetime, timezone

# ── Configuration ─────────────────────────────────────────────────────────────

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH      = base64.b64encode(b"admin:admin").decode()
NS        = "http://religioustext.org/schema/1.0"
NSP       = f"{{{NS}}}"

# Canonical Bible book order by 3-letter code (Protestant canon 1-66)
CANONICAL_ORDER = {
    "GEN":1,"EXO":2,"LEV":3,"NUM":4,"DEU":5,"JOS":6,"JDG":7,"RUT":8,
    "1SA":9,"2SA":10,"1KI":11,"2KI":12,"1CH":13,"2CH":14,"EZR":15,
    "NEH":16,"EST":17,"JOB":18,"PSA":19,"PRO":20,"ECC":21,"SNG":22,
    "ISA":23,"JER":24,"LAM":25,"EZK":26,"DAN":27,"HOS":28,"JOL":29,
    "AMO":30,"OBA":31,"JON":32,"MIC":33,"NAM":34,"HAB":35,"ZEP":36,
    "HAG":37,"ZEC":38,"MAL":39,"MAT":40,"MRK":41,"LUK":42,"JHN":43,
    "ACT":44,"ROM":45,"1CO":46,"2CO":47,"GAL":48,"EPH":49,"PHP":50,
    "COL":51,"1TH":52,"2TH":53,"1TI":54,"2TI":55,"TIT":56,"PHM":57,
    "HEB":58,"JAS":59,"1PE":60,"2PE":61,"1JN":62,"2JN":63,"3JN":64,
    "JUD":65,"REV":66,
}

APOCRYPHA_SLOT = 9999  # sentinel for books with no known canonical order

# ── BaseX helpers ─────────────────────────────────────────────────────────────

def xquery(q):
    params = urllib.parse.urlencode({"query": q})
    url = f"{BASEX_URL}?{params}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.read().decode("utf-8").strip()
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        raise RuntimeError(f"XQuery HTTP {e.code}: {body[:300]}") from e

def get_document(doc_name):
    url = f"{BASEX_URL}/{doc_name}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"GET {doc_name} HTTP {e.code}") from e

def put_document(doc_name, xml_content):
    url = f"{BASEX_URL}/{doc_name}"
    data = xml_content.encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Basic {AUTH}",
        "Content-Type": "application/xml; charset=utf-8",
    })
    with urllib.request.urlopen(req) as r:
        r.read()

def delete_document(doc_name):
    url = f"{BASEX_URL}/{doc_name}"
    req = urllib.request.Request(url, method="DELETE",
        headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req) as r:
        r.read()

def get_all_translation_ids():
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" distinct-values(db:open('religioustext')//rt:text/@id)"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]

def list_docs_for(tid):
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" for $d in db:list('religioustext')"
        f" where exists(db:open('religioustext',$d)//rt:text[@id='{tid}'])"
        f" return $d"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]

# ── Book ordering ─────────────────────────────────────────────────────────────

def get_canonical_order(book_elem):
    """
    Return canonical order for a book element.
    Priority:
      1. @canonicalOrder if set and > 0
      2. Lookup by @code in CANONICAL_ORDER table
      3. APOCRYPHA_SLOT (sort to end, never deduplicated)
    """
    order_str = book_elem.get("canonicalOrder", "0")
    try:
        order = int(order_str)
    except ValueError:
        order = 0

    if order > 0:
        return order

    code = book_elem.get("code", "").upper()
    if code in CANONICAL_ORDER:
        return CANONICAL_ORDER[code]

    return APOCRYPHA_SLOT

# ── Core consolidation ────────────────────────────────────────────────────────

def consolidate_translation(translation_id, dry_run):
    print(f"\n{'='*60}")
    print(f"Translation: {translation_id}")
    print(f"{'='*60}")

    doc_names = list_docs_for(translation_id)
    print(f"Found {len(doc_names)} document(s): {doc_names}")

    # Parse all documents, collect all books
    all_metadata = None
    all_books = []  # (book_element, verse_count, source_doc)

    for doc_name in doc_names:
        print(f"  Reading: {doc_name}")
        xml_str = get_document(doc_name)
        root = ET.fromstring(xml_str)

        if all_metadata is None:
            all_metadata = root

        books = root.findall(f"{NSP}book")
        print(f"    -> {len(books)} books")
        for book in books:
            verse_count = len(book.findall(f".//{NSP}verse"))
            all_books.append((book, verse_count, doc_name))

    if all_metadata is None:
        print("  ERROR: No documents found")
        return

    # Fix missing canonicalOrder using code lookup
    fixed_count = 0
    for book, verse_count, doc_name in all_books:
        raw_order = int(book.get("canonicalOrder", "0") or "0")
        if raw_order == 0:
            resolved = get_canonical_order(book)
            if resolved < APOCRYPHA_SLOT:
                book.set("canonicalOrder", str(resolved))
                fixed_count += 1

    if fixed_count:
        print(f"  Fixed {fixed_count} missing canonicalOrder values via code lookup")

    # Bucket by resolved order
    # APOCRYPHA_SLOT books each get their own unique key so they're never merged
    canonical_bucket = defaultdict(list)   # order (1-66+) -> list of books
    apocrypha_list   = []                  # books with no known order — keep all

    for book, verse_count, doc_name in all_books:
        order = get_canonical_order(book)
        if order == APOCRYPHA_SLOT:
            apocrypha_list.append((book, verse_count, doc_name))
        else:
            canonical_bucket[order].append((book, verse_count, doc_name))

    # Deduplicate canonical slots (same order = same book, keep most verses)
    deduped_books = []
    duplicate_count = 0

    for order in sorted(canonical_bucket.keys()):
        candidates = canonical_bucket[order]
        if len(candidates) > 1:
            best = max(candidates, key=lambda x: x[1])
            dropped = [c for c in candidates if c is not best]
            book_name = best[0].get("name", "?")
            print(f"  DEDUP order={order} ({book_name}): "
                  f"keeping {best[1]} verses, "
                  f"dropping {[d[1] for d in dropped]}")
            deduped_books.append(best[0])
            duplicate_count += 1
        else:
            deduped_books.append(candidates[0][0])

    # Append all apocrypha in their original order (no dedup)
    for book, verse_count, doc_name in apocrypha_list:
        deduped_books.append(book)

    canonical_count  = len([b for b in deduped_books
                            if int(b.get("canonicalOrder", 0)) <= 66
                            and int(b.get("canonicalOrder", 0)) > 0])
    apocrypha_count  = len(apocrypha_list)

    print(f"  Canonical books (1-66): {canonical_count}")
    if apocrypha_count:
        names = [b[0].get("name", b[0].get("code", "?")) for b in apocrypha_list]
        print(f"  Apocrypha/other kept as-is ({apocrypha_count}): {names}")
    print(f"  Total books: {len(deduped_books)} ({duplicate_count} slot(s) had duplicates)")

    # Sanity check canonical count
    text_type = all_metadata.get("type", "")
    if text_type == "bible" and canonical_count != 66:
        present = {int(b.get("canonicalOrder", 0)) for b in deduped_books
                   if 0 < int(b.get("canonicalOrder", 0)) <= 66}
        missing = set(range(1, 67)) - present
        if missing:
            print(f"  WARNING: Missing canonical orders: {sorted(missing)}")

    # Check if already clean — skip if single doc, sorted, no fixes/dedupes needed
    if len(doc_names) == 1 and duplicate_count == 0 and fixed_count == 0:
        existing_orders = [int(b.get("canonicalOrder", 0))
                          for b in all_metadata.findall(f"{NSP}book")]
        if existing_orders == sorted(existing_orders):
            print("  Already clean and sorted. Skipping.")
            return

    if dry_run:
        print(f"  DRY RUN: Would write {len(deduped_books)} books to {translation_id}.xml")
        print(f"  DRY RUN: Would delete {len(doc_names)} old document(s)")
        return

    # Build new consolidated XML
    ET.register_namespace("", NS)
    new_root = ET.Element(f"{NSP}text")

    for attr, val in all_metadata.attrib.items():
        new_root.set(attr, val)

    new_root.set("ingestedAt",
                 datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))

    total_verses = sum(len(b.findall(f".//{NSP}verse")) for b in deduped_books)
    new_root.set("totalVerses", str(total_verses))

    for book in deduped_books:
        new_root.append(book)

    # Serialise with pretty printing
    raw_xml = ET.tostring(new_root, encoding="unicode", xml_declaration=False)
    dom = xml.dom.minidom.parseString(
        f'<?xml version="1.0" encoding="UTF-8"?>{raw_xml}')
    pretty_lines = dom.toprettyxml(indent="  ", encoding=None).splitlines()
    if pretty_lines[0].startswith("<?xml"):
        pretty_lines = pretty_lines[1:]
    final_xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                 + "\n".join(pretty_lines))

    target_doc = f"{translation_id}.xml"
    print(f"  Writing: {target_doc} ({total_verses} verses, {len(deduped_books)} books)")

    for doc_name in doc_names:
        print(f"  Deleting: {doc_name}")
        delete_document(doc_name)

    put_document(target_doc, final_xml)

    verify = xquery(
        f"declare namespace rt='{NS}';"
        f" count(db:open('religioustext','{target_doc}')//rt:book)"
    )
    print(f"  Done. BaseX reports {verify} books in {target_doc}")

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    dry_run   = "--dry-run" in sys.argv
    target_id = None

    if "--id" in sys.argv:
        idx = sys.argv.index("--id")
        if idx + 1 < len(sys.argv):
            target_id = sys.argv[idx + 1]

    if dry_run:
        print("DRY RUN mode — no changes will be made\n")

    print("Connecting to BaseX...")
    try:
        all_ids = get_all_translation_ids()
    except Exception as e:
        print(f"ERROR: Cannot connect to BaseX: {e}")
        sys.exit(1)

    print(f"Found {len(all_ids)} translation(s): {all_ids}")

    ids_to_process = [target_id] if target_id else all_ids

    for tid in ids_to_process:
        if tid not in all_ids:
            print(f"ERROR: '{tid}' not found"); continue
        try:
            consolidate_translation(tid, dry_run)
        except Exception as e:
            print(f"ERROR processing {tid}: {e}")
            import traceback; traceback.print_exc()

    print("\nDone.")

if __name__ == "__main__":
    main()
