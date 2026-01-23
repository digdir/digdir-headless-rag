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