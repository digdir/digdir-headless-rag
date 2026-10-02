(ns digdir.config.llm-namespace-test
  "Phase 2 of the provider-resolver change — `services.llm.*`, one config-backed home for the provider
   decision and the OpenAI-compatible credential pair.

   This change lands the DEFINITIONS only. Nothing reads them until their reader lands, and the
   OPENAI_* bridge rows deliberately stay on the environment until then: moving
   them ahead of their reader would put `digdir.config.verify` and the
   operator's instructions on `services.llm.*` while the runtime still reads
   env - a verifier that reports success on a path the runtime does not take,
   which is the Azure-switch default mismatch. The seeding tests arrive with the reader.

   Two kinds of test live here, and they fail in opposite directions:

     BUILD  — pin what Phase 2 adds. Red before it lands.
     HOLD   — pin what Phase 2 must NOT change. Green before AND after. The
              gate on the provider-resolver change is Phase 3's, so a Phase 2 that makes the keyword
              selectors follow `services.llm.provider` has crossed it; the
              HOLD tests are how that would show.

   Values never appear here."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.config.deployment-specific :as ds]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.schema :as schema]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.llm.client]
            [digdir.setup.config :as setup-config]
            [digdir.skills.enrichment.propose-questions :as pq]))

(def ^:private llm-paths
  "What Phase 2 adds. `services.llm.model` is deliberately NOT here: it
   arrives in Phase 4 with the `services.azure-openai.model-name` migration,
   and defining it earlier would ship a path an operator can set to no effect.
  "
  {"services.llm.api-endpoint" {:value-type :string :encrypted? false}
   "services.llm.api-key"      {:value-type :string :encrypted? true}
   "services.llm.provider"     {:value-type :edn    :encrypted? false}})

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
      (is (contains? defs "services.lmstudio.api-key")))

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

    (testing "services.llm.model is NOT defined in Phase 2"
      (is (not (contains? defs "services.llm.model"))))))

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
      ;; is exactly how it behaved until the OpenRouter key-leak fix verification found it. The
      ;; union-based `provider-selector-pins-test/selector-2-openrouter-arm-reaches-openrouter`
      ;; cannot tell the halves apart, so per-half evidence for
      ;; `services.openrouter.model` lives here and in
      ;; `search-phrases-provider-paths-test` (runtime half: every-documented-…;
      ;; snapshot half: the-openrouter-model-definition-ships-…).
      (let [registered (registered-definitions)]
        (doseq [path (conj (vec (keys llm-paths)) "services.openrouter.model")]
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
          "the default is decided in code, once — :openai-compatible"))))

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
;; HOLD — services.llm.provider does not move selectors 2, 3 or 4 (the gate)
;; ---------------------------------------------------------------------------

(defn- stub-config
  "A tenant that has chosen the generic OpenAI-compatible path through BOTH
   the switch and `services.llm.provider`, and set no keyword selector. Azure
   credentials are present, as they are on any tenant that once used Azure."
  [_opts & ks]
  (case (vec ks)
    [:services :azure-openai :use-azure-openai-api] false
    [:services :llm :provider] :openai-compatible
    [:services :azure-openai :api-key] "set"
    [:services :azure-openai :api-endpoint] "https://azure.example"
    [:services :azure-openai :deployment-name] "dep"
    nil))

(deftest services-llm-provider-does-not-move-the-keyword-selectors
  ;; Phase 3 is gated on the provider-resolver change. Until it lands, selectors 2-4 keep their own
  ;; reads — so a tenant that says :openai-compatible everywhere Phase 2 lets
  ;; it still sends these three to Azure. When Phase 3 lands this test is
  ;; INVERTED, deliberately, and that inversion is the mission's done-condition.
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
        (is (= :azure (:impl (first @sent)))))

      (testing "selector 3 — self-improvement enrichment"
        (let [provider (#'pq/self-improvement-provider "t")]
          (is (= :azure-openai provider))
          (is (= :azure (:impl (#'pq/provider-impl "t" provider))))))

      (testing "selector 4 — the loader"
        (reset! sent [])
        (loader/create-chat-completion "t" {:model "gpt-4o" :messages [{:role "user" :content "x"}]})
        (is (= 1 (count @sent)))
        (is (= :azure (:impl (first @sent))))))))
