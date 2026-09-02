(ns digdir.docs.retrieval-record
  "What we record about a document whose file we tried to fetch (#308 option C).

   A document can exist, be registered, be searchable by title and metadata,
   and have no content anyone can retrieve — roughly 2,800 of them once a run
   completes. This namespace defines what we then SAY about such a document, so
   its retrievability is a fact you can query rather than an absence you have to
   infer.

   ## The five fields

   | field | meaning |
   |---|---|
   | `last_retrieval_success_at`      | unix seconds of the most recent successful fetch |
   | `first_retrieval_failure_at`     | unix seconds the CURRENT contiguous failure run began |
   | `last_retrieval_failure_at`      | unix seconds of the most recent failure |
   | `consecutive_retrieval_failures` | length of the current contiguous failure run |
   | `retrieval_attempts`             | lifetime attempts, SINCE WE BEGAN RECORDING |

   The last two answer different questions and both are kept deliberately. The
   streak says whether the file is unreachable NOW and how persistently within
   this episode; the lifetime total says how many runs have touched the document
   at all. Neither gives lifetime FAILURES — attempts 8 with a streak of 2 tells
   you it succeeded at some point, not how often it failed. If that question
   matters it needs its own field; do not compute it from these two.

   ⚠️ `retrieval_attempts` STARTS AT 1 ON THE FIRST OBSERVATION AFTER THIS
   LANDS. It cannot be reconstructed for the existing corpus, so it means
   attempts since instrumentation, NOT attempts ever. A document that has been
   through many pre-instrumentation runs still reads 1 the first time it is
   observed. Reading it as a lifetime total is the same mistake `total_chunks`
   invited.

   ## What ABSENT means — the part that must not be left to the first reader

   **Absent means this pipeline has recorded no observation.** It is a statement
   about OUR RECORDS, not about the document. It does not mean the file has
   never been retrievable: every document in the corpus predates these fields,
   so all of them read absent until a run touches them.

   Absent is genuinely distinct from zero at query time, and that was measured
   rather than assumed: on a throwaway collection, a filter of
   `consecutive_retrieval_failures:=0` matched only the document holding zero,
   and `:>=0` also excluded the absent one. An absent field matches no numeric
   filter at all — so \"never observed\" is not directly filterable, it is the
   total minus those matching `:>=0`.

   ## Every state, and what it means

   | counter | success | failure times | meaning |
   |---|---|---|---|
   | absent  | absent  | absent | never observed by this pipeline |
   | 0       | set     | absent | observed, succeeding, never failed since we began recording |
   | 0       | set     | set    | succeeding now; previously failed between them — a CLOSED episode |
   | >= 1    | absent  | set    | currently failing, never seen to succeed |
   | >= 1    | set     | set    | failing since `first_...`; last worked at `last_retrieval_success_at` |

   `retrieval_attempts` is absent in the first row and >= 1 in every other, since
   it counts observations and the other four exist only because one happened.

   On success the counter resets to 0 **and the failure timestamps are
   retained**, so a closed episode stays legible. Clearing them would destroy
   the only record that the document ever failed. On a new failure after a
   success, `first_retrieval_failure_at` is overwritten with the new episode's
   start — it always describes the current run of failures, never an older one.

   ## Why these are carried forward explicitly

   The writer uses Typesense `action=upsert`, which REPLACES the whole document,
   and `prepare-doc` builds its payload from a fixed whitelist. So any field not
   in that payload is destroyed on every run — measured on a throwaway: a
   document written with the counter at 7, rewritten without it, came back with
   the field gone. These fields therefore have to be read and carried forward.

   `action=emplace` would carry them for free and was rejected: it preserves
   EVERY field absent from the payload, so a value legitimately removed upstream
   would silently persist as stale. That would fix four fields by introducing
   the same bug class across all the others."
  (:require [clojure.set :as set]))

(def field-names
  "The five fields, as they appear in the documents collection."
  [:last_retrieval_success_at
   :first_retrieval_failure_at
   :last_retrieval_failure_at
   :consecutive_retrieval_failures
   :retrieval_attempts])

(defn- drop-nils
  "Absent means absent. A nil would be written as a null and read back as a
   present-but-empty value, which is the distinction this namespace exists to
   keep sharp."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn carried-forward
  "Just the five fields from a previously indexed document, nils dropped.
   `prior` may be nil when the document has never been indexed."
  [prior]
  (drop-nils (select-keys prior field-names)))

(defn- episode-open?
  "True when `prior` is already inside a contiguous run of failures."
  [prior]
  (pos? (or (:consecutive_retrieval_failures prior) 0)))

(defn observe
  "The five fields after recording one retrieval attempt.

   `prior`   the document as currently indexed, or nil if it is not indexed yet
   `outcome` `:success` or `:unreachable`
   `at`      unix seconds for this observation

   Returns only the five fields, with absent ones omitted rather than nil."
  [prior outcome at]
  (let [prior (or prior {})
        ;; Every observation counts, success or failure. Starts at 1 rather
        ;; than 0 because the field exists only because we observed.
        attempts (inc (or (:retrieval_attempts prior) 0))]
    (case outcome
      :success
      (drop-nils
        (merge (carried-forward prior)
               ;; The failure timestamps survive: they now describe a closed
               ;; episode, and the counter at 0 is what says it is closed.
               {:last_retrieval_success_at at
                :consecutive_retrieval_failures 0
                :retrieval_attempts attempts}))

      :unreachable
      (drop-nils
        (merge (carried-forward prior)
               {:last_retrieval_failure_at at
                :retrieval_attempts attempts
                :consecutive_retrieval_failures
                (inc (or (:consecutive_retrieval_failures prior) 0))
                ;; A new episode starts here unless one is already open. This is
                ;; why a recovered-then-failed-again document does not report a
                ;; failure run stretching back to its first ever failure.
                :first_retrieval_failure_at
                (if (episode-open? prior)
                  (or (:first_retrieval_failure_at prior) at)
                  at)})))))

(defn unreachable?
  "True when the document's most recent recorded observation was a failure.
   Absent fields mean no observation, which is not the same as a failure."
  [doc]
  (episode-open? doc))

(defn never-observed?
  "True when this pipeline has recorded nothing about the document's
   retrievability — which is every document in the corpus until a run reaches
   it. Deliberately not `(zero? counter)`: absent and zero are different."
  [doc]
  (empty? (set/intersection (set (keys doc)) (set field-names))))
