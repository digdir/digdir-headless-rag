# Claude Development Notes

This file contains important patterns and conventions for working with this codebase, particularly for Electric/Hyperfiddle components.

## Electric/Hyperfiddle Event Handling with e/Token

### When to Use e/Token

The `e/Token` pattern is **required** for event handlers that perform side effects in Electric components. This includes:
- State mutations (e.g., `reset!`, `swap!`)
- Any imperative operations that change application state
- Async operations that need proper loading/error states

### Recommended Pattern (Best Practice for Async Operations)

```clojure
;; BEST: Return token from button, handle async work outside
(dom/button
  (dom/text "Click me")
  (when-some [token (let [e (dom/On "click" identity nil)
                          [t err] (e/Token e)]
                      (dom/props {:aria-busy (some? t)
                                  :disabled (some? t)
                                  :aria-invalid (some? err)})
                      t)]
    ;; Perform async operations here
    (let [result (e/server (e/Offload (fn [] 
                                        ;; Do work here
                                        (swap! !some-atom not)
                                        ::success)))]
      (case result
        ::success (token)  ; Success - complete the token
        (token result)))))  ; Error - feed back to control
```

### Simple Pattern (for Synchronous Operations)

```clojure
;; SIMPLE: For synchronous side effects only
(dom/button
  (dom/props {:style {...}})
  (dom/text "Click me")
  (let [[token err] (e/Token (dom/On "click" identity nil))]
    (when token
      ;; Perform side effects here
      (reset! !some-atom new-value)
      ;; Call the token to complete the action
      (token))))
```

### Incorrect Pattern

```clojure
;; INCORRECT: Direct side effects in event handler
(dom/button
  (dom/props {:style {...}})
  (dom/On "click" (e/fn [e] (reset! !some-atom new-value)) nil)  ; ❌ Won't work properly
  (dom/text "Click me"))
```

### Real-World Examples from the Codebase

1. **Debug Button** (admin/src/agent/graph/ui/node.cljc):
```clojure
(e/defn NodeDebugButton
  [!show-debug]
  (e/client
    (dom/button
     (dom/props {:style {...}})
     (dom/text "🐛")
     (let [[token err] (e/Token (dom/On "click" identity nil))]
       (when token
         (reset! !show-debug true)
         (token))))))
```

2. **Modal Close Button** (admin/src/agent/graph/ui/node.cljc):
```clojure
(dom/button
 (dom/props {:style {...}})
 (dom/text "×")
 (let [[token err] (e/Token (dom/On "click" identity nil))]
   (when token
     (reset! !show-debug false)
     (token))))
```

### Key Points to Remember

1. **Order matters**: Render static content (like `dom/text`) before the event handler
2. **Always call the token**: After performing side effects, call `(token)` to complete the action
3. **Use identity**: Pass `identity` to `dom/On` when using e/Token pattern
4. **Check for token**: Always wrap side effects in `(when token ...)` to ensure proper execution

## Pending Signal Pattern (Avoiding Race Conditions)

When a button click needs to perform a server operation AND update client state that depends on the result (e.g., creating a record and selecting it in a list), use the **pending signal pattern** to avoid race conditions.

### The Problem

Directly performing server operations inside event handlers can cause race conditions:
```clojure
;; PROBLEMATIC: Race condition between server op and reactive list update
(let [[tok err] (e/Token (dom/On "click" identity nil))]
  (when tok
    (let [new-id (e/server (create-record ...))]
      ;; State updates before the reactive list has refreshed
      (swap! !state assoc :selected-id new-id))  ; ❌ List may not contain new-id yet
    (tok)))
```

### The Solution: Pending Signal Pattern

1. **Button sets a pending flag** in state (no server call)
2. **Reactive block** detects the flag, performs server operation, then updates state

```clojure
;; CORRECT: Use pending signal pattern
;; Step 1: Button just sets a flag
(dom/button
  (dom/text "Create")
  (let [[tok err] (e/Token (dom/On "click" identity nil))]
    (when tok
      (swap! !state assoc :pending-create true)
      (tok))))

;; Step 2: Reactive block handles the operation (elsewhere in component)
(when (:pending-create state)
  (let [new-id (e/server
                 (let [param1 (e/client (:param1 state))
                       param2 (e/client (:param2 state))]
                   (e/Offload
                     #(create-record param1 param2))))]
    (e/client
      (swap! !state assoc
             :pending-create nil
             :selected-id new-id))))
```

### Key Points

1. **Separation of concerns**: Button signals intent, reactive block handles execution
2. **Proper ordering**: Server operation completes before client state updates
3. **e/Offload for DB ops**: Wrap database operations in `e/Offload` to avoid blocking
4. **Transfer values correctly**: Use `(e/client ...)` inside `e/server` (but outside `e/Offload`) to transfer values, then capture them in the Offload closure

