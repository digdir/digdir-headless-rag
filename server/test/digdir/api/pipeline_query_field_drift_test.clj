(ns digdir.api.pipeline-query-field-drift-test
  "What we tell callers to put in the query string must match what we accept.

   Six console pipeline endpoints advertised `configKey` while the malli schema
   that validates them requires `dataset-config-key` (#176):

     GET    /console-api/datasets/{id}/pipelines
     GET    /console-api/datasets/{id}/pipelines/{pid}
     PUT    /console-api/datasets/{id}/pipelines/{pid}
     DELETE /console-api/datasets/{id}/pipelines/{pid}
     POST   /console-api/datasets/{id}/pipelines/{pid}/execute
     GET    /console-api/datasets/{id}/pipelines/{pid}/executions

   Unlike the body-field drift in #172 this one was loud rather than silent: the
   key is required, so a caller following the spec got a 400 for a missing key
   instead of a quiet success with a wrong result. Loud is better, but it is
   still our documentation sending them somewhere that cannot work.

   Reading the advertisement off disk is the whole point. A test that restated
   the field list in Clojure would agree with the code forever and never look at
   the doc, and the doc is what was wrong.

   ── The normalisation asymmetry, which is easy to get backwards ──

   Request BODIES are normalised before coercion: `read-json-body` runs
   `normalize-request-keys`, so `datasetConfigKey` in a body arrives as
   `:dataset-config-key` and works. A body drift test must therefore normalise
   BOTH sides or it manufactures findings.

   Query strings are NOT. `api-json-body-middleware` only compiles when the
   route declares `:body` parameters, and `request-query-params` reads reitit's
   coercion output directly. So on these endpoints the advertised name has to
   match the schema key LITERALLY, and `datasetConfigKey` would fail exactly as
   `configKey` did.

   That is why the comparison below is deliberately literal, and why
   `advertised-query-names-are-not-camel-case` exists: if this test ever fails
   and someone 'fixes' it by camel-casing the doc, the doc goes back to being
   wrong in a way a literal comparison would no longer catch."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]
            [digdir.api.routes.endpoints :as endpoints]))

(def ^:private pipeline-operations
  "The six endpoints that share `console-materialization-query-parameters`."
  [["/console-api/datasets/{dataset-id}/pipelines" "get"]
   ["/console-api/datasets/{dataset-id}/pipelines/{pipeline-id}" "get"]
   ["/console-api/datasets/{dataset-id}/pipelines/{pipeline-id}" "put"]
   ["/console-api/datasets/{dataset-id}/pipelines/{pipeline-id}" "delete"]
   ["/console-api/datasets/{dataset-id}/pipelines/{pipeline-id}/execute" "post"]
   ["/console-api/datasets/{dataset-id}/pipelines/{pipeline-id}/executions" "get"]])

;; ---------------------------------------------------------------------------
;; The two sources
;; ---------------------------------------------------------------------------

(defn- schema-query-fields
  "Query field names the malli schema accepts, mapped to whether they are
   required. A malli entry is optional only when it carries {:optional true}."
  []
  (->> (rest endpoints/console-materialization-query-parameters)
       (filter vector?)
       (map (fn [[field opts]]
              [(name field) (not (and (map? opts) (:optional opts)))]))
       (into {})))

(defn- docs-file
  "Docs live beside the code; tests run with the server directory as cwd."
  [relative]
  (let [f (io/file "docs/api" relative)]
    (is (.exists f) (str "expected to find " (.getPath f)))
    f))

(defn- openapi-spec []
  (yaml/parse-string (slurp (docs-file "openapi.yaml"))))

