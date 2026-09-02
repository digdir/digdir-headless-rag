# Agent Read-Budget Refund Plan

## Goal

Reclaim `read-content-length` budget for chunks that a read-signal evaluation confirms did not support any claim, so the agent can keep reading useful content after making a bad read. Targets the "agent reads wrong docs early, then can't read the right docs later" failure mode.

Does **not** touch conversation messages or prompt caching. The workspace `:chunks` map remains intact; only the budget accounting changes.

## Context

The agent's `read_chunks` tool is budgeted on cumulative characters returned. Default: `:max-read-content-length 12000` chars (see `digdir.skills.builtin.agent.workspace/default-max-read-content-length`).

Happy-path queries rarely stress this budget — typical successful runs read 3000–4500 chars total. Pathological runs blow past it by reading chunks from the wrong documents early, then find themselves unable to read the correct chunks later when search finally surfaces them.

### Concrete trace evidence

Trace: `server/logs/agent-trace-2026-04-17T19-42-11-504390Z.txt` (pathological, 10 iterations, `status=error`, 29.7s).

Budget consumption sequence:

| Iter | Read | Chars | Outcome |
|---|---|---:|---|
| 1 | 3 chunk-ids from About + Linked Services | ~1500 | `supported=1, gaps=3` |
| 3 | `range_read db112a3d8c92 {from: 0, to: 4}` | ~7000 | All 5 chunks from wrong doc |
| 5 | 3 chunk-ids | 3327 | 1 marginally supporting, 2 dead |
| 7 | `["8e22ae4b88b1" ...]` (answer chunks) | **0 — budget exhausted** | Blocked |
| 9 | Same attempted read | **0 — budget exhausted** | Blocked |

Iter 7's result message: *"Read budget exhausted for the requested read. Remaining budget (228/12000 chars) is too small for the requested read."*

The answer chunk `8e22ae4b88b1` was known and requestable by iter 7 but the agent had spent 98% of its budget on chunks that read-signal flagged as non-supporting. Those reads were sunk cost even though no new information had been extracted.

### Why the existing `:non-supporting-chunk-ids` mechanism didn't help

`workspace.clj:299 record-read-evaluation!` already tracks non-supporting chunks for a *suppress-reread* use case. Its marking criterion (line 315-320) is strict:

```clojure
non-supporting-chunk-ids (if (and (not (:degraded? read-signal))
                                  confidently-scoped?          ; scope=:aligned AND not :unclear
                                  zero-support?                ; no supported-claims at all
                                  contradiction-free?)
                           (set (or (:chunk-ids read-signal) []))
                           #{})
```

In the pathological, read-signals consistently return `scope=:partially-aligned` with `supported=1`, so `confidently-scoped?` and `zero-support?` are both false. Zero chunks are ever marked non-supporting. The existing path provides no budget relief.

## Problem Statement

The read budget is **monotonically consuming**: once a chunk's chars are counted against `:read-content-length`, they cannot be reclaimed, regardless of whether the chunk helped. This makes early exploration expensive: a single wasted 7000-char range read can leave the agent with no runway to recover when better chunks later present themselves.

The refund machinery should be **per-chunk** (not per-read), should **trust read-signal classifications when confidence is high enough**, and should **leave workspace state and conversation messages untouched** to avoid cache invalidation.

## Proposal

Add a per-chunk budget refund inside `record-read-evaluation!`. After each read evaluation:

1. Identify chunks that were read this iteration but are **not** referenced in any `:supported-claims :chunk-ids` entry of the read-signal.
2. Gate the refund on signal quality: `confidence >= 0.5`, not `:degraded?`, status not `:unclear`.
3. Sum those chunks' `content_length` (from workspace `:chunks`) and subtract from `:read-content-length`.
4. Record the chunk ids in a new workspace field `:refunded-chunk-ids` to prevent double-refunding across iterations.
5. Emit a `:budget-refund` stage timing entry so traces show when it fires and how much was reclaimed.

The refund is **budget-only**. Chunks remain in `:chunks` and remain citable. If the agent later issues a read that matches a refunded chunk id, `add-chunks-to-workspace!` dedupes and no new budget is charged.

## Classification Criterion

A chunk `c` just returned by read_chunks in the current iteration is refundable iff:

