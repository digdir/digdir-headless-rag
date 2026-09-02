(ns digdir.eval.run-validity-test
  "#276: a run that reached no LLM must be distinguishable from one that
   answered badly, and must not be averaged with results.

   The two tests that matter most are the BOUNDARY ones, because the bug is
   usually in the boundary nobody stated:

     - a run that DID reach the model and scored 0.0 is a real measurement and
       must stay in the mean — this is not an answer-quality judge;
     - a row from before the decomposition columns existed must be :unknown,
       not condemned — treating absent as zero would flag every archived
       sweep, which is how a guard gets a reputation for noise."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.eval.run-validity :as rv]))

(deftest zero-llm-calls-is-not-a-measurement
  (is (= :no-llm-call (rv/classify {:llm-calls "0"})))
  (is (= :no-llm-call (rv/classify {:llm-calls 0})))
  (is (true? (rv/never-ran? {:llm-calls "0"}))))

(deftest a-run-that-called-the-model-is-a-measurement
  (is (= :measured (rv/classify {:llm-calls "3"})))
  (is (= :measured (rv/classify {:llm-calls 1})))
  (is (false? (rv/never-ran? {:llm-calls "3"}))))

(deftest llm-ms-is-the-fallback-when-the-call-count-is-absent
  (is (= :no-llm-call (rv/classify {:llm-ms "0"})))
  (is (= :measured (rv/classify {:llm-ms "1200"})))
  (testing "call count wins when both are present"
    (is (= :measured (rv/classify {:llm-calls "2" :llm-ms "0"})))))

(deftest BOUNDARY-a-row-without-the-columns-is-unknown-not-condemned
  ;; The decomposition columns arrived in #254/#265. A runs.csv written before
  ;; them has no llm-calls header at all, so the key is absent — not zero.
  (is (= :unknown (rv/classify {:status "complete" :recall-at-20 "0.0"})))
  (is (= :unknown (rv/classify {:llm-calls "" :llm-ms ""})))
  (is (false? (rv/never-ran? {:status "complete"}))
      "an unproven suspicion must not be reported with the confidence of a fact"))

(deftest partition-places-every-row-and-names-every-bucket
  (let [p (rv/partition-runs [{:llm-calls "0"} {:llm-calls "2"} {:status "complete"}])]
    (is (= #{:measured :no-llm-call :unknown} (set (keys p)))
        "all three buckets present even when empty, so a caller cannot drop one
         by forgetting it exists")
    (is (= 1 (count (:no-llm-call p))))
    (is (= 1 (count (:measured p))))
    (is (= 1 (count (:unknown p))))
    (is (= 3 (reduce + (map count (vals p)))) "every row placed exactly once")))

(deftest BOUNDARY-duration-is-never-consulted
  ;; elapsed-ms 34 is what a human noticed, but asserting on it needs a
  ;; threshold that is wrong on a fast machine and wrong again on a slow one.
  ;; The acceptance says: distinguishable WITHOUT reading elapsed-ms.
  (is (= :measured (rv/classify {:llm-calls "1" :elapsed-ms "34"}))
      "a fast run that DID call the model is a measurement")
  (is (= :no-llm-call (rv/classify {:llm-calls "0" :elapsed-ms "999999"}))
      "a slow run that called nothing is still not a measurement"))

;; ---------------------------------------------------------------------------
;; The real specimens (#275, 2026-08-24)
;; ---------------------------------------------------------------------------
;;
;; Copied VERBATIM from two runs.csv files lane A produced, 16 rows each, every
;; row status=complete with recall 0.0. Inlined rather than read from disk so
;; this test does not depend on another lane's worktree surviving.
;;
;; THE SECOND IS THE DANGEROUS ONE and is why the guard keys on data rather
;; than on prose. Specimen A names its cause in plain language — a human
;; reading the CSV would notice. Specimen B says only "status 400": no cause,
;; no config, no model, and `error` is EMPTY. That is what sixteen
;; legitimate-looking zeros actually look like, and a guard that caught only A
;; would pass B and be counted as coverage.
;;
;; Note what the columns actually hold: `llm-calls` is BLANK, not "0". The
;; fact lives in `llm-ms "0"`. A call-count-only check returns :unknown for
;; all 32 real rows.

(def ^:private specimen-a
  "Reached no LLM, and says why."
  {:status "complete" :llm-calls "" :llm-ms "0" :io-ms "0" :other-ms "8"
   :ttft-ms "" :response-chunk-count "0" :typesense-uri ""
   :recall-at-20 "0.0" :recall-at-10 "0.0" :elapsed-ms "329" :error ""
   :response "LLM request failed at iteration 0: Missing secret :openai-api-key: set OPENAI_API_KEY. Tried [:env]."})

(def ^:private specimen-b
  "Reached no LLM, and says almost nothing. The one that matters."
  {:status "complete" :llm-calls "" :llm-ms "0" :io-ms "0" :other-ms "247"
   :ttft-ms "" :response-chunk-count "0" :typesense-uri ""
   :recall-at-20 "0.0" :recall-at-10 "0.0" :elapsed-ms "542" :error ""
   :response "LLM request failed at iteration 0 (status 400): clj-http: status 400"})

(defn- string-keyed [m] (into {} (map (fn [[k v]] [(name k) v])) m))

(deftest both-real-specimens-are-caught
  (testing "the one that names its cause"
    (is (= :no-llm-call (rv/classify specimen-a))))
  (testing "and the one that does not — no cause, no config, empty error column"
    (is (= :no-llm-call (rv/classify specimen-b))
        "a guard that only caught specimen A would pass this and be counted
         as coverage")))

(deftest the-specimens-are-caught-with-STRING-keys-too
  ;; A runs.csv read back from disk keeps string keys unless something
  ;; keywordises the header. Measured before this was handled: a keyword-only
  ;; lookup scored {:unknown 16} on both real files — green forever in exactly
  ;; the place the guard is needed.
  (is (= :no-llm-call (rv/classify (string-keyed specimen-a))))
  (is (= :no-llm-call (rv/classify (string-keyed specimen-b)))))

(deftest the-fact-is-in-llm-ms-not-llm-calls
  ;; Both real specimens carry llm-calls "" and llm-ms "0". Documented as a
  ;; test because the obvious simplification — key only on the call count —
  ;; misses every real row.
  (is (= "" (:llm-calls specimen-b)))
  (is (= "0" (:llm-ms specimen-b)))
  (is (= :unknown (rv/classify (dissoc specimen-b :llm-ms)))
      "with llm-ms removed there is nothing left to prove it, and :unknown is
       the honest answer rather than a guess"))
