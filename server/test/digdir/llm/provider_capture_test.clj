(ns digdir.llm.provider-capture-test
  "the resolved-parameter comparison, as a test.

   `test/fixtures/provider-capture/provider-capture.edn` is the before-capture: every call
   the corpus makes across the configuration matrix, taken on Phase 0 — where
   it went, what it sent, which runner layer set each parameter, and the joined
   source of :model, :temperature and :max-tokens (`digdir.llm.provider-harness`).

   Phases 1-4 must leave it unchanged except where a phase INTENDS a change and
   says which. A phase declares that in `declared-changes` below, in the same
   diff, so a reviewer sees the intended changes next to the code that makes
   them. Anything else that moved is a regression — and this is the only check
   in the mission that distinguishes \"same behaviour\" from \"same test outcome\".

   The differential half is paired with an absolute half, because a capture
   that silently recorded nothing would diff clean against another empty one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.llm.provider-harness :as h]))

(def ^:private before-capture-commit
  "The commit the before-capture describes: public/main after the OpenRouter key-leak fix (on the step-parameter provenance record
   Phase 0 and the provider-selector pins), before Phase 1. The OpenRouter key-leak fix changes nothing the capture
   sees (measured: zero record changes against the 18ba2502 capture)."
  "ece6ce65b321c4ec10fe08c9844bf872f481ffad")

(def ^:private before-capture-records-sha256
  "Structural hash of the committed before-capture's records
   (`h/records-hash`). Hand-typed ON PURPOSE: regenerating the fixture changes
   it, so a regeneration cannot pass without an edit here that a reviewer sees.
   Changing this value after Phase 1 is exactly the act that destroys the
   evidence — see `h/regeneration-warning`."
  "6c8b37150575ff4f597bc5e30552ee7fbe455ea3f5e604bc70b443ac30c06e6a")

