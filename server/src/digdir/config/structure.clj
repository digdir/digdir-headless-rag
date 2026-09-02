(ns digdir.config.structure
  "The canonical config structure, read from `resources/config/config-structure.edn`.

   WHY THIS NAMESPACE EXISTS. `digdir.config.db/config-roots` and
   `digdir.config.api-keys/valid-config-roots` were two private, independent,
   hand-written definitions of the same set. They were identical and they agreed
   BY COINCIDENCE — nothing made them agree, and a fourth root would have had to
   be added to both by someone who knew both existed.

   Both now derive from here. That is the whole point: agreement is STRUCTURAL
   rather than asserted, so there is one place to change and no pair to keep in
   step.

   ⚠️ DO NOT ADD A LOCAL FALLBACK SET. The obvious defensive move — `(or (roots
   from file) #{:platform :runtime :dataset})` — would recreate exactly the
   duplication this removes, and worse, because the copy would be invisible
   until the file failed to load. A missing structure file is a broken build,
   not a condition to paper over.

   This is the STRUCTURE definition — what kinds of config node may exist. It is
   NOT a seed: it carries no config values, no tenant and no corpus."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private resource-path "config/config-structure.edn")

(defn load-structure!
  "Read the structure file, or throw. Throwing is the correct behaviour: every
   caller needs the roots to validate anything at all, so a nil here would turn
   a missing file into 'no root is valid' — which fails as a confusing rejection
   of legitimate input rather than as a named startup error."
  ([] (load-structure! resource-path))
  ([resource-path]
   (let [resource (io/resource resource-path)]
    (when-not resource
      (throw (ex-info (str "config structure file not found on the classpath: " resource-path)
                      {:resource resource-path})))
    (let [parsed (try
                   (edn/read-string (slurp resource))
                   (catch Exception e
                     (throw (ex-info (str "config structure file is not readable EDN: " resource-path)
                                     {:resource resource-path} e))))
          roots (:roots parsed)]
      (when-not (and (sequential? roots) (seq roots) (every? keyword? roots))
        (throw (ex-info (str "config structure file has no usable :roots — expected a non-empty "
                             "sequence of keywords, got " (pr-str roots))
                        {:resource resource-path :roots roots})))
      (vec roots)))))

(def config-roots-ordered
  "The canonical config roots AS AN ORDERED SEQUENCE, in the order the structure
   file declares them.

   ⚠️ USE THIS WHERE ORDER IS OBSERVABLE — a delete order, an export loop, a
   clone sequence. NOT `(vec config-roots)`: a Clojure set has no defined
   iteration order, so vec-ing one gives a sequence that is stable in practice
   and guaranteed by nothing. `config/ops/retirement.clj` deletes tenant nodes
   in this order and `import_export` exports in it, so a reordering that looked
   like a no-op could change what a round-trip produces.

   The order lives in the data file, where it is authored and reviewable, rather
   than emerging from a hash."
  (load-structure!))

(def config-roots
  "The canonical config roots AS A SET, for membership tests. Derived from the
   ordered sequence — never written out here."
  (set config-roots-ordered))
