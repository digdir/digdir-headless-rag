#!/usr/bin/env bash
# Run ON gx10 (or via: ssh gx10-f695 'bash -s' < this). Creates derived ollama
# models with num_ctx baked in to 60000 — the OpenAI-compat /v1 endpoint cannot
# pass num_ctx, and ollama defaults to a tiny 4096 window which would silently
# truncate the agent's snippet display + read chunks and invalidate the run.
#
# 60000 matches the incumbent's loaded_context_length on the Mac (runtime parity).
# All three arms served from this one host (GB10) so runtime is not a confound.
set -euo pipefail
export OLLAMA_HOST=100.106.192.44:11434

mk() { # name  base-tag
  local name="$1" base="$2" mf="/tmp/Modelfile.$1"
  echo "=== create $name FROM $base ==="
  printf 'FROM %s\nPARAMETER num_ctx 60000\n' "$base" > "$mf"   # ollama 0.30 needs a file, not stdin
  ollama create "$name" -f "$mf"
}

mk h2h-control  hf.co/unsloth/Qwen3.6-35B-A3B-GGUF:UD-Q6_K_XL
mk h2h-qwen27b  hf.co/unsloth/Qwen3.6-27B-GGUF:UD-Q6_K_XL
mk h2h-gemma31b hf.co/unsloth/gemma-4-31B-it-qat-GGUF:UD-Q4_K_XL

echo "=== done. models: ==="
ollama list | grep -E 'h2h-|NAME'
