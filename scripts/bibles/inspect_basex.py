#!/usr/bin/env python3
"""
inspect_basex.py — diagnostic tool to inspect a translation's book data in BaseX.
Usage: python3 scripts/bibles/inspect_basex.py [translation-id]

Note: uses XQuery for all data extraction to avoid xml.etree / pyexpat which is
broken on macOS Tahoe with Homebrew Python (libexpat symbol mismatch).
"""
import sys
import urllib.request
import urllib.parse
import urllib.error
import base64

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH      = base64.b64encode(b"admin:admin").decode()
NS        = "http://religioustext.org/schema/1.0"

def xquery(q):
    params = urllib.parse.urlencode({"query": q})
    url = f"{BASEX_URL}?{params}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req) as r:
        return r.read().decode("utf-8").strip()

def get_all_translation_ids():
    result = xquery(f"declare namespace rt='{NS}'; distinct-values(db:open('religioustext')//rt:text/@id)")
    return [l.strip() for l in result.splitlines() if l.strip()]

def list_docs_for(tid):
    result = xquery(
        f"declare namespace rt='{NS}';"
        f" for $d in db:list('religioustext')"
        f" where exists(db:open('religioustext',$d)//rt:text[@id='{tid}'])"
        f" return $d"
    )
    return [l.strip() for l in result.splitlines() if l.strip()]

def inspect(tid):
    print(f"\nTranslation: {tid}")
    docs = list_docs_for(tid)
    print(f"Documents: {docs}")
    for doc_name in docs:
        # Query book count
        book_count = xquery(
            f"declare namespace rt='{NS}';"
            f" count(db:open('religioustext','{doc_name}')/rt:text/rt:book)"
        )
        print(f"\n  Document: {doc_name} ({book_count} books)")
        print(f"  {'canonicalOrder':<16} {'code':<6} {'name':<30} {'chapters':>8} {'verses':>6}")
        print(f"  {'-'*70}")
        # Query each book's attributes + verse count as tab-separated rows
        rows = xquery(
            f"declare namespace rt='{NS}';"
            f" for $b in db:open('religioustext','{doc_name}')/rt:text/rt:book"
            f" return string-join(("
            f"   string($b/@canonicalOrder),"
            f"   string($b/@code),"
            f"   string($b/@name),"
            f"   string(count($b/rt:chapter)),"
            f"   string(count($b//rt:verse))"
            f" ), '&#9;')"
        )
        for row in rows.splitlines():
            if not row.strip():
                continue
            parts = row.split('\t')
            order    = parts[0] if len(parts) > 0 else 'MISSING'
            code     = parts[1] if len(parts) > 1 else 'MISSING'
            name     = parts[2] if len(parts) > 2 else '?'
            chapters = parts[3] if len(parts) > 3 else '?'
            verses   = parts[4] if len(parts) > 4 else '?'
            print(f"  {order:<16} {code:<6} {name:<30} {chapters:>8} {verses:>6}")

def main():
    if len(sys.argv) > 1:
        ids = [sys.argv[1]]
    else:
        ids = get_all_translation_ids()
        print(f"All translations: {ids}")

    for tid in ids:
        inspect(tid)

if __name__ == "__main__":
    main()
