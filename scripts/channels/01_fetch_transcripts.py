#!/usr/bin/env python3
"""
yt-dlp transcript downloader for Religious Texts Platform.
Reads channel config from channels.properties and downloads transcripts
for /videos, /streams, and/or /shorts per channel.

Usage:
    python3 scripts/channels/01_fetch_transcripts.py                    # process all channels
    python3 scripts/channels/01_fetch_transcripts.py --channel shamsi   # process one channel by key
    python3 scripts/channels/01_fetch_transcripts.py --dry-run          # show what would run
    python3 scripts/channels/01_fetch_transcripts.py --info-only        # backfill .info.json (no VTTs)
"""

import argparse
import configparser
import os
import re
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR      = Path(__file__).resolve().parents[2]
CHANNELS_FILE   = SCRIPT_DIR / "channels.properties"
TRANSCRIPTS_DIR = SCRIPT_DIR / "transcripts"
ARCHIVE_FILE    = TRANSCRIPTS_DIR / "downloaded.txt"
SKIP_FILE       = TRANSCRIPTS_DIR / "unavailable.txt"

# YouTube IDs are 11 chars; in our filenames they sit in a [..] token before the
# extension, e.g. "20230924_Lesson 13 ... [UtUVJSv65Dk].en.vtt".
VIDEO_ID_RE     = re.compile(r"\[([A-Za-z0-9_-]{11})\]")

# A failed download NEVER enters the download-archive, so a members-only video
# (which fails after ~5 metadata calls x a 2s sleep each) is re-attempted on
# EVERY run — the dominant recurring cost. We learn such ids into SKIP_FILE and
# subtract them from new_ids. Only PERMANENT reasons are recorded; transient
# failures (429 throttling, bot-check) must stay retryable and are NOT matched.
YT_ERROR_RE       = re.compile(r"ERROR:\s*\[youtube\]\s*([A-Za-z0-9_-]{11}):\s*(.*)")
PERMANENT_REASONS = (
    "members",                                  # "...available to this channel's members..."
    "join this youtube channel",
    "private video",
    "video has been removed",
    "video is no longer available",
    "video is unavailable",
    "account associated with this video has been terminated",
)

DEFAULT_TYPES = ["videos", "streams"]

YTDLP_BASE = [
    "yt-dlp",
    "--write-auto-sub",
    "--skip-download",
    "--sub-lang", "en",
    "--write-info-json",
    "--sleep-requests", "2",
    "--sleep-subtitles", "2",
    "--min-sleep-interval", "2",
    "--max-sleep-interval", "8",
    "--ignore-errors",
    "--no-warnings",
    "--download-archive", str(ARCHIVE_FILE),
]

YTDLP_INFO_ONLY = [
    "yt-dlp",
    "--skip-download",
    "--no-write-subs",
    "--write-info-json",
    "--sleep-requests", "2",
    "--min-sleep-interval", "2",
    "--max-sleep-interval", "8",
    "--ignore-errors",
    "--no-warnings",
    # No --download-archive so it processes all entries regardless
]


def handled_ids():
    """The set of YouTube ids we've already handled, derived from the [videoid]
    token in filenames on disk (.info.json written for every processed video,
    .vtt for each with subtitles). YouTube ids are globally unique and videos are
    immutable, so a handled id never needs touching again."""
    ids = set()
    for pattern in ("*.info.json", "*.vtt"):
        for f in TRANSCRIPTS_DIR.rglob(pattern):
            matches = VIDEO_ID_RE.findall(f.name)
            if matches:
                ids.add(matches[-1])      # the id token is the last [..] in the name
    return ids


def write_archive(ids):
    """Persist the handled-set in yt-dlp's download-archive format, as a BACKSTOP:
    even if the flat-playlist diff below lets one through, the archive still stops
    a re-download."""
    ARCHIVE_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(ARCHIVE_FILE, "w") as fh:
        for vid in sorted(ids):
            fh.write(f"youtube {vid}\n")
    print(f"Archive: {len(ids)} already-handled videos recorded in {ARCHIVE_FILE.name}\n")


def load_skip():
    """Ids we've learned are permanently un-fetchable (members-only, private,
    removed). Format: one id per line, optional '  # reason' trailer."""
    ids = set()
    if SKIP_FILE.exists():
        for line in SKIP_FILE.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                ids.add(line.split()[0])
    return ids


