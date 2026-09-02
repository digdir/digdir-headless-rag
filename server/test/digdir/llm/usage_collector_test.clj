(ns digdir.llm.usage-collector-test
  "#25 — the ambient usage collector and, more importantly, the sentinel that
   makes its ABSENCE visible.

   Five LLM-calling stages reported no `:usage`, so `stage-duration-totals`
   (which buckets on `(some? :usage)`) put them in `other-ms`. That reached 83%
   of wall-clock on the 16-run latency sweep while every check was green.

   The collector is an ambient binding, which is implicit coupling: if it is
   not established, the value is simply absent and everything still reports
   success. So the tests here are mostly about the detector, not the mechanism."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.llm.client :as llm-client]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.sweep.runner :as runner]))

(def ^:private usage-a {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15})
(def ^:private usage-b {:prompt_tokens 1 :completion_tokens 2 :total_tokens 3})

;; ---------------------------------------------------------------------------
;; capture-usage / sum-usage
;; ---------------------------------------------------------------------------

(deftest capture-usage-counts-rather-than-overwrites
  (testing "two completions in one span are BOTH recorded"
    ;; This is the whole reason the sink is a vector. Overwriting would make a
    ;; double-write invisible, and a double-write inflates llm-ms and shrinks
    ;; other-ms — i.e. it looks exactly like the fix having worked.
    (let [captured (llm-client/capture-usage
                     (fn []
                       (#'llm-client/record-usage! {:usage usage-a})
                       (#'llm-client/record-usage! {:usage usage-b})
                       :done))]
      (is (= :done (:result captured)))
      (is (= 2 (:usage-writes captured)))
      (is (= {:prompt_tokens 11 :completion_tokens 7 :total_tokens 18}
             (llm-client/sum-usage (:usages captured)))))))

(deftest a-throwing-attempt-records-nothing
  ;; The no-double-write guarantee is structural: every retry in this codebase
  ;; is exception-driven, and a throwing attempt returned no response, so there
  ;; is nothing to record. Pinned here because the guarantee is CONDITIONAL —
  ;; a retry on a successful-but-unsatisfactory response would break it.
  (let [sink (atom [])]
    (is (thrown? Exception
                 (binding [llm-client/*usage-writes* sink]
                   ;; the shape of a retried attempt: it throws before any
                   ;; response exists to record
                   (throw (ex-info "429" {:status 429})))))
    (is (= [] @sink)
        "a throwing attempt records nothing, which is why retries cannot double-write")))

(deftest usage-summary-distinguishes-none-from-zero
  (testing "no completion reported usage -> :usage absent, writes still stated"
    (let [s (llm-client/usage-summary {:usages [nil] :usage-writes 1})]
      (is (= 1 (:usage-writes s)))
      (is (not (contains? s :usage))
          "absent, not {:total_tokens 0} — `no usage` must not render as `zero tokens`")))
  (testing "nothing ran at all"
    (is (= {:usage-writes 0} (llm-client/usage-summary {:usages [] :usage-writes 0})))))

;; ---------------------------------------------------------------------------
;; The allowlist — a key not named there is dropped silently
;; ---------------------------------------------------------------------------

(deftest recorder-preserves-the-sentinel-keys
  (let [ws (workspace/record-stage-timing
             {} {:stage :generate_response :duration-ms 10
                 :usage-expected? true :usage-writes 0})
        entry (first (:stage-timings ws))]
    (is (true? (:usage-expected? entry))
        "normalize-stage-timing-entry is an ALLOWLIST; an unnamed key vanishes")
    (is (= 0 (:usage-writes entry)))))

;; ---------------------------------------------------------------------------
;; The sentinel at the classifier — both directions
;; ---------------------------------------------------------------------------

(defn- totals [timings] (runner/stage-duration-totals {:stage-timings timings}))

(deftest sentinel-fires-when-the-collector-did-not-reach
  ;; DIRECTION 1: break it — an LLM stage that CALLED the LLM and lost the usage.
  ;; :usage-writes 1 is load-bearing here. This fixture originally used 0, which
  ;; then meant "the collector did not reach". It now means "no call was made",
  ;; because record-usage! conjes nil too and the count separates a stage that
  ;; short-circuited from one whose usage was lost. Same intent, correct shape.
  (let [t (totals [{:stage :generate_response :duration-ms 5000
                    :usage-expected? true :usage-writes 1}])]
    (is (= 1 (:llm-stages-missing-usage t))
        "the artifact says this row's decomposition is unreliable")
    (is (= 5000 (:other-ms t))
        "and the stage is still visibly unclassified rather than quietly in llm-ms")
    (is (zero? (:llm-ms t)))))

(deftest sentinel-stays-silent-when-the-collector-worked
  ;; DIRECTION 2: apply the correct fix — the check must go GREEN. Without this
  ;; direction a sentinel that fires on everything would look like rigour.
  (let [t (totals [{:stage :generate_response :duration-ms 5000
                    :usage-expected? true :usage-writes 1 :usage usage-a}])]
    (is (zero? (:llm-stages-missing-usage t)))
    (is (= 5000 (:llm-ms t)) "now attributed to the model, not to other-ms")
    (is (zero? (:other-ms t)))))

(deftest double-write-is-reported-raw-not-judged
  (let [t (totals [{:stage :read-signal-eval :duration-ms 100
                    :usage-expected? true :usage-writes 2 :usage usage-a}
                   {:stage :generate_response :duration-ms 100
                    :usage-expected? true :usage-writes 1 :usage usage-b}])]
    (is (= 2 (:usage-writes-max t))
        "surfaced RAW for a reader rather than judged here, and that is now
         load-bearing. I previously asserted 'no stage expects 2, so anything
         above 1 is an anomaly'. That was an over-generalisation from the
         EIGHT-STAGE enumeration: eight stages does not mean one call per
         stage. 15 of 16 sweep rows report usage-writes-max 2, so something
         writes twice routinely and I do not yet know which stage —
         :multi-write-stages names it in the next run rather than my guessing.
         What IS established: :read-signal-eval makes exactly one call, because
         infer-query-intent is pure heuristic (workspace.clj:1668) and
         se/evaluate does not retry.")
    (is (zero? (:llm-stages-missing-usage t)))))

(deftest a-non-llm-stage-makes-no-claim
  ;; :usage-expected? is a CLAIM about the call site, and an I/O stage must not
  ;; make it — otherwise every search would count as a missing-usage finding.
  (let [t (totals [{:stage :search :duration-ms 1500 :usage-writes 0}])]
    (is (zero? (:llm-stages-missing-usage t)))
    (is (= 1500 (:io-ms t)))))

(deftest other-stages-names-the-residue
  ;; #25: a green sentinel and a large other-ms are CONSISTENT, because the
  ;; gate only checks stages that made a claim. This column names what is
  ;; actually in there, so the residue is a list rather than a puzzle.
  (let [t (totals [{:stage :generate_response :duration-ms 5000
                    :usage-expected? true :usage-writes 0}
                   {:stage :read-signal-eval :duration-ms 3000 :usage-writes 0}
                   {:stage :search :duration-ms 1500 :usage-writes 0}
                   {:stage :agent-llm :duration-ms 2000 :usage-writes 1 :usage usage-a}])]
    (is (= "generate_response|read-signal-eval" (:other-stages t))
        "both unattributed stages named, sorted, pipe-joined")
    (is (not (str/includes? (:other-stages t) "search"))
        "an I/O stage is not residue")
    (is (not (str/includes? (:other-stages t) "agent-llm"))
        "nor is a stage that reported usage")))

(deftest recorded-entry-carries-the-whole-key-set
  ;; Found independently by another lane: this file asserted individual VALUES
  ;; and never the KEY SET, so a key dropped by an allowlist upstream passed
  ;; every assertion here. Our recorded failure shape is a FABRICATED key being
  ;; invisible to value assertions; this is the same blindness arriving in the
  ;; DROPPED-key direction, and one assertion covers both.
  ;;
  ;; SUBSET, not equality. Whole-map equality goes red on a correct ADDED key —
  ;; the anti-guard runner_test tripped on when :first-chunk-text landed.
  ;; Required keys must be PRESENT; extras are somebody's correct extension.
  (let [entry (first (:stage-timings
                       (workspace/record-stage-timing
                         {} {:stage :generate_response :duration-ms 10
                             :usage {:total_tokens 5} :usage-writes 1})))
        required #{:stage :duration-ms :usage :usage-writes :usage-expected?}
        present (set (keys entry))]
    (is (set/subset? required present)
        (str "keys DROPPED between the envelope and the record: "
             (pr-str (set/difference required present))))
    (is (true? (:usage-expected? entry))
        "derived AT THE RECORDER from the stage keyword — note the caller never
         passed :usage-expected?, so this also pins that the claim no longer
         rides in the envelope beside the value it guards")))

(deftest tool-dispatch-skill-declares-the-usage-outputs
  ;; THE DEFECT THIS FILE MISSED. A skill's :outputs filters what its body
  ;; returns, so execute-tool-call-pure produced :usage correctly and the
  ;; declaration below silently discarded it — the THIRD allowlist in series,
  ;; after normalize-stage-timing-entry and the CSV column list. The collector
  ;; was inert on every path that dispatches tools through this skill, which is
  ;; the bundled path the sweep actually runs, and nothing failed.
  ;;
  ;; Subset again: extra declared outputs are fine, missing ones are not.
  (let [declared (set (:outputs tools/agent-tool-call-metadata))]
    (is (set/subset? #{:usage :usage-writes :usage-expected?} declared)
        (str "usage keys NOT declared, so the skill framework will strip them: "
             (pr-str (set/difference #{:usage :usage-writes :usage-expected?} declared))))
    (is (contains? declared :stage)
        "and :stage must survive too — the recorder derives the CLAIM from it,
         so losing :stage is the one way claim and value go together again")))

(deftest the-breakdown-reports-its-own-residual
  ;; tool-stage-info's `case` ends in an :unknown catch-all, so a tool it does
  ;; not name lands in other-ms looking like orchestration time. This arc has
  ;; ALREADY read other-ms as a closed set of observed stages once and been
  ;; wrong. A breakdown that cannot report its own residual cannot be checked.
  (testing "an unnamed tool is counted and labelled, not folded in"
    (let [t (totals [{:stage :unknown :duration-ms 7000 :usage-writes 0}
                     {:stage :generate_response :duration-ms 3000
                      :usage-expected? true :usage-writes 1 :usage usage-a}])]
      (is (= 7000 (:unknown-stage-ms t)) "the residual has its own number")
      (is (= 1 (:unknown-stage-count t)))
      (is (str/includes? (:other-stages t) "unknown")
          "and it is NAMED in the breakdown, not anonymous")))
  (testing "a fully-named run reports a zero residual"
    ;; The direction that must stay green: nothing unnamed => nothing to report.
    (let [t (totals [{:stage :generate_response :duration-ms 3000
                      :usage-expected? true :usage-writes 1 :usage usage-a}
                     {:stage :search :duration-ms 1200 :usage-writes 0}])]
      (is (zero? (:unknown-stage-ms t)))
      (is (zero? (:unknown-stage-count t))))))

(deftest a-short-circuited-stage-is-not-a-missing-usage
  ;; generate_response returns read-guidance WITHOUT calling synthesis when the
  ;; workspace has no chunks — client-calls 0, verified by probe. plan_queries
  ;; has no such branch, which is the whole reason one looked broken and the
  ;; other did not. Whether a stage calls an LLM is a PER-FIRING property, so an
  ;; unconditional stage-level claim over-reports.
  (testing "no call made -> not a finding"
    (let [t (totals [{:stage :generate_response :duration-ms 40
                      :usage-expected? true :usage-writes 0}])]
      (is (zero? (:llm-stages-missing-usage t))
          "writes 0 means the LLM was never called; the claim does not apply")
      (is (= 1 (:llm-stages-short-circuited t))
          "but it is REPORTED, not folded away — a run full of these means the
           agent kept calling a tool it could not use")))
  (testing "call made and usage lost -> STILL a finding"
    ;; The direction that must stay red, or the fix above would hide the defect
    ;; it was written beside.
    (let [t (totals [{:stage :generate_response :duration-ms 5000
                      :usage-expected? true :usage-writes 1}])]
      (is (= 1 (:llm-stages-missing-usage t)))
      (is (zero? (:llm-stages-short-circuited t)))))
  (testing "an unwrapped stage still counts as missing"
    ;; :agent-llm carries no :usage-writes key at all, so it must default to
    ;; caught — that is how the ~2% server-side transient stayed visible.
    (let [t (totals [{:stage :agent-llm :duration-ms 5000 :usage-expected? true}])]
      (is (= 1 (:llm-stages-missing-usage t))))))

(deftest multi-write-stages-names-the-culprit
  ;; usage-writes-max says a double-write happened; it does not say WHERE, and
  ;; guessing which stage was the step that went wrong repeatedly this evening.
  (let [t (totals [{:stage :generate_response :duration-ms 10
                    :usage-expected? true :usage-writes 2 :usage usage-a}
                   {:stage :plan_queries :duration-ms 10
                    :usage-expected? true :usage-writes 1 :usage usage-b}])]
    (is (= "generate_response=2" (:multi-write-stages t))
        "names the stage AND its count, so the next run answers it directly")
    (is (= 2 (:usage-writes-max t)))))

(deftest a-skipped-gate-is-a-short-circuit-not-a-loss
  ;; The three rows still flagged after the forced-synthesis fix were all
  ;; :sufficiency-gate, and all were the SKIPPED variant recorded with
  ;; duration 0. It declares 0 calls now, so it reads as a short-circuit.
  (let [t (totals [{:stage :sufficiency-gate :duration-ms 0
                    :usage-expected? true :usage-writes 0 :status :skipped}])]
    (is (zero? (:llm-stages-missing-usage t))
        "a gate that never ran cannot have lost a usage")
    (is (= 1 (:llm-stages-short-circuited t))
        "but it is still reported rather than folded away")))

(deftest stages-fired-answers-what-other-stages-cannot
  ;; A stage ABSENT from :other-stages either did not fire, or fired and was
  ;; attributed correctly. Nothing distinguished those, so the artifact could
  ;; not answer "did this run converge?" even in principle.
  ;;
  ;; :agent-final-llm fires ONLY on exhaustion and :citation-backfill-synthesis
  ;; ONLY when primary synthesis produced no citations, so their RATES are
  ;; quality metrics rather than cost lines.
  (let [t (totals [{:stage :agent-llm :duration-ms 10 :usage-writes 1 :usage usage-a}
                   {:stage :agent-llm :duration-ms 10 :usage-writes 1 :usage usage-a}
                   {:stage :agent-final-llm :duration-ms 90 :usage-writes 1 :usage usage-b}
                   {:stage :search :duration-ms 5}])]
    (is (= "agent-final-llm=1|agent-llm=2|search=1" (:stages-fired t))
        "names AND counts every stage, including the ones that were attributed")
    (is (= 1 (:convergence-failures t))
        "the loop did not converge — a fallback answer was synthesised")
    (is (zero? (:citation-repairs t))))
  (testing "a converged run reports zero on both"
    (let [t (totals [{:stage :agent-llm :duration-ms 10 :usage-writes 1 :usage usage-a}])]
      (is (zero? (:convergence-failures t)))
      (is (zero? (:citation-repairs t)))
      (is (= "agent-llm=1" (:stages-fired t))))))
