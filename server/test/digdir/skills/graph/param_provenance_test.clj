(ns digdir.skills.graph.param-provenance-test
  "Phase 0 of the provider-resolver change: the graph runner records WHICH layer supplied each step
   parameter, and attributes every LLM call to the step that made it.

   `resolve-step-parameters` merges four layers — the step's own `:parameters`,
   the common skill-params, the per-skill params, and the execution override —
   and before this recorded none of them. Rewriting the provider fallback that
   sits BELOW that merge (Phase 1) is unverifiable without knowing, per step,
   which layer won and what actually reached the wire.

   Two halves, collected per step and reported UP as one `:graph` event into
   whatever `provenance/capture` encloses the run — never on the returned
   result, whose `:execution-metadata` reaches MCP clients (mcp/tools.clj):
   - a `:parameters` event from `resolve-step-parameters-with-trace`, one per
     `execute-step` (so one per foreach/loop iteration);
   - an `:llm-call` event per call, recorded at the client chokepoints. Here a
     mock skill records a fake one via `provenance/record!`, which tests the
     attribution plumbing without an LLM client."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.llm.provenance :as provenance]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.context :as ctx]
            [digdir.rag.skills.core :as skills]))

;; =============================================================================
;; resolve-step-parameters-with-trace — the runner half
;; =============================================================================

(def ^:private layers
  "Low → high precedence, the order of the merge in `resolve-step-parameters`."
  [:graph-step :skill-params/common :skill-params/per-skill :execution-override])

(defn- inputs-for
  "Build `[step execution-opts]` putting `layer->params` into the named layers
   for skill `:test/llm`."
  [layer->params]
  [{:id :s :skill :test/llm :parameters (get layer->params :graph-step {})}
   (merge (get layer->params :execution-override {})
          {:skill-params (merge (get layer->params :skill-params/common {})
                                (when-let [p (get layer->params :skill-params/per-skill)]
                                  {:test/llm p}))})])

(defn- merge-oracle
  "The resolution as it was written before Phase 0, verbatim. Kept here as the
   oracle so the with-trace rewrite is checked against the original form, not
   against itself."
  [step execution-opts]
  (let [skill-id (:skill step)
        skill-params (:skill-params execution-opts)
        common-params (select-keys skill-params [:model :temperature :max-tokens :prompt])
        per-skill-params (get skill-params skill-id)
        execution-overrides (select-keys execution-opts [:model :temperature :max-tokens :prompt])]
    (merge (:parameters step) common-params per-skill-params execution-overrides)))

(def ^:private layer-states
  "Each layer either lacks :model, sets it to a value naming the layer, or sets
   it to an explicit nil."
  [::absent ::value ::nil])

(defn- model-grid
  "Every combination of the three states across the four layers (3^4 = 81)."
  []
  (for [a layer-states b layer-states c layer-states d layer-states]
    (into {}
          (keep (fn [[layer state]]
                  (case state
                    ::absent nil
                    ::value [layer {:model (name layer)}]
                    ::nil [layer {:model nil}]))
                (map vector layers [a b c d])))))

(deftest with-trace-value-is-exactly-the-plain-resolution
  (testing "across all 81 layer combinations for :model, :value equals the pre-Phase-0 merge"
    (let [grid (model-grid)]
      (is (= 81 (count grid)) "the grid itself must be the size it claims")
      (doseq [layer->params grid
              :let [[step opts] (inputs-for layer->params)]]
        (is (= (merge-oracle step opts)
               (:value (runner/resolve-step-parameters-with-trace step opts)))
            (pr-str layer->params))
        (is (= (merge-oracle step opts)
               (runner/resolve-step-parameters step opts))
            (str "plain fn drifted for " (pr-str layer->params))))))

  (testing "the plain fn still returns today's map for a representative mixed case (literal, not oracle)"
    (let [[step opts] (inputs-for {:graph-step {:model "g" :temperature 0.1 :top-k 5}
                                   :skill-params/common {:max-tokens 100 :prompt "p"}
                                   :skill-params/per-skill {:temperature 0.5}
                                   :execution-override {:model "x"}})]
      (is (= {:model "x" :temperature 0.5 :top-k 5 :max-tokens 100 :prompt "p"}
             (runner/resolve-step-parameters step opts))))))

