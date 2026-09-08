#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
# run_scan.sh — run the transcript source-inventory scan in the background.
#
# Usage:
#   ./run_scan.sh            # start it; returns immediately
#   tail -f transcripts/scan_sources.log    # watch progress live
#   cat transcripts/mentioned_sources.md     # the result (written when done)
#
# It will not block your shell or any MCP bridge: nohup + & detaches it, and
# all output goes to the log file. Safe to re-run (overwrites the log/result).

cd "$(dirname "$0")/../.." || exit 1
LOG="transcripts/scan_sources.log"

# Refuse to start a second copy if one is already running.
if pgrep -f 'scan_sources.py' >/dev/null; then
    echo "A scan is already running (PID $(pgrep -f scan_sources.py | tr '\n' ' '))."
    echo "Watch it:  tail -f $LOG"
    exit 0
fi

nohup python3 scripts/channels/scan_sources.py > "$LOG" 2>&1 &
PID=$!
echo "Scan started in the background (PID $PID)."
echo "  Watch progress:  tail -f $LOG"
echo "  Result file:     transcripts/mentioned_sources.md  (written when finished)"
echo "  Stop early:      kill $PID"
