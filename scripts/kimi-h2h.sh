#!/usr/bin/env bash
# Kimi K2.6 as a head-to-head arm — tests whether a frontier-tier MoE (~1T/32B-active)
# lifts the under-enumeration ceiling the ~30B locals (control/gemma/dense ~0.73) could not.
# Held constant: the deployed snippet-AB config + gpt-5.5 judge. Kimi is OpenAI-compatible
# at http://localhost:8010/v1 (model moonshotai/Kimi-K2.6, a REASONING model).
#
# Usage: bash scripts/kimi-h2h.sh <matrix-path>
#   smoke: test/fixtures/sweep/matrices/model-h2h-smoke.edn      (3 Q, validate harness first)
#   full:  test/fixtures/sweep/matrices/model-h2h-full42-c1.edn  (42 Q, conc-1)
set -uo pipefail
WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"; cd "$WT"
MATRIX="${1:-test/fixtures/sweep/matrices/model-h2h-smoke.edn}"
export OPENAI_API_ENDPOINT="http://localhost:8010/v1"
export OPENAI_API_KEY="dummy"     # local serve accepted no key during recon
RESULTS="$WT/server/results"
RUNLOG="$RESULTS/kimi-$(date +%Y%m%dT%H%M%S).log"
# Kimi/Moonshot sampling: temp ~0.6; HIGH max_tokens — it's a reasoning model and emits a
# separate reasoning trace before content, so a low cap truncates the answer mid-reasoning.
# OPENAI_REASONING_EFFORT (client injects it as reasoning_effort, POSTed verbatim) caps the
# reasoning volume — the smoke showed uncapped reasoning makes runs ~270s/call (times out).
# Default "low"; override with KIMI_EFFORT (e.g. medium/minimal/none) to trade speed vs quality.
KIMI_SAMP="OPENAI_TEMPERATURE=0.6 OPENAI_MAX_TOKENS=16000 OPENAI_SOCKET_TIMEOUT_MS=1800000"
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$RUNLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$RUNLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }

log "=== Kimi K2.6 sweep ($(basename "$MATRIX")) ==="
echo "$RUNLOG" > "$RESULTS/.kimi-current-runlog"
if ! curl -s -m 10 http://localhost:8010/v1/models >/dev/null 2>&1; then
  log "!! localhost:8010 (Kimi) NOT reachable — aborting"; exit 3; fi
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"moonshotai/Kimi-K2.6\""
before="$(newest_sweep_dir)"
# shellcheck disable=SC2086
env $KIMI_SAMP OPENAI_API_ENDPOINT="$OPENAI_API_ENDPOINT" OPENAI_API_KEY="$OPENAI_API_KEY" \
  mise exec -- bb sweep "$MATRIX" >>"$RUNLOG" 2>&1
dir="$(newest_sweep_dir)"
if [ "$dir" = "$before" ] || [ -z "$dir" ]; then log "!! NO new sweep dir — see $RUNLOG"; exit 1; fi
echo "$dir" > "$RESULTS/.kimi-sweep-dir"
log "sweep done -> $(basename "$dir")"
log "judging (gpt-5.5, azure-on)"
cfgset services.azure-openai.use-azure-openai-api true
cfgset services.judge.model "\"gpt-5.5\""
mise exec -- bb sweep-judge "results/$(basename "$dir")" digdir >>"$RUNLOG" 2>&1
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
log "=== Kimi COMPLETE -> $(basename "$dir") (judged) ==="
