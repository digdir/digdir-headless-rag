(ns digdir.mcp.chunk-payload-test
  "What the tools DECLARE about a chunk must match what they EMIT (#504).

   THE DIAGNOSIS THIS ENCODES, because the issue offered two possibilities and
   the answer was neither:

     - the payload builder does NOT drop `url` — `:url` is in its `select-keys`;
     - the folder loader does NOT skip it — `prepare-folder-doc` assocs it.

   `make-url` returns nil when the config has no `:base-url`, the Typesense
   field is `:optional true` so a nil is never stored, and the docs-collection
   join therefore returns no `url` at all. The chunk map has no `:url` key and
   `select-keys` omits it — correctly.

   ⇒ The field is genuinely CONDITIONAL: a local markdown corpus has no public
   address. The declaration was what was wrong, by promising unconditionally
   what only some corpora can supply. Inventing a URL would be worse.

   Underneath sits a separate defect, filed rather than fixed here: a folder
   dataset cannot supply a base URL through dataset config at all, because
   `:base-url` is absent from the `:folder` entry of `source-loader-key-map`.
   Adding it there would make it a FOURTEENTH REQUIRED key for every folder
   dataset — the #453 trap one map over — so it needs the optional-key
   treatment rather than a one-line addition."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.mcp.tools :as tools]))

(def ^:private declared
  (get-in @#'tools/tool-output-schema ["properties" "chunks" "items" "properties"]))

(defn- emitted-keys
  "Keys the payload builder actually puts on a chunk, given one carrying
   everything. Behavioural: it runs the builder rather than re-reading its
   `select-keys` literal, so a change to that literal is caught."
  []
  (let [full (into {:not-declared "should be dropped"}
                   (map (fn [k] [(keyword k) "x"]))
                   (keys declared))
        out (#'tools/->structured-content {:chunks [full]} "convo-1")]
    (set (map name (keys (first (:chunks out)))))))

(deftest declared-and-emitted-chunk-fields-agree
  (testing "the comparison is not vacuous"
    ;; Both sides must be populated, or set equality is trivially true.
    (is (< 5 (count declared))
        (str "expected the full declared property set, saw " (count declared)))
    (is (< 5 (count (emitted-keys)))
        "the payload builder emitted almost nothing — the probe is wrong, not the code"))

  (testing "every declared field is emitted when the chunk carries it"
    (let [d (set (keys declared)) e (emitted-keys)]
      (is (empty? (set/difference d e))
          (str "declared but never emitted: " (pr-str (sort (set/difference d e)))
               ". A consumer builds against the declaration."))
      (is (empty? (set/difference e d))
          (str "emitted but not declared: " (pr-str (sort (set/difference e d)))))))

  (testing "and undeclared keys are dropped, so the emitted set is bounded"
    ;; Without this the first assertion passes on a builder that emits the
    ;; whole chunk map unfiltered.
    (is (not (contains? (emitted-keys) "not-declared")))))

(deftest conditional-fields-say-they-are-conditional
  ;; THE FIX FOR #504. `url` is formally optional already — the chunk items
  ;; carry no `required` list — but nothing SAID so, and a client reading the
  ;; declaration builds a link and gets undefined. The schema now states when
  ;; the field is present and when it is not.
  (testing "url is declared with a description that marks it conditional"
    (let [d (get declared "url")]
      (is (some? d) "url is no longer declared at all — clients lose the field entirely")
      (is (string? (get d "description"))
          "url has no description; a bare {\"type\":\"string\"} reads as always present")
      (is (str/includes? (str/lower-case (get d "description")) "only")
          "the description does not say the field is conditional")))

  (testing "and the chunk items declare nothing as required, so absence is legal"
    (let [items (get-in @#'tools/tool-output-schema ["properties" "chunks" "items"])]
      (is (nil? (get items "required"))
          "chunk items now mark fields required — an absent url would violate the schema"))))
