# Testing with RCF (Rich Comment Forms)

This project uses Hyperfiddle's RCF framework for inline testing, providing self-documenting requirements directly in the source code.

## Quick Start

### Running Tests

Use Babashka tasks to run tests:

```bash
# Run all tests
bb test

# Run only unit tests
bb test:unit

# Run only integration tests
bb test:integration

# Run tests in watch mode (auto-reruns on file changes)
bb test:watch

# Open REPL with test environment
bb test:repl
```

### Running Tests from REPL

```clojure
;; RCF tests are enabled via JVM options
;; Make sure to start REPL with: clojure -M:test

;; Load and test a specific namespace
(require '[agent.graph.tree] :reload)

;; Run all tests in project
(require '[agent.graph.test-runner])
(agent.graph.test-runner/run-all-tests)
```

## Writing RCF Tests

RCF tests are written inline with your code using the `tests` macro:

```clojure
(ns my.namespace
  (:require [hyperfiddle.rcf :as rcf :refer [tests tap %]]))

(defn add [a b]
  (+ a b))

;; Tests go right after the function definition
(tests
 "add function works correctly"
 (add 2 3) := 5
 (add -1 1) := 0
 (add 0 0) := 0)
```

### Test Operators

- `:=` - Assert equality
- `tap` - Print intermediate values
- `%` - Reference to previous value

Note: For predicates and patterns, use standard Clojure functions:
- `(some? x) := true` instead of `x := rcf/some?`
- `(str/includes? s "text") := true` for string matching

### Example with Advanced Features

```clojure
(tests
 "complex example"
 (let [result (process-data {:x 10})]
   (:status result) := :ok
   (some? (:value result)) := true
   (pos? (:value result)) := true
   
   ;; Use tap to debug
   (tap result)
   
   ;; Use % to reference previous value
   (:value result) % 
   (inc %) := 11))
```

## Test Organization

### Inline Tests (Recommended)
Tests are co-located with the implementation for better maintainability:

```clojure
;; src/agent/graph/tree.clj
(defn create-llm-foreach-iteration-node [...]
  ...)

(tests
 "create-llm-foreach-iteration-node creates proper structure"
 ;; Test assertions here
 )
```

### Benefits of Inline Testing

1. **Self-documenting** - Tests serve as live documentation
2. **Maintainable** - Tests move with the code
3. **Discoverable** - Requirements are visible next to implementation
4. **Production-ready** - Tests are automatically elided in production builds

## Configuration

### Development Mode
RCF tests are enabled via JVM options in `deps.edn`:

```clojure
:test {:jvm-opts ["-Dhyperfiddle.rcf.enable=true"
                  "-Dhyperfiddle.rcf.generate-tests=true"]}
```

### Production Mode
Tests are automatically removed from production builds when RCF is not enabled.

## Examples in This Project

### LLM-Foreach Tests
See `src/agent/graph/tree.clj` for comprehensive inline tests of the `:llm-foreach` implementation:

```clojure
(tests
 "llm-foreach in async mode generates child nodes"
 (let [test-node {:type :llm-foreach
                  :node-id (java.util.UUID/randomUUID)
                  :question "Process this item"
                  :children [...]}
       result (answer test-node {})]
   (some? (:generated-children result)) := true
   (count (:generated-children result)) := 3))
```

### Logging Utils Tests
See `src/agent/graph/logging_utils.clj` for utility function tests:

```clojure
(tests
 "truncate-for-logging handles strings correctly"
 (truncate-for-logging "hello") := "hello"
 (str/ends-with? (truncate-for-logging (apply str (repeat 150 "a"))) "...")
   := true)
```

## Test Runner Implementation

The test runner (`src/agent/graph/test_runner.clj`) provides:

- Automatic namespace discovery
- Parallel test execution
- Pretty-printed results
- Watch mode for development
- Separate unit/integration test running

## Best Practices

1. **Write tests immediately** after implementing a function
2. **Use descriptive test names** as documentation
3. **Test edge cases** (nil, empty collections, etc.)
4. **Keep tests focused** - one concept per test block
5. **Use `let` bindings** for complex test setups
6. **Leverage `rcf/match?`** for flexible assertions

## Troubleshooting

### Tests Not Running
- Check JVM options include `-Dhyperfiddle.rcf.enable=true`
- Start REPL with test alias: `clojure -M:test`
- Reload namespace after adding tests: `(require '[namespace] :reload)`

### Test Output
- Results appear in REPL and terminal
- Use `tap` to debug intermediate values
- Check `*out*` for println output during tests

## Migration from clojure.test

The project has migrated from `clojure.test` to RCF. Old test files in `test/` directory have been converted to inline RCF tests in the source files.

## CI/CD Integration

While CI/CD is not currently configured, tests can be run manually:

```bash
# For CI scripts
clojure -M:dev:test -e "(require '[agent.graph.test-runner]) (System/exit (agent.graph.test-runner/run-all-tests))"
```

Returns exit code 0 on success, 1 on failure.