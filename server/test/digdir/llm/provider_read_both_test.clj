(ns digdir.llm.provider-read-both-test
  "the provider decision reads BOTH paths, credentials come
   from config or the call refuses, and the sweep's run-override is the one
   non-config source.

     read-both   `services.llm.provider` wins when set; the legacy boolean is
                 the fallback; `:neither` is SAID, never defaulted to a path
                 that did not decide. Sources are `:llm-provider`,
                 `:legacy-switch`, `:neither` - deliberately not the paths'
                 own spellings, which the switch-reads census counts as reads.
     no nil      `resolve` never hands the transport a nil key or endpoint - a
                 nil there is filled from the process environment (wkok on
                 Azure, `digdir.llm.client` on the direct branch), which is the
                 per-tenant -> process-global door the provider-resolver change closes.
     override    src-dev's sweep installs an endpoint+key pair; production
                 never does (guarded in `provider-env-door-test`).

   New entry points are looked up by name so this namespace LOADS before they
   exist: a missing one fails the test that needs it, naming it, instead of
   failing the whole namespace to compile."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ;; Phase 4 of the provider-resolver change's parity guard asks the other doors that ask about
            ;; the model: the boot guard's configured signal and the verifier.
            [digdir.boot.provider-switch]
            [digdir.config.verify :as verify]
            [digdir.llm.provider :as provider]
            [digdir.llm.provider-fixtures :as fx]))

(def ^:private tenant "read-both-tenant")
(def ^:private switch "services.azure-openai.use-azure-openai-api")
(def ^:private llm-provider "services.llm.provider")

(def ^:private creds
  {"services.azure-openai.api-key" "az-key"
   "services.azure-openai.api-endpoint" "https://azure.read-both.invalid"
   "services.azure-openai.deployment-name" "az-deployment"
   "services.azure-openai.model-name" "generic-model"
   "services.llm.api-key" "llm-key"
   "services.llm.api-endpoint" "https://llm.read-both.invalid"})

(defn- with [values f] (fx/with-install (merge creds values) f))

