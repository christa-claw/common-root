#!/usr/bin/env bash
#
# health_check.sh — is the site actually serving, and did every backing service
# come back? Runs ON THE LINODE from cron; deployed to /opt/common-root/.
#
# WHY THIS EXISTS
# ---------------
# Meilisearch crash-looped for THIRTEEN DAYS in August 2026 (a `:latest` pull
# moved the engine past its own database format) and nobody noticed, because
# nothing on the box was watching and search failing is invisible from the
# reader's home page. The container was not stopped — it was in "Restarting",
# which is why this script checks container STATE rather than just curling the
# site, and why it asks Meilisearch whether it actually holds documents rather
# than only whether it answers.
#
# WHAT IT DOES NOT COVER, HONESTLY
# --------------------------------
# It runs on the box, so it cannot tell you the box is gone. It catches "one
# service did not come back" — which is the reboot failure mode — not "the
# Linode is down". An external pinger is the complement, not a duplicate.
#
# NOTIFICATION
# ------------
# Mails through the same SMTP relay the app uses (SPRING_MAIL_* in .env), via
# curl, so the box needs no MTA. It mails on the TRANSITION into failure, then
# at most once an hour while it stays broken, and once on recovery. A check
# that mails every five minutes is a check you filter to a folder and stop
# reading, which is the same as not having one.
#
# INSTALL
#   Two lines in /opt/common-root/.env decide where alerts go. Without them the
#   script says "ALERTING OFF" on every run rather than pretending to watch:
#     HEALTH_MAIL_FROM=info@common-root.org     # must be an ADDRESS: the relay
#                                               # answers 501 to a bare username
#     HEALTH_ALERT_TO=<whoever reads it>
#
#   scp scripts/ops/health_check.sh root@<box>:/opt/common-root/
#   ssh root@<box> 'chmod +x /opt/common-root/health_check.sh'
#   ssh root@<box> 'crontab -l 2>/dev/null; echo "*/5 * * * * /opt/common-root/health_check.sh >>/var/log/common-root-health.log 2>&1"' | ssh root@<box> 'crontab -'
#
# Run it by hand any time: it prints one line per check and exits non-zero if
# anything is wrong. After a reboot, that is the whole post-flight.

set -uo pipefail

COMPOSE_DIR="${COMPOSE_DIR:-/opt/common-root}"
STATE_DIR="${STATE_DIR:-/var/tmp/common-root-health}"
RENOTIFY_SECONDS="${RENOTIFY_SECONDS:-3600}"
SITE_URL="${SITE_URL:-https://common-root.org/}"
APP_URL="${APP_URL:-http://127.0.0.1:8090/}"
MEILI_INDEX="${MEILI_INDEX:-search}"

# Every container that must be running for the site to be whole. umami is
# included deliberately: analytics dying silently is how you end up trusting a
# dashboard that stopped counting.
CONTAINERS=(
  religioustext-caddy
  religioustext-app
  religioustext-basex
  religioustext-mysql
  religioustext-meili
  religioustext-umami
)

# ...but ask compose what the project ACTUALLY has, and check the union. A list
# hard-coded in a monitor drifts the first time a service is added — the audio
# container arrived in September and a fixed list would never have looked at it.
# The literals above stay as the floor: a service deleted from the compose file
# by accident should still be noticed as missing.
if compose_names=$(docker compose -f "$COMPOSE_DIR/docker-compose.prod.yml" ps -a --format '{{.Name}}' 2>/dev/null); then
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    for known in "${CONTAINERS[@]}"; do [ "$known" = "$name" ] && continue 2; done
    CONTAINERS+=("$name")
  done <<< "$compose_names"
fi

mkdir -p "$STATE_DIR"
FAILURES=()

note_ok()   { printf 'OK    %s\n' "$1"; }
note_fail() { printf 'FAIL  %s\n' "$1"; FAILURES+=("$1"); }

