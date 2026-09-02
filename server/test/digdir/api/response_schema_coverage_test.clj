(ns digdir.api.response-schema-coverage-test
  "Every documented success response must declare a shape, and every shape it
   names must exist.

   Six paths described their responses in prose and declared no schema (#185).
   Nothing could drift-check them, so the next #184 — a schema advertising
   fields the handler does not emit — would have been unnoticeable on those
   paths for the same reason: there was nothing to compare against.

   This is the structural half of the response-side guard. It does not verify
   that a declared schema MATCHES its handler; that requires exercising the
   handler, which pipeline_response_shape_test does for the Pipeline detail
   response. What this asserts is the precondition that makes such a check
   possible at all: that a shape is declared, and that it resolves.

   The rule is `every 2xx except 204`. 204 means no content by definition, so
   declaring a body for one would be wrong rather than missing — which is why
   the exemption is by status code rather than by an allowlist of paths that
   would silently grow."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]))

(defn- spec []
  (let [f (io/file "docs/api/openapi.yaml")]
    (is (.exists f) (str "expected to find " (.getPath f)))
    (yaml/parse-string (slurp f))))

(defn- key->str
  "Path templates arrive as keywords like :/console-api/datasets/{id}. `name` is
   wrong here — Clojure reads the leading `/` as a namespace separator and drops
   it."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(def ^:private http-methods #{"get" "put" "post" "delete" "patch" "head" "options"})

(defn- operations
  "[path method operation] for every operation in the spec."
  [spec]
  (for [[p item] (:paths spec)
        [m op] item
        :when (contains? http-methods (name m))]
    [(key->str p) (name m) op]))

(defn- success-responses
  "[path method status response] for every 2xx."
  [spec]
  (for [[p m op] (operations spec)
        [code resp] (:responses op)
        :let [code (name code)]
        :when (str/starts-with? code "2")]
    [p m code resp]))

(defn- declared-schema [resp]
  (get-in resp [:content (keyword "application/json") :schema]))

(defn- all-refs
  "Every $ref string anywhere in the spec."
  [x]
  (cond
    (map? x) (concat (when-let [r (:$ref x)] [r]) (mapcat all-refs (vals x)))
    (sequential? x) (mapcat all-refs x)
    :else nil))

;; ---------------------------------------------------------------------------

(deftest every-success-response-declares-a-shape
  (testing "a 2xx described only in prose cannot be drift-checked against its handler"
    (let [undeclared (for [[p m code resp] (success-responses (spec))
                           :when (not= "204" code)
                           :when (nil? (declared-schema resp))]
                       (str (str/upper-case m) " " p " " code))]
      (is (empty? undeclared)
          (str "these success responses declare no schema:\n  "
               (str/join "\n  " (sort undeclared))
               "\nDerive one from the handler — read what it emits, do not copy a "
               "neighbouring schema.")))))

(deftest empty-successes-declare-no-body
  (testing "204 means no content, so declaring a body would be wrong not missing"
    (doseq [[p m code resp] (success-responses (spec))
            :when (= "204" code)]
      (is (nil? (declared-schema resp))
          (str (str/upper-case m) " " p " returns 204 but declares a JSON body")))))

(deftest every-referenced-schema-exists
  (testing "a dangling $ref reads as a contract and resolves to nothing"
    ;; The #145 class: a response pointing at a schema that is not there.
    (let [s (spec)
          defined (set (concat (map name (keys (get-in s [:components :schemas])))
                               (map name (keys (get-in s [:components :responses])))))
          referenced (->> (all-refs s)
                          (map #(last (str/split % #"/")))
                          set)
          dangling (remove defined referenced)]
      (is (empty? dangling)
          (str "$ref targets that do not exist: " (sort dangling))))))

(deftest the-walk-actually-found-operations
  (testing "an empty walk would make every assertion above pass vacuously"
    ;; This is the failure mode worth guarding: if the parse or the traversal
    ;; breaks, the tests above go green because they iterate over nothing.
    (let [ops (operations (spec))
          successes (success-responses (spec))]
      (is (>= (count ops) 25)
          (str "expected the spec to describe at least 25 operations, walked "
               (count ops)))
      (is (>= (count successes) 25)
          (str "expected at least 25 documented success responses, walked "
               (count successes))))))