def append_skip(entries):
    """entries: list of (id, reason). Append the not-yet-recorded ones."""
    if not entries:
        return
    SKIP_FILE.parent.mkdir(parents=True, exist_ok=True)
    existing = load_skip()
    added = 0
    with open(SKIP_FILE, "a") as fh:
        for vid, reason in entries:
            if vid not in existing:
                fh.write(f"{vid}  # {reason[:80]}\n")
                existing.add(vid)
                added += 1
    if added:
        print(f"  🚫  learned {added} permanently-unavailable id(s) "
              f"→ won't retry (see {SKIP_FILE.name})")


def run_capturing(cmd):
    """Run yt-dlp, stream its output live (progress still shows), and collect any
    (id, reason) that failed for a PERMANENT reason so callers can skip-list them.
    Returns (returncode, [(id, reason), ...])."""
    permanent = []
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)
    for line in proc.stdout:
        sys.stdout.write(line)
        m = YT_ERROR_RE.search(line)
        if m:
            vid, reason = m.group(1), m.group(2).strip()
            if any(p in reason.lower() for p in PERMANENT_REASONS):
                permanent.append((vid, reason))
    proc.wait()
    return proc.returncode, permanent


def channel_video_ids(url):
    """FAST enumeration of a channel/tab's video ids via --flat-playlist: ids
    only, no per-video metadata extraction and no throttle sleeps. This is the
    cheap listing pass — a few playlist-page requests instead of one metadata
    fetch per video — so a 4k-video channel lists in seconds. Returns [] on a
    listing error (safe: the run simply fetches nothing and retries next time,
    rather than mass re-downloading)."""
    cmd = ["yt-dlp", "--flat-playlist", "--print", "id",
           "--no-warnings", "--ignore-errors", url]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0 and not res.stdout.strip():
        print(f"     (flat listing failed for {url} — skipping this pass)")
        return []
    return [ln.strip() for ln in res.stdout.splitlines() if ln.strip()]


def load_channels():
    config = configparser.ConfigParser()
    config.read(CHANNELS_FILE)

    if "channels" not in config:
        print(f"ERROR: No [channels] section in {CHANNELS_FILE}")
        sys.exit(1)

    channels = {}
    for key, value in config["channels"].items():
        if "." in key:
            continue
        base_url   = value.strip().rstrip("/")
        types_key  = f"{key}.types"
        folder_key = f"{key}.folder"

        types = DEFAULT_TYPES
        if types_key in config["channels"]:
            types = [t.strip() for t in config["channels"][types_key].split(",")]

        folder = config["channels"].get(folder_key, None)

        channels[key] = {"url": base_url, "types": types, "folder": folder}

    return channels