- `c.chunk_id` is in `read-signal.chunk-ids` (the chunks evaluated this round)
- `c.chunk_id` is **not** in the set union of `chunk-ids` across `read-signal.supported-claims`
- `read-signal.confidence >= 0.5`
- `read-signal.degraded? != true`
- `read-signal.status != :unclear`
- `c.chunk_id` is not already in `:refunded-chunk-ids` (idempotency)

This is deliberately weaker than the current `:non-supporting-chunk-ids` marking: it refunds per chunk based on whether that specific chunk appeared in any supported-claim, not whether the entire read was a wash.

## Implementation Sketch

### `server/src/digdir/skills/builtin/agent/workspace.clj`

Add `:refunded-chunk-ids #{}` to `create-workspace` initial state.

Extend `record-read-evaluation!` to compute and apply refunds:

```clojure
(defn- compute-budget-refund
  "Return {:refunded-ids [...] :refunded-chars N} for chunks read this
   round that were not referenced by any supported-claim, subject to
   signal-quality gating and idempotency."
  [workspace read-signal]
  (let [confidence (or (:confidence read-signal) 0.0)
        degraded? (boolean (:degraded? read-signal))
        status (:status read-signal)
        already-refunded (or (:refunded-chunk-ids workspace) #{})
        eligible? (and (>= confidence 0.5)
                       (not degraded?)
                       (not= :unclear status))
        read-ids (set (or (:chunk-ids read-signal) []))
        supported-ids (->> (or (:supported-claims read-signal) [])
                           (mapcat :chunk-ids)
                           set)
        refund-candidates (when eligible?
                            (->> read-ids
                                 (remove supported-ids)
                                 (remove already-refunded)
                                 vec))
        chunks-map (:chunks workspace)
        refunded-chars (reduce (fn [acc id]
                                 (+ acc (or (chunk-content-length (get chunks-map id)) 0)))
                               0
                               (or refund-candidates []))]
    {:refunded-ids (vec (or refund-candidates []))
     :refunded-chars refunded-chars}))
```

In `record-read-evaluation!`, after the existing `coverage`/`open-gaps` computation, apply the refund:

```clojure
(let [{:keys [refunded-ids refunded-chars]} (compute-budget-refund ws read-signal)]
  (-> ws
      ;; ...existing updates...
      (update :refunded-chunk-ids (fnil into #{}) refunded-ids)
      (update :read-content-length #(max 0 (- (or % 0) refunded-chars)))))
```

### `server/src/digdir/skills/builtin/agent/loop.clj`

After `record-read-evaluation!` finishes (inside the `read_chunks` handler in `tools.clj`, not loop.clj — see below), emit a stage timing when a refund occurred. The cleanest place is *after* the read-signal is recorded, where workspace delta is observable.

### `server/src/digdir/skills/builtin/agent/tools.clj`

Around `tools.clj:823` where `record-read-evaluation!` is called, capture the pre/post `:read-content-length` and emit a `:budget-refund` stage when it decreased. Example:

```clojure
(let [before (:read-content-length @!workspace)
      _ (workspace/record-read-evaluation! !workspace read-signal)
      after (:read-content-length @!workspace)]
  (when (> before after)
    (workspace/record-stage-timing!
     !workspace
     {:stage :budget-refund
      :duration-ms 0
      :status :ok
      :detail (str "chars=" (- before after)
                   " read=" before
                   " remaining=" (- (:max-read-content-length budget) after))})))
```

(Budget is already destructured at `tools.clj:603` as `budget`.)

### Trace rendering

The existing `format-trace-file` in `core.clj` already prints stage-timings generically; `:budget-refund` will appear automatically. No changes required to the trace formatter.

## Trace Instrumentation — what to look for

After the change, a new stage appears in pathological traces:

```
[N] stage=budget-refund iteration=1 duration-ms=0 status=ok detail=chars=1000 read=1500 remaining=11500
```

