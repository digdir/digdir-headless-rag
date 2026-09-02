(ns digdir.skills.enrichment.eval-sweep-test
  "Slice 2b of #82 (#94): the keep/revert gate no longer embeds the harness.

   Two things are worth testing here and they are not the same thing. One is
   that the skill still builds the comparison it always built — the
   enrichment-off/on pair, the single target question carrying every enriched
   chunk as its golden. The other is the seam that replaced
   `digdir.sweep.questions/load-questions!`, which is new surface and has the
   failure mode this whole issue keeps running into: a regression guard that
   is asked for and silently not applied returns a verdict shaped exactly
   like a guarded one.

   Every test isolates the seam by rebinding the var rather than mutating the
   atom, so running this namespace alone and running it in the suite give the
   same answer — a dev build has the fixture source installed globally by
   `digdir.skills.enrichment.eval-sweep-fixture`."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.eval-runner :as eval-runner]
            [digdir.skills.enrichment.eval-sweep :as eval-sweep]))

;; ---------------------------------------------------------------------------
;; The regression-question seam
;; ---------------------------------------------------------------------------

(def ^:private fixture-questions
  [{:id "bystander-1" :query "hva er dialogporten?" :golden-chunk-ids ["c9"]}
   {:id "bystander-2" :query "hvem eier altinn?" :golden-chunk-ids ["c8"]}])

(deftest explicit-records-need-no-source
  (testing "the production path: questions arrive as data"
    (with-redefs [eval-sweep/regression-question-source (atom nil)]
      (is (= fixture-questions
             (eval-sweep/resolve-regression-questions
               {:regression-questions fixture-questions}))))))

(deftest no-regression-set-is-not-an-error
  (with-redefs [eval-sweep/regression-question-source (atom nil)]
    (is (= [] (eval-sweep/resolve-regression-questions {})))
    (is (= [] (eval-sweep/resolve-regression-questions {:regression-question-ids []})))))

(deftest ids-resolve-through-the-installed-source
  (with-redefs [eval-sweep/regression-question-source (atom (fn [_] fixture-questions))]
    (testing "only the ids asked for come back"
      (is (= [{:id "bystander-2" :query "hvem eier altinn?" :golden-chunk-ids ["c8"]}]
             (eval-sweep/resolve-regression-questions
               {:regression-question-ids ["bystander-2"]}))))))

