#!/usr/bin/env bash
# publish_public.sh — export the public part of this repository.
#
# The private repository (religious-texts) is the working repo: code, design
# notes, correspondence, the argument ledger, automation, all of it. The public
# repository (github.com/christa-claw/common-root) receives an EXPORT of it —
# an allow-listed set of tracked files — as ONE commit per release, so its
# history is the sequence of releases and nothing else. Deliberation never
# crosses; neither does anything that is not on the list.
#
#   scripts/publish/publish_public.sh dry-run [OUTDIR]
#       Build the export tree under OUTDIR (default out/public-export) and a
#       tarball beside it. Prints what went in. Nothing leaves the machine.
#
#   scripts/publish/publish_public.sh publish [PUBLIC_CHECKOUT]
#       Sync the export into a checkout of the public repo (default
#       ../common-root, override with PUBLIC_DIR), commit "Release <version>"
#       with that version's CHANGELOG section as the body, tag v<version>, and
#       push. Refuses on a -SNAPSHOT version or a dirty private tree: run it at
#       the release commit, after the tag, before the bump (CHANGELOG step 2b).
#
#   scripts/publish/publish_public.sh check [OUTDIR]
#       Dry-run plus the leak sweep: gitleaks (if installed), a grep for keys,
#       e-mail addresses, session links and this machine's paths, and a list of
#       code comments that name files which did NOT make the export.
#
# Inputs: scripts/publish/public-paths.txt (the allow-list, see its header) and
# scripts/publish/public-overlay/ (files laid over the export last: LICENSE,
# NOTICE, the README footer, and empty placeholders for the data files the
# Dockerfile expects but which are not published).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIST="$ROOT/scripts/publish/public-paths.txt"
OVERLAY="$ROOT/scripts/publish/public-overlay"
MODE="${1:-dry-run}"
cd "$ROOT"

version() { sed -n 's#^    <version>\(.*\)</version>#\1#p' pom.xml | head -1; }

# ── 1. Select: tracked files ∩ allow prefixes − deny prefixes ────────────────
selected() {
  git ls-files -z | python3 -c '
import sys
allow, deny = [], []
for raw in open(sys.argv[1], encoding="utf-8"):
    line = raw.split("#", 1)[0].strip()
    if not line: continue
    (deny if line.startswith("!") else allow).append(line.lstrip("!"))
def hit(path, prefixes):          # plain prefix match: "app/", "pom.xml", "transcripts/notes-"
    return any(path.startswith(p) for p in prefixes)
out = sys.stdout
for path in sys.stdin.buffer.read().split(b"\0"):
    if not path: continue
    p = path.decode("utf-8", "surrogateescape")
    if hit(p, allow) and not hit(p, deny):
        out.write(p + "\n")
' "$LIST"
}

# ── 2. Build the export tree ────────────────────────────────────────────────
build() {
  local out="$1"
  rm -rf "$out"; mkdir -p "$out"
  selected > "$out.files"
  # tar preserves modes (+x on scripts) and handles odd filenames
  tar -cf - -T "$out.files" | tar -xf - -C "$out"
  # overlay: licence, notice, placeholders — laid over last, so they win
  if [ -d "$OVERLAY" ]; then
    (cd "$OVERLAY" && find . -type f ! -name 'README-footer.md' -print0 | tar -cf - --null -T -) | tar -xf - -C "$out"
    if [ -f "$OVERLAY/README-footer.md" ] && [ -f "$out/README.md" ]; then
      { printf '\n'; cat "$OVERLAY/README-footer.md"; } >> "$out/README.md"
    fi
  fi
  local n; n=$(wc -l < "$out.files" | tr -d ' ')
  echo "export: $n tracked files + overlay → $out"
  echo "  by top-level directory:"
  cut -d/ -f1 "$out.files" | sort | uniq -c | sort -rn | sed 's/^/    /'
}