Happy-path traces should show **no** `:budget-refund` entries at all — when reads succeed, all chunks appear in supported-claims.

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Over-refund (chunks that actually helped get refunded) | Low | Gated on confidence ≥ 0.5, aligned-or-partial scope, non-degraded, non-unclear. Refund criterion matches read-signal's own attribution data. |
| Budget games by the LLM | None | Refund is automatic from workspace state; agent cannot request it. |
| Cache invalidation | None | No messages or tools arrays are modified. Prompt cache is unaffected. |
| Double-refund on re-evaluation | Blocked | `:refunded-chunk-ids` set prevents repeat refunds of the same chunk id. |
| Refunded chunks silently re-read and re-charged | None | `workspace/suppress-reread-chunk-ids` (read-by-id) and `add-chunks-to-workspace!` (dedup) prevent re-reading. Budget charge is per unique chunk. |
| Low-confidence evaluator mis-classifying answer-bearing chunks as dead | Low | Confidence gate filters out noisy evaluations. Worst case: refund not applied and budget behaves as today. |

## Validation Plan

1. **Re-run the pathological query** (`Når ble Altinn 3 lansert?`) 5–10 times. Look for:
   - `:budget-refund` entries in traces where the agent reads wrong docs early.
   - Successful finalization (non-error status) on cases that previously hit max-iterations.
   - `read_content_length` in the budget-state footer trending lower per successful trace.

2. **Compare happy-path median** (5+ trials) before and after. Should be unchanged — successful reads never trigger refunds because all read chunks appear in supported-claims.

3. **Unit test** `compute-budget-refund` in `server/test/digdir/skills/builtin/agent/workspace_test.clj`:
   - Read signal with all chunks in supported-claims → refund = 0
   - Read signal with no supported-claims + confidence 0.0 → refund = 0 (gated out)
   - Read signal with some supported, some not + confidence 0.7 → refund = chars of unsupported
   - Second evaluation of same chunks → no double refund

4. **Budget-state trace field**: verify `[budget-state]` line in trace shows `:read-content-length-used` post-refund value, not pre-refund. Sanity check that refund is visible end-to-end.

## Out of Scope / Future Work

- **Conversation-message rewrite** (the "remove dead chunks from the prompt" idea). This would free both budget *and* prompt tokens but invalidates the cached prefix from the rewrite point. Defer unless refund-only fails to cover observed pathologies.
- **Smarter candidate ranking for the range-read hint** (`loop.clj range-read-suggestion`). Currently the hint picks the first-encountered doc, which contributed to the pathological by nudging the agent toward the wrong doc. Could rank by supported-claim attribution or retrieval score. Tracked separately.
- **Budget-aware range-read-hint gating** — skip the hint when estimated cost > X% of remaining budget. Would have prevented the iter-3 Linked Services blow-up in the trace above. Smaller scope than this plan, could ship independently.

## Related Landed Changes (for context)

These optimizations already exist on `fix-sufficiency`:

- **Cache-hit visibility** in traces + Detailed view (`prompt-tokens / cached-tokens / cache-hit% / completion-tokens` per agent-llm stage).
- **Search result trim** (Layer 1): `format-search-metadata-results` caps at 20 chunks + compressed per-line format.
- **Sufficiency-gate pre-filter**: skips the ~3s LLM call when the result would be discarded anyway (unkeepable guard). Stage-timing records `:skipped detail="unkeepable..."`.
- **Citation-backfill error-skip**: on `:error` status, skip the final ~2s re-synthesis. Returns raw response directly.
- **Range-read hint** (dynamic): after read_chunks with gap-remaining signal, injects a copy-pasteable `doc_num + chunk_range` suggestion into the tool-result text. Logs `:range-read-hint` stage timing. Works but fires more than needed on wrong docs — see "Out of Scope" above.

## Key Files

- `server/src/digdir/skills/builtin/agent/workspace.clj` — primary edit location (`record-read-evaluation!`, initial state, new helper)
- `server/src/digdir/skills/builtin/agent/tools.clj` — emit `:budget-refund` stage timing after evaluation
- `server/src/digdir/skills/builtin/agent/read_signals.clj` — no changes; produces the signal we consume
- `server/test/digdir/skills/builtin/agent/workspace_test.clj` — unit tests for `compute-budget-refund`
- `server/logs/agent-trace-2026-04-17T19-42-11-504390Z.txt` — reference pathological trace for reproduction

## Suggested PR Shape

1. Single commit adding `compute-budget-refund`, `:refunded-chunk-ids` state, updated `record-read-evaluation!`, and trace emission in `tools.clj`.
2. Separate commit for unit tests.
3. PR description: link to this plan and the reference trace; include before/after budget-state from a re-run of the pathological query.
