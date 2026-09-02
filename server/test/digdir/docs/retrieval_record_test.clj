(ns digdir.docs.retrieval-record-test
  "The contract from #308 option C, tested as a state machine.

   The load-bearing tests are the ones that keep ABSENT distinct from ZERO and
   the ones that prove a failing run cannot destroy an earlier success — that
   second case is what would turn `absent` into a lie, because absent claims we
   have recorded nothing rather than that we lost something."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.docs.retrieval-record :as rr]))

(def ^:private t1 1700000000)
(def ^:private t2 1700086400)
(def ^:private t3 1700172800)

;; ============================================================================
;; Absent is not zero
;; ============================================================================

(deftest a-never-observed-document-carries-none-of-the-fields
  (testing "absent means no observation recorded, and is not written as zero"
    (is (= {} (rr/carried-forward nil)))
    (is (= {} (rr/carried-forward {:doc_num "1" :title "untouched"})))
    (is (true? (rr/never-observed? {:doc_num "1" :title "untouched"})))))

(deftest zero-is-a-recorded-observation-and-absent-is-not
  (let [succeeded (rr/observe nil :success t1)]
    (is (= 0 (:consecutive_retrieval_failures succeeded))
        "a success records zero, which is a value, not an absence")
    (is (false? (rr/never-observed? succeeded)))
    (is (true? (rr/never-observed? {})))))

(deftest nils-are-never-written
  (testing "an absent field is omitted, not set to nil"
    (let [out (rr/observe nil :success t1)]
      (is (not (contains? out :first_retrieval_failure_at)))
      (is (not (contains? out :last_retrieval_failure_at)))
      (is (every? some? (vals out))))))

;; ============================================================================
;; The failure episode
;; ============================================================================

(deftest a-first-failure-opens-an-episode
  (let [out (rr/observe nil :unreachable t1)]
    (is (= 1 (:consecutive_retrieval_failures out)))
    (is (= t1 (:first_retrieval_failure_at out)))
    (is (= t1 (:last_retrieval_failure_at out)))
    (is (not (contains? out :last_retrieval_success_at))
        "never seen to succeed")))

(deftest a-continuing-failure-extends-the-same-episode
  (let [first-fail (rr/observe nil :unreachable t1)
        second-fail (rr/observe first-fail :unreachable t2)]
    (is (= 2 (:consecutive_retrieval_failures second-fail)))
    (is (= t1 (:first_retrieval_failure_at second-fail))
        "the episode still begins where it began")
    (is (= t2 (:last_retrieval_failure_at second-fail)))))

(deftest a-success-closes-the-episode-without-erasing-it
  (testing "the counter resets to zero and the failure timestamps are retained"
    (let [failing (-> (rr/observe nil :unreachable t1)
                      (rr/observe :unreachable t2))
          recovered (rr/observe failing :success t3)]
      (is (= 0 (:consecutive_retrieval_failures recovered)) "episode is closed")
      (is (= t3 (:last_retrieval_success_at recovered)))
      (is (= t1 (:first_retrieval_failure_at recovered))
          "clearing this would destroy the only record that it ever failed")
      (is (= t2 (:last_retrieval_failure_at recovered))))))

(deftest a-new-failure-after-a-success-starts-a-NEW-episode
  (testing "first_retrieval_failure_at describes the current run, never an older one"
    (let [recovered (-> (rr/observe nil :unreachable t1)
                        (rr/observe :success t2))
          failed-again (rr/observe recovered :unreachable t3)]
      (is (= 1 (:consecutive_retrieval_failures failed-again))
          "not 2 — the run of failures restarted")
      (is (= t3 (:first_retrieval_failure_at failed-again))
          "not t1 — otherwise it would report a failure run stretching back through a success")
      (is (= t2 (:last_retrieval_success_at failed-again))
          "the success is still recorded"))))

;; ============================================================================
;; The case that would turn `absent` into a lie
;; ============================================================================

(deftest a-failure-must-not-destroy-an-earlier-success
  (testing "carry-forward, not overwrite"
    (let [succeeded (rr/observe nil :success t1)
          then-failed (rr/observe succeeded :unreachable t2)]
      (is (= t1 (:last_retrieval_success_at then-failed))
          "losing this would make the document look as though it had never worked")
      (is (= 1 (:consecutive_retrieval_failures then-failed)))
      (is (= t2 (:first_retrieval_failure_at then-failed))))))

(deftest repeated-observations-converge-rather-than-drift
  (testing "ten consecutive failures then a success, values stay coherent"
    (let [failing (reduce (fn [d i] (rr/observe d :unreachable (+ t1 (* i 3600))))
                          nil
                          (range 10))]
      (is (= 10 (:consecutive_retrieval_failures failing)))
      (is (= t1 (:first_retrieval_failure_at failing)))
      (is (= (+ t1 (* 9 3600)) (:last_retrieval_failure_at failing)))
      (let [recovered (rr/observe failing :success t3)]
        (is (= 0 (:consecutive_retrieval_failures recovered)))
        (is (= t1 (:first_retrieval_failure_at recovered)))))))

;; ============================================================================
;; The predicates
;; ============================================================================

(deftest unreachable-is-about-the-latest-observation-not-history
  (is (true? (rr/unreachable? (rr/observe nil :unreachable t1))))
  (is (false? (rr/unreachable? (rr/observe (rr/observe nil :unreachable t1) :success t2)))
      "a document that recovered is not unreachable, even though it failed before")
  (is (false? (rr/unreachable? {}))
      "no observation is not a failure"))

(deftest observe-ignores-unrelated-document-fields
  (testing "only the four fields are returned, so the caller controls the payload"
    (let [out (rr/observe {:doc_num "1" :title "t" :total_chunks 9} :success t1)]
      (is (= #{:last_retrieval_success_at :consecutive_retrieval_failures
               :retrieval_attempts}
             (set (keys out)))))))

