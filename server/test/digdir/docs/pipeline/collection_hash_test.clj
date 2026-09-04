(ns digdir.docs.pipeline.collection-hash-test
  "#501: the collection-name version suffix must actually version.

   THE DEFECT. `config-hash` read

     (extract-ns-from-map (select-keys config [:hash-changer :strategy]) ns)

   `select-keys` ran FIRST, against a config whose keys are NAMESPACED —
   `:chunks/strategy`, `:chunks/hash-changer`. There is no bare `:strategy`, so it
   selected nothing, `extract-ns-from-map` was handed `{}`, and every call returned
   `(sha256-short-hash {})` = `ab897fbdedfa`. The same twelve characters for every
   tenant, every dataset and every collection kind.

   WHY THAT MATTERS. The suffix exists so a config change lands in a NEW
   collection. It never did, so a re-ingest after a chunking change silently
   overwrote the collection the old config had built — two chunkings in one index
   with nothing recording it.

   HOW IT HID. Recomputing `coll-ids` and getting the name that exists looks like
   confirmation, and was read as one. It is not: that function returned the same
   answer for every input. THE DISCRIMINATING CHECK IS TO VARY THE INPUT AND SEE
   WHETHER THE OUTPUT MOVES, which is what every case below does.

   There was also a tell needing no measurement at all: `coll-ids` calls
   `config-hash` with THREE different `ns` arguments and got ONE suffix back."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.storage :as storage]))

(def ^:private base-config
  {:store/coll-prefix "demo_norquad_"
   :chunks/strategy :header-based
   :chunks/hash-changer 1
   :chunks/minimum-length 333
   :chunks/maximum-length 256000
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model "gemma"
   :search-phrases/hash-changer 1})

(defn- chunks-name [config] (second (storage/coll-ids config)))
(defn- phrases-name [config] (nth (storage/coll-ids config) 2))

(deftest the-suffix-moves-when-the-config-that-defines-it-moves
  (testing "the empty-map constant is gone"
    ;; The literal value the old implementation returned for every input. If this
    ;; comes back, the bug is back.
    (is (not= (str "demo_norquad_chunks_" (core/sha256-short-hash {}))
              (chunks-name base-config))
        "#501: the chunks suffix is the hash of {} again, so it versions nothing"))

  (testing "a chunking change moves the CHUNKS collection"
    (doseq [[label changed] {"strategy"       (assoc base-config :chunks/strategy :semantic)
                             "hash-changer"   (assoc base-config :chunks/hash-changer 2)
                             "minimum-length" (assoc base-config :chunks/minimum-length 500)}]
      (is (not= (chunks-name base-config) (chunks-name changed))
          (str "changing :chunks/" label " must land in a new chunks collection"))))

  (testing "a phrases change moves the PHRASES collection"
    (doseq [[label changed] {"model"        (assoc base-config :search-phrases/model "other-model")
                             "hash-changer" (assoc base-config :search-phrases/hash-changer 2)}]
      (is (not= (phrases-name base-config) (phrases-name changed))
          (str "changing :search-phrases/" label " must land in a new phrases collection"))))

  (testing "and the two are INDEPENDENT — a chunking change must not rename phrases,
            or every phrase would be regenerated for a change that cannot affect it"
    (let [rechunked (assoc base-config :chunks/strategy :semantic)]
      (is (not= (chunks-name base-config) (chunks-name rechunked)))
      (is (= (phrases-name base-config) (phrases-name rechunked))))
    (let [remodelled (assoc base-config :search-phrases/model "other-model")]
      (is (= (chunks-name base-config) (chunks-name remodelled)))
      (is (not= (phrases-name base-config) (phrases-name remodelled)))))

  (testing "POSITIVE CONTROL — identical config gives identical names, so the
            inequalities above are the config moving and not the function being
            unstable"
    (is (= (storage/coll-ids base-config) (storage/coll-ids base-config)))))

(deftest ^{:issue 501} documents-collection-suffix-is-still-constant
  ;; PINNING WHAT THIS FIX DOES NOT DO, so it is a stated limit rather than an
  ;; unnoticed one. `coll-ids` asks for the "documents" namespace, and a loader
  ;; config has no `:documents/*` keys — the source lives under `:folder/*` and
  ;; `:files/*`. So the documents suffix is still the hash of {}.
  ;;
  ;; Arguably correct: the same documents re-chunked belong in the same
  ;; collection. But it also means changing the SOURCE — a different folder, a
  ;; different document limit — does not move the documents collection either,
  ;; and that is not obviously right. Left alone because choosing the source keys
  ;; is a decision about intent, not a bug fix, and this change already forces a
  ;; re-materialisation.
  (testing "documents does not move when the source does"
    (is (= (first (storage/coll-ids base-config))
           (first (storage/coll-ids (assoc base-config :folder/path "/somewhere/else"
                                           :files/limit 7)))))))
