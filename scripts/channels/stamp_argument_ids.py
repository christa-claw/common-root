#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""One-time backfill: stamp every arguments.json entry with a deterministic
cmt_ id (see comment_id.py). Idempotent — entries that already carry an `id`
are left untouched, so re-running is a no-op. Writes a .bak first.

    python3 scripts/channels/stamp_argument_ids.py            # stamp transcripts/arguments.json
    python3 scripts/channels/stamp_argument_ids.py --dry-run  # report only, write nothing

Going forward the extractor mints ids at creation; this only fills the ~11.7k
entries that predate the scheme.
"""

import argparse
import json
import os
import shutil
import sys
from collections import Counter

from comment_id import mint_comment_id

LEDGER = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                      "transcripts", "arguments.json")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--file", default=LEDGER, help="ledger path (default: transcripts/arguments.json)")
    ap.add_argument("--dry-run", action="store_true", help="report only; do not write")
    args = ap.parse_args()

    with open(args.file, encoding="utf-8") as f:
        entries = json.load(f)

    stamped = 0
    already = 0
    skipped_refless = 0
    ids = Counter()          # id -> count, to surface collisions
    for entry in entries:
        existing = entry.get("id")
        if existing:
            already += 1
            ids[existing] += 1
            continue
        if not entry.get("verse_refs"):   # no verse anchor -> can't seed -> no id
            skipped_refless += 1
            continue
        cid = mint_comment_id(entry)
        entry["id"] = cid
        ids[cid] += 1
        stamped += 1

    # Collisions: any id shared by 2+ entries. With video_id + refs + full
    # summary in the key these should be exact-duplicate arguments; report them
    # loudly so they can be de-duplicated rather than silently violating the
    # unique public_id index at seed time.
    collisions = {cid: n for cid, n in ids.items() if n > 1}

    print(f"entries: {len(entries)}")
    print(f"newly stamped: {stamped}")
    print(f"skipped (no verse_refs): {skipped_refless}")
    print(f"already had id: {already}")
    print(f"distinct ids: {len(ids)}")
    if collisions:
        print(f"!! COLLISIONS: {len(collisions)} id(s) shared by 2+ entries "
              f"({sum(collisions.values())} entries total) — de-dup before seeding:")
        for cid, n in list(collisions.items())[:20]:
            print(f"   {cid}  x{n}")
    else:
        print("collisions: none")

    if args.dry_run:
        print("(dry run — nothing written)")
        return 0 if not collisions else 2

    if stamped == 0:
        print("nothing to write (all entries already stamped).")
        return 0 if not collisions else 2

    shutil.copy2(args.file, args.file + ".bak")
    with open(args.file, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"written: {args.file}  (backup: {args.file}.bak)")
    return 0 if not collisions else 2


if __name__ == "__main__":
    sys.exit(main())
