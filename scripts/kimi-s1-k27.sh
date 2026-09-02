#!/usr/bin/env bash
# S1 — Kimi K2.7-Code head-to-head vs the committed K2.6 baselines.
# Same deployed config + matrices; only the model/endpoint swap (env-driven), driven
# through the SAME repair loop (repair-one mode) so :8020 flakiness self-heals.
# Distinct k27-* labels → own repaired dirs, no collision with the K2.6 repaired-* dirs.
#
# Usage: nohup caffeinate -dis bash scripts/kimi-s1-k27.sh >/dev/null 2>&1 &
set -uo pipefail
WT="/Users/bdbrodie/dev/digdir/rag/model-headtohead"; cd "$WT"
export KIMI_ENDPOINT="http://localhost:8020/v1"
export KIMI_MODEL_NAME='"moonshotai/Kimi-K2.7-Code"'
export KIMI_PROBE_MODEL="moonshotai/Kimi-K2.7-Code"
Q="scripts/kimi-weekend-queue.sh"; M="test/fixtures/sweep/matrices"
echo "[$(date '+%m-%d %H:%M:%S')] === S1 K2.7-Code: A then B (paired vs K2.6) ==="
bash "$Q" repair-one k27-A-headline-full42 "$M/kimi-A-headline-full42.edn"
bash "$Q" repair-one k27-B-broad118        "$M/kimi-B-broad118.edn"
# restore incumbent
mise exec -- bb config-set services.azure-openai.use-azure-openai-api false digdir platform default >/dev/null 2>&1
mise exec -- bb config-set services.azure-openai.model-name '"h2h-control"' digdir platform default >/dev/null 2>&1
echo "[$(date '+%m-%d %H:%M:%S')] === S1 K2.7-Code COMPLETE ==="
