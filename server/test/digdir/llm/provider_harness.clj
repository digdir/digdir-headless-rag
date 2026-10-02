(ns digdir.llm.provider-harness
  "the resolved-parameter comparison: the check that tells \"same
   behaviour\" from \"same test outcome\".

   A fixed corpus of LLM-calling entry points is run across a matrix of
   configuration arms, once each, with the LLM stubbed. Per call it records:

   - WHERE the call went and WHAT it sent, from the leaf stubs in
     `digdir.llm.provider-fixtures` (the verifier's own instrument);
   - WHICH runner layer set each parameter, from Phase 0's `:parameters` event
     for the step that made the call;
   - the joined SOURCE of `:model`, `:temperature` and `:max-tokens`.

   The before-capture is that record taken on Phase 0 and committed as EDN.
   Each later phase re-takes it and diffs against the committed one; every
   difference is either declared by that phase or a regression.

   A differential check is blind to common-mode failure — a capture that
   silently recorded nothing would diff clean against another empty one — so
   every run is also checked absolutely (`problems`): it made exactly one call,
   Phase 0's in-src record and the leaf stub agree on that call, and the record
   has its shape."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.llm.provenance :as provenance]
            [digdir.llm.provider-fixtures :as fx]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.synthesis :as rag-synthesis]
            [digdir.skills.builtin.agent.read-signals :as read-signals]
            [digdir.skills.context :as ctx]
            [digdir.skills.enrichment.propose-questions :as propose-questions]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.init :as skills-init]
            [digdir.sweep.judge]
            [digdir.sweep.rechunk-reground]
            [valuehash.api :as valuehash]))

;; ---------------------------------------------------------------------------
;; The install every arm starts from
;; ---------------------------------------------------------------------------

(def tenant "harness-tenant")
(def ^:private switch "services.azure-openai.use-azure-openai-api")

(def ^:private credentials
  "Every provider fully credentialed, so the arms vary routing and layers only."
  {"services.azure-openai.api-key" "az-key"
   "services.azure-openai.api-endpoint" "https://azure.harness.invalid"
   "services.azure-openai.deployment-name" "az-deployment"
   "services.azure-openai.model-name" "generic-model"
   ;; Defined by the OpenRouter key-leak fix, read by nothing until services.llm.* gets its reader. Installed now so that when
   ;; the openai-compatible branch starts resolving them, its change is a
   ;; clean declared rule (:secret -> :opts, api.openai.com -> this host)
   ;; rather than every openai-compatible run turning into a refusal.
   "services.llm.api-key" "llm-key"
   "services.llm.api-endpoint" "https://llm.harness.invalid"})

;; ---------------------------------------------------------------------------
;; The corpus
;; ---------------------------------------------------------------------------

(defn- tool-reply [fname args]
  {:role "assistant" :content nil
   :tool_calls [{:id "call_0" :type "function"
                 :function {:name fname :arguments (json/encode args)}}]})

