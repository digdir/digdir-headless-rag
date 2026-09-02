(ns digdir.docs.schema-drift
  "Does the live Typesense collection declare the fields `docs_schema.edn` says
   it does? (#377)

   `create-docs-coll` creates a collection from that file and, on conflict,
   returns `:already-exists` — it never alters. Nothing else alters it either.
   So the file describes collections created AFTER an edit and nothing else, and
   a field added to the file reaches an existing collection only if someone
   applies it by hand.

   Nothing warned when that happened. `total_chunks` entered the file and
   `prepare-doc` in February 2026, the production collection was created in
   December 2025, and the field is on none of its 11,306 documents. #239 spent
   real effort establishing that absence and first attributed it to the wrong
   cause. By August the same gap was sitting under five more fields.

   Typesense stores undeclared fields and does not index them, which is what
   makes this quiet rather than loud: the values are written, kept, and returned
   when a document is fetched — and they are not filterable and not sortable. A
   field can therefore be present in every document and useless to every query.

   ## Three states, not two

   `:ok` and `:drift` both mean the comparison HAPPENED. `:unreachable` means it
   did not, and it must never be read as a pass. That distinction is the whole
   point of the namespace: a checker that cannot tell \"no drift\" from \"no
   look\" is the same defect one layer up.

   ## What this cannot see

   A check can only compare against a collection it can reach. CI cannot reach
   production, so a green CI run says NOTHING about the production collection —
   it says the pure comparison works. Only a run pointed at production tells you
   about production, which is why every report names the host and collection it
   actually looked at. Collection names are shared across instances here; a name
   alone has already been enough to mistake one instance for another."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.set :as set]
            [typesense.client :as ts]))

(defn declared-fields
  "Field names `docs_schema.edn` declares. The file is the intended shape."
  []
  (->> (get (edn/read-string (slurp (jio/resource "docs_schema.edn"))) "fields")
       (keep #(get % "name"))
       set))

(defn compare-fields
  "Pure diff between the intended and the live field sets.

   Both directions are reported and they mean different things. Fields in the
   file but not the collection are written-but-unindexed — present in documents
   and invisible to filters. Fields in the collection but not the file mean the
   live collection has something the file does not describe, so re-creating it
   from the file would silently drop that field."
  [declared live]
  {:missing-from-collection (vec (sort (set/difference declared live)))
   :missing-from-file (vec (sort (set/difference live declared)))})

(defn drift?
  [{:keys [missing-from-collection missing-from-file]}]
  (boolean (or (seq missing-from-collection) (seq missing-from-file))))

(defn check-collection
  "Compare `docs_schema.edn` against the live collection at `settings`.

   `settings` is the Typesense client map, `collection-name` the collection the
   RUNNING system is configured to use — resolve it from live config rather than
   deriving it, so the report describes what is actually in use.

   Never throws. Returns:

     {:status :ok | :drift | :unreachable
      :checked {:host ... :collection ...}   always present
      :missing-from-collection [...]
      :missing-from-file [...]
      :reason ...}                            when :unreachable

   `:checked` is populated even when the look fails, because \"could not reach
   THIS collection on THIS host\" is the useful form of that failure."
  [settings collection-name]
  (let [checked {:host (:uri settings) :collection collection-name}]
    (try
      (let [live (->> (ts/retrieve-collection settings collection-name)
                      :fields
                      (keep :name)
                      set)
            diff (compare-fields (declared-fields) live)]
        (merge {:status (if (drift? diff) :drift :ok)
                :checked checked
                :declared-count (count (declared-fields))
                :live-count (count live)}
               diff))
      (catch Exception e
        {:status :unreachable
         :checked checked
         :reason (ex-message e)}))))

(defn ok?
  "True only when the comparison ran and found nothing. `:unreachable` is not
   ok — it is unknown, and the two must not collapse."
  [report]
  (= :ok (:status report)))

(defn report-lines
  "Human-readable lines for a report. Always states what was looked at, because
   a check that does not say what it examined is how two collections sharing a
   name get mistaken for each other."
  [{:keys [status checked missing-from-collection missing-from-file
           declared-count live-count reason]}]
  (let [where (str (:collection checked) " on " (:host checked))]
    (case status
      :ok
      [(str "docs schema: OK — " where " declares all " declared-count " fields in docs_schema.edn")]

      :unreachable
      [(str "docs schema: NOT CHECKED — could not read " where)
       (str "  reason: " reason)
       "  This is UNKNOWN, not clean. No conclusion about that collection follows."]

      :drift
      (concat
        [(str "WARNING: docs schema drift — " where)
         (str "  docs_schema.edn declares " declared-count
              " field(s); the collection declares " live-count)]
        (when (seq missing-from-collection)
          (concat
            ["  declared in the file, ABSENT from the collection"
             "  (written to documents but NOT indexed — invisible to filter_by and sort_by):"]
            (map #(str "    - " %) missing-from-collection)))
        (when (seq missing-from-file)
          (concat
            ["  present in the collection, ABSENT from the file"
             "  (re-creating the collection from the file would drop these):"]
            (map #(str "    - " %) missing-from-file)))
        ["  create-docs-coll only creates; it never alters an existing collection."
         "  Closing the gap is a deliberate act — see #377."]))))

(defn report!
  "Print the report and return it. Reports rather than throws, following the
   boot-check precedent (#271, #275): a throw here would fail a boot over the
   shape of a third-party collection, which is not this check's call to make."
  [settings collection-name]
  (let [report (check-collection settings collection-name)]
    (doseq [line (report-lines report)]
      (println line))
    report))
