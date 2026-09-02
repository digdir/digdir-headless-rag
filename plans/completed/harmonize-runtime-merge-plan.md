# Harmonize-runtime cherry-pick merge plan

Cherry-pick the 13 commits unique to `self-improve-d3` (the branch checked
out in the `harmonize-runtime` worktree at
`/Users/bdbrodie/dev/digdir/rag/harmonize-runtime`) onto `establish-baseline`.

Merge base: `1efe001` ("Capture sweep experiment results: 19 run snapshots").
`establish-baseline` is 64 commits ahead; `self-improve-d3` is 13 commits ahead.

## Why cherry-pick, not merge

- Lets us drop `d6af390` cleanly (it's reverted immediately by `052eeba`)
  without a "revert of a revert" in history.
- The retrieval.clj / api/util.clj / bb.edn divergence is heavy enough that
  a merge would produce one tangled conflict instead of three localized ones.
- Per-commit control + linear history is easier to bisect later.

## Two harnesses, kept separately

Both branches carry sweep infrastructure but optimize for different questions.
They are **complementary, not redundant** — keep both.

| Question | Use | Owned by |
|---|---|---|
| Did this retrieval change move chunk/doc baseline? | `bb v3-score` (fast, ~30s, retrieval-only) | establish-baseline |
| Is agent end-to-end recall limited by retrieval / display / agent-read / rerank-trim? | sweep runner (slow, full agent loop, decomposes stages) | self-improve-d3 |
| Noise band on a single config? | `bb v3-score` 3-run replicates | establish-baseline |
| What config survives 270 question×repeat combos? | sweep runner matrix | self-improve-d3 |

**Care needed:** `server/src-dev/digdir/sweep/v3_cites.clj` (140 LOC, the
v3-score scorer) does **not exist** on `self-improve-d3`. None of the 13
commits touch it, so a real `git cherry-pick` preserves it; a wholesale
`git checkout self-improve-d3 -- server/src-dev/digdir/sweep/` would
delete it. Avoid the brute-force path.

**Followup:** short note in CLAUDE.md or a README capturing
"use `bb v3-score` for fast retrieval iteration, sweep runner for end-to-end
attribution + cost sweeps" so the two harnesses share workflow vocabulary.
Not blocking; track here.

## Batched plan (4 commits/PRs)

### Batch 1 — orthogonal additions (low conflict, ready first)

Cherry-pick in order; each should apply with minimal/no conflict.

- [x] `cc17a77` — Fix sweep.runner-test/score-run-recall-and-hit-rate fixture → `fc56035`
- [x] `848465f` — Add `bb dev-ps` + `bb dev-stop` → `0699a69`
- [x] `6f67243` — Refuse `bb config-{get,set}` + `pipeline-config` without `DATAHIKE_FILE_PATH` → `1869e89`
  - Resolved conflict in `bb.edn` by keeping our marker-write description and
    wrapping the task body in `(do (ensure-datahike-file-path! "config-set") (let ...))`.
- [x] `d3c572c` — Plans: agent-skill-params, recall-tuning arc summary, stuck-domains diagnostic → `9032671`
- [x] `4c46f34` — Sweep matrices (scope-B: matrices only) → `44d27d4`
  - 6 matrices in `server/test/fixtures/sweep/matrices/`; 17 timestamped result
    dirs dropped; recoverable via `git show 4c46f34` if needed.

Verify after batch 1:
- [x] `bb lint` — pre-existing errors only (Electric false positives in `inheritance.cljc`)
- [x] `sweep.runner-test` green (7 tests / 37 assertions)
- [x] `bb dev-ps` runs cleanly
- [x] `bb config-set` without `DATAHIKE_FILE_PATH` aborts with mise hint

### Batch 2 — recall-tuning knobs (highest-risk merge)

- [x] `f85c984` — Add recall-tuning knobs and stage decomposition from sweep arc → `1fc5a38`

Touches four files; merge approach for each:

1. `retrieval.clj` — fold `:per-strategy-rerank?` branch into the slice-23
   `do-pass` closure so each pass independently honors it. Resulting cond is
   `[union? × per-strategy?]` = 4 branches.
2. `agent/tools.clj` — coexist with our `:last-user-intent` stash. The four
   new opt-in knobs (`:auto-read-top-k`, `:title-overlap-weight`,
   `:search-display-limit`, `:search-strategy-quota`) restructure the
   displayed window after rerank; verify they compose with our search-inputs
   addition.
3. `agent/workspace.clj` — `expand-short-doc-reads` is a sibling addition to
   our `:last-user-intent` slot; orthogonal, should apply clean.
4. `sweep/runner.clj` — strict additions (timeout, incremental CSV, columns);
   no establish-baseline divergence on this file.

**De-risking: write tests at the seams before committing the cherry-pick.**

Workflow:
1. `git cherry-pick -n f85c984`
2. Resolve mechanical conflicts
3. Write the test set below
4. Iterate until green
5. Commit (cherry-pick lands with its own pinning coverage)

Test set:

- [x] **4-branch rerank matrix in `execute-retrieval`** (highest leverage)
  - `union=false, per-strategy=false` → 1 ColBERT call (regression guard) ✓
  - `union=false, per-strategy=true`  → 3 calls (one per strategy) ✓
  - `union=true,  per-strategy=false` → 2 calls (one per pass) ✓
  - `union=true,  per-strategy=true`  → 6 calls (3 strategies × 2 passes) ✓
- [x] **`merge-per-strategy-rerank` pure logic**
  - Order by score with interleave-position tiebreak ✓
  - Dedup keeps highest `:rerank-score` ✓
  - `:final-cap` respected ✓
  - Empty / single-strategy degrade gracefully ✓
- [x] **Four agent-tools knobs** (5 tests total)
  - `rescore-with-title-bonus` promotes title-overlap above equal-score peer ✓
  - `enforce-strategy-quota` interleaves top-K per strategy at head ✓
  - `agent-search-display-limit` reads skill-params, clamps to [1,100] ✓
  - `format-search-metadata-results` honors display-limit ✓
  - `auto-read-top-k!` no-ops when k=0 ✓
- [x] **`expand-short-doc-reads`** (workspace)
  - `:total-chunks 4` → expansion fires ✓
  - `:total-chunks 12` → no expansion ✓

Verify after batch 2:
- [x] All new tests green (15 new test functions across 3 namespaces).
- [x] Existing tests unchanged: 24 slice-23 retrieval tests + 120 agent tests + 8 workspace tests + 7 sweep-runner tests all still pass.
- [x] Full regression: 174 tests / 618 assertions, zero failures across the four affected namespaces.
- [x] Behavior-neutral via the explicit unit test `rerank-matrix-no-union-no-per-strategy-runs-one-colbert-call`: with knobs default off, the rerank path is exactly one ColBERT call (identical to pre-batch-2). `bb v3-score` numbers will be unchanged.

### Batch 3 — agent skill-params capability (the centerpiece)

- [x] `86a8e55` — Agent skill-params: schema + 3-layer merge + Playground/MCP plumbing → `3710cff`
  - Resolved `api/util.clj` conflict by splitting our 9 retrieval params
    cleanly across layers: config keys (`:retrieval-*`) stay in
    `build-skill-params-from-config`, per-call keys (`:retrieve-*`) move to
    `build-skill-params-from-params`. Precedence is enforced by
    `deep-merge-skill-params` (params later → wins).
  - `retrieval.clj` `(declare apply-colbert-rerank)` was already present
    from the batch-2 forward-port; auto-merge was a no-op there.
- [x] `e5d8389` — `/api/debug/last-invocation` testability scaffold → `c06f21b`
  - Resolved `endpoints.clj` conflicts: unioned the `:refer` list, kept
    both schemas, appended the new route after our `/config/refresh`.
- [x] `0660649` — Layer-B test: agent skill-params reach rag-params through Playground → `7a982ef`
- [x] `7ad766d` — Layer-C E2E coverage → `b92f674`
  - Resolved `endpoints.clj` `:refer` list: added `debug-agent-resolution-handler`
    after `debug-last-invocation-handler`. Schema + route registration
    auto-merged.
- [x] `ed09373` — Flow agent skill-params system-prompt into outer agent graph input → `c55423d`

Verify after batch 3:
- [x] Full batch-3 sweep: **232 tests / 814 assertions across 11 namespaces, 0 failures.**
  Includes 4 new test surfaces from this batch:
  - `digdir.api.util-test` (new file from `86a8e55`)
  - `digdir.agents.db-test` (extended with skill-params round-trip)
  - `digdir.mcp.tools-test` (mocks bumped to variadic)
  - `digdir.api.routes.endpoints.debug-test` (new file, `last-invocation` + `agent-resolution`)
  - `digdir.e2e.seed-test` (extended with agent fixture)
  - `digdir.playground.core-test` (extended with Layer-B agent layer tests)

### Batch 4 — tuned-agent opt-in fixture

- [x] `052eeba` — Ship Round-5 winner as opt-in agent → `34d9cce`
  - Skipped `d6af390` entirely. Net delta against `establish-baseline`
    was purely additive: a new opt-in agent `digdir/altinn-docs-tuned`.
  - Conflict resolved by taking theirs verbatim (drop duplicate
    `:enabled? true}` left over from auto-merge).
  - Commit message + inline source code comment both flag that values
    predate slices 23 & 25.

Verify after batch 4:
- [x] Default agent (`builtin/agent-rag-agent`) behavior unchanged
      (no `:skill-params` block on it).
- [x] Registry now has 4 agents:
      `builtin/fact-checker-agent`, `builtin/agent-rag-agent`,
      `digdir/altinn-docs-tuned`, `builtin/docs-agent`.
- [x] Final regression: **232 tests / 814 assertions, 0 failures**
      (same number as batch 3 — agents/core change is data-only).

## Followups (not blocking the merge)

- [ ] Re-tune `digdir/altinn-docs-tuned` against the current v3-baseline +
      slice 23/25 stack. The Round-5 phrase-only config was tuned pre-union;
      may now actively suppress union signal.
- [ ] Investigate whether `:per-strategy-rerank?` composes meaningfully with
      slice 25's per-field fan-out (or is redundant). Run `bb v3-score` with
      `:per-strategy-rerank? true` once the knob is live.
- [ ] Short README / CLAUDE.md note: when to use `bb v3-score` vs. sweep
      runner.

## Status log

- **2026-05-28** — Plan written.
- **2026-05-28** — Batch 1 cherry-picks landed cleanly:
  - `fc56035` ← `cc17a77` (sweep.runner-test fixture)
  - `0699a69` ← `848465f` (bb dev-ps + dev-stop)
  - `1869e89` ← `6f67243` (DATAHIKE_FILE_PATH guard, bb.edn conflict resolved by combining our marker-write description + their `(do (ensure-...) (let ...))` wrapper)
  - `9032671` ← `d3c572c` (plan docs)
  - `44d27d4` ← `4c46f34` scope-B (6 matrices kept; 17 sweep-`<ts>`/ result dirs dropped; new commit message notes recoverability via `git show 4c46f34`)
- **2026-05-28** — Verification blocker: pre-existing `git stash pop` conflict in
  `server/src/digdir/skills/builtin/agent/tools.clj` (`<<<<<<< Updated upstream`
  markers) was sitting in the working tree before this session started. Tied to
  an unrelated in-flight "read signals" feature thread (3 new plan docs + 4 modified
  source/test files). Zero overlap with our 5 cherry-picks. Root cause: an earlier
  `git stash pop` of `stash@{0}` (`On release-v0.1-details: stalled-read-signals-efficiency`)
  conflicted at `tools.clj` and was abandoned. The session-start status snapshot
  said "(clean)" because it was for the wrong worktree (`agentic-skills`).
- **2026-05-28** — Parked the read-signals WIP: discarded the partial stash pop
  via `git checkout HEAD -- <4 files>` and `git restore --source=HEAD <3 plan files>`.
  All 7 files remain preserved verbatim in `stash@{0}`; user can `git stash pop`
  any time to resume.
- **2026-05-28** — Batch 1 verification complete:
  - `bb lint`: only pre-existing errors (Electric `Unresolved symbol` false
    positives in `config/ui/inheritance.cljc`); nothing new from cherry-picks.
  - `sweep.runner-test`: 7 tests / 37 assertions green — `cc17a77` fixture
    works.
  - `bb dev-ps`: runs cleanly ("No running `bb dev` instances found.").
  - `bb config-set` without `DATAHIKE_FILE_PATH`: aborts with helpful
    mise.local.toml hint as designed.
- **2026-05-28** — Batch 2 (`f85c984`) cherry-picked as `1fc5a38`. Conflicts
  resolved by folding `:per-strategy-rerank?` into the slice-23 `do-pass`
  closure (4-branch cond now: union ∈ {off,on} × per-strategy ∈ {off,on}).
  Forward-ref fix from `86a8e55` (`declare apply-colbert-rerank`) lifted
  forward to make the new per-strategy variant compile cleanly without
  hot-reload masking the issue. Tools.clj and workspace.clj auto-merged
  cleanly. 15 new pinning tests added; 174 tests / 618 assertions across
  the four affected namespaces all green.
- **2026-05-28** — Batch 3 complete. Five cherry-picks landed:
  `86a8e55 → 3710cff` (agent skill-params + 3-layer merge),
  `e5d8389 → c06f21b` (`/api/debug/last-invocation`),
  `0660649 → 7a982ef` (Layer-B Playground test),
  `7ad766d → b92f674` (Layer-C E2E + `/api/debug/agent-resolution`),
  `ed09373 → c55423d` (system-prompt flow into outer graph input).
  Two conflicts: `api/util.clj` (split 9 retrieval params across the new
  3-layer merge); `endpoints.clj` (union both `:refer` lists and route
  registrations). Total batch-3 verification: 232 tests / 814 assertions
  across 11 namespaces, zero failures.
- **2026-05-28** — Batch 4 (`052eeba`) cherry-picked as `34d9cce`.
  Skipped `d6af390` as planned. The conflict in `agents/core.clj` was a
  duplicate `:enabled? true}` (auto-merge artifact); resolved by taking
  theirs. Augmented the commit message and added an inline source comment
  flagging the staleness of the values vs slices 23/25. Registry now has
  4 agents; default unchanged; 232 tests / 814 assertions still green.
  **Harmonize-runtime merge arc COMPLETE.**
