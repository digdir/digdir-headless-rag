#!/usr/bin/env bash
# Sequential 3-runs-per-variant stability batch for Phase 2.5 eval gate.
# Captures one EDN payload per (variant, run-index) into this directory.
# Total wall time ~60-90 min.

set -euo pipefail

export DATAHIKE_FILE_PATH="../local-db/add_evals_20260516_evalgate"
: "${CONFIG_MASTER_KEY:?CONFIG_MASTER_KEY must be set in the environment (mise.local.toml, or export it) - see docs/secrets-inventory.md}"

OUT_DIR="plans/in-progress/2.5-eval-gate-2026-05-16/stability"
mkdir -p "$OUT_DIR"

START=$(date +%s)
for variant in imperative bundled faithful; do
  for run in 1 2 3; do
    LABEL="${variant}-run${run}"
    OUT="$OUT_DIR/$LABEL.edn"
    echo "[$(date +%H:%M:%S)] starting $LABEL ..."
    bb agent-budget-benchmark digdir dataset public-docs \
      --suite test/fixtures/agent/public_docs_agent_smoke.edn \
      --tenant-config-key default \
      --fail-on-gate false \
      --graph-variant "$variant" 2>&1 | tail -1 > "$OUT"
    SUMMARY=$(grep -oE ':summary \{[^}]+\}' "$OUT" || echo "(no summary)")
    echo "[$(date +%H:%M:%S)] $LABEL done: $SUMMARY"
  done
done
END=$(date +%s)
echo "[$(date +%H:%M:%S)] batch done in $((END-START))s"
