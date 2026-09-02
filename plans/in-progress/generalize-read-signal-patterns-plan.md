# Generalize read-signal patterns across the stack

## Context

The read-signal sufficiency work and the Altinn retrieval fixes revealed several patterns that are currently one-off implementations but would benefit the broader stack. This plan covers 6 improvements, ordered by leverage, each with concrete implementation tasks.

## 1. Shared LLM structured-evaluator utility

**Problem:** `read_signals.clj` and `sufficiency.clj` both implement the same pattern (LLM call → strip code fences → parse JSON → validate enums → fallback on failure), but only `read_signals.clj` has underscore-to-hyphen normalization, code-fence stripping, confidence clamping, and a conservative degraded fallback. `sufficiency.clj` uses hardcoded `case` statements for normalization and falls back to a heuristic evaluator without marking the result as degraded.

**New file:** `server/src/digdir/llm/structured_eval.clj`

Extract into it:
- `strip-code-fences` (from `read_signals.clj:220-225`)
- `extract-json-object` (from `read_signals.clj:227-231`)
- `parse-json-response` — combines strip + extract + `json/read-str`
- `keyword-like` with underscore-to-hyphen + lowercase normalization (from `read_signals.clj:237-251`)
- `->validated-enum` (rename of `->enum-keyword`, from `read_signals.clj:253-261`)
- `clamp-confidence` (from `read_signals.clj:263-269`)
- `(evaluate [{:keys [system-prompt user-prompt llm-fn model temperature fallback-fn]}])` — the envelope: call LLM, parse, catch Exception, log warning, call fallback-fn

**Refactor consumers:**
- `read_signals.clj`: replace inline helpers with `require [digdir.llm.structured-eval :as llm-eval]` calls. `evaluate-read` becomes a thin wrapper around `llm-eval/evaluate`.
- `sufficiency.clj`: replace `normalize-status`/`normalize-strategy` case-statements (lines 578-602) with `llm-eval/->validated-enum`. Add `strip-code-fences` to the JSON parse path (line 653). Add `:degraded?` flag to heuristic-fallback results so traces can distinguish LLM vs fallback decisions.

**Tests:** `server/test/digdir/llm/structured_eval_test.clj`
- `parse-json-response` handles raw JSON, fenced JSON, triple-backtick JSON
- `->validated-enum` normalizes `"Support_Found"` → `:support-found`, `"ALIGNED"` → `:aligned`, rejects unknown values
- `clamp-confidence` clamps to [0.0, 1.0]
- `evaluate` calls fallback-fn on LLM exception, returns result with `:degraded? true`

**Existing tests to re-run:**
- `digdir.skills.builtin.agent.read-signals-test` (must still pass after refactor)
- `digdir.skills.builtin.agent.sufficiency-test` (must still pass + new assertions for degraded flag)

## 2. Expose retrieval boost coefficients as skill parameters

**Problem:** `retrieval.clj` has 16 hardcoded scoring constants (lines 186-204). Tenants can't tune retrieval ranking without a code deploy.

**File:** `server/src/digdir/skills/builtin/retrieval.clj`

**Changes to `retrieval-metadata` parameters (line 50-58):**
Add:
```clojure
:boost-weights :map
:diversity-config :map
```

**Changes to `execute-retrieval` (~line 344):**
Destructure `:boost-weights` and `:diversity-config` from `parameters`. Merge with module-level defaults:
```clojure
boost-weights (merge default-boost-weights (or (:boost-weights parameters) {}))
diversity-config (merge default-diversity-config (or (:diversity-config parameters) {}))
```

Extract current constants into two def maps:
```clojure
(def ^:private default-boost-weights
  {:title-overlap-per-token 0.03
   :title-overlap-max 0.30
   :content-overlap-per-token 0.04
   :content-overlap-max 0.40
   :year-match 0.15
   :org-filter-match 0.40
   :content-search-type 0.35
   :phrase-search-type 0.08
   :metadata-search-type 0.05
   :numeric-evidence 0.45
   :original-rank-weight 0.1})

(def ^:private default-diversity-config
  {:relax-min-total 30
   :relax-min-docs 3
   :relax-min-top-doc-count 20
   :relax-min-top-doc-share 0.45
   :relaxed-max-per-document 50
   :default-max-per-document 10})
```