(def ^:private declared-changes
  "What the CURRENT phase intends to change relative to the committed
   before-capture — rules and refusals, in the shape documented at
   `digdir.llm.provider-harness` \"Declared changes\". Declared next to the
   code that makes the change, in the same diff. Phase 1 declared nothing.

   Phase 2 of the provider-resolver change, the read side: the openai-compatible branch takes its
   key and endpoint from `services.llm.*`, and a missing tenant key refuses
   naming its path instead of borrowing a process-global credential.

   In Phase 3 of the provider-resolver change, the dispatchers collapsed: search-phrases, the loader and
   enrichment (propose-questions) call `provider/resolve`, so they follow the
   provider decision - on the `false`/unset arms and on the two retired keyword
   arms they now leave Azure for the tenant's OpenAI-compatible provider - and
   with no key they refuse like everything else.

   the last of Phase 3: the loader's OpenRouter route for three
   fallback model names is gone. Its fallback call (`loader-openrouter`) goes
   to the tenant's resolved provider with the fallback model's name kept, so
   it follows the provider decision and refuses without a key like every
   other call."
  (let [refuses #{:agent :agent-streaming :entity-extraction :fact-checking :graph-builder
                  :rag-generate :summarization :sweep-judge :sweep-rechunk :synthesis}
        ;; These two catch the refusal and degrade: query-planner falls back to
        ;; the raw query, read-signals to a local fallback signal. The run
        ;; throws nothing and prints the path.
        swallows #{:query-planner :read-signals}
        ;; the three former dispatchers, and the arms on which the
        ;; provider decision is NOT Azure (the retired keyword arms included - the
        ;; keywords are no longer read, so those runs match `:unset`).
        dispatchers #{:search-phrases :loader :propose-questions}
        off-azure #{[:false :none] [:unset :none] [:search-phrases-lmstudio :none] [:self-improvement-lmstudio :none]}
        to-llm {[:sent :branch] :openai [:sent :via] :client [:sent :endpoint-host] "llm.harness.invalid"}]
    {:rules
     [{:why "openai-compatible through digdir.llm.client: key and endpoint come from services.llm.*
             (opts), not the OPENAI_API_KEY secret and the public default endpoint"
       :select {[:sent :via] :client [:sent :branch] :openai [:sent :key-from] :secret}
       :from {[:sent :key-from] :secret [:sent :endpoint-from] :default [:sent :endpoint-host] "api.openai.com"}
       :to {[:sent :key-from] :opts [:sent :endpoint-from] :opts [:sent :endpoint-host] "llm.harness.invalid"}}
      {:why "openai-compatible through raw wkok (streaming, rechunk): the resolver passes services.llm.*
             instead of nil, so wkok no longer re-derives either one from the environment"
       :select {[:sent :via] :wkok [:sent :branch] :openai}
       :from {[:sent :key-from] nil [:sent :key?] false [:sent :key-rederived?] true
              [:sent :endpoint-from] :default [:sent :endpoint-rederived?] true
              [:sent :endpoint-host] "api.openai.com"}
       :to {[:sent :key-from] :opts [:sent :key?] true [:sent :key-rederived?] false
            [:sent :endpoint-from] :opts [:sent :endpoint-rederived?] false
            [:sent :endpoint-host] "llm.harness.invalid"}}
      ;; --- Phase 3 of the provider-resolver change ---
      {:why "Phase 3: search-phrases and the loader follow the provider decision off Azure, still
             overwriting the caller's model - now with model-name, not the deployment name"
       :select {[:entry] #{:search-phrases :loader} [:arm] off-azure [:sent :branch] :azure}
       :from {[:sent :via] :wkok [:sent :endpoint-host] "azure.harness.invalid" [:sent :model] "az-deployment"}
       :to (assoc to-llm [:sent :model] "generic-model")}
      {:why "Phase 3: enrichment follows the provider decision off Azure; a runner layer that names
             a model still wins, so only the destination changes"
       :select {[:entry] :propose-questions
                [:arm] #{[:false :common] [:false :graph-step] [:false :override] [:false :per-skill]
                         [:unset :common] [:unset :graph-step] [:unset :override] [:unset :per-skill]}}
       :from {[:sent :branch] :azure [:sent :via] :wkok [:sent :endpoint-host] "azure.harness.invalid"}
       :to to-llm}
      {:why "Phase 3: enrichment with no model from any layer takes the provider's default, which the
             join now attributes to the provider (it was propose-questions' own read, below the runner)"
       :select {[:entry] :propose-questions
                [:arm] #{[:false :none] [:false :override-nil] [:unset :none] [:unset :override-nil]
                         [:search-phrases-lmstudio :none]}}
       :from {[:sent :branch] :azure [:sent :via] :wkok [:sent :endpoint-host] "azure.harness.invalid"
              [:sent :model] "az-deployment" [:source :model] :below-runner}
       :to (assoc to-llm [:sent :model] "generic-model" [:source :model] :provider-fallback)}
      {:why "Phase 3: services.search-phrases.provider :lmstudio is retired - its run goes where the
             unset decision says, the tenant's services.llm.*, not services.lmstudio.*"
       :select {[:entry] :search-phrases [:arm] [:search-phrases-lmstudio :none]}
       :from {[:sent :endpoint-host] "lmstudio.harness.invalid:1234" [:sent :model] "lm-model"}
       :to {[:sent :endpoint-host] "llm.harness.invalid" [:sent :model] "generic-model"}}
      {:why "Phase 3: services.self-improvement.provider :lmstudio is retired - the direct POST that
             Phase 0 could not observe is gone; the run goes through digdir.llm.client, at its step,
             exactly as the unset decision's run does"
       :select {[:entry] :propose-questions [:arm] [:self-improvement-lmstudio :none]}
       :from {[:path] [:unobserved-by-phase-0] [:call-index] nil [:sent :via] :direct-post
              [:sent :endpoint-host] "lmstudio.harness.invalid:1234" [:sent :model] "lm-model"
              [:source :model] :below-runner}
       :to {[:path] [:harness/enrichment-propose-questions :step] [:call-index] 0 [:sent :via] :client
            [:sent :endpoint-host] "llm.harness.invalid" [:sent :model] "generic-model"
            [:source :model] :provider-fallback}}
      ;; --- the provider-resolver change ---
      {:why "the loader's fallback model on an Azure decision goes to Azure, not OpenRouter, under
             its own name - Azure takes it as the deployment, and a tenant with no such deployment
             fails there. Key and endpoint come from the resolved spec, as before"
       :select {[:entry] :loader-openrouter [:arm] #{[:true :none]}}
       :from {[:sent :via] :client [:sent :branch] :openai [:sent :endpoint-host] "openrouter.ai"}
       :to {[:sent :via] :wkok [:sent :branch] :azure [:sent :endpoint-host] "azure.harness.invalid"}}
      {:why "off Azure, the loader's fallback model goes to the tenant's services.llm.* endpoint
             under its own name, not to a hardcoded openrouter.ai. OpenRouter is a services.llm value"
       :select {[:entry] :loader-openrouter [:arm] off-azure}
       :from {[:sent :endpoint-host] "openrouter.ai"}
       :to {[:sent :endpoint-host] "llm.harness.invalid"}}]
     :refusals
     [{:why "Azure with no tenant key: the resolver refuses naming the path, where wkok used to
             substitute AZURE_OPENAI_API_KEY"
       :select {[:arm] #{[:azure-no-key :none]} [:entry] refuses}
       :names-path "services.azure-openai.api-key"}
      {:why "openai-compatible with no services.llm.api-key: the resolver refuses naming the path,
             where the client used to take the OPENAI_API_KEY secret"
       :select {[:arm] #{[:openai-no-key :none]} [:entry] refuses}
       :names-path "services.llm.api-key"}
      {:why "the same Azure refusal, caught by the skill: it degrades quietly instead of calling"
       :select {[:arm] #{[:azure-no-key :none]} [:entry] swallows}
       :names-path "services.azure-openai.api-key"
       :swallowed? true}
      {:why "the same openai-compatible refusal, caught by the skill: it degrades quietly instead of calling"
       :select {[:arm] #{[:openai-no-key :none]} [:entry] swallows}
       :names-path "services.llm.api-key"
       :swallowed? true}
      ;; --- Phase 3 of the provider-resolver change ---
      {:why "Phase 3: the former dispatchers with no Azure key refuse naming it, where each passed
             nil to wkok and borrowed AZURE_OPENAI_API_KEY"
       :select {[:arm] #{[:azure-no-key :none]} [:entry] dispatchers}
       :names-path "services.azure-openai.api-key"}
      {:why "Phase 3: the former dispatchers, openai-compatible with no services.llm.api-key, refuse
             naming it, where they used to go to Azure whatever the decision said"
       :select {[:arm] #{[:openai-no-key :none]} [:entry] dispatchers}
       :names-path "services.llm.api-key"}
      ;; --- the provider-resolver change ---
      {:why "the loader's fallback with no Azure key refuses naming it, where it went to OpenRouter
             on services.openrouter.api-key whatever the decision said"
       :select {[:arm] #{[:azure-no-key :none]} [:entry] :loader-openrouter}
       :names-path "services.azure-openai.api-key"}
      {:why "the loader's fallback, openai-compatible with no services.llm.api-key, refuses naming
             it, where it went to OpenRouter on services.openrouter.api-key"
       :select {[:arm] #{[:openai-no-key :none]} [:entry] :loader-openrouter}
       :names-path "services.llm.api-key"}]}))

(def ^:private guard-4-exceptions
  "Calls Guard 4 does not cover yet, each with the field values it excuses and
   why. Checked, not trusted (`h/guard-4-problems`): an exception no call needs
   fails, so it is deleted in the commit that makes it unnecessary.

   None. The last one, the loader's OpenRouter route (7 calls), was deleted by
   which removed the route: every call the corpus makes now takes its
   credentials from provider/resolve."
  {})

(def ^:private taken (delay (h/capture declared-changes)))

(deftest the-before-capture-is-the-one-taken-before-phase-1
  (testing (str "The before-half is only evidence while it describes the world BEFORE
                 Phase 1. " h/regeneration-warning)
    (let [snap (h/read-snapshot)]
      (is (str/includes? (str (:taken-on snap)) before-capture-commit)
          "captured on the pinned commit")
      (is (= before-capture-records-sha256 (h/records-hash (:records snap)))
          (str "the before-capture's CONTENT changed. If it was regenerated, revert it "
               "and declare the change in `declared-changes` instead.")))))

(deftest the-shell-cannot-leak-into-the-capture
  (testing "Transport env reads the stubs cannot neutralise. If any is set, the
            capture would describe the shell that took it, not the code."
    (is (empty? (filterv #(some? (System/getenv %)) h/env-that-would-leak-in))
        "unset these and re-run")))

(deftest every-run-is-a-trustworthy-record
  (let [{:keys [runs unregistered problems records]} @taken
        expected (h/runs)]
    (is (empty? unregistered)
        (str "corpus skills not registered — other tests clear the registry: " unregistered))
    (is (= (count expected) runs) "every planned run executed")
    (is (<= 150 runs) "the matrix is the size it claims — an empty corpus would pass everything below")
    (is (= runs (count records)))
    (is (empty? problems)
        (str "runs that cannot be trusted as records (threw, made other than one call, "
             "or the two instruments disagreed): " (pr-str problems)))
    (testing "every entry and every arm is present"
      (is (= (set (map (fn [[e arm]] [(:id e) arm]) expected))
             (set (map (juxt :entry :arm) records)))))
    (testing "the joins produced labels, not holes"
      (is (every? #(every? keyword? (vals (:source %))) records)))))

(deftest the-capture-matches-the-committed-before-capture
  (let [snap (h/read-snapshot)]
    (is (some? snap) (str "the before-capture must be committed at " h/snapshot-path))
    (is (<= 150 (count (:records snap))) "and must itself be non-empty")
    (let [{:keys [records problems]} (h/expected-after (:records snap) declared-changes)]
      (is (empty? problems)
          (str "a declaration must select something and describe the before-state truly: " (pr-str problems)))
      (is (= {:removed [] :added [] :changed {}} (h/diff-records records (:records @taken)))
          "the capture must equal the before-capture with the declared changes applied — nothing more, nothing less"))))

(deftest declarations-are-checked-not-trusted
  (testing "The machinery, on records whose answer is known — both directions."
    (let [before [{:entry :e :arm [:a :none] :path [:g :s] :call-index 0 :sent {:key-from :secret :branch :openai}}
                  {:entry :e :arm [:b :none] :path [:g :s] :call-index 0 :sent {:key-from :opts :branch :azure}}]
          rule {:why "t" :select {[:sent :branch] :openai} :from {[:sent :key-from] :secret} :to {[:sent :key-from] :opts}}]
      (testing "a true rule rewrites exactly what it selects"
        (let [{:keys [records problems]} (h/expected-after before {:rules [rule]})]
          (is (empty? problems))
          (is (= [:opts :opts] (mapv #(get-in % [:sent :key-from]) records)))))
      (testing "a rule that selects nothing is a failure, not a no-op"
        (is (= [[:vacuous-rule "v"]]
               (:problems (h/expected-after before {:rules [(assoc rule :why "v" :select {[:sent :branch] :none})]})))))
      (testing "a rule whose :from misdescribes the before-state is a failure"
        (is (= :rule-from-mismatch
               (ffirst (:problems (h/expected-after before {:rules [(assoc rule :from {[:sent :key-from] :env})]}))))))
      (testing "a refusal replaces the runs it selects, and a vacuous one fails"
        (let [{:keys [records problems]} (h/expected-after before {:refusals [{:why "r" :select {[:arm] #{[:a :none]}} :names-path "p"}]})]
          (is (empty? problems))
          (is (some #(= {:entry :e :arm [:a :none] :refused "p"} %) records))
          (is (= 2 (count records))))
        (is (= [[:vacuous-refusal "r"]]
               (:problems (h/expected-after before {:refusals [{:why "r" :select {[:arm] #{[:zz :none]}} :names-path "p"}]}))))))))

(deftest refusals-are-checked-not-trusted
  (testing "The refusal check, on runs whose answer is known: each way a refusal can
            be real, and each way a run can look like one without being one."
    (let [check #'h/refusal-problems
          p "services.llm.api-key"
          rf {:names-path p}
          sw (assoc rf :swallowed? true)
          msg (str p " is unset for tenant t")
          direct (ex-info msg {:path p})
          step-error (fn [m] (ex-info "Step execution failed" {:step-id :step :error {:error {:error-type :x :error-message m}}}))]
      (testing "thrown directly, naming the path: a refusal"
        (is (nil? (check rf {:calls [] :error direct}))))
      (testing "thrown from a graph step: the runner wraps it with no cause, and the path is read from the step's error-result"
        (is (nil? (check rf {:calls [] :error (step-error msg)}))))
      (testing "a wrapped step error that does not name the path is not a refusal of it"
        (is (= :refusal-does-not-name (ffirst (check rf {:calls [] :error (step-error "boom")})))))
      (testing "the path elsewhere in ex-data (inputs, context) does not count: only the step's error message does"
        (is (= :refusal-does-not-name
               (ffirst (check rf {:calls [] :error (ex-info "Step execution failed"
                                                             {:inputs {:q p} :error {:error {:error-message "boom"}}})})))))
      (testing "zero calls and no error, NOT declared swallowed: fails, whatever was printed"
        (is (= [[:declared-refusal-but-no-error]] (check rf {:calls [] :error nil :output msg}))))
      (testing "declared swallowed: zero calls, no error, and the path in what the run printed"
        (is (nil? (check sw {:calls [] :error nil :output (str "LLM call failed: " msg " - falling back")}))))
      (testing "declared swallowed but silent: zero calls and no error is also a skill that never tried"
        (is (= [[:swallowed-refusal-does-not-name p]] (check sw {:calls [] :error nil :output ""}))))
      (testing "declared swallowed but it threw: the declaration misdescribes the run"
        (is (= :declared-swallowed-but-threw (ffirst (check sw {:calls [] :error direct})))))
      (testing "any call at all is not a refusal, swallowed or not"
        (is (= :declared-refusal-but-called (ffirst (check rf {:calls [{}] :error direct}))))
        (is (= :declared-refusal-but-called (ffirst (check sw {:calls [{}] :error nil :output msg}))))))))

(deftest guard-4-every-call-takes-its-credentials-from-the-resolved-spec
  (testing "the provider-resolver change's done-condition (Guard 4), over every call the corpus x matrix makes: key
            and endpoint from the resolved spec (:opts), and the spec says each came from
            config or the sweep's run-override - never the environment, never untagged.
            sweep-rechunk calls wkok below every capture point (h/unobserved-by-phase-0),
            so its calls are outside this guard; its wire records are held by the rules."
    (let [calls (:calls @taken)]
      (is (<= 150 (count calls))
          "POSITIVE CONTROL: the guard saw the corpus's calls - an empty capture would pass it")
      (is (empty? (h/guard-4-problems calls guard-4-exceptions))
          (str "calls that do not take their credentials from config through provider/resolve: "
               (pr-str (h/guard-4-problems calls guard-4-exceptions)))))))

(deftest guard-4-is-checked-not-trusted
  (testing "The guard, on calls whose answer is known: each way a call can pass, and each
            way it can escape."
    (let [ok {:entry :e :arm [:a :none] :key-from :opts :endpoint-from :opts
              :key-source :config :endpoint-source :config}
          check (fn [calls] (mapv first (h/guard-4-problems calls {})))]
      (testing "config and the run-override pass"
        (is (nil? (h/guard-4-problems [ok (assoc ok :key-source :run-override :endpoint-source :run-override)] {}))))
      (testing "a key or endpoint the transport re-derived fails"
        (is (= [:guard-4] (check [(assoc ok :key-from :env)])))
        (is (= [:guard-4] (check [(assoc ok :key-from nil)])))
        (is (= [:guard-4] (check [(assoc ok :endpoint-from :default)]))))
      (testing "an allowlist: untagged, unresolved and absent sources fail"
        (doseq [v [:untagged :unresolved nil]]
          (is (= [:guard-4] (check [(assoc ok :key-source v)])) (pr-str v))
          (is (= [:guard-4] (check [(assoc ok :endpoint-source v)])) (pr-str v))))
      (let [ex {:e {:why "t" :excuses {:key-source :untagged :endpoint-source :untagged}}}
            untagged (assoc ok :key-source :untagged :endpoint-source :untagged)]
        (testing "an exception excuses exactly its fields, on its entry"
          (is (nil? (h/guard-4-problems [ok untagged] ex))))
        (testing "and nothing more: the same entry with a nil key still fails"
          (is (= [:guard-4] (mapv first (h/guard-4-problems [ok untagged (assoc untagged :key-from nil)] ex)))))
        (testing "another entry is not excused by it"
          (is (= [:guard-4] (mapv first (h/guard-4-problems [ok untagged (assoc untagged :entry :other)] ex)))))
        (testing "an exception that excuses no call fails"
          (is (= [[:vacuous-guard-4-exception :e]] (h/guard-4-problems [ok] ex))))))))
