#!/usr/bin/env bash
# P2 (read-hi reversal) on gx10-f695, IN PARALLEL with P2 enrichment-HQ on eccf.
# The head-to-head is done and the shared DB is restored to h2h-control + azure-off,
# so this worktree's DB is free — no clone needed. Runs the read-hi full-42 A/B
# (snippet-AB vs snippet-AB+read-hi) against f695, then judges (gpt-5.5).
set -uo pipefail

WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"
cd "$WT"
export OPENAI_API_ENDPOINT="http://gx10-f695:11434/v1"
export OPENAI_API_KEY="ollama"
MATRIX="test/fixtures/sweep/matrices/local-snippet-readhi-full42-c3.edn"
RESULTS="$WT/server/results"
RUNLOG="$RESULTS/p2-readhi-f695-$(date +%Y%m%dT%H%M%S).log"

QWEN_SAMP="OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768 OPENAI_PRESERVE_THINKING=true"

log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$RUNLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$RUNLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }

log "=== P2 read-hi on gx10-f695 (model=h2h-control, conc-3, full-42) ==="
echo "$RUNLOG" > "$RESULTS/.p2readhi-current-runlog"

if ! curl -s -m 10 http://gx10-f695:11434/v1/models >/dev/null 2>&1; then
  log "!! gx10-f695 NOT reachable — aborting"; exit 3
fi

cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""

before="$(newest_sweep_dir)"
# shellcheck disable=SC2086
env $QWEN_SAMP OPENAI_API_ENDPOINT="$OPENAI_API_ENDPOINT" OPENAI_API_KEY="$OPENAI_API_KEY" \
  mise exec -- bb sweep "$MATRIX" >>"$RUNLOG" 2>&1
dir="$(newest_sweep_dir)"
if [ "$dir" = "$before" ] || [ -z "$dir" ]; then
  log "!! NO new sweep dir produced — see $RUNLOG"; exit 1
fi
echo "$dir" > "$RESULTS/.p2readhi-sweep-dir"
log "sweep done -> $(basename "$dir")"

log "judging (gpt-5.5, azure-on)"
cfgset services.azure-openai.use-azure-openai-api true
cfgset services.judge.model "\"gpt-5.5\""
mise exec -- bb sweep-judge "results/$(basename "$dir")" digdir >>"$RUNLOG" 2>&1
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
log "=== P2 read-hi COMPLETE -> $(basename "$dir") (judged) ==="
