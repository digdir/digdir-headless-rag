#!/usr/bin/env bash
# P1 — RE-RUN the answer-completeness directive screen with the truncation fix.
# (recheck-contaminated-runs-plan.md, P1). The contaminated verdict was "NULL"
# (effQ 0.660->0.657); the directive LENGTHENS answers, so it was the arm MOST
# clipped at 800 → the NULL may be a MASKED gain. Re-run full-text-judged.
#
# snippet-AB vs snippet-AB+complete, 8-Q × N=3, conc 3, 1200s, judge? false.
# Model = h2h-control (the deployed MoE, same as P0). gx10/ollama.
set -uo pipefail

WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"
cd "$WT"
export OPENAI_API_ENDPOINT="http://gx10-f695:11434/v1"
export OPENAI_API_KEY="ollama"
MATRIX="test/fixtures/sweep/matrices/local-completeness-screen-c3.edn"
RESULTS="$WT/server/results"
RUNLOG="$RESULTS/p1-completeness-$(date +%Y%m%dT%H%M%S).log"

QWEN_SAMP="OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768 OPENAI_PRESERVE_THINKING=true"

log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$RUNLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$RUNLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }

log "=== P1 completeness screen (model=h2h-control, conc-3, N=3, 8-Q) ==="
echo "$RUNLOG" > "$RESULTS/.p1-current-runlog"

# Agent → ollama (azure-off) + deployed incumbent model.
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
echo "$dir" > "$RESULTS/.p1-sweep-dir"
log "sweep done -> $(basename "$dir")"

# Judge full responses with gpt-5.5 (azure-on), then restore azure-off.
log "judging (gpt-5.5, azure-on)"
cfgset services.azure-openai.use-azure-openai-api true
cfgset services.judge.model "\"gpt-5.5\""
mise exec -- bb sweep-judge "results/$(basename "$dir")" digdir >>"$RUNLOG" 2>&1
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
log "=== P1 COMPLETE -> $(basename "$dir") (judged) ==="
