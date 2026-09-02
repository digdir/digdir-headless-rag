(ns digdir.sweep.judge-input-test
  "#323 row 2 / #430 — the sweep judge graded a CLIPPED answer, and nothing failed.

   `run-single` truncated string fields to 800 characters for CSV-cell safety,
   and `:response` was one of them — the column the SEPARATE judge pass
   (`judge-sweep-dir!`) reads. Any answer longer than 800 characters reached the
   judge cut off, so the judge saw an enumerative answer stop mid-list and
   returned `partial`. That manufactured a 'model ceiling' which redirected a
   whole research arc before anyone re-judged on full text.

   The fix persists `:response` in full and truncates only human-facing fields
   such as `:error`. IT SURVIVED AS TWO COMMENTS AND NOTHING ELSE: re-wrapping
   `:response` in `truncate` would have gone unnoticed by every test in the
   repository, which is the defect #323 is about rather than the truncation
   itself.

   THE INVARIANT, stated so it cannot be met by accident: what the runner
   persists as `:response` is byte-identical to what the agent returned,
   whatever its length."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.sweep.invoke :as sweep-invoke]
            [digdir.sweep.runner :as runner]))

(def ^:private question
  {:id "q-judge-input" :query "List alle tilstander en filoverforing kan ha."
   :tags [] :expected-chunk-ids []})

(def ^:private base-args
  {:config {:id "deployed" :skill-params {:builtin/agent {:search-snippets true}}}
   :question question
   :collections {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"}
   :execution-scope {:tenant "digdir" :dataset-config-key "default"}
   :judge? false})

(def ^:private long-response
  "An answer well past the 800-character cap that caused the defect.

   Built as an enumeration on purpose: the original failure was the judge
   grading a list that stopped mid-item and calling it `partial`, so the
   fixture reproduces the shape that produced the wrong verdict rather than
   an arbitrary long string."
  (str/join " " (map #(str "Tilstand " % ": filoverforingen er i en definert fase "
                           "med egne overganger og feilhaandtering.")
                     (range 1 41))))

(defn- record-for [response]
  (with-redefs [sweep-invoke/invoke-with-clarification-loop
                (fn [_args]
                  {:status :complete :response response :chunks []
                   :clarification-rounds 0 :terminal-clarification? false})]
    (runner/run-single base-args)))

(deftest the-judged-column-is-persisted-in-full
  ;; The fixture must actually exceed the cap, or this whole namespace asserts
  ;; nothing while looking rigorous — the failure mode #323 row 11 shipped.
  (is (> (count long-response) 800)
      "fixture must exceed the 800-character cap to exercise the defect at all")

  (let [record (record-for long-response)]
    (testing "the response reaches the judge byte-identical, however long"
      (is (= long-response (:response record))
          "re-truncating this column is what made the judge grade a clipped answer")
      (is (= (count long-response) (count (:response record)))))

    (testing "no truncation marker survives into the judged column"
      ;; `truncate` appends a single-character ellipsis. Asserting on the marker
      ;; as well as the length catches a future truncation that pads back to
      ;; length, which a count check alone would miss.
      (is (not (str/includes? (:response record) "…"))
          "an ellipsis in this column means something clipped it"))

    (testing ":response-chars agrees with the column it describes"
      ;; A diagnosable null (#323 requirement 2): if the two ever disagree, the
      ;; count was taken from the raw result while the column was clipped, which
      ;; is exactly the original defect and is otherwise invisible.
      (is (= (:response-chars record) (count (:response record)))))))

(deftest human-facing-fields-are-still-truncated
  ;; The fix is "truncate human-facing fields, never the judged one". Without
  ;; this, the correct fix and "stop truncating everything" are indistinguishable,
  ;; and the next person could satisfy the guard above by removing truncation
  ;; wholesale — losing the CSV-cell safety the cap exists for.
  (let [long-error (str/join (repeat 1200 "x"))
        record (with-redefs [sweep-invoke/invoke-with-clarification-loop
                             (fn [_args]
                               {:status :error
                                :error {:error-message long-error}
                                :response "" :chunks []
                                :clarification-rounds 0
                                :terminal-clarification? false})]
                 (runner/run-single base-args))]
    ;; Asserted as "shorter than the input, and marked", NOT as a specific
    ;; length. `(<= (count ...) 801)` was the first version of this line and it
    ;; was an ANTI-GUARD: raising the cap 800 -> 1000 is a correct change and it
    ;; turned this red. Caught by running direction 2 against this very test,
    ;; minutes after auditing row 10 for the same species. The cap is a tuning
    ;; value; that human-facing text is truncated at all is the invariant.
    (is (< (count (:error record)) (count long-error))
        "human-facing error text stays capped; only the judged column is exempt")
    (is (str/includes? (:error record) "…")
        "and carries the truncation marker, so the capping is visible")))
