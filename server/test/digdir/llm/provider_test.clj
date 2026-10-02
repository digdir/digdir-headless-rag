(ns digdir.llm.provider-test
  "`digdir.llm.provider` is the ONE read of the provider switch
   and the one place that turns it into a call spec.

   Every expectation here is today's behaviour, written as a literal — the
   read it replaces (`accessor/use-azure-openai?`) is deleted in the same
   diff, so it cannot be the oracle. the selector pins and its
   before-capture are the independent check that nothing moved."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.accessor :as accessor]
            [digdir.llm.provider :as provider]
            [digdir.llm.provider-fixtures :as fx]))

(def ^:private tenant "provider-test-tenant")
(def ^:private switch "services.azure-openai.use-azure-openai-api")

(def ^:private azure-config
  {"services.azure-openai.api-key" "az-key"
   "services.azure-openai.api-endpoint" "https://azure.provider-test.invalid"
   "services.azure-openai.deployment-name" "az-deployment"
   "services.azure-openai.model-name" "generic-model"
   ;; the openai-compatible branch now reads its own
   ;; per-tenant credentials instead of leaving them to the transport.
   "services.llm.api-key" "llm-key"
   "services.llm.api-endpoint" "https://llm.provider-test.invalid"})

(defn- with [values f] (fx/with-install (merge azure-config values) f))

;; ---------------------------------------------------------------------------
;; The decision
;; ---------------------------------------------------------------------------