(defn- key->str
  "Path templates arrive as keywords like :/console-api/datasets/{id}/pipelines.
   `name` is wrong here: Clojure reads the leading `/` as a namespace separator
   and returns \"console-api/datasets/{id}/pipelines\", silently dropping it."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- openapi-query-fields
  "Advertised query parameters for one operation, mapped to their `required`
   flag. Path-level parameters are merged in: OpenAPI allows a parameter to be
   declared once for every method on a path, and today these are all `in: path`
   — but a query parameter hoisted there later must not become invisible here."
  [spec path method]
  (let [paths (into {} (map (fn [[k v]] [(key->str k) v])) (:paths spec))
        path-item (get paths path)
        _ (is (some? path-item) (str "openapi.yaml has no path " path))
        ops (into {} (map (fn [[k v]] [(name k) v])) path-item)
        op (get ops method)]
    (is (some? op) (str "openapi.yaml has no " (str/upper-case method) " " path))
    (->> (concat (:parameters path-item) (:parameters op))
         (filter map?)
         (filter #(= "query" (some-> (:in %) name)))
         (map (fn [p] [(name (:name p)) (boolean (:required p))]))
         (into {}))))

(defn- markdown-query-names
  "Query parameter names in the request-line examples of pipelines.md. This file
   advertises through URL examples rather than field tables, so the examples are
   the thing a reader copies."
  []
  (let [text (slurp (docs-file "endpoints/pipelines.md"))]
    (->> (re-seq #"/console-api/datasets/[^\s`]*\?([^\s`]+)" text)
         (map second)
         (mapcat #(str/split % #"&"))
         (map #(first (str/split % #"=")))
         (remove str/blank?)
         set)))

;; ---------------------------------------------------------------------------

(deftest openapi-advertises-exactly-the-query-fields-the-schema-accepts
  (testing "every pipeline operation's query parameters match the malli schema"
    (let [spec (openapi-spec)
          schema (schema-query-fields)]
      (doseq [[path method] pipeline-operations]
        (let [advertised (openapi-query-fields spec path method)]
          (is (= (set (keys schema)) (set (keys advertised)))
              (str (str/upper-case method) " " path "\n"
                   "  advertised but not accepted: "
                   (sort (set/difference (set (keys advertised)) (set (keys schema)))) "\n"
                   "  accepted but not advertised: "
                   (sort (set/difference (set (keys schema)) (set (keys advertised)))))))))))

(deftest openapi-marks-required-query-fields-as-required
  (testing "a required key advertised as optional is why the 400 was surprising"
    ;; GET /pipelines advertised both params as optional while the schema
    ;; required both, so the spec promised a call that could never succeed.
    (let [spec (openapi-spec)
          schema (schema-query-fields)]
      (doseq [[path method] pipeline-operations
              [field required?] (openapi-query-fields spec path method)]
        (when-let [schema-required? (get schema field)]
          (is (= schema-required? required?)
              (str (str/upper-case method) " " path " — `" field "` is "
                   (if schema-required? "required" "optional")
                   " in the schema but advertised as "
                   (if required? "required" "optional"))))))))

(deftest markdown-examples-use-only-accepted-query-names
  (testing "the copyable URL examples agree with the schema too"
    (let [schema (set (keys (schema-query-fields)))
          documented (markdown-query-names)]
      (is (seq documented) "expected pipelines.md to show query-string examples")
      (is (empty? (set/difference documented schema))
          (str "pipelines.md shows query names the schema does not accept: "
               (sort (set/difference documented schema)))))))

(deftest advertised-query-names-are-not-camel-case
  (testing "query strings are not normalised, so a camelCase name cannot work"
    ;; This is the guard against 'fixing' a future failure the wrong way.
    ;; A body field may be advertised camelCase and still work, because
    ;; read-json-body normalises it. A query parameter may not.
    (let [spec (openapi-spec)]
      (doseq [[path method] pipeline-operations
              field (keys (openapi-query-fields spec path method))]
        (is (not (re-find #"[A-Z]" field))
            (str (str/upper-case method) " " path " advertises `" field
                 "`. Query parameters are not camel->kebab normalised — only "
                 "request bodies are — so this name would 400 exactly as "
                 "`configKey` did."))))))
