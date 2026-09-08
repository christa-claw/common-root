#!/usr/bin/env python3
"""
Detect arguments whose source video is gone from YouTube, and produce the SQL
that removes them from the site.

WHY THIS EXISTS
---------------
The corpus is decoupled from YouTube. `fetch_transcripts.py` pulls a .vtt once
and `extract_arguments_ollama.py` reads it from disk forever after,
reconstructing the video URL from the [id] in the filename. Nothing re-checks
the source. So when a creator deletes their video, the argument stays on the
site with a dead "watch on YouTube" link — the opposite of the deal we offer
channels, which is that a reader who opens the passage is sent to them.

This script closes that loop: probe the videos backing visible arguments, and
when one is confirmed gone, emit tombstones for the comments derived from it.

WHY TOMBSTONES RATHER THAN DELETES
----------------------------------
`DataSeeder` rebuilds channel comments from arguments.json at every boot (V14),
so a plain DELETE resurrects on the next deploy. `comment_tombstones` is the
durable form — the reseed skips any tombstoned public_id. And because
`comment_id.py` mints cmt_<uuid5> deterministically from
video_id|refs|summary, a later re-extraction regenerates the SAME id, which the
existing tombstone still catches. The removal survives both re-seeding and
re-extraction with no extra bookkeeping.

WHY THE SQL IS EMITTED, NOT EXECUTED
------------------------------------
The comments live in prod's MySQL; this runs on a laptop. Emitting a reviewable
.sql file that you apply with the same `docker exec … mysql` you use for
everything else keeps prod credentials out of this script and puts a human
between "a probe returned 404" and "someone's work disappeared".

SAFETY
------
A false positive silently deletes a creator's arguments, so:

  * DEAD_RUNS_REQUIRED consecutive dead readings across SEPARATE runs before a
    video counts as gone. Covers transient 404s, rate limiting, and videos set
    private for a few minutes during an edit.
  * A circuit breaker: if more than MAX_DEAD_FRACTION of probes come back dead
    in one run, the run is discarded WITHOUT touching the ledger. Three hundred
    creators do not delete videos on the same afternoon — that is YouTube
    blocking us, and it is the failure mode that would otherwise empty the
    corpus. A terminated channel trips it too, on purpose: removing a whole
    channel's arguments should be a decision, not a cron job.
  * Anything that is not a clean 200/404/401 is UNKNOWN and resets nothing.
    Unknown never counts toward removal.
  * Transcripts on disk are never touched.

USAGE
-----
    python3 check_video_liveness.py                  # probe + report (cron-safe)
    python3 check_video_liveness.py --emit-sql out.sql
    python3 check_video_liveness.py --limit 50       # smoke test
    python3 check_video_liveness.py --report-only    # read the ledger, no network

Then, after reading the file:
    docker exec -i religioustext-mysql mysql -urtuser -p… religioustext < out.sql
"""

import argparse
import collections
import json
import os
import re
import signal
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

HERE            = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ARGUMENTS_FILE  = os.path.join(HERE, "transcripts", "arguments.json")
LEDGER_FILE     = os.path.join(HERE, "transcripts", "video_liveness.json")

OEMBED          = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={vid}&format=json"
USER_AGENT      = "common-root-link-check/1.0 (+https://common-root.org)"

REQUEST_TIMEOUT     = 15      # seconds
DELAY_BETWEEN       = 0.5     # ~2 req/s — polite; ~17 min for the visible set
DEAD_RUNS_REQUIRED  = 3       # consecutive dead readings, in separate runs
MAX_DEAD_FRACTION   = 0.05    # circuit breaker

ALIVE, GONE, PRIVATE, UNKNOWN = "alive", "gone", "private", "unknown"
DEAD_STATUSES = (GONE, PRIVATE)

_STOP = False


def _on_sigint(signum, frame):
    global _STOP
    _STOP = True
    print("\n[interrupt] finishing current probe, then saving the ledger…", file=sys.stderr)


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ── video id recovery ─────────────────────────────────────────────────

_ID_IN_FILENAME = re.compile(r"\[([A-Za-z0-9_-]{11})\]")
_ID_IN_URL      = re.compile(r"[?&]v=([A-Za-z0-9_-]{11})")


