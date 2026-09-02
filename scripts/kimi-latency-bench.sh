#!/usr/bin/env bash
# conc-1 LATENCY BENCHMARK: K2.6 (:8010) vs K2.7-Code (:8020), single-request (no conc-48
# batching confound). Per model: a direct tok/s probe (raw generation speed) + a conc-1
# agent sweep (true end-to-end per-query latency on the deployed path). Runs K2.7 first
# (up now), then waits for K2.6's :8010 tunnel. Each model runs ALONE on its endpoint.
# Usage: nohup caffeinate -dis bash scripts/kimi-latency-bench.sh >/dev/null 2>&1 &
set -uo pipefail
WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"; cd "$WT"
SAMP="OPENAI_TEMPERATURE=0.6 OPENAI_MAX_TOKENS=16000 OPENAI_SOCKET_TIMEOUT_MS=1800000"
MATRIX="test/fixtures/sweep/matrices/kimi-latency-bench.edn"
LOG="$WT/server/results/latency-bench-$(date +%Y%m%dT%H%M%S).log"
DIRS="$WT/server/results/.latency-bench-dirs"
echo "$LOG" > "$WT/server/results/.latency-bench-log"; : > "$DIRS"
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

health(){ [ "$(curl -s -m 30 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H 'Authorization: Bearer dummy' \
  -d "{\"model\":\"$2\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}" "$1/chat/completions" 2>/dev/null)" = "200" ]; }
wait_health(){ local i; for i in $(seq 1 360); do health "$1" "$2" && { [ "$i" -gt 1 ] && log "  endpoint up (after $i checks)"; return 0; }; [ $(((i-1)%10)) -eq 0 ] && log "  waiting for $1 (check $i/360)"; sleep 30; done; return 1; }

probe(){ # $1 endpoint $2 model $3 label — 3 single completions, raw tok/s
  local r out t ct tps
  log "[$3] direct tok/s probe (3x, max_tokens 400, single request)"
  for r in 1 2 3; do
    out=$(curl -s -m 300 -w '\nTIME=%{time_total}' -H 'Content-Type: application/json' -H 'Authorization: Bearer dummy' \
      -d "{\"model\":\"$2\",\"messages\":[{\"role\":\"user\",\"content\":\"Forklar kort på norsk hva Maskinporten er og hvordan en klient autentiserer seg.\"}],\"max_tokens\":400,\"temperature\":0.6}" \
      "$1/chat/completions" 2>/dev/null)
    t=$(printf '%s' "$out" | grep -oE 'TIME=[0-9.]+' | cut -d= -f2)
    ct=$(printf '%s' "$out" | grep -oE '"completion_tokens":[0-9]+' | grep -oE '[0-9]+' | head -1)
    tps=$(awk "BEGIN{if(\"$t\"+0>0 && \"$ct\"!=\"\")printf \"%.1f\",$ct/$t; else print \"NA\"}")
    log "  [$3] probe$r: ${ct:-?} tok in ${t:-?}s = ${tps} tok/s"
  done
}

bench(){ # $1 label  $2 endpoint  $3 model-name(quoted)  $4 probe-model
  log ">>> [$1] waiting for $2 …"
  wait_health "$2" "$4" || { log "!! [$1] endpoint never came up (3h) — skipping"; return 1; }
  probe "$2" "$4" "$1"
  log "[$1] conc-1 agent sweep (8 Q x N2, sequential)"
  mise exec -- bb config-set services.azure-openai.use-azure-openai-api false digdir platform default >>"$LOG" 2>&1
  mise exec -- bb config-set services.azure-openai.model-name "$3" digdir platform default >>"$LOG" 2>&1
  local before dir
  before=$(ls -dt "$WT"/server/results/sweep-*/ 2>/dev/null | head -1)
  # shellcheck disable=SC2086
  env $SAMP OPENAI_API_ENDPOINT="$2" OPENAI_API_KEY=dummy mise exec -- bb sweep "$MATRIX" >>"$LOG" 2>&1
  dir=$(ls -dt "$WT"/server/results/sweep-*/ 2>/dev/null | head -1)
  [ "$dir" = "$before" ] && { log "!! [$1] no sweep dir produced"; return 1; }
  log "[$1] conc-1 sweep dir -> $(basename "$dir")"
  printf '%s\t%s\n' "$1" "$(basename "$dir")" >> "$DIRS"
}

log "=== conc-1 LATENCY BENCHMARK: K2.7 (now) then K2.6 (waits for :8010) ==="
bench K2.7 http://localhost:8020/v1 '"moonshotai/Kimi-K2.7-Code"' moonshotai/Kimi-K2.7-Code
bench K2.6 http://localhost:8010/v1 '"moonshotai/Kimi-K2.6"'      moonshotai/Kimi-K2.6
mise exec -- bb config-set services.azure-openai.use-azure-openai-api false digdir platform default >>"$LOG" 2>&1
mise exec -- bb config-set services.azure-openai.model-name '"h2h-control"' digdir platform default >>"$LOG" 2>&1
log "=== LATENCY BENCHMARK COMPLETE — dirs: $(tr '\n' ' ' < "$DIRS") ==="
