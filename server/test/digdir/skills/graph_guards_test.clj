(ns digdir.skills.graph-guards-test
  "Guards for two declarations the graph machinery does not enforce (#240, #268).

   Both are the same family: something written in a graph definition that no
   mechanism reads back, so it can drift from the truth without anything
   failing.

   1. `:condition` is a dependency `topological-sort` cannot see — it builds
      edges from `:inputs` refs only. A conditioned step that does not consume
      what its condition reads can be scheduled first, and the condition then
      evaluates against an absent value. No crash: just a uniformly wrong
      branch in a graph that reads correctly in source.

   2. A graph's declared `:outputs` are never consulted by `collect-outputs`,
      but `digdir.skills.ui` renders them to a human. A stale key advertises an
      output a caller will never receive.

   Each guard is tested three ways: it passes on the live registry, it FIRES on
   a deliberately broken graph, and its instrument is tested on its own. The
   middle one is the one that matters — a guard that has never been seen to
   fail is indistinguishable from one that cannot."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.skills.builtin.overview :as overview]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.init :as init]
            [digdir.skills.templates.core :as templates-core]))

;; Both guards resolve a step's declared outputs through the skill registry, so
;; the registry has to be populated for the hand-built graphs below to be judged
;; against real skill contracts rather than against nothing.
(use-fixtures :once (fn [f] (init/ensure-initialized!) (f)))

;; =============================================================================
;; The live registry
;; =============================================================================

(deftest live-registry-has-no-condition-ordering-problems
  (let [{:keys [ok problems checked]} (init/verify-graph-conditions)]
    (is (pos? checked) "the guard must actually have graphs to check")
    (is ok (str "conditioned steps depending on a step not ordered before them: "
                (pr-str problems)))))

(deftest live-registry-has-no-undeclared-outputs
  (let [{:keys [ok problems checked]} (init/verify-graph-declared-outputs)]
    (is (pos? checked))
    (is ok (str "graphs declaring :outputs no step produces, or carrying a "
                "stale exemption: " (pr-str problems)))))

;; =============================================================================
;; The condition probe, on its own
;; =============================================================================

(deftest probe-records-step-ids-only
  (testing "only depth-1 keys of step-outputs are step ids"
    ;; The first version of this probe returned itself at every depth, so
    ;; `[:gate :outputs :evidence-sufficient?]` came back as three
    ;; dependencies — two of which are not steps — and failed a correctly
    ;; wired graph. Depth matters.
    (is (= #{:gate}
           (init/condition-step-deps
             {:condition (fn [_ outs] (get-in outs [:gate :outputs :evidence-sufficient?]))})))))

(deftest probe-does-not-short-circuit
  (testing "a condition reading two steps behind `and` reports both"
    ;; A probe returning nil would let `and` short-circuit on the first lookup,
    ;; report only {:a}, and pass a step whose :b dependency is unordered.
    (is (= #{:a :b}
           (init/condition-step-deps
             {:condition (fn [_ outs]
                           (and (get-in outs [:a :outputs :x])
                                (get-in outs [:b :outputs :y])))})))))

(deftest probe-reports-opaque-rather-than-empty
  (testing "an unprobeable condition is :opaque, never silently dependency-free"
    (is (= :opaque
           (init/condition-step-deps
             {:condition (fn [_ _] (throw (ex-info "cannot probe me" {})))})))))

(deftest declared-condition-deps-win-over-probing
  (testing "an author can declare what an unprobeable condition reads"
    (is (= #{:gate}
           (init/condition-step-deps
             {:condition-deps [:gate]
              :condition (fn [_ _] (throw (ex-info "opaque" {})))})))))

(defn- fn-object-paths
  "Every path in `x` whose value is a function object."
  [x]
  (let [acc (atom [])]
    ((fn walk [node path]
       (cond
         (fn? node) (swap! acc conj path)
         (map? node) (doseq [[k v] node] (walk v (conj path k)))
         (sequential? node) (doseq [[i v] (map-indexed vector node)] (walk v (conj path i)))
         :else nil))
     x [])
    @acc))

