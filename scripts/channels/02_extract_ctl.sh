#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
#
# extract_ctl.sh — pause/resume control for the local Ollama comment extractor.
#
# The extractor is resumable (it skips any source_file already recorded in
# arguments.json), so "pause" just stops the process and "start"/"resume" runs
# it again, picking up where it left off. `stop` sends SIGTERM, which the
# extractor catches to save the current batch and exit cleanly; if it is mid
# model-call it is force-killed after a short grace period — safe either way,
# because saves are atomic and the at-most-one in-flight video is simply redone.
#
# Usage:
#   scripts/channels/02_extract_ctl.sh start [extractor args...]   # e.g. --channels "Apologia Studios"
#   scripts/channels/02_extract_ctl.sh stop                        # pause (graceful)
#   scripts/channels/02_extract_ctl.sh status                      # running? + progress count
#   scripts/channels/02_extract_ctl.sh tail                        # follow the log
#
set -u
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
PIDFILE="$REPO/transcripts/.ollama_extract.pid"
LOG="$REPO/transcripts/ollama_extract.log"
PY=/usr/bin/python3
cd "$REPO" || { echo "cannot cd $REPO"; exit 1; }

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; }

progress() {
  [ -f transcripts/arguments.json ] || return 0
  "$PY" - <<'PY'
import json
try:
    d = json.load(open('transcripts/arguments.json'))
    print(f"  arguments.json: {len(d)} processed, {sum(1 for r in d if r.get('useful'))} useful")
except Exception as e:
    print(f"  (arguments.json not readable this instant: {e})")
PY
}

case "${1:-status}" in
  start|resume)
    if running; then echo "already running (PID $(cat "$PIDFILE"))"; exit 0; fi
    nohup "$PY" -u scripts/channels/02_extract_arguments_ollama.py "${@:2}" >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    echo "started PID $(cat "$PIDFILE")   args: ${*:2}"
    echo "  log:    $LOG"
    echo "  follow: scripts/channels/02_extract_ctl.sh tail"
    ;;
  stop|pause)
    if running; then
      PID="$(cat "$PIDFILE")"
      kill -TERM "$PID" 2>/dev/null
      printf "pausing PID %s " "$PID"
      for _ in $(seq 1 15); do running || break; printf "."; sleep 1; done
      if running; then echo " (mid-call) forcing"; kill -9 "$PID" 2>/dev/null; else echo " stopped cleanly"; fi
      rm -f "$PIDFILE"
    else
      echo "not running"; rm -f "$PIDFILE"
    fi
    progress
    ;;
  status)
    if running; then
      PID="$(cat "$PIDFILE")"
      echo "RUNNING  PID $PID  ($(ps -p "$PID" -o etime= 2>/dev/null | tr -d ' ') elapsed)"
    else
      echo "STOPPED"
    fi
    progress
    ;;
  tail)
    tail -n 30 -f "$LOG"
    ;;
  remaining)
    # How much is left, and when the current run will drain the queue:
    # start time, processed (all / since this run's resume banner), % left,
    # and an ETA at this run's pace. All counts come from disk, so this works
    # (minus pace) even when the extractor is stopped.
    if running; then
      PID="$(cat "$PIDFILE")"
      STARTED="$(ps -p "$PID" -o lstart= 2>/dev/null)"
      ELAPSED="$(ps -p "$PID" -o etime= 2>/dev/null | tr -d ' ')"
    else
      STARTED=""; ELAPSED=""
    fi
    RESUMED="$(grep 'Resuming:' "$LOG" 2>/dev/null | tail -1 | sed 's/[^0-9]//g')"
    TOTAL="$(find transcripts -name '*.vtt' | wc -l | tr -d ' ')"
    STARTED="$STARTED" ELAPSED="$ELAPSED" RESUMED="$RESUMED" TOTAL="$TOTAL" "$PY" - <<'PY'
import json, os

processed = len(json.load(open('transcripts/arguments.json')))
total     = int(os.environ['TOTAL'] or 0)
resumed   = os.environ['RESUMED']
started   = os.environ['STARTED'].strip()
elapsed   = os.environ['ELAPSED']
remaining = max(0, total - processed)
this_run  = processed - int(resumed) if resumed else None

def elapsed_minutes(e):
    """ps etime is [[dd-]hh:]mm:ss."""
    if not e: return None
    days, rest = (e.split('-', 1) + [''])[:2] if '-' in e else ('0', e)
    parts = [int(x) for x in rest.split(':')]
    while len(parts) < 3: parts.insert(0, 0)
    h, m, sec = parts
    return int(days) * 1440 + h * 60 + m + sec / 60

mins = elapsed_minutes(elapsed)
if started:
    print(f"Run started: {started}  (elapsed {elapsed})")
else:
    print("Run:         STOPPED (counts from disk; no pace/ETA)")
run_part = f", {this_run} this run" if this_run is not None else ""
rate = (this_run / mins) if (this_run and mins and mins > 0) else None
if rate: run_part += f" ({rate:.1f}/min)"
print(f"Processed:   {processed} of {total} transcripts{run_part}")
pct = (remaining / total * 100) if total else 0
eta = f" — ETA ~{remaining / rate:.0f} min" if (rate and remaining) else ""
print(f"Remaining:   {remaining} ({pct:.1f}% left){eta}")
PY
    ;;
  *)
    echo "usage: $0 {start|resume [extractor args]|stop|pause|status|tail|remaining}"
    exit 1
    ;;
esac