;; ============================================================================
;; Lifetime attempts, kept alongside the streak
;; ============================================================================
;;
;; The two counters are deliberately different. A success resets the streak and
;; still increments attempts — collapsing them is the substitution these tests
;; exist to prevent.

(deftest a-success-increments-attempts-while-resetting-the-streak
  (let [failing (-> (rr/observe nil :unreachable t1)
                    (rr/observe :unreachable t2))
        recovered (rr/observe failing :success t3)]
    (is (= 2 (:consecutive_retrieval_failures failing)))
    (is (= 2 (:retrieval_attempts failing)))
    (is (= 0 (:consecutive_retrieval_failures recovered)) "streak resets")
    (is (= 3 (:retrieval_attempts recovered)) "attempts does not")))

(deftest attempts-counts-every-observation-of-either-kind
  (let [d (-> (rr/observe nil :success t1)
              (rr/observe :unreachable t2)
              (rr/observe :success t3)
              (rr/observe :unreachable t1)
              (rr/observe :unreachable t2))]
    (is (= 5 (:retrieval_attempts d)) "three failures and two successes")
    (is (= 2 (:consecutive_retrieval_failures d)) "only the current run")))

(deftest the-five-times-failed-succeeded-twice-failed-case
  (testing "the case that distinguishes the two counters"
    ;; A document that failed five times, succeeded, then failed twice has
    ;; eight attempts and a consecutive count of two.
    (let [d (as-> nil d
              (reduce (fn [acc i] (rr/observe acc :unreachable (+ t1 i))) d (range 5))
              (rr/observe d :success t2)
              (rr/observe d :unreachable t3)
              (rr/observe d :unreachable (inc t3)))]
      (is (= 8 (:retrieval_attempts d)))
      (is (= 2 (:consecutive_retrieval_failures d)))
      (is (= t2 (:last_retrieval_success_at d)))
      (is (= t3 (:first_retrieval_failure_at d))
          "the current episode, not the first ever failure"))))

(deftest attempts-starts-at-one-not-zero
  (testing "absent means never observed; the first observation writes 1"
    (is (not (contains? {} :retrieval_attempts)))
    (is (= 1 (:retrieval_attempts (rr/observe nil :success t1))))
    (is (= 1 (:retrieval_attempts (rr/observe nil :unreachable t1))))))

(deftest attempts-is-carried-forward-like-the-others
  (is (= {:retrieval_attempts 4}
         (rr/carried-forward {:retrieval_attempts 4 :doc_num "1" :title "t"}))))