Thread `boost-weights` into `prioritize-chunks` (add as 5th arg). Thread `diversity-config` into `maybe-relax-default-diversity-cap`.

**Tests:**
- Add to `server/test/digdir/skills/builtin/retrieval_test.clj`: test that passing `{:boost-weights {:numeric-evidence 0.0}}` zeroes out numeric-evidence boosting. Test that omitting it keeps defaults.
- Run `retrieval_merge_fixture_test` to confirm golden bounds hold.

## 3. Language-preservation prompt fragment

**Problem:** Query planner now has language-preservation guidance but synthesis, sufficiency evaluator, and auto-filter detection prompts don't. Norwegian queries occasionally get English system-prompt language leaking into reasoning text, trace messages, and even synthesis responses.

**New file:** `server/src/digdir/llm/prompt_fragments.clj`

```clojure
(def same-language-rule
  "Always respond in the same language as the user's question. If the question is Norwegian, answer in Norwegian. If English, answer in English. Do not mix languages.")
```

**Consumers to update:**

- `server/src/digdir/skills/builtin/synthesis.clj` — append `same-language-rule` to the system prompt in `build-generation-prompt`. Currently the system prompt is `"You are a helpful assistant. The date..."` — add the language rule after the date line.
- `server/src/digdir/skills/builtin/agent/sufficiency.clj` — append to `evaluator-system-prompt`. The evaluator's reasoning text surfaces in traces; Norwegian reasoning for Norwegian queries improves readability.
- `server/src/digdir/skills/builtin/query_planner.clj` — replace the inline language rule in `default-prompt` with `(str ... prompt-fragments/same-language-rule ...)` to keep it DRY.

**Tests:**
- `synthesis_test.clj`: assert `build-generation-prompt` output contains "same language"
- `sufficiency_test.clj`: assert evaluator system prompt contains "same language"
- `query_planner_test.clj`: existing test already checks — no change needed

## 4. Surface every blocker in tool results

**Problem:** When a tool can't proceed for multiple simultaneous reasons, some `cond` ladders report only the first match. Fix (3) from the earlier work solved this for `read_chunks` (suppression + budget). Two more gaps remain.

**File:** `server/src/digdir/skills/builtin/agent/tools.clj`

**(4a) `rerank_results` — distinguish "never read" from "read but empty after trim"**

Current code (line ~924):
```clojure
(if (and (empty? workspace-chunks) (seq (:search-history workspace)))
  (workspace/format-read-guidance workspace "rerank_results")
  ...)
```

Add a second branch: if workspace-chunks is empty AND `(:read-history workspace)` is non-empty, the agent DID read but chunks got evicted (e.g., all truncated). Emit a distinct message:
```
"Workspace has no readable content despite prior reads. All read chunks may have been truncated or evicted. Try read_chunks with a narrower selection or without max_content_length truncation."
```

**(4b) `generate_response` — combine insufficient-context with budget state**

Current code (line ~969):
```clojure
(if (and (empty? workspace-chunks) (seq (:search-history workspace)))
  (workspace/format-read-guidance workspace "generate_response")
  ...)
```

Same pattern as rerank. Add the "read but empty" distinction.

**Tests:** Add focused test cases in `agent_test.clj`:
- `test-rerank-distinguishes-never-read-from-evicted`: workspace with search-history + read-history but empty chunks → expects the eviction message.
- `test-generate-distinguishes-never-read-from-evicted`: same for generate_response path.

## 5. Shadow-first auto-filter decisions

**Problem:** Auto-filter applies detected org/year filters silently. When the filter returns 0 hits, retrieval retries unfiltered with a silent fallback. The detection decision is only visible post-hoc via `:auto-filter-applied` and `:auto-filter-fallback` in search-attribution. The agent loop and diagnostics can't see WHY a filter was applied or override it.

**File:** `server/src/digdir/skills/builtin/retrieval.clj`

**Changes:**

Add a `:filter-decisions` output to `retrieval-metadata :outputs` (alongside `:chunks` and `:search-attribution`).

Before applying the detected filter, record a structured decision:
```clojure
(let [filter-decision {:detected-filter detected-filter
                        :confidence (:confidence detected-filter)
                        :applied? true
                        :fallback? false}]
  ...)
```