(deftest asking-for-a-guard-this-build-cannot-supply-throws
  (testing "ids with no source installed — the production build asked for the fixture"
    (with-redefs [eval-sweep/regression-question-source (atom nil)]
      (let [e (try (eval-sweep/resolve-regression-questions
                     {:regression-question-ids ["bystander-1"]})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo e)
            "running on without the regression guard would return a verdict
             indistinguishable from a guarded one")
        (is (re-find #":regression-questions" (ex-message e))
            "the error must name what to pass instead, not merely refuse"))))

  (testing "an id the source does not know is equally an error"
    (with-redefs [eval-sweep/regression-question-source (atom (fn [_] fixture-questions))]
      (let [e (try (eval-sweep/resolve-regression-questions
                     {:regression-question-ids ["bystander-1" "typo-in-the-graph"]})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo e))
        (is (= ["typo-in-the-graph"] (:missing (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; The comparison the skill builds
;; ---------------------------------------------------------------------------

(defn- capture-execute
  "Run the skill with execution and verdict stubbed, returning what each was
   handed. Nothing here invokes an agent."
  [ctx]
  (let [seen (atom {})]
    (with-redefs [eval-runner/run-comparison
                  (fn [m] (swap! seen assoc :comparison m) {:rows [::row]})
                  eval-sweep/compute-batch-verdict
                  (fn [rows target-id chunk-ids regression-ids]
                    (swap! seen assoc :verdict {:rows rows :target-id target-id
                                                :chunk-ids chunk-ids
                                                :regression-ids regression-ids})
                    {:verdicts {} :batch-summary "stub"})]
      (let [result (eval-sweep/execute-eval-sweep ctx)]
        (assoc @seen :result result)))))

(deftest the-target-question-carries-every-enriched-chunk-as-its-golden
  (with-redefs [eval-sweep/regression-question-source (atom nil)]
    (let [{:keys [comparison verdict]}
          (capture-execute {:inputs {:user-query "hvordan sletter jeg en dialog?"
                                     :chunk-outcomes [{:chunk-id "c1"} {:chunk-id "c2"}]
                                     :chunk-ids ["c2" "c3"]
                                     :tenant "digdir"}
                            :parameters {}})
          [target] (:questions comparison)]
      (is (= 1 (count (:questions comparison))) "no regression set was asked for")
      (is (= "hvordan sletter jeg en dialog?" (:query target)))
      (is (= ["c1" "c2" "c3"] (:golden-chunk-ids target))
          "chunk-outcomes, chunk-ids and chunk-id merge, in order, deduplicated")
      (is (= ["c1" "c2" "c3"] (:chunk-ids verdict))
          "the verdict scores the same chunk set the comparison retrieved for"))))

(deftest enrichment-is-the-only-delta-between-the-two-arms
  (with-redefs [eval-sweep/regression-question-source (atom nil)]
    (let [{:keys [comparison]}
          (capture-execute {:inputs {:user-query "q" :chunk-ids ["c1"]}
                            :parameters {:enrichment-type :hypothetical-questions
                                         :expansion-mode :blind}})
          {:keys [configs repeats execution-scope]} comparison
          by-id (into {} (map (juxt :id identity)) configs)]
      (is (= #{"enrichment-off" "enrichment-on"} (set (keys by-id))))
      (is (= 3 repeats) "N=3 by default — n=2 produced a false harmful verdict")
      (is (= "public-docs" (:dataset-config-key execution-scope))
          "dataset falls back when neither inputs nor skill-params carry one")

      (testing "both arms share the baseline, and only :builtin/retrieval differs"
        (let [off (:skill-params (by-id "enrichment-off"))
              on (:skill-params (by-id "enrichment-on"))]
          (is (= {:enabled true :expansion-mode :blind} (:builtin/query-planner off)))
          (is (= (:builtin/query-planner off) (:builtin/query-planner on))
              "expansion-mode sets the baseline for BOTH arms")
          (is (nil? (:builtin/retrieval off)))
          (is (= {:enrichment-types [:hypothetical-questions]} (:builtin/retrieval on)))
          (is (= #{:builtin/query-planner} (set (keys off))))
          (is (= #{:builtin/query-planner :builtin/retrieval} (set (keys on)))))))))

(deftest tenant-and-dataset-fall-back-to-skill-params
  (with-redefs [eval-sweep/regression-question-source (atom nil)]
    (let [{:keys [comparison]}
          (capture-execute {:inputs {:user-query "q" :chunk-ids ["c1"]}
                            :parameters {}
                            :skill-params {:tenant "digdir" :dataset-config-key "kudos"}})]
      (is (= {:tenant "digdir" :dataset-config-key "kudos" :agent-id "builtin/agent-rag-agent"}
             (:execution-scope comparison))
          "in a graph step these arrive via skill-params, not as graph inputs"))))

(deftest the-verdict-gets-the-ids-of-the-questions-that-actually-ran
  ;; The trap this assertion exists for: regression ids used to be read
  ;; straight off :parameters. A caller passing :regression-questions supplies
  ;; no ids, so compute-batch-verdict would receive an empty regression set,
  ;; skip every bystander check, and still return a verdict that looks guarded.
  (with-redefs [eval-sweep/regression-question-source (atom nil)]
    (let [{:keys [comparison verdict]}
          (capture-execute {:inputs {:user-query "q" :chunk-ids ["c1"]}
                            :parameters {:regression-questions fixture-questions}})]
      (is (= 3 (count (:questions comparison))) "target + two bystanders")
      (is (= ["bystander-1" "bystander-2"] (:regression-ids verdict))
          "derived from the resolved questions, not from the parameter"))))

(deftest ids-and-records-reach-the-verdict-identically
  (with-redefs [eval-sweep/regression-question-source (atom (fn [_] fixture-questions))]
    (let [{:keys [verdict]}
          (capture-execute {:inputs {:user-query "q" :chunk-ids ["c1"]}
                            :parameters {:regression-question-ids ["bystander-1"]}})]
      (is (= ["bystander-1"] (:regression-ids verdict))))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest the-skill-registers-itself-on-the-production-classpath
  (eval-sweep/register!)
  (let [skill (skills/get-skill :builtin/enrichment-eval-sweep)]
    (is (some? skill) "the outer self-improve graph's :batch-eval step names this id")
    (testing "the declared parameters are the ones execute actually reads"
      ;; :max-clarification-rounds was dropped in slice 2b: run-comparison does
      ;; not simulate a user, so declaring the knob would advertise a control
      ;; that does nothing.
      (is (= #{:enrichment-type :expansion-mode :repeats
               :regression-question-ids :regression-questions :agent-id}
             (set (keys (get-in skill [:metadata :parameters]))))))))
