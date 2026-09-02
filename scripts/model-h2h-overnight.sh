#!/usr/bin/env bash
# MODEL HEAD-TO-HEAD overnight orchestration. Run from the worktree root under
# caffeinate:   caffeinate -dis bash scripts/model-h2h-overnight.sh
#
# Holds the DEPLOYED snippet-AB config constant (matrix model-h2h-full42.edn) and
# swaps ONLY the agent/synthesis model across three arms, all on gx10 (GB10) via
# ollama, ONE model resident at a time (unloaded between arms):
#   control  Qwen3.6-35B-A3B  (MoE ~3B active, thinking)  -> h2h-control
#   gemma    gemma-4-31B-qat  (dense, non-thinking)       -> h2h-gemma31b
#   dense    Qwen3.6-27B      (dense, all active, thinking)-> h2h-qwen27b   [SLOW, last]
#
# PRIMARY clean comparison = control vs dense (same Qwen family, both thinking,
# MoE-3B-active vs dense-all-active). gemma = secondary cross-family (non-thinking;
# its GGUF won't honour any think-off switch, and Qwen3.6's thinking can't be
# disabled via ollama either — so each model runs in its NATURAL mode; documented).
#
# Order control,gemma (fast ~1.5-2h each, produce early judged results) then the
# slow dense (~6-7h) last. Each arm is SWEPT (azure-off) then JUDGED (gpt-5.5,
# azure-on) before the next, so finished arms are fully scored by morning.
#
# Env knobs: N_CONTROL/N_GEMMA/N_DENSE (repeats, default 1), SKIP_JUDGE=1.
set -uo pipefail

WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"
cd "$WT"
GX="gx10-f695"
GX_OLLAMA_HOST="100.106.192.44:11434"
export OPENAI_API_ENDPOINT="http://gx10-f695:11434/v1"
export OPENAI_API_KEY="ollama"
MATRIX_FAST="test/fixtures/sweep/matrices/model-h2h-full42.edn"        # conc 3, 1200s (MoE control only — cheap prefill)
MATRIX_C1="test/fixtures/sweep/matrices/model-h2h-full42-c1.edn"        # conc 1, 1200s (gemma — DENSE, slow prefill, conc>1 cascades to timeouts)
MATRIX_DENSE="test/fixtures/sweep/matrices/model-h2h-full42-dense.edn"  # conc 1, 1800s (dense Qwen; ollama serializes + thinking)
RESULTS="$WT/server/results"
RUNLOG="$RESULTS/h2h-overnight-$(date +%Y%m%dT%H%M%S).log"
MANIFEST="$RESULTS/h2h-manifest.tsv"

log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$RUNLOG"; }
cfgset(){ mise exec -- bb config-set "$1" "$2" digdir platform default >>"$RUNLOG" 2>&1; }
newest_sweep_dir(){ ls -dt "$RESULTS"/sweep-*/ 2>/dev/null | head -1; }
unload(){ ssh -o BatchMode=yes "$GX" "OLLAMA_HOST=$GX_OLLAMA_HOST ollama stop $1" >>"$RUNLOG" 2>&1 || true; log "unloaded $1"; }

# Qwen3.6 recommended thinking-mode sampling (control + dense), per runbook.
QWEN_SAMP="OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768 OPENAI_PRESERVE_THINKING=true"
# Gemma sampling (non-thinking; no Qwen-specific preserve_thinking/presence_penalty).
GEMMA_SAMP="OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=64 OPENAI_MIN_P=0.0 OPENAI_MAX_TOKENS=32768"

run_and_judge_arm(){ # arm-label  ollama-tag  sampling  repeats  matrix
  local arm="$1" tag="$2" samp="$3" reps="$4" matrix="$5"
  log "=== ARM $arm  model=$tag  repeats=$reps  matrix=$(basename "$matrix") ==="
  cfgset services.azure-openai.use-azure-openai-api false
  cfgset services.azure-openai.model-name "\"$tag\""
  local before; before="$(newest_sweep_dir)"
  # shellcheck disable=SC2086
  env $samp OPENAI_API_ENDPOINT="$OPENAI_API_ENDPOINT" OPENAI_API_KEY="$OPENAI_API_KEY" \
    mise exec -- bb sweep "$matrix" --repeats "$reps" >>"$RUNLOG" 2>&1
  local dir; dir="$(newest_sweep_dir)"
  if [ "$dir" = "$before" ] || [ -z "$dir" ]; then
    log "!! ARM=$arm produced NO new sweep dir — see $RUNLOG"; unload "$tag"; return
  fi
  printf '%s\t%s\t%s\n' "$arm" "$tag" "$dir" >> "$MANIFEST"
  log "ARM=$arm sweep done -> $(basename "$dir")"
  unload "$tag"
  if [ -z "${SKIP_JUDGE:-}" ]; then
    log "judging $arm (gpt-5.5, azure-on)"
    cfgset services.azure-openai.use-azure-openai-api true
    cfgset services.judge.model "\"gpt-5.5\""
    mise exec -- bb sweep-judge "results/$(basename "$dir")" digdir >>"$RUNLOG" 2>&1
    cfgset services.azure-openai.use-azure-openai-api false   # restore for next arm
    log "ARM=$arm judged"
  fi
}

log "=== MODEL HEAD-TO-HEAD (RESUME: gemma+dense at conc 1; control already done+judged) ==="
# NB: manifest NOT reset — control's line is preserved. gemma+dense run conc 1
# (both DENSE → slow prefill; conc>1 cascades to ollama-queue timeouts).
[ "${RUN_CONTROL:-0}" = "1" ] && run_and_judge_arm control h2h-control "$QWEN_SAMP" "${N_CONTROL:-1}" "$MATRIX_FAST"
[ "${RUN_GEMMA:-0}" = "1" ]   && run_and_judge_arm gemma   h2h-gemma31b "$GEMMA_SAMP" "${N_GEMMA:-1}" "$MATRIX_C1"
run_and_judge_arm dense   h2h-qwen27b  "$QWEN_SAMP"  "${N_DENSE:-1}" "$MATRIX_DENSE"   # Q4, conc-1; deployment-name=gpt-5.5 now fixed for the judge

log "=== restore agent config (azure-off, model-name -> h2h-control) ==="
cfgset services.azure-openai.use-azure-openai-api false
cfgset services.azure-openai.model-name "\"h2h-control\""
log "=== MODEL HEAD-TO-HEAD COMPLETE — manifest: ==="
cat "$MANIFEST" | tee -a "$RUNLOG"
