(ns digdir.skills.builtin.agent.ablation-test
  "#390 part (1): the ablation flag must be INERT unless asked for, and must
   actually disable the named stage. A flag that silently does nothing would
   produce an ablation arm identical to the control and we would report 'this
   step changes nothing' — the strongest possible wrong answer to the question
   being asked."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.iteration-bundled :as ib]))

(defn- ctx [stages]
  {:opts {:skill-params {:builtin/agent (cond-> {} stages (assoc :ablate-stages stages))}}})

(deftest ablation-is-inert-by-default
  ;; The direction that matters most: every normal run must be unaffected.
  (testing "no skill-params at all"
    (is (false? (#'ib/ablated? {} :read-signal-eval)))
    (is (false? (#'ib/ablated? {:opts {}} :sufficiency-gate))))
  (testing "skill-params present but no ablate-stages key"
    (is (false? (#'ib/ablated? (ctx nil) :read-signal-eval))))
  (testing "an empty ablation set disables nothing"
    (is (false? (#'ib/ablated? (ctx #{}) :read-signal-eval)))))

(deftest ablation-disables-only-the-named-stage
  (let [c (ctx #{:read-signal-eval})]
    (is (true?  (#'ib/ablated? c :read-signal-eval)))
    (is (false? (#'ib/ablated? c :sufficiency-gate))
        "ablating one stage must not disable its neighbours — otherwise an arm
         attributes another stage's effect to the one under test")
    (is (false? (#'ib/ablated? c :response-validation))))
  (testing "several at once"
    (let [c (ctx #{:sufficiency-gate :response-validation})]
      (is (true? (#'ib/ablated? c :sufficiency-gate)))
      (is (true? (#'ib/ablated? c :response-validation)))
      (is (false? (#'ib/ablated? c :read-signal-eval)))))
  (testing "accepts a vector as well as a set, since matrix EDN round-trips"
    (is (true? (#'ib/ablated? (ctx [:read-signal-eval]) :read-signal-eval)))))