(deftest live-registry-holds-no-function-objects
  (testing "No registered graph may hold a function object anywhere (#348)"
    ;; This is the CLASS guard, not the instance one. A function in a graph is
    ;; unserializable, and the raw registry crosses the Electric server->client
    ;; boundary at `digdir.skills.ui/SkillGraphsList` — one such object closed
    ;; the websocket and rendered the Skill Graphs admin screen as a blank white
    ;; page, deterministically. Since #350 removed the mode-listing endpoints,
    ;; that screen is the only way an operator can see which modes exist, so
    ;; this is not cosmetic.
    ;;
    ;; Express conditions as input-refs (`[:step-id :key]`) rather than `fn`s.
    (let [offenders (->> (templates-core/list-skill-graphs)
                         (keep (fn [g]
                                 (when-let [paths (seq (fn-object-paths g))]
                                   {:graph (:id g) :paths (vec paths)})))
                         vec)]
      (is (= [] offenders)
          (str "Function object(s) in the registry, which cannot cross the "
               "Electric boundary: " (pr-str offenders))))))

(deftest vector-conditions-evaluate-like-input-refs
  (testing "a [:step-id :key] condition reads THAT key, not the whole map (#348)"
    (let [step {:id :overview :condition [:gate :evidence-sufficient?]}]
      (is (true? (runner/should-execute-step?
                   step {} {:gate {:outputs {:evidence-sufficient? true}}})))
      (is (false? (runner/should-execute-step?
                    step {} {:gate {:outputs {:evidence-sufficient? false}}})))
      (is (false? (runner/should-execute-step?
                    step {} {:gate {:outputs {}}}))
          "absent key is falsey, not an error")))
  (testing "why a bare keyword could NOT express this, which is why a fn was used"
    ;; `:gate` resolves to the step's whole outputs MAP, and a map is truthy —
    ;; so the overview would have run even when the gate declined. The
    ;; declarative slot could not say what was meant until #348 widened it.
    (is (true? (runner/should-execute-step?
                 {:id :overview :condition :gate}
                 {} {:gate {:outputs {:evidence-sufficient? false}}})))))