# ── 3. Sweep the export for what must not be there ──────────────────────────
sweep() {
  local out="$1" bad=0
  echo "── leak sweep ─────────────────────────────────────────────"
  if command -v gitleaks >/dev/null 2>&1; then
    gitleaks detect --no-git --source "$out" --redact --exit-code 2 >/dev/null 2>&1 \
      && echo "gitleaks: clean" || { echo "gitleaks: FINDINGS (run: gitleaks detect --no-git --source $out)"; bad=1; }
  else
    echo "gitleaks: not installed (brew install gitleaks) — pattern grep only"
  fi
  local pat='crk_[A-Za-z0-9]{20,}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}|claude\.ai/code/session|/Volumes/VMs|/Users/[a-z]+|christina|BEGIN (RSA|OPENSSH) PRIVATE|api[_-]?key\s*[:=]\s*[A-Za-z0-9_-]{16,}'
  # Reviewed and accepted (2026-09-08): dev seed accounts, the site's own
  # public addresses, documentation placeholders, and the superuser seed in
  # V11 (the maintainer's public commit address).
  local BENIGN='noreply@|example\.(com|org)|a@b\.cd|@author|@test\.local|info@common-root\.org|system@religioustext\.platform|your@|you@|crk_0{20,}|crk_REPLACE|christa\.claw@proton\.me'
  local hits
  hits=$(grep -rEIn --exclude-dir=node_modules --exclude=publish_public.sh "$pat" "$out" \
         | grep -vE "$BENIGN" || true)
  if [ -n "$hits" ]; then
    echo "pattern hits (review each — e-mails and local paths are the usual):"
    echo "$hits" | cut -c1-160 | sed 's/^/  /'
    bad=1
  else
    echo "patterns: clean"
  fi
  echo "── dangling references (code/docs naming files not exported) ──"
  local missing=0
  for f in CLAUDE_NOTES.md CLAUDE.md COMMANDS.md RUNGS.md channels.properties \
           docs/api-design.md docs/access-control.md docs/search.md docs/groups.md \
           docs/logged-in.md docs/bible-queue.tsv docs/bible-expansion-plan.md \
           docs/channel-vetting.md scripts/automation; do
    local refs
    refs=$(grep -rIl --exclude-dir=node_modules -- "$f" "$out" 2>/dev/null | sed "s#^$out/##" | tr '\n' ' ' || true)
    if [ -n "$refs" ]; then echo "  $f ← $refs"; missing=1; fi
  done
  [ $missing -eq 0 ] && echo "  none"
  echo "───────────────────────────────────────────────────────────"
  return $bad
}

case "$MODE" in
  dry-run|check)
    OUT="${2:-$ROOT/out/public-export}"
    build "$OUT"
    V=$(version)
    tar -czf "$OUT-$V.tgz" -C "$(dirname "$OUT")" "$(basename "$OUT")"
    echo "tarball: $OUT-$V.tgz ($(du -h "$OUT-$V.tgz" | cut -f1))"
    if [ "$MODE" = check ]; then sweep "$OUT" || { echo "check: review the findings above"; exit 2; }; fi
    ;;
  publish)
    PUB="${2:-${PUBLIC_DIR:-$ROOT/../common-root}}"
    V=$(version)
    case "$V" in *-SNAPSHOT) echo "refusing: $V is a snapshot — publish at the release commit" >&2; exit 1;; esac
    [ -z "$(git status --porcelain)" ] || { echo "refusing: private tree is dirty" >&2; exit 1; }
    git rev-parse -q --verify "refs/tags/v$V" >/dev/null || { echo "refusing: tag v$V not found — tag first" >&2; exit 1; }
    [ -d "$PUB/.git" ] || { echo "refusing: $PUB is not a git checkout (clone the public repo there)" >&2; exit 1; }
    if git -C "$PUB" rev-parse -q --verify "refs/tags/v$V" >/dev/null; then
      echo "refusing: public repo already has v$V" >&2; exit 1; fi
    TMP="$(mktemp -d)/export"
    build "$TMP"
    sweep "$TMP" || { echo "refusing: sweep found something — fix, or run 'check' to inspect" >&2; exit 2; }
    # mirror the tree: everything except .git is replaced by the export
    rsync -a --delete --exclude .git "$TMP/" "$PUB/"
    # release body = this version's CHANGELOG section
    BODY=$(awk -v v="$V" '$0 ~ "^## \\["v"\\]" {p=1; next} /^## \[/ {if(p) exit} p' CHANGELOG.md)
    git -C "$PUB" add -A
    if git -C "$PUB" diff --cached --quiet; then echo "nothing changed since the last export"; exit 0; fi
    git -C "$PUB" commit -q -F - <<EOF
Release $V

$BODY
EOF
    git -C "$PUB" tag -a "v$V" -m "Release $V"
    git -C "$PUB" push -q origin HEAD "v$V"
    echo "published: $(git -C "$PUB" rev-parse --short HEAD) → $(git -C "$PUB" remote get-url origin) v$V"
    ;;
  *) echo "usage: $0 dry-run|check [OUTDIR] | publish [PUBLIC_CHECKOUT]" >&2; exit 64;;
esac