(deftest winning-layer-is-the-highest-layer-containing-the-key
  (testing "each layer, alone, wins"
    (doseq [layer layers
            :let [[step opts] (inputs-for {layer {:model "m"}})
                  t (runner/resolve-step-parameters-with-trace step opts)]]
      (is (= layer (get-in t [:trace :model :winning-layer])) (str layer))
      (is (= [layer] (get-in t [:trace :model :layers])) (str layer))))

  (testing "all four set: the override wins and every layer is listed low → high"
    (let [[step opts] (inputs-for (zipmap layers (map (fn [l] {:model (name l)}) layers)))
          t (runner/resolve-step-parameters-with-trace step opts)]
      (is (= :execution-override (get-in t [:trace :model :winning-layer])))
      (is (= layers (get-in t [:trace :model :layers])))
      (is (= "execution-override" (get-in t [:value :model])))))

  (testing "graph step and per-skill set: per-skill wins over the graph step"
    (let [[step opts] (inputs-for {:graph-step {:temperature 0.1}
                                   :skill-params/per-skill {:temperature 0.7}})
          t (runner/resolve-step-parameters-with-trace step opts)]
      (is (= :skill-params/per-skill (get-in t [:trace :temperature :winning-layer])))
      (is (= [:graph-step :skill-params/per-skill] (get-in t [:trace :temperature :layers])))
      (is (= 0.7 (get-in t [:value :temperature]))))))

(deftest explicit-nil-wins-the-source
  ;; a higher layer that sets :model to nil wins the
  ;; source; the skill's fallback then supplies the value, recorded at the wire.
  (testing "{:model nil} in the override beats a value below it"
    (let [[step opts] (inputs-for {:graph-step {:model "g"}
                                   :execution-override {:model nil}})
          t (runner/resolve-step-parameters-with-trace step opts)]
      (is (= :execution-override (get-in t [:trace :model :winning-layer])))
      (is (contains? (:value t) :model) "the key is present …")
      (is (nil? (get-in t [:value :model])) "… and nil, exactly as merge leaves it"))))

(deftest the-llm-trio-is-always-reported
  (testing "with no layer setting anything, :model/:temperature/:max-tokens are traced as unset"
    (let [[step opts] (inputs-for {})
          t (runner/resolve-step-parameters-with-trace step opts)]
      (is (= {} (:value t)))
      (doseq [k [:model :temperature :max-tokens]]
        (is (= {:winning-layer nil :layers []} (get-in t [:trace k])) (str k))))))