(defn- entry
  "The var named `sym` in `digdir.llm.provider`, or a fn that throws naming it."
  [sym]
  (or (some-> (ns-resolve 'digdir.llm.provider sym) deref)
      (fn [& _] (throw (ex-info (str "digdir.llm.provider/" sym " does not exist") {:missing sym})))))

(use-fixtures :each
  (fn [f]
    (when-let [clear (ns-resolve 'digdir.llm.provider 'clear-run-override!)] (clear))
    (try (f)
         (finally (when-let [clear (ns-resolve 'digdir.llm.provider 'clear-run-override!)] (clear))))))

(defn- attempt [f]
  (try {:value (f)}
       (catch Exception e {:threw (ex-message e) :data (ex-data e)})))

;; ---------------------------------------------------------------------------
;; Read-both: one decision, two paths
;; ---------------------------------------------------------------------------

(deftest the-decision-reads-both-paths-and-says-which-decided
  (let [with-trace (entry 'configured-provider-with-trace)]
    (doseq [[label values expected]
            [["the new key wins over a disagreeing boolean (azure)"
              {llm-provider :azure switch false} {:value :azure :source :llm-provider}]
             ["the new key wins over a disagreeing boolean (openai-compatible)"
              {llm-provider :openai-compatible switch true} {:value :openai-compatible :source :llm-provider}]
             ["the boolean decides when the new key is unset: true"
              {switch true} {:value :azure :source :legacy-switch}]
             ["the boolean decides when the new key is unset: false"
              {switch false} {:value :openai-compatible :source :legacy-switch}]
             ["NEITHER set: nil, and the record SAYS neither"
              {} {:value nil :source :neither}]]]
      (testing label
        (is (= expected (with values #(with-trace tenant))))
        (is (= (:value expected) (with values #(provider/configured-provider tenant)))
            "configured-provider is the value of the same one read")))))

(deftest an-unset-decision-still-means-not-azure
  (is (= :openai-compatible (with {} #(provider/selected-provider tenant))))
  (is (= :azure (with {llm-provider :azure} #(provider/selected-provider tenant))))
  (is (= :openai-compatible (with {llm-provider :openai-compatible switch true} #(provider/selected-provider tenant)))
      "the new key overrides a legacy true"))

(deftest a-blank-provider-value-is-unset-not-invalid
  ;; Blank is absent (the env bridge skips blanks; the boot guard agrees), so
  ;; it falls through to the boolean exactly as nil does - not a refusal.
  (let [with-trace (entry 'configured-provider-with-trace)]
    (is (= {:value :azure :source :legacy-switch} (with {llm-provider "   " switch true} #(with-trace tenant))))
    (is (= {:value nil :source :neither} (with {llm-provider "  "} #(with-trace tenant))))))

(deftest a-provider-value-outside-the-vocabulary-refuses-naming-the-path
  ;; `:azure-openai` is the KEYWORD SELECTORS' spelling (services.search-phrases.provider)
  ;; and the likeliest thing an operator will type here by analogy. Routing it
  ;; to the default would be a silent wrong answer.
  (doseq [bad [:azure-openai :lmstudio "azure"]]
    (let [{:keys [threw data]} (with {llm-provider bad} #(attempt (fn [] (provider/configured-provider tenant))))]
      (is (some? threw) (str (pr-str bad) " must not resolve"))
      (is (= llm-provider (:path data)) (str (pr-str bad) ": " threw)))))

;; ---------------------------------------------------------------------------
;; resolve never returns a nil credential
;; ---------------------------------------------------------------------------

(deftest openai-compatible-credentials-come-from-services-llm
  (let [spec (with {llm-provider :openai-compatible} #(provider/resolve tenant))]
    (is (= "llm-key" (:api-key spec)))
    (is (= "https://llm.read-both.invalid" (:api-endpoint spec)))
    (is (= {:api-key {:from :config :path "services.llm.api-key"}
            :api-endpoint {:from :config :path "services.llm.api-endpoint"}
            ;; the model answers from a path too, and the spec
            ;; says which one. Here the legacy key holds the only value.
            :model {:from :config :path "services.azure-openai.model-name"}}
           (:provider/source spec)))))

(deftest azure-credentials-come-from-config-and-are-tagged-so
  (let [spec (with {llm-provider :azure} #(provider/resolve tenant))]
    (is (= "az-key" (:api-key spec)))
    (is (= {:api-key {:from :config :path "services.azure-openai.api-key"}
            :api-endpoint {:from :config :path "services.azure-openai.api-endpoint"}
            :model {:from :config :path "services.azure-openai.deployment-name"}}
           (:provider/source spec)))))

(deftest a-missing-credential-refuses-naming-its-path-on-both-branches
  (doseq [[branch decision path] [[:openai-compatible {llm-provider :openai-compatible} "services.llm.api-key"]
                                  [:openai-compatible {llm-provider :openai-compatible} "services.llm.api-endpoint"]
                                  [:azure {llm-provider :azure} "services.azure-openai.api-key"]
                                  [:azure {llm-provider :azure} "services.azure-openai.api-endpoint"]]
          [state v] [[:unset nil] [:blank "   "]]]
    (testing (str branch " " path " " state)
      (let [{:keys [threw data]} (with (assoc decision path v) #(attempt (fn [] (provider/resolve tenant))))]
        (is (some? threw) "a nil credential must never reach the transport")
        (is (= path (:path data)) (str "names the path: " threw))))))

;; ---------------------------------------------------------------------------
;; The sweep's run-override
;; ---------------------------------------------------------------------------

(deftest a-run-override-supplies-the-openai-compatible-pair
  (let [install! (entry 'install-run-override!)]
    (install! {:api-endpoint "https://sweep.read-both.invalid" :api-key "sweep-key"})
    (testing "it wins on the openai-compatible branch, and says so"
      (let [spec (with {llm-provider :openai-compatible
                        "services.llm.api-key" nil "services.llm.api-endpoint" nil}
                       #(provider/resolve tenant))]
        (is (= "sweep-key" (:api-key spec)))
        (is (= "https://sweep.read-both.invalid" (:api-endpoint spec)))
        (is (= {:api-key {:from :run-override} :api-endpoint {:from :run-override}
                :model {:from :config :path "services.azure-openai.model-name"}}
               (:provider/source spec))
            "the override supplies the pair, not the model: the model still says which key answered")))
    (testing "it does not touch the Azure branch"
      (let [spec (with {llm-provider :azure} #(provider/resolve tenant))]
        (is (= "az-key" (:api-key spec)))
        (is (= :config (get-in spec [:provider/source :api-key :from])))))
    (testing "cleared, config is back - and absent config refuses again"
      ((entry 'clear-run-override!))
      (is (some? (:threw (with {llm-provider :openai-compatible "services.llm.api-key" nil}
                               #(attempt (fn [] (provider/resolve tenant))))))))))

(deftest a-half-pair-is-refused-naming-the-missing-variable
  (let [install! (entry 'install-run-override!)]
    (doseq [[pair missing] [[{:api-endpoint "https://sweep.invalid"} "OPENAI_API_KEY"]
                            [{:api-key "sweep-key"} "OPENAI_API_ENDPOINT"]
                            [{:api-endpoint "https://sweep.invalid" :api-key "  "} "OPENAI_API_KEY"]]]
      (let [{:keys [threw]} (attempt #(install! pair))]
        (is (some? threw) (str "must refuse " (pr-str (keys pair))))
        (is (re-find (re-pattern missing) (str threw)) (str "names " missing ": " threw))))))

;; ---------------------------------------------------------------------------
;; the MODEL reads both paths, the same way the decision does
;; ---------------------------------------------------------------------------

(def ^:private llm-model "services.llm.model")
(def ^:private legacy-model "services.azure-openai.model-name")

(deftest the-model-reads-both-paths-and-says-which-decided
  ;; `services.llm.model` is the model; `services.azure-openai.model-name` is its
  ;; legacy spelling and the FALLBACK, so an operator can migrate values in any
  ;; order with nothing broken in between. The record says which answered, and
  ;; never names a path that did not: a source that reports a path which did not
  ;; answer is worse than none, because it will be believed.
  (let [with-trace (entry 'configured-model-with-trace)]
    (doseq [[label values expected]
            [["the new key wins when both are set"
              {llm-model "new-model"} {:value "new-model" :source :llm-model}]
             ["the new key alone"
              {llm-model "new-model" legacy-model nil} {:value "new-model" :source :llm-model}]
             ["the legacy key is the fallback when the new one is unset"
              {llm-model nil} {:value "generic-model" :source :legacy-model-name}]
             ["NEITHER set: nil, and the record SAYS neither"
              {llm-model nil legacy-model nil} {:value nil :source :neither}]
             ["blank is absent, so it falls through to the legacy key"
              {llm-model "   "} {:value "generic-model" :source :legacy-model-name}]
             ["blank on both is neither"
              {llm-model "  " legacy-model "   "} {:value nil :source :neither}]]]
      (testing label
        (is (= expected (with values #(with-trace tenant))))))))

(deftest the-spec-says-which-model-key-answered
  (testing "openai-compatible: the path that answered, and :neither when none did"
    (doseq [[label values expected-source expected-model]
            [["the new key" {llm-model "new-model"} {:from :config :path "services.llm.model"} "new-model"]
             ["the legacy key" {llm-model nil} {:from :config :path "services.azure-openai.model-name"} "generic-model"]
             ["neither" {llm-model nil legacy-model nil} {:from :neither} nil]]]
      (testing label
        (let [spec (with values #(provider/resolve tenant))]
          (is (= expected-source (get-in spec [:provider/source :model])))
          (is (= expected-model (:model spec))
              "the value and the source it names come from the same read")))))
  (testing "a caller that names its model is not attributed to a config path"
    (let [spec (with {} #(provider/resolve tenant {:model "caller-model"}))]
      (is (= "caller-model" (:model spec)))
      (is (= {:from :caller} (get-in spec [:provider/source :model])))))
  (testing "Azure: the deployment name, named as its own path"
    (let [spec (with {llm-provider :azure} #(provider/resolve tenant))]
      (is (= "az-deployment" (:model spec)))
      (is (= {:from :config :path "services.azure-openai.deployment-name"}
             (get-in spec [:provider/source :model]))))))

(deftest neither-model-key-set-leaves-the-model-nil-rather-than-refusing
  ;; Phase 4 is a RENAME. A refusal here would be a behaviour change with its
  ;; own blast radius (a server that serves its loaded model on a nil), so it
  ;; is its own decision with its own evidence. What Phase 4 adds is that the
  ;; record SAYS `:neither` instead of naming a path that did not answer.
  (let [spec (with {llm-model nil legacy-model nil} #(provider/resolve tenant))]
    (is (nil? (:model spec)))
    (is (= {:from :neither} (get-in spec [:provider/source :model])))
    (is (= "llm-key" (:api-key spec)) "the credentials still resolve: only the model is absent")))

(deftest every-door-that-asks-about-the-model-asks-about-both-keys
  ;; THE PARITY GUARD. Four sites ask about the model, and only one of them
  ;; reads it to route. `config/verify` says the rule in its own words about the
  ;; switch: a presence check of either raw path "would report it unconfigured
  ;; while the runtime routes it fine, which is the Azure-switch default mismatch at the verifier". So each
  ;; door here must STATE its answer, and they must agree - if a door stopped
  ;; answering, mutual absence would otherwise pass.
  (let [read-credentials (or (some-> (ns-resolve 'digdir.boot.provider-switch 'read-credentials) deref)
                            (fn [& _] (throw (ex-info "provider-switch/read-credentials is gone" {}))))]
    (doseq [[label values expected]
            [["the new key only" {llm-model "new-model" legacy-model nil} "new-model"]
             ["the legacy key only" {llm-model nil} "generic-model"]
             ["both set: the new one" {llm-model "new-model"} "new-model"]
             ["neither" {llm-model nil legacy-model nil} nil]]]
      (testing label
        (with values
          (fn []
            (let [resolver (provider/model-for tenant)
                  boot (:configured (read-credentials tenant))]
              (is (= expected resolver) "DOOR 1, the resolver, STATES a model")
              (is (= expected boot)
                  "DOOR 2, the boot guard's configured signal, answers from the same read")))))))
  (testing "DOOR 3, the verifier: it no longer presence-checks the legacy path, and it names
            both paths for REPORTING only"
    (let [required (set (map #(str/join "." %) verify/runtime-required-service-paths))
          reporting (ns-resolve 'digdir.config.verify 'model-decision-paths)]
      (is (not (contains? required "services.azure-openai.model-name"))
          "a raw presence check of the legacy path is the Azure-switch default mismatch at the verifier")
      (is (some? reporting) "digdir.config.verify/model-decision-paths does not exist")
      (when reporting
        (is (= #{"services.llm.model" "services.azure-openai.model-name"} (set @reporting))
            "both paths are named for reporting, spelled once each")))))