# .env carries MEILI_MASTER_KEY and the SMTP relay credentials.
#
# DO NOT source this file. One of its values contains a literal `$V4`, and
# `. .env` makes bash expand it: under `set -u` the script dies before it runs,
# and without `set -u` it silently swallows the `$V4` and hands you a password
# that is wrong in a way nothing reports. That is the same expansion that lost
# the MySQL root password in June 2026 — see CLAUDE_NOTES. So parse the file as
# literal KEY=VALUE text, which is also how the values were meant to be read.
load_env() {
  local file="$1" line key val
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; *=*) ;; *) continue ;; esac
    key=${line%%=*}; val=${line#*=}
    key=${key#export }
    key=${key// /}
    case "$key" in [A-Za-z_]*) ;; *) continue ;; esac
    case "$key" in *[!A-Za-z0-9_]*) continue ;; esac
    case "$val" in
      \"*\") val=${val#\"}; val=${val%\"} ;;
      \'*\') val=${val#\'}; val=${val%\'} ;;
    esac
    printf -v "$key" '%s' "$val"     # assignment, not evaluation: $ stays a $
  done < "$file"
}
load_env "$COMPOSE_DIR/.env"

# ── 1. Containers: running, and healthy where a healthcheck exists ───────────
for c in "${CONTAINERS[@]}"; do
  state=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null) || state="missing"
  health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null) || health="none"
  if [ "$state" != "running" ]; then
    note_fail "$c is $state"                 # "restarting" lands here — the August case
  elif [ "$health" = "unhealthy" ]; then
    note_fail "$c is running but unhealthy"
  else
    note_ok "$c ($state${health:+, $health})"
  fi
done

# ── 2. The site as a visitor sees it: Caddy + TLS + the app behind them ──────
code=$(curl -sS -m 15 -o /dev/null -w '%{http_code}' "$SITE_URL" 2>/dev/null) || code="000"
[ "$code" = "200" ] && note_ok "site $SITE_URL -> 200" || note_fail "site $SITE_URL -> $code"

# The app directly, so a failure tells you WHICH half broke: if this is 200 and
# the one above is not, the problem is Caddy or the certificate, not the app.
code=$(curl -sS -m 15 -o /dev/null -w '%{http_code}' "$APP_URL" 2>/dev/null) || code="000"
[ "$code" = "200" ] && note_ok "app $APP_URL -> 200" || note_fail "app $APP_URL -> $code"