def fetch(channel_key, base_url, content_type, folder=None, dry_run=False,
          info_only=False, handled=None, skip=None):
    """Fetch transcripts for a channel + content type.

    Fast path: flat-list the tab's ids, drop everything already in `handled`, and
    run the expensive subtitle download ONLY for the new ids (fed as a batch of
    watch URLs). Known videos are bypassed completely — no per-video yt-dlp work,
    no throttle sleeps — which is the whole speed win over letting yt-dlp re-walk
    the channel each run.
    """
    url = f"{base_url}/{content_type}"

    if folder:
        output_template = str(
            TRANSCRIPTS_DIR / folder / content_type /
            "%(upload_date)s_%(title)s [%(id)s].%(ext)s"
        )
    else:
        output_template = str(
            TRANSCRIPTS_DIR / "%(channel)s" / content_type /
            "%(upload_date)s_%(title)s [%(id)s].%(ext)s"
        )

    # info-only intentionally reprocesses everything (no archive, no diff).
    if info_only:
        cmd = YTDLP_INFO_ONLY + ["--output", output_template, url]
        print(f"  ▶  {url} (info only)")
        if dry_run:
            print(f"     DRY RUN: {' '.join(cmd)}")
            return
        result = subprocess.run(cmd)
        print(f"  {'✅  Done' if result.returncode == 0 else f'❌  Failed (exit {result.returncode})'}: {url}")
        return

    handled = handled or set()
    skip = skip or set()
    listed = channel_video_ids(url)
    new_ids = [v for v in listed if v not in handled and v not in skip]
    skipped = sum(1 for v in listed if v in skip)
    print(f"  ▶  {url}: {len(listed)} listed, {len(new_ids)} new"
          + (f", {skipped} skip-listed (unavailable)" if skipped else ""))

    if dry_run:
        if new_ids:
            preview = ", ".join(new_ids[:5]) + (" …" if len(new_ids) > 5 else "")
            print(f"     DRY RUN: would fetch {len(new_ids)} new video(s): {preview}")
        return
    if not new_ids:
        print("  ⏭  nothing new — bypassed")
        return

    # Only the new ids get the expensive path — as explicit watch URLs via a batch
    # file, so yt-dlp never touches the already-handled back-catalogue.
    import tempfile
    tf = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
    try:
        for v in new_ids:
            tf.write(f"https://www.youtube.com/watch?v={v}\n")
        tf.close()
        cmd = YTDLP_BASE + ["--output", output_template, "-a", tf.name]
        returncode, permanent = run_capturing(cmd)
        # Record permanently-unavailable ids so future runs never re-attempt them.
        append_skip(permanent)
        skip.update(vid for vid, _ in permanent)
        if returncode == 0:
            print(f"  ✅  Done: {len(new_ids)} new from {url}")
        else:
            # A members-only/private id in the batch makes yt-dlp exit non-zero
            # even though the good videos downloaded — not a real failure if every
            # error was permanent and now skip-listed.
            note = " (all errors were permanent → skip-listed)" if permanent else ""
            print(f"  ❌  yt-dlp exit {returncode}: {url}{note}")
    finally:
        os.unlink(tf.name)


def main():
    parser = argparse.ArgumentParser(description="Fetch YouTube transcripts for Religious Texts Platform")
    parser.add_argument("--channel", help="Process only this channel key")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--types", help="Override content types (comma-separated)")
    parser.add_argument("--info-only", action="store_true",
                        help="Fetch only .info.json files — use to backfill video URLs")
    parser.add_argument("--retry-unavailable", action="store_true",
                        help="Ignore the learned skip-list for this run (e.g. after "
                             "gaining channel membership) so those ids are re-attempted")
    args = parser.parse_args()

    channels = load_channels()

    if args.channel:
        if args.channel not in channels:
            print(f"ERROR: Channel '{args.channel}' not found in {CHANNELS_FILE}")
            print(f"Available: {', '.join(channels.keys())}")
            sys.exit(1)
        channels = {args.channel: channels[args.channel]}

    if args.types:
        override_types = [t.strip() for t in args.types.split(",")]
        for ch in channels.values():
            ch["types"] = override_types

    # The handled-set (from filenames on disk) drives the fast bypass. Compute it
    # once for the whole run; also persist it as the yt-dlp archive backstop
    # (except for --info-only, which reprocesses everything).
    handled = set() if args.info_only else handled_ids()
    if not args.dry_run and not args.info_only:
        write_archive(handled)
    elif not args.info_only:
        print(f"{len(handled)} already-handled videos on disk (bypass set)\n")

    # Learned skip-list of permanently-unavailable ids (members-only, private,
    # removed). --retry-unavailable ignores it for this run; --info-only doesn't
    # download subtitles so it's irrelevant there.
    skip = set()
    if not args.info_only and not args.retry_unavailable:
        skip = load_skip()
        if skip:
            print(f"Skip-list: {len(skip)} permanently-unavailable videos will be "
                  f"bypassed (from {SKIP_FILE.name})\n")
    elif args.retry_unavailable:
        print("--retry-unavailable: ignoring skip-list this run\n")

    print(f"Processing {len(channels)} channel(s)...\n")

    for key, cfg in channels.items():
        print(f"[{key}] {cfg['url']} → {cfg['types']}"
              + (f" (folder: {cfg['folder']})" if cfg['folder'] else ""))
        for content_type in cfg["types"]:
            fetch(key, cfg["url"], content_type,
                  folder=cfg["folder"],
                  dry_run=args.dry_run,
                  info_only=args.info_only,
                  handled=handled,
                  skip=skip)
        print()

    print("Done.")


if __name__ == "__main__":
    main()