(deftest ai-overview-condition-gates-on-the-gate-decision
  (testing "the SHIPPED graph's condition, both ways (#348)"
    ;; Taken from the real graph rather than hand-built, so a mistyped ref key
    ;; fails here. The isolated `should-execute-step?` test above cannot catch
    ;; that: it supplies its own ref. The behaviour this protects is the decline
    ;; — the overview must NOT run when the gate found evidence thin, which is
    ;; the whole point of the step's condition (#240).
    (let [step (->> overview/ai-overview-graph :steps
                    (filter #(= :overview (:id %)))
                    first)]
      (is (= [:gate :evidence-sufficient?] (:condition step))
          "declarative ref, not a function object")
      (is (true? (runner/should-execute-step?
                   step {} {:gate {:outputs {:evidence-sufficient? true}}}))
          "runs when the gate passed")
      (is (false? (runner/should-execute-step?
                    step {} {:gate {:outputs {:evidence-sufficient? false}}}))
          "declines when the gate declined"))))

(deftest vector-conditions-are-transparent-to-the-ordering-guard
  (testing "the condition guard sees a vector ref without probing (#348)"
    (is (= #{:gate} (init/condition-step-deps {:condition [:gate :evidence-sufficient?]})))
    (is (= #{} (init/condition-step-deps {:condition [:$enabled :flag]}))
        "a $-prefixed head is a graph input and depends on no step")))

(deftest keyword-conditions-resolve-like-input-refs
  (testing "a $-prefixed keyword is a graph input and depends on no step"
    (is (= #{} (init/condition-step-deps {:condition :$enabled}))))
  (testing "any other keyword names a step"
    (is (= #{:gate} (init/condition-step-deps {:condition :gate})))))

;; =============================================================================
;; The condition guard fires — known-answer control
;; =============================================================================

(def ^:private broken-overview
  "The shape :builtin/ai-overview actually shipped with first: :overview takes
   its context from :rerank and leans on the :condition alone, so nothing
   orders :gate before it. The sort produces
   [:plan :retrieve :rerank :overview :gate :finalize]."
  {:id :test/broken-overview
   :graph (update overview/ai-overview-graph :steps
                  (fn [steps]
                    (mapv (fn [step]
                            (if (= :overview (:id step))
                              (assoc-in step [:inputs :context-docs] [:rerank :context-docs])
                              step))
                          steps)))})

(deftest condition-guard-fires-on-the-shape-that-shipped-broken
  (let [problems (init/condition-problems-for-graph broken-overview)]
    (is (= 1 (count problems)))
    (is (= :condition-dep-not-consumed (:reason (first problems))))
    (is (= :overview (:step (first problems))))))

(deftest condition-guard-passes-the-shipped-graph
  (is (empty? (init/condition-problems-for-graph
                {:id :builtin/ai-overview :graph overview/ai-overview-graph}))))

(deftest condition-guard-fails-on-an-opaque-condition
  (testing "unprobeable must fail, not pass — otherwise the guard is counted as
            coverage while covering nothing"
    (let [problems (init/condition-problems-for-graph
                     {:id :test/opaque
                      :graph {:inputs [:q] :outputs [:r]
                              :steps [{:id :a :skill :builtin/retrieval :inputs {}}
                                      {:id :b :skill :builtin/synthesis :inputs {}
                                       :condition (fn [_ _] (throw (ex-info "nope" {})))}]}})]
      (is (= 1 (count problems)))
      (is (= :opaque-condition (:reason (first problems)))))))

(deftest condition-guard-accepts-a-transitive-dependency
  (testing "a dependency two steps upstream is still guaranteed"
    (is (empty? (init/condition-problems-for-graph
                  {:id :test/transitive
                   :graph {:inputs [:q] :outputs [:r]
                           :steps [{:id :a :skill :builtin/retrieval :inputs {}}
                                   {:id :b :skill :builtin/rerank :inputs {:chunks [:a :chunks]}}
                                   {:id :c :skill :builtin/synthesis
                                    :inputs {:context-docs [:b :context-docs]}
                                    ;; reads :a, consumes :b, and :b consumes :a
                                    :condition (fn [_ outs] (get-in outs [:a :outputs :chunks]))}]}})))))

;; =============================================================================
;; The declared-outputs guard fires — known-answer control
;; =============================================================================

(def ^:private graph-with-orphan-output
  {:id :test/orphan-output
   :graph {:inputs [:user-query]
           :outputs [:chunks :search-phrases]
           :steps [{:id :retrieve :skill :builtin/retrieval :inputs {}}]}})

(deftest declared-outputs-guard-fires-on-an-orphan
  (testing ":search-phrases is query-planner's pre-rename name — the real
            :docs/outline-graph defect"
    (let [problem (init/declared-output-problems-for-graph graph-with-orphan-output #{})]
      (is (some? problem))
      (is (= [:search-phrases] (:unexpected problem)))
      (is (empty? (:stale-exemptions problem))))))

(deftest declared-outputs-guard-honours-an-exemption
  (is (nil? (init/declared-output-problems-for-graph
              graph-with-orphan-output #{:search-phrases}))))

(deftest declared-outputs-guard-rejects-a-stale-exemption
  (testing "an exemption that no longer excuses anything must fail, or the list
            outlives the problem and blesses whatever reuses the name"
    (let [problem (init/declared-output-problems-for-graph
                    {:id :test/clean
                     :graph {:inputs [:user-query]
                             :outputs [:chunks]
                             :steps [{:id :retrieve :skill :builtin/retrieval :inputs {}}]}}
                    #{:long-since-fixed})]
      (is (some? problem))
      (is (empty? (:unexpected problem)))
      (is (= [:long-since-fixed] (:stale-exemptions problem))))))