def video_id_of(row):
    """The row's YouTube id, recovered from whichever field still carries it.

    Two thirds of visible rows have a null `video_id` but keep the id in
    `video_url` or in the [id] the fetcher wrote into the transcript filename —
    the same [id] `extract_arguments_ollama.py` reads to rebuild the watch URL.
    Trusting `video_id` alone would silently leave those unchecked while the
    coverage line claimed otherwise.
    """
    vid = row.get("video_id")
    if vid:
        return vid
    m = _ID_IN_URL.search(row.get("video_url") or "")
    if m:
        return m.group(1)
    m = _ID_IN_FILENAME.search(row.get("source_file") or "")
    return m.group(1) if m else None


# ── data ──────────────────────────────────────────────────────────────

def load_arguments():
    with open(ARGUMENTS_FILE, encoding="utf-8") as f:
        return json.load(f)


def visible_rows(rows):
    """Rows that actually surface beside a verse.

    `useful` alone is not enough: comment_id.py keys on the verse refs, and the
    stamping pass skips ref-less entries, so a useful argument with no refs
    never reaches a page. Checking those would be probing links no reader can
    follow.
    """
    return [r for r in rows if r.get("useful") and r.get("verse_refs")]


def load_ledger():
    if not os.path.exists(LEDGER_FILE):
        return {}
    with open(LEDGER_FILE, encoding="utf-8") as f:
        return json.load(f)


def save_ledger(ledger):
    tmp = LEDGER_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(ledger, f, indent=1, sort_keys=True)
    os.replace(tmp, LEDGER_FILE)   # atomic; a killed run never leaves a half ledger


# ── probing ───────────────────────────────────────────────────────────

