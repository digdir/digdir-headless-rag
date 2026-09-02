(ns digdir.architecture.schemas
  "Emit JSON Schema for the module contracts carried in the System Atlas data.

   WHY A SEPARATE JVM STEP: the Atlas page is built by `script/build_atlas.clj`,
   which is a BABASHKA script, and babashka cannot load malli — verified, the
   require fails. So the contract is authored once as malli in the module EDN,
   converted here on the JVM, and written to `docs/architecture/schemas/` as
   checked-in JSON that the bb build can simply inline.

   THAT MAKES THE JSON A GENERATED ARTIFACT, which is a staleness risk: an EDN
   contract could be edited and the JSON left behind, and the page would show a
   schema that no longer matches what Clojure validates. `atlas-test` calls
   `module-schema-json` directly and compares it to what is on disk, so the two
   cannot drift without a test going red."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.json-schema :as jsc]))

;; Tests and the -main both run with `server/` as the working directory; the
;; Atlas data lives at the repo root, one level up.
(def repo-root "..")
(def modules-dir (io/file repo-root "docs/architecture/modules"))
(def schemas-dir (io/file repo-root "docs/architecture/schemas"))

(defn- deep-sort
  "Recursively replace maps with sorted maps so the emitted JSON is byte-stable.
   Without this the checked-in file could differ from a regeneration purely by
   key order, and the staleness guard would fail for no real reason."
  [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (deep-sort v)])) x)
        (sequential? x) (mapv deep-sort x)
        :else x))

(defn module-files []
  (->> (.listFiles modules-dir)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (remove #(= "order.edn" (.getName %)))
       sort))

(defn modules-with-contracts []
  (->> (module-files)
       (map #(edn/read-string (slurp %)))
       (filter :contract)))

(defn module-schema-json
  "The exact bytes that belong in docs/architecture/schemas/<id>.json.
   Pure — the staleness guard depends on it being callable without side effects."
  [module]
  (let [{:keys [input output]} (:contract module)]
    (str (json/generate-string
          (deep-sort {:module (:id module)
                      :input (jsc/transform input)
                      :output (jsc/transform output)})
          {:pretty true})
         "\n")))

(defn schema-file [module]
  (io/file schemas-dir (str (:id module) ".json")))

(defn validate-contract!
  "A contract that malli cannot compile is a broken contract, and it would
   otherwise surface only as a confusing JSON-Schema error."
  [module]
  (doseq [k [:input :output]]
    (try (m/schema (get-in module [:contract k]))
         (catch Exception e
           (throw (ex-info (str "module " (pr-str (:id module))
                                " has an invalid malli " (name k) " contract: "
                                (ex-message e))
                           {:module (:id module) :key k}))))))

(defn -main [& _]
  (.mkdirs schemas-dir)
  (let [ms (modules-with-contracts)]
    (when (empty? ms)
      (throw (ex-info "no module carries a :contract — refusing to write nothing over the schemas dir" {})))
    (doseq [m ms]
      (validate-contract! m)
      (spit (schema-file m) (module-schema-json m)))
    (println (format "Module schemas written: %s -> %s"
                     (str/join ", " (map :id ms)) (.getPath schemas-dir)))))
