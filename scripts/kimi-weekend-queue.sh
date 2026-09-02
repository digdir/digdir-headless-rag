#!/usr/bin/env bash
# Kimi K2.6 WEEKEND QUEUE — conc-48 sweeps against the (free but flaky) vLLM Kimi
# endpoint, each judged by gpt-5.5. Agent model swapped via config (azure OFF + model-name).
#
# REPAIR LOOP (handles mid-stage flakiness): each stage runs round-0, then re-runs ONLY
# the endpoint-damaged (config×question) combos — health-gated — until every combo has N
# healthy runs (or MAXROUNDS), merges the healthy runs into one clean dir, and judges THAT.
# A degraded run = endpoint-abort signature (status≠complete / empty / <2 LLM calls /
# <200-char response), detected by scripts/repair-util.clj. So a flaky endpoint can no
# longer silently corrupt a stage. Resumable: a stage with an existing repaired+judged dir
# is skipped.
#
# Usage:
#   full queue : nohup caffeinate -dis bash scripts/kimi-weekend-queue.sh >/dev/null 2>&1 &
#   one stage  : bash scripts/kimi-weekend-queue.sh repair-one <label> <server-rel-matrix> [seed-dir-basename]
set -uo pipefail
WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"; cd "$WT"
RESULTS="$WT/server/results"
M="test/fixtures/sweep/matrices"
# endpoint/model parameterized so the SAME repair harness drives K2.6 (:8010) or K2.7 (:8020):
#   KIMI_ENDPOINT=http://localhost:8020/v1 KIMI_MODEL_NAME='"<k2.7-id>"' KIMI_PROBE_MODEL=<k2.7-id> bash ...
ENDPOINT="${KIMI_ENDPOINT:-http://localhost:8010/v1}"
KIMI_MODEL="${KIMI_MODEL_NAME:-\"moonshotai/Kimi-K2.6\"}"
PROBE_MODEL="${KIMI_PROBE_MODEL:-moonshotai/Kimi-K2.6}"
RESTORE_MODEL='"h2h-control"'
SAMP="OPENAI_TEMPERATURE=0.6 OPENAI_MAX_TOKENS=16000 OPENAI_SOCKET_TIMEOUT_MS=1800000"
MAXROUNDS=10
QLOG="$RESULTS/kimi-weekend-$(date +%Y%m%dT%H%M%S).log"
MANIFEST="$RESULTS/kimi-weekend-manifest.tsv"

log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$QLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$QLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }
kimi_on(){ cfgset services.azure-openai.use-azure-openai-api false
           cfgset services.azure-openai.model-name "$KIMI_MODEL"; }
ru(){ mise exec -- bb scripts/repair-util.clj "$@"; }   # repair-util (paths are WT-relative)

kimi_health(){
  local code
  code=$(curl -s -m 45 -o /dev/null -w '%{http_code}' \
    -H 'Content-Type: application/json' -H 'Authorization: Bearer dummy' \
    -d "{\"model\":\"$PROBE_MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1,\"temperature\":0.6}" \
    "$ENDPOINT/chat/completions" 2>/dev/null)
  [ "$code" = "200" ]
}
wait_for_health(){ # $1 label
  local i
  for i in $(seq 1 2880); do
    if kimi_health; then [ "$i" -gt 1 ] && log "  [$1] endpoint healthy (after $i checks)"; return 0; fi
    [ $(( (i-1) % 10 )) -eq 0 ] && log "  [$1] Kimi endpoint not healthy — waiting (check $i/2880, ~60s apart)…"
    sleep 60
  done
  return 1
}
sweep_state(){ local p t d; p="$RESULTS/$1/progress.edn"
  [ -f "$p" ] || { echo "?/?"; return; }
  t=$(grep -oE ':total [0-9]+' "$p" | grep -oE '[0-9]+'); d=$(grep -oE ':completed [0-9]+' "$p" | grep -oE '[0-9]+')
  echo "${d:-?}/${t:-?}"; }
