#!/usr/bin/env bash
# Concurrency-readiness watch for the Kimi endpoint. Polls a conc-48 burst on a
# fixed cadence; on the FIRST burst that clears the threshold it launches the full
# weekend queue (which then runs its OWN hardened agent-health smoke gate before
# A→F — so a burst pass cannot feed A→F a half-alive endpoint). If no burst clears
# within the polling window, leaves the queue PAUSED (holding) and exits.
#
# Args: $1 initial-delay min (default 0)
#       $2 poll interval sec (default 120)
#       $3 max polls        (default 30  → 30×120s ≈ 60min window)
# Usage: bash scripts/kimi-conc-watch.sh 0 120 30
set -uo pipefail
WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"; cd "$WT"
ENDPOINT="http://localhost:8010/v1"
N=48; THRESH=46                 # require >=46/48 HTTP-200 under concurrent load
DELAY_MIN="${1:-0}"
INTERVAL_SEC="${2:-120}"
MAX_ATTEMPTS="${3:-30}"
LOG="$WT/server/results/kimi-conc-watch-$(date +%Y%m%dT%H%M%S).log"
echo "$LOG" > "$WT/server/results/.kimi-conc-watch-log"
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

conc_test(){ # 0 if >=THRESH of N concurrent completions return HTTP 200 (fails fast when down)
  local codes ok dist
  codes=$(seq 1 "$N" | xargs -P "$N" -I{} curl -s -m 90 -o /dev/null -w '%{http_code}\n' \
    -H 'Content-Type: application/json' -H 'Authorization: Bearer dummy' \
    -d '{"model":"moonshotai/Kimi-K2.6","messages":[{"role":"user","content":"Svar kort på norsk: hva er hovedstaden i Norge?"}],"max_tokens":64,"temperature":0.6}' \
    "$ENDPOINT/chat/completions" 2>/dev/null)
  ok=$(printf '%s\n' "$codes" | grep -c '^200$')
  dist=$(printf '%s\n' "$codes" | sort | uniq -c | tr '\n' ' ')
  log "  conc-$N burst: ${ok}/${N} HTTP-200  [codes: ${dist}]"
  [ "$ok" -ge "$THRESH" ]
}

log "=== conc-readiness watch: conc-$N burst every ${INTERVAL_SEC}s, up to ${MAX_ATTEMPTS} polls, threshold ${THRESH}/${N}; initial-delay ${DELAY_MIN}min ==="
if [ "${DELAY_MIN}" -gt 0 ] 2>/dev/null; then
  log "delaying first check by ${DELAY_MIN}min"; sleep $(( DELAY_MIN * 60 )); log "delay elapsed"
fi

launched=0
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  log "poll ${attempt}/${MAX_ATTEMPTS}"
  if conc_test; then
    log "✅ CONCURRENCY CLEARED — launching the full run."
    if pgrep -f "kimi-weekend-queue.sh" >/dev/null 2>&1; then
      log "  (queue already running — not relaunching)"
    else
      nohup caffeinate -dis bash "$WT/scripts/kimi-weekend-queue.sh" >/dev/null 2>&1 &
      log "  launched queue pid $!  (its hardened smoke gate runs before A→F)"
    fi
    launched=1; break
  fi
  [ "$attempt" -lt "$MAX_ATTEMPTS" ] && { log "  not ready — wait ${INTERVAL_SEC}s"; sleep "$INTERVAL_SEC"; }
done
[ "$launched" = 0 ] && log "=== polling window elapsed (${MAX_ATTEMPTS}×${INTERVAL_SEC}s), concurrency NEVER cleared — HOLDING; queue stays PAUSED. ==="
echo "RESULT: $([ "$launched" = 1 ] && echo LAUNCHED-FULL-RUN || echo HOLDING-STILL-PAUSED)"