(deftest selected-provider-answers-as-the-runtime-always-has
  (testing "UNSET MEANS NOT AZURE: defined, no value"
    (is (= :openai-compatible (with {} #(provider/selected-provider tenant)))))
  (testing "explicit false"
    (is (= :openai-compatible (with {switch false} #(provider/selected-provider tenant)))))
  (testing "explicit true"
    (is (= :azure (with {switch true} #(provider/selected-provider tenant)))))
  (testing "a tenant with no platform tree has not chosen a provider — not Azure"
    (is (= :openai-compatible
           (fx/with-install (merge azure-config {switch true}) {:tenant-tree? false}
             #(provider/selected-provider tenant)))))
  (testing "no definition registered for the switch: throws, exactly as the read it replaces did"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No config definition registered"
                          (fx/with-install azure-config
                            {:defined (disj (fx/fresh-install-definitions) switch)}
                            #(provider/selected-provider tenant))))))

(deftest configured-provider-keeps-unset-apart-from-a-chosen-provider
  ;; provider_switch's boot refusal fires on UNSET only; collapsing it would
  ;; refuse deliberate non-Azure deployments. FLIPPED by the provider-resolver change: the value is
  ;; now provider vocabulary, read from `services.llm.provider` with the boolean
  ;; as its fallback (`provider-read-both-test` pins the precedence).
  (is (nil? (with {} #(provider/configured-provider tenant))))
  (is (= :openai-compatible (with {switch false} #(provider/configured-provider tenant))))
  (is (= :azure (with {switch true} #(provider/configured-provider tenant)))))

;; ---------------------------------------------------------------------------
;; The call spec
;; ---------------------------------------------------------------------------

(deftest resolve-on-azure-carries-the-azure-credentials-and-their-source
  (is (= {:provider :azure
          :impl :azure
          :model "az-deployment"
          :api-key "az-key"
          :api-endpoint "https://azure.provider-test.invalid"
          ;; FLIPPED by the provider-resolver change: no `:present?` - a credential that is not
          ;; present refuses instead of being tagged and passed on.
          :provider/source {:api-key {:from :config :path "services.azure-openai.api-key"}
                            :api-endpoint {:from :config :path "services.azure-openai.api-endpoint"}
                            ;; which path supplied the model
                            :model {:from :config :path "services.azure-openai.deployment-name"}}}
         (with {switch true} #(provider/resolve tenant)))))

(deftest resolve-on-azure-with-no-configured-key-refuses-naming-it
  ;; FLIPPED by the provider-resolver change. Phase 1 passed the nil on, tagged `:present? false`,
  ;; and wkok filled it from AZURE_OPENAI_API_KEY - the per-tenant ->
  ;; process-global door Phase 2 closes. Now the nil never reaches wkok.
  (let [e (try (with {switch true "services.azure-openai.api-key" nil} #(provider/resolve tenant))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "resolve must refuse rather than return a nil key")
    (is (= "services.azure-openai.api-key" (:path (ex-data e))))))

(deftest resolve-on-openai-compatible-takes-credentials-from-services-llm
  ;; FLIPPED by the provider-resolver change. Phase 1 left these nil, and client.clj filled them
  ;; from OPENAI_API_* in the environment (process-global). They now come from
  ;; the tenant's own `services.llm.*`; the resolver still never reads the
  ;; environment itself.
  (doseq [values [{} {switch false}]]
    (is (= {:provider :openai-compatible
            :impl :openai
            :model "generic-model"
            :api-key "llm-key"
            :api-endpoint "https://llm.provider-test.invalid"
            :provider/source {:api-key {:from :config :path "services.llm.api-key"}
                              :api-endpoint {:from :config :path "services.llm.api-endpoint"}
                              ;; the legacy key holds the value here,
                              ;; and the spec says so rather than naming the new one
                              :model {:from :config :path "services.azure-openai.model-name"}}}
           (with values #(provider/resolve tenant)))
        (pr-str values))))

(deftest the-transport-key-is-never-the-provider-spelling
  ;; wkok 0.23.0 dispatches (case impl :openai … :azure …) with NO default, so
  ;; :openai-compatible reaching it throws "No matching clause".
  (doseq [values [{} {switch false} {switch true}]]
    (is (contains? #{:azure :openai} (:impl (with values #(provider/resolve tenant)))))))

(deftest resolve-reads-only-what-the-branch-it-takes-read-before
  ;; Reading a value today's path never touched can throw where it did not —
  ;; an encrypted Azure key on a non-Azure tenant is the obvious one.
  (let [reads (fn [values]
                (let [seen (atom [])]
                  (with values
                    ;; Wrap the INSTALL's stub (bound inside `with`), not the real accessor.
                    #(let [installed accessor/get]
                       (with-redefs [accessor/get (fn [opts & parts]
                                                    (swap! seen conj (vec parts))
                                                    (apply installed opts parts))]
                         (provider/resolve tenant))))
                  (is (seq @seen) "absolute: the wrapper saw reads at all")
                  (set (map (fn [p] (str/join "." (map name p))) @seen))))]
    ;; both branches now also read `services.llm.provider` (the
    ;; decision reads both paths), and openai-compatible reads its own
    ;; `services.llm.*` credentials. Still: neither branch reads the other's.
    (testing "Azure: the decision, the deployment name, the key and the endpoint — not model-name, not services.llm.*"
      (is (= #{"services.llm.provider" switch "services.azure-openai.deployment-name"
               "services.azure-openai.api-key" "services.azure-openai.api-endpoint"}
             (reads {switch true}))))
    ;; the openai-compatible branch reads the MODEL's two paths -
    ;; services.llm.model first, then the legacy services.azure-openai.model-name
    ;; as its fallback. Azure's model is its deployment name, unchanged.
    (testing "openai-compatible: the decision, BOTH model paths and its own services.llm.* — no Azure credential"
      (is (= #{"services.llm.provider" switch "services.llm.model" "services.azure-openai.model-name"
               "services.llm.api-key" "services.llm.api-endpoint"}
             (reads {switch false}))))))

(deftest a-caller-model-wins-and-the-default-is-not-even-read
  ;; Every call site's fallback was `(or model (if azure deployment-name
  ;; model-name))` — the `or` short-circuits, so a step that supplies :model
  ;; never read the default. On a tenant with no platform tree that read
  ;; throws, so reading it eagerly would turn a working call into a failure.
  (testing "the caller's model is the spec's model, on both branches"
    (is (= "step-model" (:model (with {switch true} #(provider/resolve tenant {:model "step-model"})))))
    (is (= "step-model" (:model (with {switch false} #(provider/resolve tenant {:model "step-model"}))))))
  (testing "an explicit nil model falls back, exactly as `or` did"
    (is (= "generic-model" (:model (with {switch false} #(provider/resolve tenant {:model nil}))))))
  (testing "no platform tree + a caller model: the default is still not read, but the call now REFUSES"
    ;; FLIPPED by the provider-resolver change. Phase 1 resolved this with nil credentials and the
    ;; transport borrowed the process environment. A tenant with no platform
    ;; tree has no credential of its own, so it refuses - naming the path, and
    ;; keeping `:kind` for callers that decide on it.
    (let [e (try (fx/with-install (merge azure-config {switch true}) {:tenant-tree? false}
                   #(provider/resolve tenant {:model "step-model"}))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :tenant-root-missing (:kind (ex-data e))))
      (is (= "services.llm.api-key" (:path (ex-data e))))))
  (testing "…whereas with no caller model it reads the default, and that read throws there, as it always did"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fx/with-install (merge azure-config {switch true}) {:tenant-tree? false}
                   #(provider/resolve tenant))))))

(deftest resolve-with-a-caller-model-never-reads-the-default-model
  ;; What `a-caller-model-wins-and-the-default-is-not-even-read` could see before
  ;; Phase 2 of the provider-resolver change, through its no-tree case: that case now refuses at the credential
  ;; read first, so it no longer shows whether the default was read. Pinned here
  ;; on a NORMAL tenant, on both branches: a caller model means neither
  ;; `deployment-name` nor `model-name` is read.
  (doseq [values [{switch true} {switch false}]]
    (let [seen (atom [])]
      (with values
        #(let [installed accessor/get]
           (with-redefs [accessor/get (fn [o & parts] (swap! seen conj (vec parts)) (apply installed o parts))]
             (provider/resolve tenant {:model "caller"}))))
      (is (seq @seen) "absolute: the wrapper saw reads at all")
      (is (not-any? #{[:services :azure-openai :deployment-name] [:services :azure-openai :model-name]} @seen)
          (str (pr-str values) " read a default model: " (pr-str @seen))))))

(deftest model-for-is-the-old-fallback-and-reads-no-credential
  ;; For a site that picks its model in one place and makes the call in
  ;; another (query_planner): today's `(or model (if azure deployment-name
  ;; model-name))`, without resolve's credential reads.
  (is (= "az-deployment" (with {switch true} #(provider/model-for tenant))))
  (is (= "generic-model" (with {switch false} #(provider/model-for tenant))))
  (is (= "generic-model" (with {} #(provider/model-for tenant nil))))
  (is (= "caller" (with {switch true} #(provider/model-for tenant "caller"))))
  (testing "a caller model short-circuits: not even the switch is read (the `or` did the same)"
    (let [seen (atom [])]
      (with {switch true}
        #(let [installed accessor/get]
           (with-redefs [accessor/get (fn [o & parts] (swap! seen conj (vec parts)) (apply installed o parts))]
             (provider/model-for tenant "caller"))))
      (is (= [] @seen))))
  (testing "without one: the switch and the default model only — never a credential"
    (let [seen (atom [])]
      (with {switch true}
        #(let [installed accessor/get]
           (with-redefs [accessor/get (fn [o & parts] (swap! seen conj (vec parts)) (apply installed o parts))]
             (provider/model-for tenant))))
      ;; the decision now reads `services.llm.provider` first.
      (is (= #{[:services :llm :provider] [:services :azure-openai :use-azure-openai-api]
               [:services :azure-openai :deployment-name]}
             (set @seen))))))
