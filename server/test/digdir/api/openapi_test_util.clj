(ns digdir.api.openapi-test-util
  "Shared helpers for comparing a handler's real output against openapi.yaml.

   Extracted so the response-shape tests share one definition of what
   'advertised' means. Duplicating it per test is how two checks drift into
   disagreeing about the same spec."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.test :refer [is]]))

(defn schema-properties
  "Advertised property names at `path` under :components :schemas, as strings."
  [path]
  (let [f (io/file "docs/api/openapi.yaml")]
    (is (.exists f) (str "expected to find " (.getPath f)))
    (-> (yaml/parse-string (slurp f))
        (get-in (concat [:components :schemas] path))
        keys
        (->> (map name))
        set)))

(defn non-empty-properties
  "schema-properties with a vacuity guard.

   An empty advertised set makes every set/difference assertion pass, which is
   exactly what a wrong YAML path produces and which a green run cannot
   distinguish from agreement."
  [path]
  (let [props (schema-properties path)]
    (is (seq props)
        (str "no properties found at " path " — the comparison would be vacuous"))
    props))

(defn keyset
  "Key names of `m` as a set of strings."
  [m]
  (set (map name (keys m))))