After fallback (when auto-filter returned 0):
```clojure
(assoc filter-decision :applied? false :fallback? true
       :reason :zero-hits-with-filter)
```

Include `filter-decision` in the skill result outputs. Consumers (diagnostics, trace) can then display it.

**File:** `server/src/digdir/skills/builtin/agent/tools.clj`

In the `search` tool branch, after `record-search!`, also record the filter decision in workspace state if present:
```clojure
(when-let [fd (get-in result [:outputs :filter-decisions])]
  (swap! !workspace update :filter-decisions (fnil conj []) fd))
```

Add `:filter-decisions []` to `create-workspace` initial state in `workspace.clj`.

**File:** `server/src/digdir/skills/builtin/agent/core.clj`

Add a `FILTER DECISIONS` section in `format-trace-file` (after SEARCH HISTORY) that prints each decision with detected-filter, confidence, applied?, fallback?, reason.

**Tests:**
- `retrieval_test.clj`: assert that when auto-filter detects an org, the result includes `:filter-decisions` with `:applied? true`. When fallback fires, `:fallback? true`.
- `agent_test.clj`: assert the filter-decision flows through workspace and appears in trace output.

## 6. Shared LLM test-stub helpers

**Problem:** 3+ test files duplicate LLM stub construction patterns. 6 identical `make-scripted-call-llm` usages in integration tests. 3 identical "throw on call" stubs in agent_test.

**New file:** `server/test/digdir/skills/test_helpers.clj`

Extract:
```clojure
(ns digdir.skills.test-helpers
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn llm-returning-json
  "LLM stub that returns a canned JSON body as a chat-completion response."
  [json-str]
  (fn [_messages _tools _model _temperature]
    {:choices [{:message {:content json-str}}]}))

(defn llm-throwing
  "LLM stub that throws, forcing degraded-fallback paths."
  ([] (llm-throwing "Stubbed LLM exception"))
  ([message]
   (fn [& _]
     (throw (ex-info message {})))))

(defn llm-capturing-prompt
  "LLM stub that captures the user prompt into !captured and returns the given phrases
   as searchPhrases tool-call output."
  [!captured phrases]
  (fn [_messages _tools _model _temperature]
    (reset! !captured ...)
    {:choices [...]}))

(defn make-scripted-call-llm
  "Returns a fn that replays LLM responses in order from a vector."
  [llm-responses]
  ...)
```

**Refactor consumers:**
- `agent_test.clj`: replace inline `(fn [& _] (throw ...))` stubs with `(test-helpers/llm-throwing)`. Replace inline JSON-response fns with `(test-helpers/llm-returning-json ...)`.
- `agent_integration_test.clj`: move `make-scripted-call-llm` to test-helpers. Keep `make-scripted-execute-tool-call` in integration test (it's integration-specific).
- `query_planner_test.clj`: replace `completion-stub-returning` with `test-helpers/llm-capturing-prompt` adapted for litellm arity (5-arg vs 4-arg). Or keep query-planner-specific if litellm's signature differs enough.
- `read_signals_test.clj`: replace inline `(fn [_messages _tools _model _temperature] ...)` with `test-helpers/llm-returning-json`.

**Tests:** The refactored test files must all still pass with zero behavior change.

## Rollout order

1. **#6 (test helpers)** — no production code. Reduces friction for all subsequent work.
2. **#1 (shared evaluator)** — enables #3 cleanly (language rule uses the same prompt-building infra).
3. **#3 (language fragments)** — small, low-risk. Depends on #1 only for the sufficiency.clj refactor.
4. **#2 (boost params)** — independent. Pure refactor + parameter exposure.
5. **#4 (tool-result blockers)** — independent. Small targeted fixes in tools.clj.
6. **#5 (shadow auto-filter)** — most invasive (touches retrieval, tools, workspace, core, diagnostics). Do last.

## Verification

After each item:
- `bb test` full suite — must stay at ≤1 failure (pre-existing `chat_session_integration_test.clj:254`).
- `bb lint` — no new warnings in touched files.
- For #1 and #3: re-run the Altinn trace to confirm no behavioral regression.
- For #2: run `bb retrieve-debug digdir public-docs kudos "Når ble Altinn 3 lansert?"` and confirm answer-chunk ranking is preserved.
- For #5: run a filtered query trace and verify `:filter-decisions` appears in diagnostics.
