(ns digdir.skills.graph.optimizer-test
  "Tests for graph optimizer functionality.

   These tests verify:
   - Dependency analysis
   - Parallel execution level computation
   - Critical path analysis
   - Execution plan generation
   - Optimization suggestions"
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.skills.graph.optimizer :as opt]))

;; =============================================================================
;; Test Fixtures - Sample Graphs
;; =============================================================================

(def linear-graph
  "A -> B -> C (fully sequential)"
  {:inputs [:query]
   :outputs [:result]
   :steps [{:id :a :skill :test/a :inputs {:q :$query}}
           {:id :b :skill :test/b :inputs {:x :a}}
           {:id :c :skill :test/c :inputs {:y :b}}]})

(def parallel-graph
  "A and B run in parallel, C depends on both"
  {:inputs [:query]
   :outputs [:result]
   :steps [{:id :a :skill :test/a :inputs {:q :$query}}
           {:id :b :skill :test/b :inputs {:q :$query}}
           {:id :c :skill :test/c :inputs {:x :a :y :b}}]})

(def diamond-graph
  "A -> B, A -> C, B -> D, C -> D (diamond pattern)"
  {:inputs [:query]
   :outputs [:result]
   :steps [{:id :a :skill :test/a :inputs {:q :$query}}
           {:id :b :skill :test/b :inputs {:x :a}}
           {:id :c :skill :test/c :inputs {:x :a}}
           {:id :d :skill :test/d :inputs {:y :b :z :c}}]})

(def wide-graph
  "A, B, C, D all independent (max parallelization)"
  {:inputs [:q1 :q2 :q3 :q4]
   :outputs [:r1 :r2 :r3 :r4]
   :steps [{:id :a :skill :test/a :inputs {:q :$q1}}
           {:id :b :skill :test/b :inputs {:q :$q2}}
           {:id :c :skill :test/c :inputs {:q :$q3}}
           {:id :d :skill :test/d :inputs {:q :$q4}}]})

(def complex-graph
  "Multi-level graph with varying parallelism:
   Level 0: A, B (parallel)
   Level 1: C (depends on A), D (depends on B)
   Level 2: E (depends on C and D)
   Level 3: F (depends on E)"
  {:inputs [:input]
   :outputs [:output]
   :steps [{:id :a :skill :test/a :inputs {:q :$input}}
           {:id :b :skill :test/b :inputs {:q :$input}}
           {:id :c :skill :test/c :inputs {:x :a}}
           {:id :d :skill :test/d :inputs {:x :b}}
           {:id :e :skill :test/e :inputs {:x :c :y :d}}
           {:id :f :skill :test/f :inputs {:x :e}}]})

(def single-step-graph
  "Single step graph"
  {:inputs [:query]
   :outputs [:result]
   :steps [{:id :only :skill :test/only :inputs {:q :$query}}]})

;; =============================================================================
;; Dependency Analysis Tests
;; =============================================================================

