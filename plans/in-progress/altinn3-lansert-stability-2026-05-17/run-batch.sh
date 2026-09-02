#!/usr/bin/env bash
# Focused stability batch for the "Når ble Altinn 3 lansert?" query.
# 5 invocations × 3 variants = 15 invocations total. Each invocation runs
# the fixture's single case under both budgets (identical) = 2 runs per
# invocation, so 10 runs per variant when complete.

set -euo pipefail

export DATAHIKE_FILE_PATH="/Users/bdbrodie/dev/digdir/rag/add-evals/local-db/add_evals_20260516_evalgate"
: "${CONFIG_MASTER_KEY:?CONFIG_MASTER_KEY must be set in the environment (mise.local.toml, or export it) - see docs/secrets-inventory.md}"

OUT_DIR="plans/in-progress/altinn3-lansert-stability-2026-05-17"
mkdir -p "$OUT_DIR"

START=$(date +%s)
for variant in imperative bundled faithful; do
  for run in 1 2 3 4 5; do
    LABEL="${variant}-run${run}"
    OUT="$OUT_DIR/$LABEL.edn"
    echo "[$(date +%H:%M:%S)] starting $LABEL ..."
    bb agent-budget-benchmark digdir dataset public-docs \
      --suite test/fixtures/agent/altinn3_lansert_stability.edn \
      --tenant-config-key default \
      --fail-on-gate false \
      --graph-variant "$variant" 2>&1 | tail -1 > "$OUT"
    SUMMARY=$(grep -oE ':summary \{[^}]+\}' "$OUT" || echo "(no summary)")
    echo "[$(date +%H:%M:%S)] $LABEL done: $SUMMARY"
  done
done
END=$(date +%s)
echo "[$(date +%H:%M:%S)] batch done in $((END-START))s"
