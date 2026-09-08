#!/usr/bin/env python3
"""
Fix NIV duplicate books in BaseX.
The NIV document has two sets of books — an old partial set and a new complete set.
Strategy: for each duplicated book code, delete the one with fewer verses (the old one).
For non-duplicated books in the first set only, delete those too (they're from the old ingestion).
"""
import urllib.request
import urllib.parse
import base64

BASEX_URL = "http://localhost:8984/rest/religioustext"
AUTH = base64.b64encode(b"admin:admin").decode()

def query(xq):
    params = urllib.parse.urlencode({"query": xq})
    url = f"{BASEX_URL}?{params}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {AUTH}"})
    with urllib.request.urlopen(req) as r:
        return r.read().decode().strip()

NS = "declare namespace rt='http://religioustext.org/schema/1.0';"

# Get all books with their codes and verse counts
print("Fetching book data...")
result = query(f"""
{NS}
for $b in db:open('religioustext')//rt:text[@id='bible-niv-2011']//rt:book
return concat(string($b/@code), '|', string($b/@name), '|', count($b//rt:verse))
""")

lines = [l for l in result.split('\n') if '|' in l]
print(f"Total book entries: {len(lines)}")

# Group by code
from collections import defaultdict
by_code = defaultdict(list)
for line in lines:
    parts = line.split('|')
    if len(parts) == 3:
        code, name, count = parts[0], parts[1], int(parts[2])
        by_code[code].append((name, count))

# Find duplicates
dupes = {code: entries for code, entries in by_code.items() if len(entries) > 1}
print(f"Duplicated book codes: {len(dupes)}")
for code, entries in sorted(dupes.items()):
    print(f"  {code}: {entries}")

# For each duplicated code, keep the one with MORE verses (the new complete ingestion)
# Delete the one with fewer verses
print("\nDeleting duplicate books (keeping the one with more verses)...")
deleted = 0
for code, entries in dupes.items():
    # Sort by verse count descending — keep first (most verses), delete rest
    entries_sorted = sorted(entries, key=lambda x: x[1], reverse=True)
    keep_count = entries_sorted[0][1]
    
    # Delete books with this code that have fewer verses than the max
    xq = f"""
    {NS}
    for $b in db:open('religioustext')//rt:text[@id='bible-niv-2011']//rt:book[@code='{code}']
    where count($b//rt:verse) < {keep_count}
    return delete node $b
    """
    query(xq)
    deleted += 1
    print(f"  Deleted duplicate {code}")

# Also find books that only exist in the first (partial) set
# These are books whose names are in the first set but NOT in the second
# We can identify them by checking if they have very few verses compared to expected
# Actually — let's just check the final count
final = query(f"{NS} count(db:open('religioustext')//rt:text[@id='bible-niv-2011']//rt:book)")
print(f"\nFinal book count: {final} (expected 66)")

# List remaining books
remaining = query(f"""
{NS}
for $b in db:open('religioustext')//rt:text[@id='bible-niv-2011']//rt:book
order by string($b/@code)
return concat(string($b/@code), ' ', string($b/@name), ' (', count($b//rt:verse), ' verses)')
""")
print("\nRemaining books:")
print(remaining)
