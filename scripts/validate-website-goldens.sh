#!/usr/bin/env bash
# Plan 2 / D4 — VALIDATION SWEEP for the website goldens.
# deployed (snippet-AB) vs deployed+enrichment-HQ over all 118 new goldens, on the
# incumbent local model (h2h-control) on gx10-f695. Then judge with gpt-5.5.
set -uo pipefail
WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"; cd "$WT"
export OPENAI_API_ENDPOINT="http://gx10-f695:11434/v1"
export OPENAI_API_KEY="ollama"
MATRIX="test/fixtures/sweep/matrices/validate-website-goldens.edn"
RESULTS="$WT/server/results"
RUNLOG="$RESULTS/validate-goldens-$(date +%Y%m%dT%H%M%S).log"
QWEN_SAMP="OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768 OPENAI_PRESERVE_THINKING=true"
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$RUNLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$RUNLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }

log "=== VALIDATION sweep (118 website goldens, deployed vs +enrichment, h2h-control) ==="
echo "$RUNLOG" > "$RESULTS/.validate-current-runlog"
if ! curl -s -m 10 http://gx10-f695:11434/v1/models >/dev/null 2>&1; then log "!! gx10-f695 NOT reachable — aborting"; exit 3; fi
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
before="$(newest_sweep_dir)"
# shellcheck disable=SC2086
env $QWEN_SAMP OPENAI_API_ENDPOINT="$OPENAI_API_ENDPOINT" OPENAI_API_KEY="$OPENAI_API_KEY" \
  mise exec -- bb sweep "$MATRIX" >>"$RUNLOG" 2>&1
dir="$(newest_sweep_dir)"
if [ "$dir" = "$before" ] || [ -z "$dir" ]; then log "!! NO new sweep dir — see $RUNLOG"; exit 1; fi
echo "$dir" > "$RESULTS/.validate-sweep-dir"
log "sweep done -> $(basename "$dir")"
log "judging (gpt-5.5, azure-on)"
cfgset services.azure-openai.use-azure-openai-api true
cfgset services.judge.model "\"gpt-5.5\""
mise exec -- bb sweep-judge "results/$(basename "$dir")" digdir >>"$RUNLOG" 2>&1
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
log "=== VALIDATION COMPLETE -> $(basename "$dir") (judged) ==="
