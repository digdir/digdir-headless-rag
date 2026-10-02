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
   "services.azure-openai.model-name" "generic-model"})

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

(deftest switch-value-keeps-unset-apart-from-false
  ;; provider_switch's boot refusal fires on UNSET only; collapsing it to a
  ;; boolean would refuse deliberate `false` deployments.
  (is (nil? (with {} #(provider/switch-value tenant))))
  (is (false? (with {switch false} #(provider/switch-value tenant))))
  (is (true? (with {switch true} #(provider/switch-value tenant)))))

;; ---------------------------------------------------------------------------
;; The call spec
;; ---------------------------------------------------------------------------

(deftest resolve-on-azure-carries-the-azure-credentials-and-their-source
  (is (= {:provider :azure
          :impl :azure
          :model "az-deployment"
          :api-key "az-key"
          :api-endpoint "https://azure.provider-test.invalid"
          :provider/source {:api-key {:from :config :path "services.azure-openai.api-key" :present? true}
                            :api-endpoint {:from :config :path "services.azure-openai.api-endpoint" :present? true}}}
         (with {switch true} #(provider/resolve tenant)))))

(deftest resolve-on-azure-with-no-configured-key-says-so
  ;; The key is nil, so wkok will fall back to AZURE_OPENAI_API_KEY — the
  ;; per-tenant -> process-global door Phase 2 closes. Phase 1 changes nothing
  ;; about it; the tag makes the state explicit instead of guessable.
  (let [spec (with {switch true "services.azure-openai.api-key" nil} #(provider/resolve tenant))]
    (is (nil? (:api-key spec)))
    (is (= {:from :config :path "services.azure-openai.api-key" :present? false}
           (get-in spec [:provider/source :api-key])))))

(deftest resolve-on-openai-compatible-leaves-credentials-to-the-transport
  ;; Phase 1 reproduces today exactly: the non-Azure sites passed NO opts, so
  ;; client.clj (secret / OPENAI_API_ENDPOINT / default) and wkok (env) chose.
  ;; The resolver never reads the environment itself.
  (doseq [values [{} {switch false}]]
    (is (= {:provider :openai-compatible
            :impl :openai
            :model "generic-model"
            :api-key nil
            :api-endpoint nil
            :provider/source {:api-key {:from :unresolved}
                              :api-endpoint {:from :unresolved}}}
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
    (testing "Azure: the switch, the deployment name, the key and the endpoint — not model-name"
      (is (= #{switch "services.azure-openai.deployment-name"
               "services.azure-openai.api-key" "services.azure-openai.api-endpoint"}
             (reads {switch true}))))
    (testing "openai-compatible: the switch and model-name only — no Azure credential"
      (is (= #{switch "services.azure-openai.model-name"} (reads {switch false}))))))

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
  (testing "no platform tree + a caller model: resolves without touching the default"
    (is (= {:provider :openai-compatible :model "step-model"}
           (select-keys (fx/with-install (merge azure-config {switch true}) {:tenant-tree? false}
                          #(provider/resolve tenant {:model "step-model"}))
                        [:provider :model]))))
  (testing "…whereas with no caller model it reads the default, and that read throws there, as it always did"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fx/with-install (merge azure-config {switch true}) {:tenant-tree? false}
                   #(provider/resolve tenant))))))

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
      (is (= #{[:services :azure-openai :use-azure-openai-api] [:services :azure-openai :deployment-name]}
             (set @seen))))))
