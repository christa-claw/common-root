#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
drop_argument.py — surgically remove bad extracted arguments from the ledger
(transcripts/arguments.json), one entry at a time or by pattern.

Two removal modes with different meanings:
  requeue    delete the entry entirely -> the extractor RE-PROCESSES that
             video on its next pass (use after a prompt/extractor fix).
  tombstone  replace the entry with {"source_file", "useful": false,
             "dropped": true} -> permanently retired, never re-extracted
             (use for videos the model will never get right, or after a
             channel owner requests removal).

Usage:
  python3 scripts/channels/drop_argument.py list <pattern>            # preview matches
  python3 scripts/channels/drop_argument.py requeue <pattern>
  python3 scripts/channels/drop_argument.py tombstone <pattern>

<pattern> is a case-insensitive substring matched against source_file AND
argument_summary, so both of these work:
  python3 scripts/channels/drop_argument.py list "How should we Utilize"
  python3 scripts/channels/drop_argument.py list "imitating the pagans"

Only entries with an extracted summary are touched: skip/tombstone/error
entries never match, so a broad pattern can't nuke bookkeeping rows.

Safety:
  - refuses to modify while the extractor is running (its in-memory state
    would clobber the edit on its next save) — pause it first:
        scripts/channels/02_extract_ctl.sh stop
  - always prints every matched entry and asks for confirmation
  - atomic write (same tmp+rename discipline as the extractor)

NOTE: this edits the LEDGER only. Comments already seeded into MySQL are a
separate matter (see DataSeeder semantics) — reseed or delete rows there too.
"""
import json
import os
import sys

REPO    = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER  = os.path.join(REPO, "transcripts", "arguments.json")
PIDFILE = os.path.join(REPO, "transcripts", ".ollama_extract.pid")


def extractor_running():
    try:
        with open(PIDFILE) as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)          # signal 0: existence check only
        return pid
    except (FileNotFoundError, ValueError, ProcessLookupError, PermissionError):
        return None


def matches(entry, pattern):
    if "argument_summary" not in entry:
        return False             # skip/tombstone/error rows are never touched
    hay = (entry.get("source_file", "") + "\n" +
           entry.get("argument_summary", "")).lower()
    return pattern.lower() in hay


def show(entries):
    for e in entries:
        print(f"  {e.get('source_file','?')}")
        print(f"    [{e.get('argument_type','?')}] "
              f"{e.get('argument_summary','')[:100]}")


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("list", "requeue", "tombstone"):
        print(__doc__)
        sys.exit(1)
    cmd, pattern = sys.argv[1], sys.argv[2]

    with open(LEDGER) as f:
        ledger = json.load(f)

    hits = [e for e in ledger if matches(e, pattern)]
    if not hits:
        print(f"no extracted-argument entries match {pattern!r}")
        sys.exit(0)

    print(f"{len(hits)} match(es) for {pattern!r}:")
    show(hits)
    if cmd == "list":
        return

    pid = extractor_running()
    if pid:
        print(f"\nREFUSING: extractor is running (PID {pid}) and would clobber "
              f"this edit on its next save.\nPause it first:  scripts/channels/02_extract_ctl.sh stop")
        sys.exit(1)

    verb = ("DELETE (re-extracted next run)" if cmd == "requeue"
            else "TOMBSTONE (permanently retired)")
    if input(f"\n{verb} these {len(hits)} entr{'y' if len(hits)==1 else 'ies'}? [y/N] ").strip().lower() != "y":
        print("aborted, nothing changed")
        sys.exit(0)

    hit_ids = {id(e) for e in hits}
    if cmd == "requeue":
        ledger = [e for e in ledger if id(e) not in hit_ids]
    else:
        ledger = [({"source_file": e["source_file"], "useful": False, "dropped": True}
                   if id(e) in hit_ids else e) for e in ledger]

    tmp = LEDGER + ".tmp"
    with open(tmp, "w") as f:
        json.dump(ledger, f, indent=2, ensure_ascii=False)
    os.replace(tmp, LEDGER)
    print(f"done — ledger now {len(ledger)} entries "
          f"({'removed' if cmd == 'requeue' else 'tombstoned'} {len(hits)})")


if __name__ == "__main__":
    main()