(deftest test-build-dependency-map
  (testing "Linear graph dependencies"
    (let [deps (opt/build-dependency-map linear-graph)]
      (is (= #{} (get deps :a)))
      (is (= #{:a} (get deps :b)))
      (is (= #{:b} (get deps :c)))))

  (testing "Parallel graph dependencies"
    (let [deps (opt/build-dependency-map parallel-graph)]
      (is (= #{} (get deps :a)))
      (is (= #{} (get deps :b)))
      (is (= #{:a :b} (get deps :c)))))

  (testing "Diamond graph dependencies"
    (let [deps (opt/build-dependency-map diamond-graph)]
      (is (= #{} (get deps :a)))
      (is (= #{:a} (get deps :b)))
      (is (= #{:a} (get deps :c)))
      (is (= #{:b :c} (get deps :d))))))

(deftest test-build-reverse-dependency-map
  (testing "Linear graph reverse dependencies"
    (let [rev-deps (opt/build-reverse-dependency-map linear-graph)]
      (is (= #{:b} (get rev-deps :a)))
      (is (= #{:c} (get rev-deps :b)))
      (is (= #{} (get rev-deps :c)))))

  (testing "Diamond graph reverse dependencies"
    (let [rev-deps (opt/build-reverse-dependency-map diamond-graph)]
      (is (= #{:b :c} (get rev-deps :a)))
      (is (= #{:d} (get rev-deps :b)))
      (is (= #{:d} (get rev-deps :c)))
      (is (= #{} (get rev-deps :d))))))

(deftest test-get-step-depth
  (testing "Linear graph depths"
    (let [depths (opt/get-step-depth linear-graph)]
      (is (= 0 (get depths :a)))
      (is (= 1 (get depths :b)))
      (is (= 2 (get depths :c)))))

  (testing "Parallel graph depths"
    (let [depths (opt/get-step-depth parallel-graph)]
      (is (= 0 (get depths :a)))
      (is (= 0 (get depths :b)))
      (is (= 1 (get depths :c)))))

  (testing "Diamond graph depths"
    (let [depths (opt/get-step-depth diamond-graph)]
      (is (= 0 (get depths :a)))
      (is (= 1 (get depths :b)))
      (is (= 1 (get depths :c)))
      (is (= 2 (get depths :d)))))

  (testing "Wide graph depths (all zero)"
    (let [depths (opt/get-step-depth wide-graph)]
      (is (every? zero? (vals depths))))))

;; =============================================================================
;; Parallel Execution Level Tests
;; =============================================================================

(deftest test-compute-execution-levels
  (testing "Linear graph has one step per level"
    (let [levels (opt/compute-execution-levels linear-graph)]
      (is (= 3 (count levels)))
      (is (= 1 (count (nth levels 0))))
      (is (= 1 (count (nth levels 1))))
      (is (= 1 (count (nth levels 2))))
      (is (= :a (first (nth levels 0))))
      (is (= :b (first (nth levels 1))))
      (is (= :c (first (nth levels 2))))))

  (testing "Parallel graph groups independent steps"
    (let [levels (opt/compute-execution-levels parallel-graph)]
      (is (= 2 (count levels)))
      (is (= 2 (count (nth levels 0))))
      (is (= #{:a :b} (set (nth levels 0))))
      (is (= [:c] (nth levels 1)))))

  (testing "Diamond graph has correct levels"
    (let [levels (opt/compute-execution-levels diamond-graph)]
      (is (= 3 (count levels)))
      (is (= [:a] (nth levels 0)))
      (is (= #{:b :c} (set (nth levels 1))))
      (is (= [:d] (nth levels 2)))))

  (testing "Wide graph all in one level"
    (let [levels (opt/compute-execution-levels wide-graph)]
      (is (= 1 (count levels)))
      (is (= 4 (count (first levels))))))

  (testing "Complex graph levels"
    (let [levels (opt/compute-execution-levels complex-graph)]
      (is (= 4 (count levels)))
      (is (= #{:a :b} (set (nth levels 0))))
      (is (= #{:c :d} (set (nth levels 1))))
      (is (= [:e] (nth levels 2)))
      (is (= [:f] (nth levels 3))))))

(deftest test-get-parallelization-factor
  (testing "Linear graph has factor 1"
    (is (= 1 (opt/get-parallelization-factor linear-graph))))

  (testing "Parallel graph has factor 2"
    (is (= 2 (opt/get-parallelization-factor parallel-graph))))

  (testing "Wide graph has factor 4"
    (is (= 4 (opt/get-parallelization-factor wide-graph))))

  (testing "Diamond graph has factor 2"
    (is (= 2 (opt/get-parallelization-factor diamond-graph))))

  (testing "Complex graph has factor 2"
    (is (= 2 (opt/get-parallelization-factor complex-graph)))))

;; =============================================================================
;; Critical Path Tests
;; =============================================================================

(deftest test-compute-critical-path
  (testing "Linear graph critical path is entire graph"
    (let [path (opt/compute-critical-path linear-graph)]
      (is (= [:a :b :c] path))))

  (testing "Parallel graph critical path"
    (let [path (opt/compute-critical-path parallel-graph)]
      (is (= 2 (count path)))
      (is (contains? #{:a :b} (first path)))
      (is (= :c (last path)))))

  (testing "Diamond graph critical path"
    (let [path (opt/compute-critical-path diamond-graph)]
      (is (= 3 (count path)))
      (is (= :a (first path)))
      (is (= :d (last path)))))

  (testing "Single step critical path"
    (let [path (opt/compute-critical-path single-step-graph)]
      (is (= [:only] path)))))

;; =============================================================================
;; Output Dependency Analysis Tests
;; =============================================================================

(deftest test-find-required-outputs
  (testing "Linear graph output dependencies"
    (let [required (opt/find-required-outputs linear-graph)]
      ;; :a is referenced by :b, :b is referenced by :c
      (is (contains? required :a))
      (is (contains? required :b))))

  (testing "Parallel graph output dependencies"
    (let [required (opt/find-required-outputs parallel-graph)]
      ;; Both :a and :b are referenced by :c
      (is (contains? required :a))
      (is (contains? required :b)))))

(deftest test-find-unused-steps
  (testing "Linear graph has unused last step"
    (let [unused (opt/find-unused-steps linear-graph)]
      ;; :c has no dependents (it's the final step)
      (is (contains? unused :c))))

  (testing "Parallel graph unused steps"
    (let [unused (opt/find-unused-steps parallel-graph)]
      ;; :c is the final step with no dependents
      (is (contains? unused :c))))

  (testing "Wide graph all unused"
    (let [unused (opt/find-unused-steps wide-graph)]
      ;; All steps are independent with no dependents
      (is (= 4 (count unused))))))

;; =============================================================================
;; Execution Plan Tests
;; =============================================================================

(deftest test-create-execution-plan
  (testing "Execution plan for linear graph"
    (let [plan (opt/create-execution-plan linear-graph)]
      (is (= 3 (:total-steps plan)))
      (is (= 1 (:parallelization plan)))
      (is (= 3 (count (:levels plan))))
      (is (= [:a :b :c] (:critical-path plan)))))

  (testing "Execution plan for complex graph"
    (let [plan (opt/create-execution-plan complex-graph)]
      (is (= 6 (:total-steps plan)))
      (is (= 2 (:parallelization plan)))
      (is (= 4 (count (:levels plan)))))))

(deftest test-get-ready-steps
  (testing "Initial ready steps"
    (let [plan (opt/create-execution-plan diamond-graph)]
      (is (= #{:a} (opt/get-ready-steps plan #{})))))

  (testing "Ready steps after :a completes"
    (let [plan (opt/create-execution-plan diamond-graph)]
      (is (= #{:b :c} (opt/get-ready-steps plan #{:a})))))

  (testing "Ready steps after :a, :b complete"
    (let [plan (opt/create-execution-plan diamond-graph)]
      (is (= #{:c} (opt/get-ready-steps plan #{:a :b})))))

  (testing "Ready steps after :a, :b, :c complete"
    (let [plan (opt/create-execution-plan diamond-graph)]
      (is (= #{:d} (opt/get-ready-steps plan #{:a :b :c})))))

  (testing "No ready steps when all complete"
    (let [plan (opt/create-execution-plan diamond-graph)]
      (is (= #{} (opt/get-ready-steps plan #{:a :b :c :d}))))))

(deftest test-estimate-parallel-speedup
  (testing "Linear graph speedup is 1x"
    (let [plan (opt/create-execution-plan linear-graph)
          speedup (opt/estimate-parallel-speedup plan)]
      (is (= 3 (:sequential-steps speedup)))
      (is (= 3 (:parallel-levels speedup)))
      (is (= 1.0 (:theoretical-speedup speedup)))))

  (testing "Wide graph has high speedup"
    (let [plan (opt/create-execution-plan wide-graph)
          speedup (opt/estimate-parallel-speedup plan)]
      (is (= 4 (:sequential-steps speedup)))
      (is (= 1 (:parallel-levels speedup)))
      (is (= 4.0 (:theoretical-speedup speedup)))))

  (testing "Complex graph speedup"
    (let [plan (opt/create-execution-plan complex-graph)
          speedup (opt/estimate-parallel-speedup plan)]
      (is (= 6 (:sequential-steps speedup)))
      (is (= 4 (:parallel-levels speedup)))
      (is (= 1.5 (:theoretical-speedup speedup))))))

;; =============================================================================
;; Graph Analysis Tests
;; =============================================================================

(deftest test-analyze-graph
  (testing "Analyze linear graph"
    (let [analysis (opt/analyze-graph linear-graph)]
      (is (some? (:execution-plan analysis)))
      (is (= 3 (get-in analysis [:analysis :total-steps])))
      (is (= 1 (get-in analysis [:analysis :max-parallelization])))
      (is (= 3 (get-in analysis [:analysis :critical-path-length])))
      ;; Should suggest graph is linear
      (is (some #(= :linear-graph (:type %)) (:suggestions analysis)))))

  (testing "Analyze parallel graph"
    (let [analysis (opt/analyze-graph parallel-graph)]
      (is (= 2 (get-in analysis [:analysis :max-parallelization])))
      ;; Should suggest parallelization benefit
      (is (some #(= :parallelization (:type %)) (:suggestions analysis)))))

  (testing "Analyze wide graph"
    (let [analysis (opt/analyze-graph wide-graph)]
      (is (= 4 (get-in analysis [:analysis :max-parallelization])))
      (is (= 4.0 (get-in analysis [:analysis :theoretical-speedup])))
      ;; All steps are unused (no downstream consumers)
      (is (= 4 (count (get-in analysis [:analysis :unused-steps])))))))

;; =============================================================================
;; Execution Levels Sequence Tests
;; =============================================================================

(deftest test-execution-levels-seq
  (testing "Iteration over execution levels"
    (let [plan (opt/create-execution-plan diamond-graph)
          levels (opt/execution-levels-seq plan)]
      (is (seq? levels))
      (is (= 3 (count levels)))
      (is (= [:a] (first levels)))
      (is (= #{:b :c} (set (second levels))))
      (is (= [:d] (nth levels 2))))))
