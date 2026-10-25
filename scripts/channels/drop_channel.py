#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""drop_channel.py — take a whole channel off the site.

Removing a channel touches six places. Two of them will put it back if you
miss them, which is why this exists rather than a note in a doc:

  1. transcripts/arguments.json   its entries                  (this script)
  2. channels.properties          its fetch block              (this script)
  3. transcripts/<folder>/        its .vtt files               (this script, moved aside)
  4. prod MySQL  comments         deleted by the ORPHAN SWEEP on the next app
                                  boot — DataSeeder drops rows whose public_id
                                  is no longer in the ledger, unless a human
                                  edited them. So steps 1-3 plus a release and
                                  deploy is what actually clears the site.
  5. prod MySQL  the channel's system User account             (manual, below)
  6. Meilisearch                  reindex, or its comments stay searchable

The two that bite:

  * The folder. 02b walks os.listdir(TRANSCRIPTS_DIR) — it does NOT read
    channels.properties. Leave the transcripts in place and the next nightly
    backfill re-anchors the channel straight back into the ledger.
  * Locally edited rows. The orphan sweep KEEPS a row a human edited, on the
    rule that a correction outranks the ledger. For a channel being removed
    that rule is wrong, so this script reports how many there are and they
    have to go through the editor (or SQL) by hand.

Deliberately does NOT delete anything: the folder is MOVED to
transcripts/_removed/<folder>/ and the ledger is backed up first. Reversing
means moving the folder back and restoring the backup.

Usage:
  python3 scripts/channels/drop_channel.py "Some Channel"           # dry run
  python3 scripts/channels/drop_channel.py "Some Channel" --apply
"""
import argparse
import json
import os
import shutil
import sys
from datetime import datetime

REPO       = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER     = os.path.join(REPO, "transcripts", "arguments.json")
PROPS      = os.path.join(REPO, "channels.properties")
TRANSCRIPTS = os.path.join(REPO, "transcripts")
REMOVED    = os.path.join(TRANSCRIPTS, "_removed")
PIDFILE    = os.path.join(TRANSCRIPTS, ".ollama_extract.pid")


def extractor_running():
    """Same check drop_argument.py makes: the extractor's in-memory ledger
    would clobber our write on its next save."""
    try:
        with open(PIDFILE) as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
        return pid
    except (FileNotFoundError, ValueError, ProcessLookupError, PermissionError):
        return None


def props_block(channel_folder):
    """The channels.properties lines whose `.folder` is this channel, plus the
    bare key line that owns them. Returns (keys, line_indices)."""
    with open(PROPS, encoding="utf-8") as f:
        lines = f.read().splitlines()
    keys = set()
    for line in lines:
        if "=" not in line:
            continue
        k, v = (x.strip() for x in line.split("=", 1))
        if k.endswith(".folder") and v == channel_folder:
            keys.add(k[: -len(".folder")])
    idx = [i for i, line in enumerate(lines)
           if "=" in line and line.split("=", 1)[0].strip().split(".")[0] in keys]
    return keys, idx, lines


def preflight_sql(channel):
    """The one thing this script cannot answer for itself.

    It reads the ledger; the account lives in prod MySQL, which the Mac never
    writes to and this script has no credentials for. So rather than guess, it
    emits the query that says whether the account owns ANYTHING beyond the
    arguments we seeded.

    Everything below hangs off users.id by ON DELETE CASCADE, which is the
    danger: one DELETE on the user row silently takes all of it. The counts are
    what decide whether that is a tidy-up or a destruction.
    """
    c = channel.replace("'", "''")
    return """PRE-FLIGHT — run on prod, BEFORE deleting the account.
Everything here cascades from users.id, so a DELETE takes all of it at once.

    SELECT u.id, u.email, u.is_system,
           (u.password_hash = 'LOCKED-NO-LOGIN')                      AS unclaimed,
           (SELECT COUNT(*) FROM comments      c WHERE c.user_id = u.id
              AND c.seed_ledger = 'arguments')                        AS seeded_ours,
           (SELECT COUNT(*) FROM comments      c WHERE c.user_id = u.id
              AND c.seed_ledger = 'arguments' AND c.locally_edited)   AS seeded_edited,
           (SELECT COUNT(*) FROM comments      c WHERE c.user_id = u.id
              AND (c.seed_ledger IS NULL OR c.seed_ledger <> 'arguments'))
                                                                      AS comments_not_ours,
           (SELECT COUNT(*) FROM personal_notes n WHERE n.user_id = u.id) AS notes,
           (SELECT COUNT(*) FROM highlights     h WHERE h.user_id = u.id) AS highlights,
           (SELECT COUNT(*) FROM saved_searches s WHERE s.user_id = u.id) AS saved_searches,
           (SELECT COUNT(*) FROM api_keys       k WHERE k.user_id = u.id) AS api_keys,
           (SELECT COUNT(*) FROM group_memberships g WHERE g.user_id = u.id) AS groups
      FROM users u
     WHERE u.display_name = '%s';

