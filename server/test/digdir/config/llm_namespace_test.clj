(ns digdir.config.llm-namespace-test
  "Phase 2 of the provider-resolver change — `services.llm.*`, one config-backed home for the provider
   decision and the OpenAI-compatible credential pair.

   The DEFINITIONS landed first; their reader landed next and, in the same
   change, moved the OPENAI_* bridge rows onto `services.llm.*` and made
   AZURE_OPENAI_USE_AZURE seed `services.llm.provider`. The rows could not move
   first: that would have put `digdir.config.verify` and the operator's
   instructions on `services.llm.*` while the runtime still read env - a
   verifier reporting success on a path the runtime does not take, the Azure-switch default mismatch.

   Two kinds of test live here, and they fail in opposite directions:

     BUILD  — pin what Phase 2 adds. Red before it lands.
     HOLD   — pin what Phase 2 must NOT change. Green before AND after. The
              gate on the provider-resolver change was Phase 3's, so a Phase 2 that made the keyword
              selectors follow `services.llm.provider` would have crossed it.
              Phase 3 of the provider-resolver change then INVERTED that hold, deliberately:
              `services-llm-provider-moves-the-keyword-selectors`.

   Values never appear here."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.accessor :as accessor]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.deployment-specific :as ds]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.schema :as schema]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.llm.client]
            [digdir.secrets :as secrets]
            [digdir.setup.config :as setup-config]
            [digdir.skills.enrichment.propose-questions :as pq]))

(def ^:private llm-paths
  "What Phase 2 adds, plus `services.llm.model`, which Phase 4 adds with the
   `services.azure-openai.model-name` migration. Phase 2 deliberately left the
   model out: defining it before its reader existed would have shipped a path an
   operator can set to no effect, which is the trap the provider-resolver change
   exists to remove. Phase 4 defines it and reads it in the same change."
  {"services.llm.api-endpoint" {:value-type :string :encrypted? false}
   "services.llm.api-key"      {:value-type :string :encrypted? true}
   "services.llm.provider"     {:value-type :edn    :encrypted? false}
   "services.llm.model"        {:value-type :string :encrypted? false}})