(deftest other-keys-are-traced-and-prompt-carries-no-value
  (testing "an arbitrary per-skill key is traced like the trio"
    (let [[step opts] (inputs-for {:skill-params/per-skill {:top-k 7}})
          t (runner/resolve-step-parameters-with-trace step opts)]
      (is (= :skill-params/per-skill (get-in t [:trace :top-k :winning-layer])))))

  (testing ":prompt is traced by layer only — its text never enters the trace"
    (let [[step opts] (inputs-for {:skill-params/common {:prompt "SECRET-SHAPED PROMPT TEXT"}})
          t (runner/resolve-step-parameters-with-trace step opts)]
      (is (= :skill-params/common (get-in t [:trace :prompt :winning-layer])))
      (is (not (re-find #"SECRET-SHAPED" (pr-str (:trace t))))))))

;; =============================================================================
;; Attribution — events land on the step that produced them
;; =============================================================================

(defn- fake-llm-skill
  "A skill that behaves like an LLM-calling skill as far as provenance is
   concerned: it records one :llm-call per invocation, as the client
   chokepoint would, carrying the model it resolved."
  [ctx]
  (let [model (or (get-in ctx [:parameters :model]) "provider-fallback-model")]
    (provenance/record! {:event :llm-call
                         :caller {:model model}
                         :sent {:model model}})
    (skills/success-result {:out (str "ran " model)} {})))

(defn- quiet-skill [_ctx] (skills/success-result {:out "no llm"} {}))

(defn- register! []
  (skills/register-skill!
   {:metadata {:skill-id :test/llm :name "llm" :description "fake llm skill"
               :category :generation :inputs [] :outputs [:out]
               :parameters {} :required-services #{}}
    :execute fake-llm-skill})
  (skills/register-skill!
   {:metadata {:skill-id :test/quiet :name "quiet" :description "no llm"
               :category :generation :inputs [] :outputs [:out]
               :parameters {} :required-services #{}}
    :execute quiet-skill}))

(use-fixtures :each
  (fn [f]
    (skills/clear-registry!)
    (register!)
    (with-redefs [ctx/resolve-all-services (constantly {})]
      (f))
    (skills/clear-registry!)))

(def ^:private no-trace {::runner/suppress-graph-trace? true})

(defn- run-captured
  "Run `graph` inside a provenance sink. Returns the run's result and the ONE
   top-level `:graph` event it reported — the record reaches a caller only
   that way, never on the returned result."
  [graph inputs opts]
  (let [{:keys [result events]} (provenance/capture #(runner/run-graph graph inputs opts))
        graphs (filterv #(= :graph (:event %)) events)]
    (is (= 1 (count graphs)) "exactly one :graph event per top-level run")
    (is (= (count graphs) (count events)) "nothing else leaks out of the run's own per-step sinks")
    {:result result :graph (first graphs)}))

(defn- events-of [{:keys [graph]} step-id]
  (get-in graph [:child step-id]))

(deftest each-step-carries-its-parameters-then-its-calls
  (let [run (run-captured {:inputs [] :outputs [:out]
                           :steps [{:id :a :skill :test/llm :inputs {} :parameters {:model "step-model"}}
                                   {:id :b :skill :test/llm :inputs {}}]}
                          {} (merge no-trace {:tenant "t"}))]
    (testing "absolute: the record exists and has the expected shape (a differential check alone is blind to an empty capture)"
      (is (= :ok (get-in run [:graph :status])))
      (is (= #{:a :b} (set (keys (get-in run [:graph :child]))))))

    (testing "step :a — the graph-step layer supplied the model, and the call is attributed to :a"
      (let [[params call & more] (events-of run :a)]
        (is (= :parameters (:event params)))
        (is (= :test/llm (:skill-id params)))
        (is (= "step-model" (get-in params [:value :model])))
        (is (= :graph-step (get-in params [:trace :model :winning-layer])))
        (is (= :llm-call (:event call)))
        (is (= 0 (:call-index call)))
        (is (= "step-model" (get-in call [:sent :model])))
        (is (empty? more))))

    (testing "step :b — no layer set :model, so the value came from below the runner"
      (let [[params call] (events-of run :b)]
        (is (nil? (get-in params [:trace :model :winning-layer])))
        (is (= "provider-fallback-model" (get-in call [:sent :model])))))))

(deftest the-returned-result-is-unchanged
  ;; :execution-metadata is forwarded wholesale to MCP clients and persisted
  ;; with the message (mcp/tools.clj), so the record must not ride on it.
  (let [result (runner/run-graph {:inputs [] :outputs [:out]
                                  :steps [{:id :a :skill :test/llm :inputs {}}]}
                                 {} (merge no-trace {:tenant "t"}))]
    (is (= #{:outputs :step-results :execution-metadata} (set (keys result))))
    (is (= #{:total-duration-ms :steps-executed :step-timings :stage-timings}
           (set (keys (:execution-metadata result)))))))

(defn- without-timings
  "A run result with its wall-clock numbers removed, so two runs compare equal
   exactly when they did the same thing."
  [result]
  (-> result
      (update :execution-metadata dissoc :total-duration-ms)
      (update-in [:execution-metadata :step-timings] update-vals #(dissoc % :duration-ms))
      (update-in [:execution-metadata :stage-timings] #(mapv (fn [t] (dissoc t :duration-ms)) %))))

(deftest a-bound-sink-changes-nothing-about-the-run
  ;; The pin: production output is identical whether or not anyone is
  ;; capturing. The same graph, run bare and inside a sink.
  (let [graph {:inputs [] :outputs [:out]
               :steps [{:id :a :skill :test/llm :inputs {} :parameters {:model "m"}}
                       {:id :b :skill :test/quiet :inputs {}}]}
        opts (merge no-trace {:tenant "t"})
        bare (runner/run-graph graph {} opts)
        captured (:result (provenance/capture #(runner/run-graph graph {} opts)))]
    (is (seq (get-in bare [:execution-metadata :step-timings])) "absolute: there is something to compare")
    (is (= (without-timings bare) (without-timings captured)))))

(deftest a-step-with-no-llm-call-still-records-its-parameters
  (let [run (run-captured {:inputs [] :outputs [:out]
                           :steps [{:id :q :skill :test/quiet :inputs {}}]}
                          {} (merge no-trace {:tenant "t"}))]
    (is (= [:parameters] (mapv :event (events-of run :q))))))

(deftest foreach-records-parameters-per-iteration
  (let [run (run-captured {:inputs [:xs] :outputs [:out]
                           :steps [{:id :each
                                    :foreach {:over :$xs}
                                    :do {:skill :test/llm :inputs {}}
                                    :collect-as :out}]}
                          {:xs [1 2 3]} (merge no-trace {:tenant "t"}))
        evs (events-of run :each)]
    (is (= [:parameters :llm-call :parameters :llm-call :parameters :llm-call]
           (mapv :event evs)))
    (testing "call-index counts calls within the step, not events"
      (is (= [0 1 2] (keep :call-index evs))))))

(defn- register-graph! [graph-id steps]
  (require 'digdir.skills.templates.core)
  ((resolve 'digdir.skills.templates.core/register-skill-graph!)
   {:id graph-id :name "child" :description "child"
    :graph {:id graph-id :inputs [] :outputs [:out] :steps steps}}))

(defn- unregister-graph! [graph-id]
  ((resolve 'digdir.skills.templates.core/unregister-skill-graph!) graph-id))

(deftest a-sub-graph-step-nests-the-child-run-under-the-parent-step
  (let [child-id :test/provenance-child]
    (register-graph! child-id [{:id :inner :skill :test/llm :inputs {} :parameters {:model "child-model"}}])
    (try
      (let [run (run-captured {:inputs [] :outputs [:out]
                               :steps [{:id :outer :sub-graph {:graph-id child-id :inputs {}}}]}
                              {} (merge no-trace {:tenant "t"}))
            [nested & more] (events-of run :outer)]
        (is (= :graph (:event nested)))
        (is (= child-id (:graph-id nested)))
        (is (empty? more) "the child's events are nested, not also flattened into the parent")
        (is (= [:parameters :llm-call] (mapv :event (get-in nested [:child :inner]))))
        (is (= "child-model" (get-in nested [:child :inner 1 :sent :model]))))
      (finally (unregister-graph! child-id)))))

(deftest a-skill-that-runs-its-own-graph-nests-it-too
  ;; The OTHER door into a nested run: agent/core.clj calls run-graph from
  ;; inside a skill and keeps only :outputs. The record must survive that the
  ;; same way it survives a sub-graph step.
  (let [inner {:id :test/in-skill-graph :inputs [] :outputs [:out]
               :steps [{:id :deep :skill :test/llm :inputs {} :parameters {:model "deep-model"}}]}]
    (skills/register-skill!
     {:metadata {:skill-id :test/runs-a-graph :name "runs a graph" :description "like agent/core"
                 :category :generation :inputs [] :outputs [:out]
                 :parameters {} :required-services #{}}
      :execute (fn [_ctx]
                 (skills/success-result
                  (:outputs (runner/run-graph inner {} (merge no-trace {:tenant "t"})))
                  {}))})
    (let [run (run-captured {:inputs [] :outputs [:out]
                             :steps [{:id :host :skill :test/runs-a-graph :inputs {}}]}
                            {} (merge no-trace {:tenant "t"}))
          evs (events-of run :host)]
      (is (= [:parameters :graph] (mapv :event evs)))
      (is (= :test/in-skill-graph (:graph-id (second evs))))
      (is (= "deep-model" (get-in (second evs) [:child :deep 1 :sent :model]))))))

(deftest a-failed-run-still-reports-what-it-recorded
  (skills/register-skill!
   {:metadata {:skill-id :test/boom :name "boom" :description "fails"
               :category :generation :inputs [] :outputs [:out]
               :parameters {} :required-services #{}}
    :execute (fn [_ctx] (skills/error-result :test/boom "boom" {}))})
  (let [{:keys [events]} (provenance/capture
                          #(try (runner/run-graph {:inputs [] :outputs [:out]
                                                   :steps [{:id :a :skill :test/llm :inputs {}}
                                                           ;; :z consumes :a's output, so :a runs first
                                                           {:id :z :skill :test/boom :inputs {:x [:a :out]}}]}
                                                  {} (merge no-trace {:tenant "t"}))
                                (catch clojure.lang.ExceptionInfo _ ::threw)))
        [g] (filter #(= :graph (:event %)) events)]
    (is (= :error (:status g)))
    (is (= [:parameters :llm-call] (mapv :event (get-in g [:child :a]))))
    (is (= [:parameters] (mapv :event (get-in g [:child :z]))))))

(deftest record-outside-a-sink-is-a-no-op-and-capture-collects-in-order
  (testing "outside a graph run, record! neither throws nor retains anything"
    (is (nil? (provenance/record! {:event :llm-call}))))
  (testing "capture collects what is recorded inside it, in order, and returns the fn's value"
    (is (= {:result 42 :events [{:event :a} {:event :b}]}
           (provenance/capture (fn []
                                 (provenance/record! {:event :a})
                                 (provenance/record! {:event :b})
                                 42))))))