run_gen(){ # $1 server-rel matrix  $2 log  -> echoes new sweep dir basename
  local matrix="$1" outlog="$2" before dir
  kimi_on; before="$(newest_sweep_dir)"
  # shellcheck disable=SC2086
  env $SAMP OPENAI_API_ENDPOINT="$ENDPOINT" OPENAI_API_KEY="dummy" \
    mise exec -- bb sweep "$matrix" >>"$outlog" 2>&1
  dir="$(newest_sweep_dir)"
  { [ "$dir" = "$before" ] || [ -z "$dir" ]; } && return 1
  basename "$dir"
}
judge(){ # $1 results-dir-basename (sweep-* or repaired-*)
  log "  judging $1 (gpt-5.5, azure-on)"
  cfgset services.azure-openai.use-azure-openai-api true
  cfgset services.azure-openai.deployment-name '"gpt-5.5"'
  cfgset services.judge.model '"gpt-5.5"'
  mise exec -- bb sweep-judge "results/$1" digdir >>"$QLOG" 2>&1
  kimi_on
}

# ── REPAIR LOOP for one stage ──────────────────────────────────────────────────
repair_stage(){ # $1 label  $2 server-rel-matrix  $3 optional seed-dir-basename
  local label="$1" matrix="$2" seed="${3:-}" full="$WT/server/$2"
  # resumable: skip if already repaired+judged
  if ls "$RESULTS"/repaired-"$label"-*/runs-judged.csv >/dev/null 2>&1; then
    log "[$label] already repaired+judged ($(ls -d "$RESULTS"/repaired-"$label"-*/ | tail -1 | xargs basename)) — skip"; return 0; fi
  local N; N=$(grep -oE ':repeats[ ]+[0-9]+' "$full" | grep -oE '[0-9]+' | head -1); N="${N:-1}"
  local dirs=()
  if [ -n "$seed" ]; then
    dirs=("$seed"); log ">>> [$label] repair from seed round0=$seed (N=$N)"
  else
    wait_for_health "$label" || { log "!! [$label] endpoint never recovered — ABORT"; exit 5; }
    log ">>> [$label] round0 generating ($matrix, N=$N)"
    local r0; r0="$(run_gen "$matrix" "$QLOG")" || { log "!! [$label] round0 no dir — SKIP"; return 1; }
    dirs=("$r0"); log "[$label] round0 $(sweep_state "$r0") -> $r0"
  fi
  local repmat="$M/.repair-${label}.edn" repmatfull="$WT/server/$M/.repair-${label}.edn"
  local qidsfile="$WT/server/results/.short-${label}.txt"
  local round nshort repdir done_rounds=0
  for round in $(seq 1 "$MAXROUNDS"); do
    local sdirs=(); local d; for d in "${dirs[@]}"; do sdirs+=("server/results/$d"); done
    nshort="$(ru shortq "$N" "$qidsfile" "${sdirs[@]}" 2>/dev/null | tr -dc '0-9')"
    if [ -z "${nshort:-}" ] || [ "${nshort:-0}" = "0" ]; then
      log "[$label] all combos reached $N healthy runs (after $done_rounds repair round(s))"; break; fi
    log "[$label] repair round $round/$MAXROUNDS — $nshort question(s) short of $N healthy"
    ru emit-repair "$WT/server/$matrix" "$repmatfull" "$qidsfile" >>"$QLOG" 2>&1
    wait_for_health "$label" || { log "  [$label] endpoint gone mid-repair — merging what we have"; break; }
    repdir="$(run_gen "$repmat" "$QLOG")" || { log "  [$label] repair round produced no dir — retry"; continue; }
    dirs+=("$repdir"); done_rounds=$round
    log "  [$label] repair round $round -> $repdir ($(sweep_state "$repdir"))"
  done
  local ts final; ts=$(date +%Y%m%dT%H%M%S); final="repaired-${label}-${ts}"
  local sdirs=(); local d; for d in "${dirs[@]}"; do sdirs+=("server/results/$d"); done
  log "[$label] merging healthy runs -> $final"
  ru merge "$N" "server/results/$final" "${sdirs[@]}" 2>/dev/null | while IFS= read -r ln; do log "    [$label] $ln"; done
  printf '%s\t%s\t%s\trounds=%s\n' "$label" "$final" "merged" "$done_rounds" >> "$MANIFEST"
  judge "$final"
  log "[$label] DONE -> $final (judged)"
}