(def corpus
  "Every entry makes exactly one LLM call with its canned reply. Inputs are the
   ones each skill's metadata REQUIRES (execute-skill validates them), not the
   ones its body happens to read.

   `:graph` entries run as a one-step graph through `runner/run-graph`, so the
   step has a `:parameters` event and the runner layers apply. The nine
   LLM-calling graph skills are here; `agent/read_signals` is not a registered
   skill (it is reached only from the agent's read tool), so it and the
   non-skill dispatchers run as `:direct` entries, whose calls carry no step
   layer."
  [{:id :synthesis :kind :graph :skill :builtin/synthesis :inputs {:query "Hva er X?" :context-docs []}}
   {:id :query-planner :kind :graph :skill :builtin/query-planner :inputs {:query "Hva er X?" :conversation-history []}
    :respond (constantly (tool-reply "planQueries" {:user_intent "x" :search_phrases ["x"]}))}
   {:id :fact-checking :kind :graph :skill :builtin/fact-checking :inputs {:claim "c" :evidence "e"}
    :respond (constantly (tool-reply "verifyFact" {:verdict "SUPPORTED" :explanation "e"}))}
   {:id :summarization :kind :graph :skill :builtin/summarization :inputs {:content "A passage."}}
   {:id :entity-extraction :kind :graph :skill :builtin/entity-extraction :inputs {:text "Oslo"}
    :respond (constantly (tool-reply "extractEntities" {:entities []}))}
   {:id :graph-builder :kind :graph :skill :builtin/graph-builder :inputs {:task-description "t" :available-skills []}
    :step-params {:validate false}
    :respond (constantly (tool-reply "createSkillGraph"
                                     {:name "g" :inputs [] :outputs []
                                      :steps [{:id "s" :skill "builtin/summarization" :inputs {}}]}))}
   {:id :agent :kind :graph :skill :builtin/agent
    :inputs {:query "Hva er X?" :docs-collection "docs" :chunks-collection "chunks"
             :phrases-collection "phrases" :conversation-history []}}
   ;; The agent with a progress-fn takes the STREAMING path (every playground
   ;; run does): loop.clj's llm-opts reach `streaming-chat-completion`, which
   ;; the blocking :agent entry never exercises.
   {:id :agent-streaming :kind :graph :skill :builtin/agent
    :inputs {:query "Hva er X?" :docs-collection "docs" :chunks-collection "chunks"
             :phrases-collection "phrases" :conversation-history []}
    :skill-params {:progress-fn (fn [_] nil)}}
   {:id :propose-questions :kind :graph :skill :builtin/enrichment-propose-questions
    :inputs {:chunk-id "c" :chunk-content "A passage." :doc-title "T" :doc-url "u"}}
   {:id :read-signals :kind :direct
    :call #(read-signals/evaluate-read "Hva er X?" nil nil [] [] {:tenant tenant})}
   {:id :rag-generate :kind :direct
    :call #(rag-synthesis/rag-generate nil "convo" nil "prompt" {:tenant tenant})}
   {:id :search-phrases :kind :direct
    :call #(sp/create-chat-completion tenant {:model "caller-model" :messages [{:role "user" :content "x"}]})}
   {:id :loader :kind :direct
    :call #(loader/create-chat-completion tenant {:model "gpt-4o" :messages [{:role "user" :content "x"}]})}
   ;; The loader's fallback call. The id names the OpenRouter route this entry
   ;; was written for; the provider-resolver change removed that route, and the fallback model now
   ;; names its model to the tenant's resolved provider (`{:model ...}`), so
   ;; the entry drives that call. Its records keep their id, so that change to
   ;; them is a declared one.
   {:id :loader-openrouter :kind :direct
    :call #(loader/create-chat-completion tenant {:model :google/gemma-3-27b-it
                                                  :messages [{:role "user" :content "x"}]}
                                          {:model :google/gemma-3-27b-it})}
   ;; The two direct routing reads Phase 1 removes (one in production code, one in src-dev).
   ;; A third read (sweep/runner.clj) chooses a model but makes no call, so only the
   ;; the provider-switch census covers it.
   {:id :sweep-judge :kind :direct
    :call #(@#'digdir.sweep.judge/call-model tenant [{:role "user" :content "x"}] "judge-model")}
   {:id :sweep-rechunk :kind :direct
    :call #(@#'digdir.sweep.rechunk-reground/call-model tenant [{:role "user" :content "x"}] "rechunk-model")}])

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(def switch-arms {:unset {} :false {switch false} :true {switch true}})

(def layer-arms
  "Which runner layer sets :model (graph entries only). :graph-step also sets
   :temperature and :max-tokens, so skills that ignore a runner value show it;
   :override-nil is an explicit nil above a step value."
  [:none :graph-step :common :per-skill :override :override-nil])

(defn- layer-setup [layer skill]
  (case layer
    :none {}
    :graph-step {:step-params {:model "step-model" :temperature 0.7 :max-tokens 256}}
    :common {:skill-params {:model "common-model"}}
    :per-skill {:skill-params {skill {:model "per-skill-model"}}}
    :override {:opts {:model "override-model"}}
    :override-nil {:step-params {:model "step-model"} :opts {:model nil}}))

(def extra-arms
  "Configuration beyond the switch that a later phase is known to act on."
  {:azure-no-key {switch true "services.azure-openai.api-key" nil}
   ;; The openai-compatible mirror of :azure-no-key. Before the resolver read its own credentials the key comes
   ;; from the OPENAI_API_KEY secret whatever this path holds; after it, a nil
   ;; key must refuse. Without this arm that refusal is invisible here.
   :openai-no-key {switch false "services.llm.api-key" nil}
   ;; The two retired keyword selectors. Phase 4 of the provider-resolver change removed their definitions,
   ;; so a fresh install cannot hold these values; an install that predates it
   ;; still can, and these arms are that install. They stay in the corpus too,
   ;; because the before-capture has records for them.
   :search-phrases-lmstudio {"services.search-phrases.provider" :lmstudio}
   :self-improvement-lmstudio {"services.self-improvement.provider" :lmstudio}})

(def unobserved-by-phase-0
  "Calls Phase 0 cannot record. The leaf stubs still see them, so they stay in
   the record — only the count check is waived. A run listed here that IS
   recorded fails (`:expected-unobserved-but-recorded`), so an entry cannot
   outlive the blind spot it names.
   - rechunk_reground calls wkok directly, below every capture point.
   The step-parameter provenance record listed a second: propose-questions' :lmstudio arm POSTed past
   digdir.llm.client. Phase 3 of the provider-resolver change deleted that arm; the run now goes through
   the client at its step and is recorded, so its `:runs` entry is gone."
  {:entries #{:sweep-rechunk}
   :runs #{}})

(defn- unobserved? [entry-id arm]
  (or (contains? (:entries unobserved-by-phase-0) entry-id)
      (contains? (:runs unobserved-by-phase-0) [entry-id arm])))

(defn runs
  "Every [entry arm values layer] the capture executes."
  []
  (concat
   (for [e corpus, [s values] switch-arms, layer (if (= :graph (:kind e)) layer-arms [:none])]
     [e [s layer] values layer])
   (for [e corpus, [x values] extra-arms]
     [e [x :none] values :none])))

;; ---------------------------------------------------------------------------
;; One run
;; ---------------------------------------------------------------------------

(defn- ctx-without-services
  "The execution context with `:services` empty: no Typesense, no retrieval.
   Every corpus entry reaches its LLM call without them."
  [skill-id inputs opts]
  (skills/make-execution-context skill-id inputs (:parameters opts) {} (:skill-params opts)))

(defn- one-step-graph [{:keys [skill inputs]} step-params]
  {:id (keyword "harness" (name skill))
   :inputs (vec (keys inputs))
   :outputs []
   :steps [(cond-> {:id :step :skill skill
                    :inputs (into {} (map (fn [k] [k (keyword (str "$" (name k)))])) (keys inputs))}
             (seq step-params) (assoc :parameters step-params))]})

(defn- register-corpus!
  "Register every skill the corpus runs — on EVERY capture, not once per JVM.
   Several test namespaces `clear-registry!`. propose-questions is not in
   `register-builtin-skills!`'s list: production registers it through
   `initialize!` -> `questions-graph/register!`, so it is named here
   explicitly. Without that, a full-suite run after a registry clear lost it
   (measured: every propose-questions run threw before its call). Returns the
   corpus skill ids still unregistered afterwards; that must be empty."
  []
  (binding [*out* (java.io.StringWriter.)]
    (skills-init/register-builtin-skills!)
    (skills-init/register-builtin-skill-graphs!)
    (propose-questions/register!))
  (vec (remove skills/get-skill (keep :skill corpus))))

(defn run-one
  "Run `entry` once under `values` and runner `layer`. The sink is bound
   directly rather than through `provenance/capture`, so a run that throws
   still yields the events recorded before it did.

   `:output` is what the run printed. A skill that catches a refusal and
   degrades (query-planner, read-signals) reports it only there.

   Does NOT register the corpus — `capture` does, once per capture. Called on
   its own (a REPL, a scratch test), register first or a graph entry fails
   with :skill-not-found before any call."
  [{:keys [kind call skill inputs respond] :as entry} values layer]
  (let [{:keys [step-params skill-params opts]} (if (= :graph kind) (layer-setup layer skill) {})
        sink (atom [])
        out (java.io.StringWriter.)
        wire (binding [*out* out]
               (fx/with-install (merge credentials values)
                 (fn []
                   (with-redefs [ctx/build-execution-context ctx-without-services]
                     (fx/with-wire {:respond (or respond (constantly "ok"))}
                       (fn []
                         (binding [provenance/*sink* sink]
                           (if (= :graph kind)
                             (runner/run-graph (one-step-graph entry (merge (:step-params entry) step-params))
                                               inputs
                                               (merge {:tenant tenant
                                                       :skill-params (merge {:tenant tenant} (:skill-params entry) skill-params)
                                                       ::runner/suppress-graph-trace? true}
                                                      opts))
                             (call)))))))))]
    {:calls (:calls wire) :error (:error wire) :events @sink :output (str out)}))

;; ---------------------------------------------------------------------------
;; The join
;; ---------------------------------------------------------------------------

(defn- flatten-events
  "[path event] pairs, depth first. A `:graph` event's child is {step-id events};
   its steps are walked in sorted order, since that map does not keep
   execution order, and the path records graph-id and step-id."
  ([events] (flatten-events [] events))
  ([path events]
   (mapcat (fn [e]
             (if (= :graph (:event e))
               (mapcat (fn [[step-id evs]] (flatten-events (conj path (:graph-id e) step-id) evs))
                       (sort-by (comp str key) (:child e)))
               [[path e]]))
           events)))

(defn- in-src-calls [events]
  (filterv (comp #{:llm-call} :event second) (flatten-events events)))

(defn- step-parameters [events path]
  (some (fn [[p e]] (when (and (= p path) (= :parameters (:event e))) e)) (flatten-events events)))

(defn- enclosing-parameters
  "The `:parameters` events of the steps enclosing `path`, innermost first. A
   nested run (the agent's own graphs) makes its call in an inner step whose
   layers may be empty while the value came from a layer of the OUTER step."
  [events path]
  (->> (range (count path) 0 -2)
       (map #(subvec path 0 %))
       (keep #(step-parameters events %))
       vec))

(defn- attribute
  "The innermost enclosing step whose runner layers set `k` to a non-nil value:
   [parameters-event depth], depth 0 being the step that made the call."
  [chain k]
  (first (keep-indexed (fn [i p] (when (and (get-in p [:trace k :winning-layer])
                                             (some? (get-in p [:value k])))
                                    [p i]))
                       chain)))

(defn- caller-max-tokens [trio] (or (:max_completion_tokens trio) (:max_tokens trio)))

(defn- fallback-model
  "What the in-skill provider fallback picks today: the deployment name when
   the switch says Azure, the model name otherwise."
  [values]
  (if (true? (get values switch)) "az-deployment" "generic-model"))

(defn- layer-label [layer depth]
  (if (zero? depth) layer (keyword "enclosing" (name layer))))

(defn- model-source [chain sent fallback]
  (let [[p depth] (attribute chain :model)]
    (cond
      (empty? chain) :no-step-layer
      (and p (= (get-in p [:value :model]) sent)) (layer-label (get-in p [:trace :model :winning-layer]) depth)
      p :overridden-below-runner
      (= sent fallback) :provider-fallback
      :else :below-runner)))

(defn- param-source
  [chain k sent caller env-applied wire-k]
  (let [[p depth] (attribute chain k)]
    (cond
      (contains? env-applied wire-k) :env-override
      (and (some? caller) (nil? sent)) :normalized-away
      (and p (nil? caller) (nil? sent)) :ignored-by-skill
      (and (nil? caller) (nil? sent)) :absent
      (empty? chain) :no-step-layer
      (and p (= (get-in p [:value k]) caller)) (layer-label (get-in p [:trace k :winning-layer]) depth)
      :else :skill-default)))

(defn- leaf-projection [c]
  (-> (select-keys c [:branch :path :via :model :temperature :max-tokens :key?
                      :key-from :endpoint-from :key-rederived? :endpoint-rederived?])
      (assoc :endpoint-host (provenance/endpoint-host (:endpoint c)))))

(defn record
  "The joined record of the one call a run made."
  [entry arm values {:keys [calls events]}]
  (let [leaf (first calls)
        [path ev] (first (in-src-calls events))
        ;; A call Phase 0 cannot see has no event, but its step's :parameters
        ;; event was still recorded by the runner.
        params-path (or path (some (fn [[pp e]] (when (= :parameters (:event e)) pp))
                                   (flatten-events events)))
        chain (if params-path (enclosing-parameters events params-path) [])
        p (first chain)
        sent (leaf-projection leaf)
        caller (:caller ev)]
    {:entry (:id entry)
     :arm arm
     :path (if ev path [:unobserved-by-phase-0])
     :call-index (:call-index ev)
     :winning (when p (into {} (for [k [:model :temperature :max-tokens]]
                                 [k (get-in p [:trace k :winning-layer])])))
     :sent sent
     :source {:model (model-source chain (:model sent) (fallback-model values))
              :temperature (param-source chain :temperature (:temperature sent) (:temperature caller)
                                         (:env-applied ev) :temperature)
              :max-tokens (param-source chain :max-tokens (:max-tokens sent) (caller-max-tokens caller)
                                        (:env-applied ev) :max_tokens)}}))

;; ---------------------------------------------------------------------------
;; The absolute checks
;; ---------------------------------------------------------------------------

(defn problems
  "Why this run cannot be trusted as a record, or nil."
  [entry arm {:keys [calls events error]}]
  (let [in-src (in-src-calls events)
        leaf (first calls)
        [_ ev] (first in-src)
        unobserved? (unobserved? (:id entry) arm)]
    (not-empty
     (cond-> []
       error (conj [:threw (ex-message error)])
       (not= 1 (count calls)) (conj [:leaf-calls (count calls)])
       (and unobserved? (not= 0 (count in-src))) (conj [:expected-unobserved-but-recorded (count in-src)])
       (and (not unobserved?) (not= (count calls) (count in-src))) (conj [:instruments-disagree-on-count (count calls) (count in-src)])
       (and leaf ev (not= (:branch leaf) (:branch ev))) (conj [:branch (:branch leaf) (:branch ev)])
       (and leaf ev (not= (provenance/endpoint-host (:endpoint leaf)) (:endpoint-host ev)))
       (conj [:endpoint-host (provenance/endpoint-host (:endpoint leaf)) (:endpoint-host ev)])
       (and leaf ev (not= (:model leaf) (let [m (get-in ev [:sent :model])] (if (keyword? m) (subs (str m) 1) m))))
       (conj [:model (:model leaf) (get-in ev [:sent :model])])
       (and leaf (not (keyword? (:branch leaf)))) (conj [:shape :branch])
       (and leaf (not (string? (:model leaf)))) (conj [:shape :model])))))

;; ---------------------------------------------------------------------------
;; The capture
;; ---------------------------------------------------------------------------

(def env-that-would-leak-in
  "Transport env reads the stubs cannot neutralise. Set, they would make the
   capture depend on the shell that took it."
  ["OPENAI_API_ENDPOINT" "OPENAI_API_KEY" "OPENAI_ORGANIZATION"
   "AZURE_OPENAI_API_KEY" "AZURE_OPENAI_API_ENDPOINT"
   "OPENAI_REASONING_EFFORT" "OPENAI_ENABLE_THINKING"])

;; ---------------------------------------------------------------------------
;; Declared changes
;;
;; A phase that changes behaviour DECLARES it; it never regenerates the
;; before-capture. A declaration is data, checked rather than trusted:
;;
;;   {:rules    [{:why str :select {path v} :from {path v} :to {path v}} ...]
;;    :refusals [{:why str :select {path v} :names-path "services.x.y"
;;                :swallowed? bool} ...]}
;;
;; `path` is a vector into a record (`[:sent :key-from]`); a `:select` value that
;; is a SET matches any member. A rule rewrites the records it selects from
;; `:from` to `:to`; a refusal says the selected runs now make ZERO calls and
;; throw naming `:names-path`. With `:swallowed? true` they instead throw
;; NOTHING and print the path: the skill catches the refusal and degrades.
;; Refusals select on `[:entry]` / `[:arm]` only — a refused run has no `:sent`
;; to select on.
;;
;; A rule or refusal that selects nothing is a failure, and so is a rule whose
;; `:from` does not describe every record it selects: a declaration cannot be
;; vacuous, and cannot describe a before-state that never existed.
;; ---------------------------------------------------------------------------

(defn- matches? [record select]
  (every? (fn [[path v]]
            (let [x (get-in record path)]
              (if (set? v) (contains? v x) (= v x))))
          select))

(defn- refusal-for [declarations entry-id arm]
  (some #(when (matches? {:entry entry-id :arm arm} (:select %)) %) (:refusals declarations)))

(defn- refusal-message
  "The one message a thrown refusal carries. From a graph step it arrives
   wrapped by the runner, `(ex-info \"Step execution failed\" {:step-id ..
   :error <step result>})` with no cause, and the skill's own message is the
   step result's `[:error :error-message]` (the `skills/error-result` shape;
   `graph.trace` reads it the same way). Only that string is read, never the
   whole ex-data: a path in the run's inputs or context would pass a refusal
   that never happened."
  [error]
  (str (or (get-in (ex-data error) [:error :error :error-message])
           (ex-message error))))

(defn- refusal-problems
  "Why a run declared as a refusal is not one, or nil. A refusal makes ZERO
   calls and names `:names-path` in the error it throws — or, declared
   `:swallowed? true`, in what the run printed: a skill that catches the
   refusal and degrades. Zero calls and no error is also what a skill that
   never tried looks like, so the printed path is the positive evidence, and a
   swallowed refusal that throws is a declaration that misdescribes the run."
  [{:keys [names-path swallowed?]} {:keys [calls error output]}]
  (not-empty
   (cond-> []
     (seq calls) (conj [:declared-refusal-but-called (count calls)])
     (and swallowed? error) (conj [:declared-swallowed-but-threw (refusal-message error)])
     (and swallowed? (nil? error) (not (str/includes? (str output) names-path)))
     (conj [:swallowed-refusal-does-not-name names-path])
     (and (not swallowed?) (nil? error)) (conj [:declared-refusal-but-no-error])
     (and (not swallowed?) error (not (str/includes? (refusal-message error) names-path)))
     (conj [:refusal-does-not-name names-path (refusal-message error)]))))

(defn record-key [r] [(:entry r) (:arm r) (:path r) (:call-index r)])

(defn expected-after
  "The before-capture with `declarations` applied:
   `{:records [...] :problems [...]}`. `:problems` lists vacuous rules and
   refusals, and rules whose `:from` misdescribes a record they select."
  [before {:keys [rules refusals]}]
  (let [problems (atom [])
        refusal-of (fn [r] (some #(when (matches? r (:select %)) %) refusals))]
    (doseq [rf refusals]
      (when-not (some #(matches? % (:select rf)) before)
        (swap! problems conj [:vacuous-refusal (:why rf)])))
    (let [kept (remove refusal-of before)
          applied (reduce (fn [recs {:keys [why select from to]}]
                            (let [hits (filter #(matches? % select) recs)]
                              (when (empty? hits) (swap! problems conj [:vacuous-rule why]))
                              (doseq [h hits]
                                (when-not (matches? h from)
                                  (swap! problems conj [:rule-from-mismatch why (record-key h)])))
                              (mapv #(if (matches? % select)
                                       (reduce (fn [r [p v]] (assoc-in r p v)) % to)
                                       %)
                                    recs)))
                          (vec kept) rules)
          refused (distinct (for [r before :let [rf (refusal-of r)] :when rf]
                              {:entry (:entry r) :arm (:arm r) :refused (:names-path rf)}))]
      {:records (vec (concat applied refused)) :problems @problems})))

(defn- call-sources
  "Guard 4's input: where each in-src call of run `r` took its credentials,
   and the `:provider/source` tag that says why."
  [entry arm r]
  (mapv (fn [[path ev]]
          (merge {:entry (:id entry) :arm arm :path path}
                 (select-keys ev [:key-from :endpoint-from :key-source :endpoint-source])))
        (in-src-calls (:events r))))

(defn capture
  "{:runs n :unregistered [skill-id ...] :problems {[entry arm] [...]}
    :records [record ...] :calls [call-source ...]} over every run. A run
   `declarations` names as a refusal is held to that instead: zero calls, and
   an error naming the path. `:calls` is Guard 4's input and never part of a
   record, so the before-capture comparison does not see it."
  ([] (capture {}))
  ([declarations]
   (reduce (fn [acc [entry arm values layer]]
             (let [r (run-one entry values layer)
                   rf (refusal-for declarations (:id entry) arm)
                   ps (if rf (refusal-problems rf r) (problems entry arm r))]
               (cond-> (-> acc
                           (update :runs inc)
                           (update :calls into (call-sources entry arm r))
                           (update :records conj
                                   (if rf
                                     {:entry (:id entry) :arm arm :refused (:names-path rf)}
                                     (record entry arm values r))))
                 ps (assoc-in [:problems [(:id entry) arm]] ps))))
           {:runs 0 :unregistered (register-corpus!) :problems {} :records [] :calls []}
           (runs))))

;; ---------------------------------------------------------------------------
;; Guard 4 — the provider-resolver change's done-condition
;; ---------------------------------------------------------------------------

(def guard-4-sources
  "The only places a credential may come from: the tenant's config, or the
   sweep's run-override (src-dev only; `provider-env-door-test`)."
  #{:config :run-override})

(defn- guard-4-holds? [c]
  (and (= :opts (:key-from c))
       (= :opts (:endpoint-from c))
       (contains? guard-4-sources (:key-source c))
       (contains? guard-4-sources (:endpoint-source c))))

(defn guard-4-problems
  "Every call takes its key and endpoint from the resolved spec (`:opts`), and
   the spec says each came from config or the run-override. An ALLOWLIST:
   `:untagged`, `:unresolved`, `:env` and nil all fail — a guard that accepted
   whatever was not a known-bad value would pass a call site that dropped the
   tag.

   `exceptions` is {entry-id {:why str :excuses {field value}}}: that entry's
   calls may have exactly those field values, and must hold Guard 4 in every
   other respect. Checked, not trusted: an exception that excuses no call is a
   problem, so it is deleted in the commit that makes it unnecessary."
  [calls exceptions]
  (let [excused? (fn [c]
                   (when-let [{:keys [excuses]} (get exceptions (:entry c))]
                     (and (= excuses (select-keys c (keys excuses)))
                          (guard-4-holds? (merge c (zipmap (keys excuses) (repeat :config)))))))
        violations (remove guard-4-holds? calls)
        used (set (map :entry (filter excused? violations)))]
    (not-empty
     (vec (concat
           (for [c violations :when (not (excused? c))]
             [:guard-4 (select-keys c [:entry :arm :key-from :endpoint-from :key-source :endpoint-source])])
           (for [e (keys exceptions) :when (not (contains? used e))]
             [:vacuous-guard-4-exception e]))))))

(defn diff-records
  "What changed between two record sets, keyed by `record-key`."
  [before now]
  (let [b (into {} (map (juxt record-key identity)) before)
        n (into {} (map (juxt record-key identity)) now)]
    {:removed (vec (sort-by str (remove (set (keys n)) (keys b))))
     :added (vec (sort-by str (remove (set (keys b)) (keys n))))
     :changed (into (sorted-map-by #(compare (str %1) (str %2)))
                    (for [[k rb] b
                          :let [rn (get n k)]
                          :when (and rn (not= rb rn))]
                      [k {:before (select-keys rb [:winning :sent :source :refused])
                          :now (select-keys rn [:winning :sent :source :refused])}]))}))

(def snapshot-path "test/fixtures/provider-capture/provider-capture.edn")

(def regeneration-warning
  "Why the before-capture must not be regenerated, stated wherever it can be."
  (str "provider-capture.edn is the BEFORE-half of the provider-resolver change's resolved-parameter comparison, "
       "captured BEFORE Phase 1 (the commit is named in the file header and pinned in the "
       "test). Regenerating it after Phase 1 "
       "has landed DESTROYS the only evidence that Phase 1 changed nothing: the comparison "
       "then passes by construction. A phase that changes behaviour DECLARES the change in "
       "digdir.llm.provider-capture-test/declared-changes; it does not regenerate this file."))

(defn records-hash
  "Structural sha-256 of a record set, independent of order, map key order and
   file layout, so the pin in the test tracks CONTENT only."
  [records]
  (valuehash/sha-256-str (set records)))

(defn read-snapshot []
  (let [f (io/file snapshot-path)]
    (when (.exists f) (read-string (slurp f)))))

(defn write-snapshot!
  "Take the capture and write it as the committed before-capture. `taken-on`
   names the commit it describes.

   Refuses to write a capture with problems, and refuses to OVERWRITE an
   existing before-capture unless `:replacing-the-before-capture true` is
   passed — see `regeneration-warning` for why that is almost always wrong.
   The test pins the content hash too, so a regeneration cannot pass silently."
  [taken-on & {:keys [replacing-the-before-capture]}]
  (binding [*out* *err*] (println "WARNING:" regeneration-warning))
  (when (and (.exists (io/file snapshot-path)) (not (true? replacing-the-before-capture)))
    (throw (ex-info (str "refusing to overwrite the before-capture. " regeneration-warning)
                    {:path snapshot-path})))
  (let [{:keys [runs unregistered problems records]} (capture)]
    (when (or (seq unregistered) (seq problems))
      (throw (ex-info "refusing to snapshot a capture with problems"
                      {:unregistered unregistered :problems problems})))
    (io/make-parents snapshot-path)
    (spit snapshot-path
          (with-out-str
            (println ";; the provider-resolver change BEFORE-CAPTURE — the before-half of the resolved-parameter comparison.")
            (println (str ";; Captured on: " taken-on))
            (println ";;")
            (println ";; DO NOT REGENERATE THIS FILE AFTER PHASE 1. Doing so destroys the only evidence")
            (println ";; that Phase 1 changed nothing: the comparison would then pass by construction.")
            (println ";; A phase that changes behaviour DECLARES the change in")
            (println ";; digdir.llm.provider-capture-test/declared-changes, next to the code that makes it.")
            (println ";;")
            (println ";; Content is pinned by digdir.llm.provider-capture-test/before-capture-records-sha256.")
            (println ";; Written by digdir.llm.provider-harness/write-snapshot!, which refuses to overwrite")
            (println ";; this file without :replacing-the-before-capture true. Never edit by hand.")
            (pp/pprint {:taken-on taken-on
                        :runs runs
                        :records (vec (sort-by (comp str record-key) records))})))
    {:runs runs :records (count records) :sha256 (records-hash records)}))
