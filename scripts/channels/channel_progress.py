#!/usr/bin/env python3
"""
channel_progress.py — per-channel transcript-extraction progress report.

"When can we notify a channel?" -> when this says DONE for it: every fetched
transcript (.vtt on disk) has a ledger entry in transcripts/arguments.json.

  processed  = videos with a ledger entry (the extractor finished them)
  useful     = of those, entries the extractor flagged as carrying an argument
  on_site    = of those, entries that actually SEED as comment cards: useful
               AND verse_refs non-empty AND video_url present (DataSeeder's
               rule since 2026-07-07 — ref-less entries are skipped, they
               cannot anchor to a verse). THIS is the number the reader shows
               and the number outreach emails must quote.
  on_disk    = .vtt transcripts fetched for the channel (videos/ + streams/)
  DONE       = processed == on_disk (and > 0)

NOTE: DONE means "everything FETCHED is processed". If fetch_transcripts.py
hasn't pulled a channel's full history (or the Tuesday cron adds new videos),
on_disk itself grows — so DONE is 'current backlog cleared', not 'channel
frozen forever'. For outreach that is the right bar: their existing catalogue
is represented. Stdlib only.

Usage:  python3 scripts/channels/channel_progress.py
"""
import json, os, sys, collections, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TDIR = os.path.join(ROOT, "transcripts")
LEDGER = os.path.join(TDIR, "arguments.json")
SITE = "https://common-root.org"

SKIP_DIRS = {"__pycache__"}


def outreach_link(channel):
    """Deep link opening the reader with the comments panel pre-filtered to
    this channel (?comments=<name>; ReaderView.beforeEnter, v0.3.3+)."""
    return f"{SITE}/reader?comments={urllib.parse.quote(channel)}"


def main():
    with open(LEDGER, encoding="utf-8") as f:
        ledger = json.load(f)

    processed = collections.Counter()
    useful = collections.Counter()
    on_site = collections.Counter()
    for e in ledger:
        ch = (e.get("source_file") or "?/").split("/")[0]
        processed[ch] += 1
        if e.get("useful"):
            useful[ch] += 1
            if e.get("verse_refs") and e.get("video_url"):
                on_site[ch] += 1

    on_disk = collections.Counter()
    for name in sorted(os.listdir(TDIR)):
        p = os.path.join(TDIR, name)
        if not os.path.isdir(p) or name in SKIP_DIRS:
            continue
        n = 0
        for dirpath, _dirs, files in os.walk(p):
            n += sum(1 for f in files if f.endswith(".vtt"))
        on_disk[name] = n

    channels = sorted(set(on_disk) | set(processed),
                      key=lambda c: -(on_disk.get(c, 0)))
    print(f"{'channel':34}{'on_disk':>8}{'processed':>10}{'useful':>8}"
          f"{'on_site':>9}{'pct':>6}  status")
    done, partial = [], []
    for ch in channels:
        d, p, u = on_disk.get(ch, 0), processed.get(ch, 0), useful.get(ch, 0)
        s = on_site.get(ch, 0)
        pct = (100 * p // d) if d else 0
        if d > 0 and p >= d:
            status = "\u2705 DONE"
            done.append(ch)
        elif d == 0 and p > 0:
            status = "\u26a0\ufe0f ledger-only (folder gone/renamed?)"
        else:
            status = ""
            partial.append((ch, d - p))
        print(f"{ch:34}{d:>8}{p:>10}{u:>8}{s:>9}{pct:>5}%  {status}")

    print()
    if done:
        print("Notify-ready (fetched catalogue fully processed) — email fill-ins")
        print("for docs/outreach-email.md:")
        for ch in done:
            print(f"\n  {ch}")
            print(f"    [N] videos indexed : {processed.get(ch, 0)}")
            print(f"    [M] arguments      : {on_site.get(ch, 0)}   "
                  f"(on the site — useful w/ refs+video; "
                  f"{useful.get(ch, 0)} useful total)")
            print(f"    [CHANNEL_URL]      : {outreach_link(ch)}")
            if on_site.get(ch, 0) == 0:
                print("    ⚠️  0 arguments on the site — do NOT email this one.")
    if partial:
        nxt = sorted(partial, key=lambda t: t[1])[:3]
        print("\nClosest to done:",
              ", ".join(f"{c} ({r} to go)" for c, r in nxt))


if __name__ == "__main__":
    sys.exit(main())