# ── CLI dispatch: repair a single stage standalone ─────────────────────────────
if [ "${1:-}" = "repair-one" ]; then
  echo "$QLOG" > "$RESULTS/.kimi-weekend-runlog"
  log "=== REPAIR-ONE label=$2 matrix=$3 seed=${4:-none} ==="
  repair_stage "$2" "$3" "${4:-}"
  log "=== REPAIR-ONE $2 COMPLETE ==="
  exit 0
fi

# ── FULL QUEUE ─────────────────────────────────────────────────────────────────
echo "$QLOG" > "$RESULTS/.kimi-weekend-runlog"
log "=== KIMI WEEKEND QUEUE start (log: $QLOG) ==="

while pgrep -f "kimi-h2h.sh" >/dev/null 2>&1 || pgrep -f "bb sweep " >/dev/null 2>&1 \
   || pgrep -f "bb sweep-judge" >/dev/null 2>&1; do
  log "waiting for another in-flight sweep to finish…"; sleep 30
done

# SMOKE GATE — planner-404 (abort) vs endpoint flakiness (wait+retry) + agent-health
smoke_ok=0
for attempt in $(seq 1 8); do
  wait_for_health "smoke" || { log "!! [smoke] endpoint never recovered — ABORT"; exit 3; }
  SL="$RESULTS/kimi-weekend-smoke-$(date +%Y%m%dT%H%M%S).log"
  log ">>> [smoke] attempt $attempt — 3 Q, checking planner + agent health"
  SMOKE_DIR="$(run_gen "$M/kimi-weekend-smoke.edn" "$SL")" \
    || { log "  [smoke] no sweep dir — endpoint dropped, retry"; sleep 60; continue; }
  if grep -qE "clj-http: status 404|DeploymentNotFound" "$SL"; then
    log "!! [smoke] FAILED — planner STILL 404s → deployment-name fix did NOT take. ABORTING."; exit 2; fi
  nfail=$(grep -cE "query-planner LLM call failed|falling back to raw query" "$SL" 2>/dev/null || echo 0)
  if [ "${nfail:-0}" -gt 0 ]; then
    log "  [smoke] $nfail planner failure(s), NO 404 → endpoint flaky. Wait + retry."; sleep 60; continue; fi
  if ! mise exec -- bb scripts/smoke-health.clj "$RESULTS/$SMOKE_DIR" >>"$SL" 2>&1; then
    log "  [smoke] agent runs DEGRADED → endpoint flaky. Wait + retry."
    grep -E "DEGRADED|agent-health" "$SL" | tail -4 | while IFS= read -r ln; do log "    $ln"; done
    sleep 60; continue; fi
  log "[smoke] PASSED — 0×404, expansion fires, agent runs healthy. Proceeding."; smoke_ok=1; break
done
[ "$smoke_ok" = 1 ] || { log "!! [smoke] endpoint too flaky after 8 attempts — ABORT."; exit 4; }

# stages (each self-heals; A is skipped if already repaired standalone)
repair_stage "A-headline-full42"   "$M/kimi-A-headline-full42.edn"
repair_stage "B-broad118"          "$M/kimi-B-broad118.edn"
repair_stage "C-snippet-ablation"  "$M/kimi-C-snippet-full42.edn"
repair_stage "E-completeness"      "$M/kimi-E-completeness-full42.edn"
repair_stage "F-readhi"            "$M/kimi-F-readhi-full42.edn"
repair_stage "D-enrichment-118"    "$M/kimi-D-enrichment-broad118.edn"

cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "$RESTORE_MODEL"
log "=== KIMI WEEKEND QUEUE COMPLETE — manifest: $MANIFEST ==="