How to read it:

  unclaimed = 1 and everything after seeded_ours is 0
      Nobody ever took the account and it owns only what we put there. The
      orphan sweep clears the comments; deleting the row afterwards is tidy-up.

  unclaimed = 0
      It was CLAIMED. There is a real person behind it. Stop — deleting now
      destroys their account, and claiming also provisions a member group and
      an org ACL that the sweep knows nothing about.

  comments_not_ours, notes, highlights, saved_searches > 0
      They WROTE things. None of it came from our ingest and none of it is in
      the ledger, so nothing else in this removal would touch it — only the
      DELETE would. Keep the account, or export first.

  seeded_edited > 0
      Rows they corrected by hand. The orphan sweep deliberately KEEPS these
      (a human correction outranks the ledger), so they survive the removal as
      orphans and need clearing separately — by public_id, not by user.

  api_keys > 0
      Someone was reading the API as this account. Deleting it breaks those
      keys without warning.
""" % c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("channel", help='channel name as it appears in the ledger, e.g. "Some Channel"')
    ap.add_argument("--apply", action="store_true", help="actually make the changes")
    args = ap.parse_args()
    ch = args.channel

    pid = extractor_running()
    if pid and args.apply:
        sys.exit("the extractor is running (PID %d) and would clobber the edit — "
                 "pause it first:\n    scripts/channels/02_extract_ctl.sh stop" % pid)

    with open(LEDGER, encoding="utf-8") as f:
        ledger = json.load(f)
    mine = [e for e in ledger if e.get("channel") == ch]
    seedable = [e for e in mine if e.get("id") and e.get("verse_refs")]
    if not mine:
        names = sorted({e.get("channel") for e in ledger if e.get("channel")})
        sys.exit("no ledger entries for %r.\nknown channels:\n  %s" % (ch, "\n  ".join(names)))

    folder = os.path.join(TRANSCRIPTS, ch)
    vtts = 0
    for root, _dirs, files in os.walk(folder):
        vtts += sum(1 for n in files if n.endswith(".vtt"))

    keys, idx, lines = props_block(ch)

    print("channel            : %s" % ch)
    print("ledger entries     : %d  (%d seedable — these are the rows on the site)" % (len(mine), len(seedable)))
    print("transcripts        : %d .vtt in %s" % (vtts, folder if os.path.isdir(folder) else "(no folder)"))
    print("channels.properties: %s" % (", ".join(sorted(keys)) or "(no block found)"))
    print()
    print("NOT handled here — do these too:")
    print("  * release + deploy, so the orphan sweep drops the %d rows from prod MySQL" % len(seedable))
    print("  * run the PRE-FLIGHT below before deleting the account")
    print("  * reindex Meilisearch, or its comments stay searchable")
    print("  * record the reason in docs/channel-vetting.md and update docs/outreach-sent.md")
    print()
    print(preflight_sql(ch))

    if not args.apply:
        print("dry run — nothing changed. Re-run with --apply.")
        return

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    backup = LEDGER + ".bak.before-drop-%s-%s" % (ch.replace(" ", "_"), stamp)
    shutil.copy2(LEDGER, backup)

    kept = [e for e in ledger if e.get("channel") != ch]
    tmp = LEDGER + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False)
    os.replace(tmp, LEDGER)
    print("ledger  : %d entries removed, %d remain (backup: %s)"
          % (len(mine), len(kept), os.path.basename(backup)))

    if idx:
        drop = set(idx)
        with open(PROPS, "w", encoding="utf-8") as f:
            f.write("\n".join(l for i, l in enumerate(lines) if i not in drop) + "\n")
        print("properties: %d lines removed" % len(idx))

    if os.path.isdir(folder):
        os.makedirs(REMOVED, exist_ok=True)
        dest = os.path.join(REMOVED, ch)
        if os.path.exists(dest):
            dest += "-" + stamp
        shutil.move(folder, dest)
        print("transcripts: moved to %s" % dest)
        print("             (02b walks the transcripts dir, so leaving them would")
        print("              have re-anchored the channel on the next nightly run)")

    print()
    print("done. The site still shows the channel until a release is deployed.")


if __name__ == "__main__":
    main()
