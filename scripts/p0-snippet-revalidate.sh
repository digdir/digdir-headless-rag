#!/usr/bin/env bash
# P0 — RE-RUN snippet-AB control-vs-AB full-42 with the truncation fix active.
# (recheck-contaminated-runs-plan.md, P0). The deployed +0.083 was judged on
# 800-char-clipped answers; revalidate whether the Δ holds on full text.
#
# Holds everything constant, runs the local incumbent (h2h-control = Qwen3.6-35B-A3B
# MoE, the deployed model) on gx10/ollama, sweeps local-snippet-full42-c3.edn
# (control + snippet-AB, N=3, conc 3, judge? false), then judges full responses
# with gpt-5.5 (azure-on). conc-3 is safe for the MoE (cheap prefill) — validated
# in the head-to-head control arm.
set -uo pipefail

WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"
cd "$WT"
export OPENAI_API_ENDPOINT="http://gx10-f695:11434/v1"
export OPENAI_API_KEY="ollama"
MATRIX="test/fixtures/sweep/matrices/local-snippet-full42-c3.edn"
RESULTS="$WT/server/results"
RUNLOG="$RESULTS/p0-snippet-revalidate-$(date +%Y%m%dT%H%M%S).log"

# Qwen3.6 thinking-mode sampling (the deployed incumbent config).
QWEN_SAMP="OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768 OPENAI_PRESERVE_THINKING=true"

log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$RUNLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$RUNLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }

log "=== P0 snippet-AB revalidation (model=h2h-control, conc-3, N=3, full-42) ==="
echo "$RUNLOG" > "$RESULTS/.p0-current-runlog"

# Agent runs against ollama (azure-off); deployed incumbent model.
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
echo "$dir" > "$RESULTS/.p0-sweep-dir"
log "sweep done -> $(basename "$dir")"

# Judge full responses with gpt-5.5 (azure-on), then restore azure-off.
log "judging (gpt-5.5, azure-on)"
cfgset services.azure-openai.use-azure-openai-api true
cfgset services.judge.model "\"gpt-5.5\""
mise exec -- bb sweep-judge "results/$(basename "$dir")" digdir >>"$RUNLOG" 2>&1
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
log "=== P0 COMPLETE -> $(basename "$dir") (judged) ==="