def probe(vid):
    """One video's liveness via the keyless oEmbed endpoint.

    200 → public (or unlisted, which is still reachable by link, so it stays)
    404 → deleted, or made private in a way that removes it from oEmbed
    401 → private
    anything else, or a network error → UNKNOWN, which never counts as dead
    """
    req = urllib.request.Request(OEMBED.format(vid=vid), headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            return ALIVE if resp.status == 200 else UNKNOWN
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return GONE
        if e.code in (401, 403):
            return PRIVATE
        return UNKNOWN          # 429 above all — rate limiting is not a death
    except Exception:
        return UNKNOWN


def run_probes(video_ids, ledger, limit=None):
    todo = video_ids[:limit] if limit else video_ids
    results, counts = {}, collections.Counter()

    for i, vid in enumerate(todo, 1):
        if _STOP:
            print(f"[interrupt] stopped after {i - 1}/{len(todo)}", file=sys.stderr)
            return None, counts          # partial run: discard, do not half-update
        status = probe(vid)
        results[vid] = status
        counts[status] += 1
        if i % 100 == 0 or i == len(todo):
            print(f"  probed {i}/{len(todo)}  "
                  f"alive={counts[ALIVE]} gone={counts[GONE]} "
                  f"private={counts[PRIVATE]} unknown={counts[UNKNOWN]}", flush=True)
        time.sleep(DELAY_BETWEEN)
    return results, counts


def apply_results(results, ledger):
    """Fold one run's observations into the ledger's consecutive-dead counters."""
    for vid, status in results.items():
        e = ledger.setdefault(vid, {"consecutive_dead": 0, "first_dead": None,
                                    "last_alive": None, "last_status": None})
        e["last_status"]  = status
        e["last_checked"] = now_iso()
        if status == ALIVE:
            e["consecutive_dead"] = 0
            e["first_dead"] = None
            e["last_alive"] = now_iso()
        elif status in DEAD_STATUSES:
            e["consecutive_dead"] += 1
            if not e["first_dead"]:
                e["first_dead"] = now_iso()
        # UNKNOWN: leave the counters untouched — neither progress nor reset.


def confirmed_gone(ledger, include_acknowledged=False):
    """Videos dead long enough to act on.

    `acknowledged` is what makes a weekly cron liveable. Without it, every run
    after the first re-reports the same videos forever: the script runs on a
    laptop and cannot see prod's comment_tombstones, so it has no way to learn
    that you already applied the SQL. Acknowledging records "handled" locally,
    so the job only shouts about something new. The ledger entry is kept, not
    dropped — the history of when a video died stays readable.
    """
    return {v for v, e in ledger.items()
            if e.get("consecutive_dead", 0) >= DEAD_RUNS_REQUIRED
            and (include_acknowledged or not e.get("acknowledged"))}


# ── output ────────────────────────────────────────────────────────────

def emit_sql(path, rows, gone_ids):
    """SQL that tombstones comments from confirmed-gone videos — except edited ones.

    A MANUALLY EDITED COMMENT IS NEVER REMOVED AUTOMATICALLY. That is the same
    rule V14 already applies to the reseed ("a human edited it — the ledger
    loses"); a dead source video is no reason to throw away work someone did by
    hand, and an edited comment is the most likely place a misattribution was
    corrected. The liveness checker loses to a human edit too.

    The rule is enforced in the SQL rather than here on purpose: `locally_edited`
    lives in prod's `comments` table, and this script reads a JSON snapshot on a
    laptop. Filtering client-side would mean guessing from stale data. Joining
    server-side means the protection cannot be wrong.

    The file leads with a SELECT listing the edited comments it is about to
    spare, so applying it prints the review list before doing anything — the
    reminder arrives at the moment it is actionable. Those ids stay in the ledger
    as confirmed-gone, so they reappear in every later run until handled.
    """
    doomed = [r for r in rows if video_id_of(r) in gone_ids and r.get("id")]
    by_channel = collections.Counter(r.get("channel") or "?" for r in doomed)
    ids = sorted({r["id"] for r in doomed})

    with open(path, "w", encoding="utf-8") as f:
        f.write("-- Tombstones for arguments whose source video is gone from YouTube.\n")
        f.write(f"-- Generated {now_iso()} by check_video_liveness.py\n")
        f.write(f"-- {len(ids)} comments across {len(gone_ids)} videos.\n--\n")
        f.write("-- MANUALLY EDITED COMMENTS ARE SPARED. The INSERT below joins on\n")
        f.write("--   comments.locally_edited = FALSE, so an edited comment cannot be\n")
        f.write("--   removed by this path no matter what the ledger says. The SELECT\n")
        f.write("--   above it prints the ones being spared — read that output; those\n")
        f.write("--   need a human decision (usually: fix the link, or delete it in the\n")
        f.write("--   editor, which tombstones it properly).\n--\n")
        f.write("-- Not reversible through the app: the reseed skips tombstoned ids\n")
        f.write("--   permanently (V14). To undo, DELETE from comment_tombstones and\n")
        f.write("--   reboot the app to reseed.\n--\n")
        for ch, n in by_channel.most_common():
            f.write(f"--   {n:5d}  {ch}\n")
        f.write("\n")

        f.write("CREATE TEMPORARY TABLE dead_video_comments (\n"
                "    public_id VARCHAR(40) NOT NULL PRIMARY KEY\n"
                ") ENGINE=InnoDB;\n\n")
        # Chunked so a large run does not build one enormous statement.
        for i in range(0, len(ids), 500):
            chunk = ids[i:i + 500]
            f.write("INSERT INTO dead_video_comments (public_id) VALUES\n")
            f.write(",\n".join(f"    ('{cid}')" for cid in chunk))
            f.write(";\n\n")

        f.write("-- ── REVIEW: edited comments this will NOT touch ──────────────────\n")
        f.write("SELECT c.public_id,\n"
                "       LEFT(c.content, 160) AS content,\n"
                "       'SPARED - manually edited' AS note\n"
                "  FROM comments c\n"
                "  JOIN dead_video_comments d ON d.public_id = c.public_id\n"
                " WHERE c.locally_edited = TRUE;\n\n")

        f.write("-- ── REMOVAL: everything else ─────────────────────────────────────\n")
        f.write("INSERT IGNORE INTO comment_tombstones (public_id, deleted_at)\n"
                "SELECT c.public_id, NOW(6)\n"
                "  FROM comments c\n"
                "  JOIN dead_video_comments d ON d.public_id = c.public_id\n"
                " WHERE c.locally_edited = FALSE;\n\n")

        f.write("SELECT ROW_COUNT() AS tombstoned;\n")
    return doomed, by_channel


def report(rows, all_rows, ledger, gone_ids):
    print("\n── liveness ledger ──")
    st = collections.Counter(e.get("last_status") for e in ledger.values())
    print(f"  tracked videos     : {len(ledger)}")
    print(f"  last run status    : " + ", ".join(f"{k}={v}" for k, v in st.most_common()))
    pending = {v: e for v, e in ledger.items()
               if 0 < e.get("consecutive_dead", 0) < DEAD_RUNS_REQUIRED}
    print(f"  dead but unconfirmed: {len(pending)} "
          f"(need {DEAD_RUNS_REQUIRED} consecutive runs)")
    ack = sum(1 for e in ledger.values() if e.get("acknowledged"))
    print(f"  CONFIRMED GONE (new): {len(gone_ids)}")
    print(f"  already acknowledged: {ack}")

    if gone_ids:
        doomed = [r for r in rows if video_id_of(r) in gone_ids]
        by_ch = collections.Counter(r.get("channel") or "?" for r in doomed)
        print(f"\n  {len(doomed)} visible comments would be tombstoned:")
        for ch, n in by_ch.most_common():
            print(f"    {n:5d}  {ch}")

    # Coverage, stated rather than implied.
    no_vid = sum(1 for r in rows if not video_id_of(r))
    useful_no_refs = sum(1 for r in all_rows if r.get("useful") and not r.get("verse_refs"))
    print("\n── coverage ──")
    print(f"  rows in arguments.json        : {len(all_rows)}")
    print(f"  visible (useful + verse_refs) : {len(rows)}")
    print(f"  NOT CHECKED — no recoverable id: {no_vid}  (no video_id, video_url or [id] in the filename)")
    print(f"  NOT CHECKED — useful, no refs : {useful_no_refs}  (never reach a page)")


# ── main ──────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--emit-sql", metavar="PATH",
                   help="write tombstone SQL for confirmed-gone videos (review before running)")
    p.add_argument("--limit", type=int, help="probe only the first N videos (smoke test)")
    p.add_argument("--report-only", action="store_true",
                   help="read the ledger and report; no network")
    p.add_argument("--acknowledge", action="store_true",
                   help="mark the currently confirmed-gone videos as handled, so later "
                        "runs stop re-reporting them (use AFTER applying the SQL)")
    args = p.parse_args()

    signal.signal(signal.SIGINT, _on_sigint)

    all_rows = load_arguments()
    rows     = visible_rows(all_rows)
    video_ids = sorted({v for v in (video_id_of(r) for r in rows) if v})
    ledger   = load_ledger()

    if not args.report_only:
        print(f"probing {len(video_ids)} videos backing {len(rows)} visible arguments "
              f"(~{len(video_ids) * DELAY_BETWEEN / 60:.0f} min)…", flush=True)
        results, counts = run_probes(video_ids, ledger, args.limit)

        if results is None:
            print("run discarded (interrupted) — ledger untouched.", file=sys.stderr)
            return 130

        probed = sum(counts.values())
        dead   = counts[GONE] + counts[PRIVATE]
        if probed and dead / probed > MAX_DEAD_FRACTION:
            print(f"\n!! CIRCUIT BREAKER: {dead}/{probed} ({dead / probed:.1%}) came back dead, "
                  f"over the {MAX_DEAD_FRACTION:.0%} threshold.\n"
                  f"   That is far more likely to be rate limiting, a network fault or a\n"
                  f"   terminated channel than mass deletion by creators. The ledger has NOT\n"
                  f"   been updated and nothing will be tombstoned. Investigate, then re-run.",
                  file=sys.stderr)
            return 2

        apply_results(results, ledger)
        save_ledger(ledger)
        print(f"ledger updated: {LEDGER_FILE}")

    gone = confirmed_gone(ledger)
    report(rows, all_rows, ledger, gone)

    if args.acknowledge:
        for v in gone:
            ledger[v]["acknowledged"] = now_iso()
        save_ledger(ledger)
        print(f"\nacknowledged {len(gone)} videos — they will not be reported again.")
        return 0

    if args.emit_sql:
        if not gone:
            print("\nnothing confirmed gone — no SQL written.")
            return 0
        doomed, by_ch = emit_sql(args.emit_sql, rows, gone)
        print(f"\nwrote {len(doomed)} tombstone statements to {args.emit_sql}")
        print("REVIEW IT, then apply on prod:")
        print(f"  docker exec -i religioustext-mysql mysql -urtuser -p… religioustext "
              f"< {args.emit_sql}")
        print("Afterwards: python3 check_video_liveness.py --acknowledge")
        return 3          # "action required" — the wrapper branches on this
    return 0


if __name__ == "__main__":
    sys.exit(main())