(defn- with-env
  "Stub the environment through the seam `digdir.secrets` and the bridge both
   read, so the stub is on the door the code under test actually uses."
  [m f]
  (binding [secrets/*env-lookup* (fn [k] (get m k))]
    (f)))

(defn- capture-writes
  "Run `f` with config-DB writes captured instead of performed. Same stub shapes
   as `digdir.config.env-bridge-test`."
  [f]
  (let [writes (atom [])]
    (with-redefs [config-db/get-config-node-by-tenant-config-key (fn [& _] {:config.node/id "platform/t/default"})
                  config-core/get-master-key (constantly "test-master-key")
                  config-db/set-node-value! (fn [_ opts]
                                              (swap! writes conj (select-keys opts [:path :value]))
                                              :created)]
      [(f) @writes])))

(defn- registered-definitions
  "Every definition `ensure-all-config-definitions!` registers, as
   {path opts}, captured at `ensure-config-definition!` — the one function
   every ensure-* goes through — instead of written to a database."
  []
  (let [defs (atom {})]
    (with-redefs [setup-config/ensure-config-definition! (fn [path opts] (swap! defs assoc path opts))]
      (with-out-str (setup-config/ensure-all-config-definitions!)))
    @defs))

(def ^:private snapshot-path "../config/system-import.normalized.20260821.json")

(defn- snapshot-definitions
  "The committed snapshot's `data.definitions`, keyed by path. Parsed, never
   line-grepped: the file is one line of JSON, so a grep scores any needle 0 or
   1 for the whole file."
  []
  (let [f (io/file snapshot-path)]
    (when (.exists f)
      (->> (get-in (json/parse-string (slurp f) true) [:data :definitions])
           (map (juxt :config-def/path identity))
           (into {})))))

;; ---------------------------------------------------------------------------
;; BUILD — the definitions
;; ---------------------------------------------------------------------------

(deftest services-llm-definitions-are-registered
  (let [defs (registered-definitions)]
    (testing "the instrument can see a registration"
      ;; Positive control on the SAME capture: without it, an empty capture
      ;; would make every absence below pass.
      (is (contains? defs "services.azure-openai.use-azure-openai-api"))
      (is (contains? defs "services.colbert.api-key")))

    (doseq [[path {:keys [value-type encrypted?]}] llm-paths]
      (testing path
        (let [opts (get defs path)]
          (is (some? opts) (str path " is not registered by ensure-all-config-definitions!"))
          (is (= :platform (:root opts)))
          (is (= value-type (:value-type opts)))
          (is (= encrypted? (boolean (:encrypted? opts))))
          ;; Fork-owned, like the Azure family and the switch it replaces: the
          ;; env bridge seeds onto the tenant's own Platform/default node, and
          ;; `cfg/get` walks up to __global__ only for inherit-owned paths.
          (is (not= :inherit (:ownership opts))))))

    ;; FLIPPED by Phase 4 of the provider-resolver change. In Phase 2 this asserted the opposite: the model
    ;; was deliberately undefined while nothing read it, because a path an
    ;; operator can set to no effect is the trap the provider-resolver change exists to remove.
    ;; Phase 4 defines it and reads it in the same change.
    (testing "services.llm.model is defined by Phase 4, with its reader"
      (is (contains? defs "services.llm.model")))))

(deftest services-llm-definitions-ship-in-the-snapshot-and-are-decided
  ;; `every-services-path-is-decided` iterates the SNAPSHOT's definitions, so a
  ;; path registered only at runtime escapes it. Shipping the definitions is
  ;; what puts them under that forcing function.
  (let [snap (snapshot-definitions)]
    (testing "the instrument can read the snapshot"
      (is (<= 100 (count snap)))
      (is (contains? snap "services.azure-openai.use-azure-openai-api")))

    (doseq [[path {:keys [value-type encrypted?]}] llm-paths]
      (testing path
        (let [row (get snap path)]
          (is (some? row) (str path " is not in the committed snapshot"))
          (is (= (name value-type) (:config-def/value-type row))
              "the snapshot and the code must agree on the type")
          (is (= encrypted? (boolean (:config-def/encrypted? row)))))))

    (testing "the snapshot and the code describe each path identically"
      ;; The descriptions are worded true before AND after the reader lands, so
      ;; the reader needs no snapshot edit. This keeps that honest: a
      ;; description changed in code alone goes red here.
      ;;
      ;; ⚠️ The two `some?` checks are load-bearing. Without them a path missing
      ;; from BOTH code and snapshot compares nil = nil and this passes - which
      ;; is exactly how it behaved until the OpenRouter key-leak fix verification found it.
      (let [registered (registered-definitions)]
        (doseq [path (keys llm-paths)]
          (let [in-code (:description (get registered path))
                in-snap (:config-def/description (get snap path))]
            (is (some? in-code) (str path " has no description in code - is it registered?"))
            (is (some? in-snap) (str path " has no description in the snapshot - does it ship?"))
            (is (= in-code in-snap)
                (str path " is described differently in code and snapshot"))))))

    (testing "each path has its global-default decision made"
      (is (contains? ds/deployment-specific-paths "services.llm.api-endpoint"))
      (is (contains? ds/deployment-specific-paths "services.llm.api-key"))
      (is (contains? ds/globally-defaultable-paths "services.llm.provider")
          "the default is decided in code, once — :openai-compatible")
      ;; the model follows its legacy spelling's classification.
      ;; services.azure-openai.model-name is globally defaultable, and the two
      ;; must agree while one is the other's fallback - a migrated tenant would
      ;; otherwise resolve differently from an unmigrated one.
      (is (contains? ds/globally-defaultable-paths "services.llm.model")
          "the model is globally defaultable, as services.azure-openai.model-name is")
      (is (contains? ds/globally-defaultable-paths "services.azure-openai.model-name")
          "POSITIVE CONTROL: the legacy spelling is in the same set"))))

(defn- create-test-db []
  (let [cfg {:store {:backend :mem :id (str "llm-ns-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- delete-test-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(deftest the-provider-reads-back-as-a-keyword
  ;; A keyword stored under a path whose definition is not :edn comes back as
  ;; the string ":openai-compatible", and every `case` on it falls through.
  ;; The definition used is the one the code REGISTERS, not one written here.
  (let [spec (get (registered-definitions) "services.llm.provider")
        conn (create-test-db)]
    (try
      (is (some? spec) "services.llm.provider must be registered before it can round-trip")
      (when spec
        (config-db/upsert-definition! conn (assoc spec :path "services.llm.provider"))
        (ops-bootstrap/bootstrap-config-tree! conn {:root :platform
                                                    :tenant "t"
                                                    :base-values {"services.llm.provider" :openai-compatible}})
        (with-redefs [config-db/get-conn (constantly conn)]
          (let [v (accessor/get {:tenant "t"} :services :llm :provider)]
            (is (keyword? v) (str "read back as " (type v)))
            (is (= :openai-compatible v)))))
      (finally (delete-test-db conn)))))

;; ---------------------------------------------------------------------------
;; BUILD — the env bridge seeds services.llm.*
;; ---------------------------------------------------------------------------

(deftest the-openai-compatible-pair-is-seeded-into-services-llm
  (let [by-name (into {} (map (juxt :env-var identity)) env-bridge/env-config-bindings)]
    (testing "the rows are config-db rows at services.llm.*"
      (is (= {:destination :config-db :path "services.llm.api-endpoint" :value-type :string :secret? false}
             (select-keys (get by-name "OPENAI_API_ENDPOINT") [:destination :path :value-type :secret?])))
      (is (= {:destination :config-db :path "services.llm.api-key" :value-type :string :secret? true}
             (select-keys (get by-name "OPENAI_API_KEY") [:destination :path :value-type :secret?]))))
    (testing "seeding writes both, and only both"
      (let [[result writes] (capture-writes
                              #(with-env {"OPENAI_API_ENDPOINT" "set" "OPENAI_API_KEY" "set"}
                                 (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
        (is (= ["services.llm.api-endpoint" "services.llm.api-key"] (:paths-written result)))
        (is (= #{"services.llm.api-endpoint" "services.llm.api-key"} (set (map :path writes))))))))

(deftest the-legacy-switch-variable-seeds-the-provider
  ;; ONE variable for ONE decision. AZURE_OPENAI_USE_AZURE
  ;; writes `services.llm.provider`; it no longer writes the boolean, which
  ;; becomes an input only the resolver's fallback reads (live values are not
  ;; rewritten - migration, not replacement).
  (doseq [[raw expected] [["true" :azure] ["false" :openai-compatible]]]
    (let [[result writes] (capture-writes
                            #(with-env {"AZURE_OPENAI_USE_AZURE" raw}
                               (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
      (is (= ["services.llm.provider"] (:paths-written result)) raw)
      (is (= [{:path "services.llm.provider" :value expected}] writes) raw)
      (is (not-any? #(= "services.azure-openai.use-azure-openai-api" (:path %)) writes)
          "the boolean is no longer written"))))

(deftest the-legacy-model-variable-seeds-the-model
  ;; Phase 4 of the provider-resolver change, the same shape as the switch variable above: ONE variable for
  ;; ONE setting. AZURE_OPENAI_MODEL_NAME keeps its legacy NAME and now writes
  ;; `services.llm.model`. It no longer writes services.azure-openai.model-name,
  ;; which becomes an input only the resolver's fallback reads - live values are
  ;; not rewritten, because this is a migration, not a replacement.
  (let [by-name (into {} (map (juxt :env-var identity)) env-bridge/env-config-bindings)]
    (is (= {:destination :config-db :path "services.llm.model" :value-type :string :secret? false}
           (select-keys (get by-name "AZURE_OPENAI_MODEL_NAME") [:destination :path :value-type :secret?])))
    (let [[result writes] (capture-writes
                            #(with-env {"AZURE_OPENAI_MODEL_NAME" "a-model"}
                               (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
      (is (= ["services.llm.model"] (:paths-written result)))
      (is (= [{:path "services.llm.model" :value "a-model"}] writes))
      (is (not-any? #(= "services.azure-openai.model-name" (:path %)) writes)
          "the legacy path is no longer written"))))

;; ---------------------------------------------------------------------------
;; HOLD — the run-level knobs stay in the environment
;; ---------------------------------------------------------------------------

(def ^:private client-source "src/digdir/llm/client.clj")

(defn- run-level-knobs
  "Every OPENAI_* variable the client reads, DERIVED from its source rather
   than typed here, minus the endpoint/key pair Phase 2 moves. A knob added to
   the client tomorrow is covered the day it lands."
  []
  (let [f (io/file client-source)]
    (when (.exists f)
      (-> (set (re-seq #"\"(OPENAI_[A-Z_]+)\"" (slurp f)))
          (->> (map second) set)
          (disj "OPENAI_API_ENDPOINT" "OPENAI_API_KEY")))))

(deftest the-run-level-knobs-stay-environment-only
  ;; Deliberately global per-run knobs for sweeps and benchmarks (issue the provider-resolver change,
  ;; Phase 2 "out of scope"). Bridging one would freeze a sweep arm's setting
  ;; at the last import.
  (let [knobs (run-level-knobs)]
    (testing "the instrument found the family"
      (is (contains? knobs "OPENAI_TEMPERATURE"))
      (is (contains? knobs "OPENAI_REASONING_EFFORT"))
      (is (<= 8 (count knobs)) (str "derived only " (count knobs) " knobs from " client-source)))

    (testing "none of them is written into the config DB"
      (doseq [b env-bridge/env-config-bindings
              :when (contains? knobs (:env-var b))]
        (is (not (env-bridge/bridged? b)) (str (:env-var b) " must not be bridged"))))

    (testing "and none of them lands under services.llm"
      (is (empty? (filter #(and (contains? knobs (:env-var %))
                                (some-> (:path %) (str/starts-with? "services.llm.")))
                          env-bridge/env-config-bindings))))))

;; ---------------------------------------------------------------------------
;; services.llm.provider moves selectors 2, 3 and 4
;; ---------------------------------------------------------------------------

(defn- stub-config
  "A tenant that has chosen the generic OpenAI-compatible path through BOTH
   the switch and `services.llm.provider`, with its own `services.llm.*`
   credentials, and set no keyword selector. Azure credentials are present, as
   they are on any tenant that once used Azure."
  [_opts & ks]
  (case (vec ks)
    [:services :azure-openai :use-azure-openai-api] false
    [:services :llm :provider] :openai-compatible
    [:services :llm :api-key] "llm-key"
    [:services :llm :api-endpoint] "https://llm.example"
    [:services :azure-openai :model-name] "generic-model"
    [:services :azure-openai :api-key] "set"
    [:services :azure-openai :api-endpoint] "https://azure.example"
    [:services :azure-openai :deployment-name] "dep"
    nil))

(deftest services-llm-provider-moves-the-keyword-selectors
  ;; INVERTED by Phase 3 of the provider-resolver change, deliberately - that inversion is the mission's
  ;; done-condition. It was `services-llm-provider-does-not-move-the-keyword-selectors`,
  ;; the HOLD that kept selectors 2-4 on their own reads while Phase 3 was gated:
  ;; a tenant that said :openai-compatible everywhere still sent these three to
  ;; Azure. Now all three follow it. The Azure credentials are present, so a
  ;; selector that still defaulted to Azure would have somewhere to go.
  (let [sent (atom [])]
    (with-redefs [accessor/get stub-config
                  digdir.llm.client/create-chat-completion
                  (fn [_conversation opts]
                    (swap! sent conj opts)
                    {:choices [{:message {:content "{\"phrases\": []}"}}]})]

      (testing "selector 2 — search-phrases"
        (reset! sent [])
        (sp/create-chat-completion "t" {:messages [{:role "user" :content "x"}]})
        (is (= 1 (count @sent)) "the call must reach the client stub, or this proves nothing")
        (is (= [:openai "https://llm.example"] ((juxt :impl :api-endpoint) (first @sent)))))

      (testing "selector 3 — self-improvement enrichment"
        (reset! sent [])
        (pq/execute-propose-questions {:inputs {:chunk-id "c" :chunk-content "A passage."}
                                       :parameters {}
                                       :skill-params {:tenant "t"}})
        (is (= 1 (count @sent)) "the call must reach the client stub, or this proves nothing")
        (is (= [:openai "https://llm.example"] ((juxt :impl :api-endpoint) (first @sent)))))

      (testing "selector 4 — the loader"
        (reset! sent [])
        (loader/create-chat-completion "t" {:model "gpt-4o" :messages [{:role "user" :content "x"}]})
        (is (= 1 (count @sent)))
        (is (= [:openai "https://llm.example"] ((juxt :impl :api-endpoint) (first @sent))))))))

(deftest the-loader-keeps-its-azure-timeout
  ;; Phase 3 of the provider-resolver change routes the loader through `provider/resolve`, whose spec carries no
  ;; `:request`. The loader has always given its Azure calls 30s (wkok honours it);
  ;; the OpenAI-compatible branch of the client ignores `:request`, so only Azure
  ;; can lose it - and nothing else would notice.
  (let [sent (atom [])
        azure (fn [_opts & ks]
                (get {[:services :llm :provider] :azure
                      [:services :azure-openai :api-key] "set"
                      [:services :azure-openai :api-endpoint] "https://azure.example"
                      [:services :azure-openai :deployment-name] "dep"}
                     (vec ks)))]
    (with-redefs [accessor/get azure
                  digdir.llm.client/create-chat-completion (fn [_conversation opts]
                                                             (swap! sent conj opts)
                                                             {:choices [{:message {:content "a, b"}}]})]
      (loader/create-chat-completion "t" {:model "gpt-4o" :messages [{:role "user" :content "x"}]}))
    (is (= 1 (count @sent)) "the call must reach the client stub, or this proves nothing")
    (is (= [:azure {:timeout 30000}] ((juxt :impl :request) (first @sent))))))