### Real-World Example: Creating and Selecting a Conversation

```clojure
;; Button signals intent
(dom/button
  (dom/text "New Chat")
  (let [[tok err] (e/Token (dom/On "click" identity nil))]
    (when tok
      (swap! !playground-chat-state assoc :pending-new-conversation true)
      (tok))))

;; Reactive handler creates conversation and updates selection
(when (:pending-new-conversation state)
  (let [new-convo-id (e/server
                       (let [entity-id (e/client effective-entity-id)
                             tenant (e/client selected-tenant)
                             env (e/client selected-environment)]
                         (e/Offload
                           #(let [result (db/create-playground-conversation
                                           (db/get-conn)
                                           entity-id
                                           {:tenant tenant :environment env})]
                              (:conversation-id result)))))]
    (e/client
      (swap! !playground-chat-state assoc
             :pending-new-conversation nil
             :conversation-id new-convo-id))))
```

## Node Rendering Patterns

### Displaying Node Content Before Processing

For nodes that haven't been processed yet, we display different content based on the node type:

- **:value nodes**: Display the `:text` field if available
- **:container nodes**: Display the `:question` field if available  
- **Other nodes**: Display the node type as a string

Example from `NodeQuestion` component:
```clojure
(dom/text (or q  ; First try the main question field
              (case (:type node)
                :value (or (:text node) (str (:type node)))
                :container (or (:question node) (str (:type node)))
                (str (:type node)))))
```

## Testing Commands

When implementing new features, always verify with:
```bash
# Run linting
bb lint

# Run tests
bb test
```

## Component Structure

The codebase follows a consistent pattern for Electric components:

1. **Props first**: Component props are passed as the first argument
2. **e/fn for content**: Use `(e/fn [] ...)` for dynamic content blocks
3. **Atoms for local state**: Use atoms with `!` prefix for local component state
4. **e/watch for reactivity**: Use `(e/watch !atom)` to make components reactive to state changes

## Retrieval evaluation harnesses

Two complementary tools for measuring retrieval quality. They answer different
questions; keep both, use whichever fits the task.

### `bb v3-score` — fast retrieval-only inner loop

- **Where:** `server/src-dev/digdir/sweep/v3_cites.clj`, driven by the
  `v3-score` task in `bb.edn`.
- **What:** scores the retrieval skill (or the debug endpoint) against the
  7-question v3-baseline ground truth. Reports chunk-level recall@10/@30 and
  doc-level recall@10/@30.
- **Bypasses the agent loop entirely.** Calls the retrieval pipeline directly
  via the debug endpoint — fast (~30s per run), tight feedback loop.
- **Use when:** you changed something in the retrieval skill (a knob, a
  scoring change, a new strategy) and want to know if chunk/doc recall
  moved. 3-run replicates give a noise band.
- **Example flag set used during slice 23:**
  ```bash
  bb v3-score --rerank-with-colbert true \
              --expand-queries 8 \
              --user-intent-first-pass true \
              --server-side-union true
  ```

### Sweep runner — end-to-end agent attribution

- **Where:** `server/src-dev/digdir/sweep/runner.clj`, matrices under
  `server/test/fixtures/sweep/matrices/`.
- **What:** drives the cartesian product of `{config × question × repeat}`
  through `invoke-with-clarification-loop` — i.e., the **full agent loop**,
  not just retrieval. Captures real per-run cost (`:prompt-tokens`,
  `:completion-tokens`, `:llm-calls`) and Filter-4 stage decomposition
  (`:golden-in-search-pool?`, `:golden-in-display?`, `:golden-read?`)
  in `runs.csv`.
- **Use when:** you need to attribute an agent-end-to-end recall problem
  to a specific stage (retrieval / display / agent-read / rerank-trim),
  or sweep a parameter matrix to find a config that survives across many
  question×repeat combos.
- **Slower:** hundreds of runs per matrix; budget a long lunch or overnight.

### Rule of thumb

- "Did this retrieval change move the chunk/doc baseline?" → **`bb v3-score`**.
- "Is agent recall limited by retrieval / display / read / trim?" → **sweep runner**.
- "Noise band on a single config?" → `bb v3-score` 3-run replicates.
- "What config survives 270 question×repeat combos?" → sweep matrix.

Pre-existing matrices (`baseline-v0`, `round-1..9`) live in
`server/test/fixtures/sweep/matrices/`; archived per-run CSVs of rounds 4–9
remain recoverable via `git show 4c46f34` if you need the raw research log
(they were intentionally dropped from the merged history to keep the repo
diff small).