# The app's own verdict on its subsystems (GET /api/v1/health, api-spec §3.1):
# "ok" with 200, or "degraded" with 503. It sees things from INSIDE that this
# script cannot, and this script sees the case it cannot report on — an app too
# dead to answer at all. Keep both.
health_body=$(curl -sS -m 15 -w '\n%{http_code}' "${HEALTH_URL:-${APP_URL%/}/api/v1/health}" 2>/dev/null) || health_body=$'\n000'
health_code=${health_body##*$'\n'}
if [ "$health_code" = "200" ] && printf '%s' "$health_body" | grep -q '"ok"'; then
  note_ok "app reports healthy (/api/v1/health)"
elif [ "$health_code" = "000" ]; then
  note_fail "/api/v1/health did not answer"
else
  note_fail "/api/v1/health -> $health_code: $(printf '%s' "$health_body" | head -c 200 | tr '\n' ' ')"
fi

# ── 3. Meilisearch, from inside the network ──────────────────────────────────
# Meili publishes no port, so the probe runs from a container that is already on
# the network. caddy:2 is Alpine and carries busybox wget — no extra image to
# pull every five minutes just to ask one question.
probe() { docker exec religioustext-caddy wget -q -T 10 -O - "$@" 2>/dev/null; }

if probe "http://meilisearch:7700/health" | grep -q available; then
  note_ok "meilisearch answers /health"
  # Answering is not the same as holding the corpus. After August's engine
  # upgrade the index was EMPTY until reindex_search.py ran, and an empty index
  # looks exactly like a healthy one from /health.
  if [ -n "${MEILI_MASTER_KEY:-}" ]; then
    # busybox wget wants --header with a SPACE, not an = sign.
    stats=$(probe --header "Authorization: Bearer ${MEILI_MASTER_KEY}" \
                  "http://meilisearch:7700/indexes/${MEILI_INDEX}/stats")
    docs=$(printf '%s' "$stats" | tr ',' '\n' \
           | grep -o '"numberOfDocuments":[0-9]*' | sed 's/.*://')
    if [ -z "${stats:-}" ]; then
      # No answer at all is far more likely a probe limitation than an outage —
      # /health already passed. Say so; do not wake anyone for it.
      note_ok "meilisearch document count unreadable (probe returned nothing)"
    elif [ -z "${docs:-}" ]; then
      note_fail "meilisearch index '${MEILI_INDEX}' returned no document count (missing index?)"
    elif [ "$docs" -lt 1000 ]; then
      note_fail "meilisearch index '${MEILI_INDEX}' holds only $docs documents — reindex needed"
    else
      note_ok "meilisearch index holds $docs documents"
    fi
  else
    note_ok "meilisearch document count skipped (no MEILI_MASTER_KEY in .env)"
  fi
else
  note_fail "meilisearch does not answer /health"
fi

# ── 4. Notify, sparingly ─────────────────────────────────────────────────────
send_mail() {
  local subject="$1" body="$2" from to rc
  # HEALTH_MAIL_FROM first: RELIGIOUSTEXT_MAIL_FROM is set literally in the
  # compose file, NOT in .env, so it is invisible here — and falling back to
  # SPRING_MAIL_USERNAME hands the relay a username where it wants an address,
  # which is answered with "501 MAIL failed" and no mail. Observed 2026-09-23.
  from="${HEALTH_MAIL_FROM:-${RELIGIOUSTEXT_MAIL_FROM:-${SPRING_MAIL_USERNAME:-}}}"
  to="${HEALTH_ALERT_TO:-$from}"

  if [ -z "${SPRING_MAIL_HOST:-}" ]; then
    echo "(ALERTING OFF: no SPRING_MAIL_HOST in .env - nothing was sent)"
    return 0
  fi
  case "$from" in *@*) ;; *)
    echo "(ALERTING OFF: sender '$from' is not an address - set HEALTH_MAIL_FROM in .env)"
    return 0 ;;
  esac
  case "$to" in *@*) ;; *)
    echo "(ALERTING OFF: recipient '$to' is not an address - set HEALTH_ALERT_TO in .env)"
    return 0 ;;
  esac

  printf 'From: %s\nTo: %s\nSubject: %s\n\n%s\n' "$from" "$to" "$subject" "$body" \
    | curl -sS --ssl-reqd \
        --url "smtp://${SPRING_MAIL_HOST}:${SPRING_MAIL_PORT:-587}" \
        --user "${SPRING_MAIL_USERNAME:-}:${SPRING_MAIL_PASSWORD:-}" \
        --mail-from "$from" --mail-rcpt "$to" --upload-file -
  rc=$?
  # Say so loudly when the alert itself fails. A monitor whose alerting is
  # broken is worse than no monitor: it reports success by staying quiet, which
  # is precisely what it does when it cannot speak at all.
  if [ "$rc" -eq 0 ]; then
    echo "(alert mailed to $to)"
  else
    echo "(ALERT NOT SENT: curl exit $rc sending to $to via ${SPRING_MAIL_HOST})"
  fi
}

STAMP="$STATE_DIR/failing-since"
NOTIFIED="$STATE_DIR/last-notified"
now=$(date +%s)

if [ ${#FAILURES[@]} -gt 0 ]; then
  body=$(printf '%s\n' "${FAILURES[@]}")
  last=0; [ -f "$NOTIFIED" ] && last=$(cat "$NOTIFIED" 2>/dev/null || echo 0)
  if [ ! -f "$STAMP" ] || [ $(( now - last )) -ge "$RENOTIFY_SECONDS" ]; then
    send_mail "common-root: ${#FAILURES[@]} check(s) failing" "$body"
    echo "$now" > "$NOTIFIED"
  fi
  [ -f "$STAMP" ] || echo "$now" > "$STAMP"
  exit 1
fi

if [ -f "$STAMP" ]; then
  since=$(cat "$STAMP" 2>/dev/null || echo "$now")
  send_mail "common-root: recovered" "All checks passing again after $(( (now - since) / 60 )) minutes."
  rm -f "$STAMP" "$NOTIFIED"
fi
exit 0
