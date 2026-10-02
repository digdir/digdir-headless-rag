(ns digdir.skills.graph.step-parameters-pin-test
  "The VALUES `resolve-step-parameters` produces today, pinned before
   Phase 0 adds provenance to them.

   Phase 0 promises no behaviour change: `resolve-step-parameters` keeps its
   plain-map return, and a `-with-trace` sibling adds where each value came from.
   This namespace is the evidence for that promise. It must pass UNCHANGED
   across Phase 0 — if it has to be edited in the same diff, the diff has
   changed values.

   ## No `merge` oracle

   Phase 0's own test keeps the old `merge` form as its oracle. This one
   deliberately does not: an oracle built from the implementation agrees with
   the implementation however it changes. The cases here are literal, and the
   grid below states the rule as layer precedence, not as `merge`.

   The four layers, low -> high (`runner.clj:146-150`):

     1  (:parameters step)                                  — any key
     2  (select-keys skill-params [:model :temperature :max-tokens :prompt])
     3  (get skill-params <skill-id>)                       — any key
     4  (select-keys execution-opts [:model :temperature :max-tokens :prompt])

   A key a layer CONTAINS wins over the layers below it — including an explicit
   nil, which then reaches the skill as nil and triggers the skill's own
   fallback. That is today's behaviour, pinned, not endorsed."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.context :as ctx]
            [digdir.skills.graph.runner :as runner]))

(def ^:private skill-id :pin/skill)

(defn- resolve*
  "Resolve for a step running `skill-id`. `step-params` are the graph step's
   :parameters; `skill-params` and `opts` form execution-opts."
  ([step-params] (resolve* step-params {} {}))
  ([step-params skill-params] (resolve* step-params skill-params {}))
  ([step-params skill-params opts]
   (runner/resolve-step-parameters
    (cond-> {:id :s1 :skill skill-id}
      (some? step-params) (assoc :parameters step-params))
    (assoc opts :skill-params skill-params))))

;; ---------------------------------------------------------------------------
;; Literal cases
;; ---------------------------------------------------------------------------

(deftest each-layer-wins-over-the-ones-below-it
  (is (= {:model "step" :temperature 0.2}
         (resolve* {:model "step" :temperature 0.2}))
      "layer 1 alone")
  (is (= {:model "common" :temperature 0.2}
         (resolve* {:model "step" :temperature 0.2} {:model "common"}))
      "layer 2 over layer 1, key by key")
  (is (= {:model "per-skill" :temperature 0.2}
         (resolve* {:model "step" :temperature 0.2} {:model "common" skill-id {:model "per-skill"}}))
      "layer 3 over layer 2")
  (is (= {:model "override" :temperature 0.2}
         (resolve* {:model "step" :temperature 0.2}
                   {:model "common" skill-id {:model "per-skill"}}
                   {:model "override"}))
      "layer 4 over layer 3"))

(deftest an-explicit-nil-at-a-higher-layer-wins
  (testing "The skill then receives :model nil and falls back to the tenant default —
            the graph step's model is silently discarded."
    (let [r (resolve* {:model "step"} {} {:model nil})]
      (is (contains? r :model) "the key is present")
      (is (nil? (:model r)) "and its value is nil"))
    (is (nil? (:model (resolve* {:model "step"} {:model nil}))) "the same from layer 2")
    (is (nil? (:model (resolve* {:model "step"} {skill-id {:model nil}}))) "and from layer 3")))

(deftest a-key-no-layer-sets-is-absent-not-nil
  (let [r (resolve* {:model "step"})]
    (is (not (contains? r :max-tokens)))
    (is (not (contains? r :temperature)))))

(deftest the-common-layer-carries-only-four-keys
  (let [r (resolve* {} {:model "m" :temperature 0.1 :max-tokens 10 :prompt "p"
                        :top-k 5 :tenant "t" :style :brief})]
    (is (= {:model "m" :temperature 0.1 :max-tokens 10 :prompt "p"} r)
        ":top-k, :tenant and anything else top-level in skill-params do not reach the skill")))

(deftest the-per-skill-layer-carries-any-key
  (is (= {:top-k 7 :style :brief :model "ps"}
         (resolve* {} {skill-id {:top-k 7 :style :brief :model "ps"}}))))

(deftest the-per-skill-layer-is-keyed-by-this-steps-skill
  (is (= {:model "step"} (resolve* {:model "step"} {:other/skill {:model "wrong"}}))))

(deftest execution-overrides-carry-only-four-keys
  (is (= {:temperature 0.9}
         (resolve* {} {} {:temperature 0.9 :top-k 3 :tenant "t" :style :brief}))))

(deftest the-step-layer-carries-any-key
  (is (= {:style :brief :bullet-points true}
         (resolve* {:style :brief :bullet-points true}))))

(deftest a-step-with-no-parameters-resolves-to-an-empty-map
  (is (= {} (resolve* nil))))

;; ---------------------------------------------------------------------------
;; The precedence grid, for the trio
;; ---------------------------------------------------------------------------

(def ^:private states [:absent :value :nil])

(defn- layer-map [layer k state]
  (case state
    :absent {}
    :nil {k nil}
    :value {k (str (name layer) "-" (name k))}))

(deftest precedence-grid
  (testing "Every combination of {absent, value, nil} across the four layers, for
            :model :temperature :max-tokens. Expected: the HIGHEST layer that
            contains the key supplies it (value or nil); no layer -> absent."
    (doseq [k [:model :temperature :max-tokens]
            s1 states, s2 states, s3 states, s4 states]
      (let [layers [[:step s1] [:common s2] [:per-skill s3] [:override s4]]
            winner (last (remove #(= :absent (second %)) layers))
            expected (if winner
                       {k (get (layer-map (first winner) k (second winner)) k)}
                       {})
            actual (resolve* (layer-map :step k s1)
                             (merge (layer-map :common k s2)
                                    {skill-id (layer-map :per-skill k s3)})
                             (layer-map :override k s4))]
        (is (= expected actual) (str k " " [s1 s2 s3 s4]))))))

;; ---------------------------------------------------------------------------
;; The skill receives exactly what was resolved
;; ---------------------------------------------------------------------------

(deftest execute-step-hands-the-skill-exactly-the-resolved-parameters
  (testing "Phase 0 edits execute-step to record provenance. The skill must still
            receive the same map — not the trace, not a {value, source} pair."
    (let [seen (atom ::not-called)
          step {:id :s1 :skill skill-id :parameters {:model "step" :style :brief}}
          opts {:tenant "t" :skill-params {:temperature 0.4 skill-id {:top-k 3}} :max-tokens 50}]
      (with-redefs [ctx/build-execution-context (fn [_skill-id _inputs o] (reset! seen (:parameters o)) {})
                    skills/execute-skill (fn [_ _] (skills/success-result {}))]
        (runner/execute-step step {} opts))
      (is (= {:model "step" :style :brief :temperature 0.4 :top-k 3 :max-tokens 50} @seen))
      (is (= @seen (runner/resolve-step-parameters step opts))))))
