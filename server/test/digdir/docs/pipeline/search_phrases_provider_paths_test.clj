(ns digdir.docs.pipeline.search-phrases-provider-paths-test
  "Every provider `services.search-phrases.provider` documents must be
   able to resolve its model path on a REAL config DB.

   `search-phrases-test` stubs `digdir.config.accessor/get` wholesale, and a
   stub answers for a path whether or not it is registered. That is how the
   :openrouter arm could throw on every install while its tests stayed green:
   `services.openrouter.model` had no definition, and an unregistered path
   throws rather than resolving to nil (`accessor/get`). Only a real
   definition table can see that, so this test registers definitions the way
   an install does and asks through the real accessor."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.config.deployment-specific :as ds]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.schema :as schema]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.setup.config :as setup-config]))

(defn- create-test-db []
  (let [cfg {:store {:backend :mem :id (str "sp-provider-paths-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- delete-test-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- attempt
  "Run `f`, answering {:value v} or {:threw message} — so a throw is a FAIL
   that names itself instead of an ERROR that aborts the rest of the test."
  [f]
  (try {:value (f)}
       (catch Exception e {:threw (ex-message e)})))

(deftest every-documented-search-phrases-provider-can-resolve-its-model
  ;; The three values the selector's own definition documents (setup/config.clj,
  ;; "Keyword: :azure-openai, :openrouter, or :lmstudio"), and the model path
  ;; each reads (search_phrases.clj `resolve-model`).
  (let [conn (create-test-db)]
    (try
      (with-redefs [config-db/get-conn (constantly conn)]
        (with-out-str (setup-config/ensure-all-config-definitions!))
        (ops-bootstrap/bootstrap-config-tree! conn {:root :platform
                                                    :tenant "t"
                                                    :base-values {"services.azure-openai.deployment-name" "dep"
                                                                  "services.lmstudio.model" "lm-model"}})

        (testing "the instrument can see the throw this test is about"
          (is (re-find #"No config definition registered"
                       (str (:threw (attempt #(accessor/get {:tenant "t"} :services :openrouter :no-such-path)))))))

        (testing "the two arms that always worked"
          (is (= {:value "dep"} (attempt #(#'sp/resolve-model "t" :azure-openai))))
          (is (= {:value "lm-model"} (attempt #(#'sp/resolve-model "t" :lmstudio)))))

        (testing ":openrouter, unset, refuses naming its path rather than passing nil on"
          ;; Not the OpenRouter model-registration issue throw: the path is defined now. OpenRouter has no
          ;; "whatever model is loaded" default, so nil could only fail at the vendor.
          (let [{:keys [threw]} (attempt #(#'sp/resolve-model "t" :openrouter))]
            (is (some? threw) "unset must not resolve to nil")
            (is (not (re-find #"No config definition registered" (str threw))) "the path IS registered")
            (is (re-find #"services\.openrouter\.model" (str threw)))))

        (testing ":openrouter, set, answers its model"
          (is (= {:value "or-model"}
                 (attempt #(do (ops-bootstrap/bootstrap-config-tree! conn {:root :platform
                                                                          :tenant "t2"
                                                                          :base-values {"services.openrouter.model" "or-model"}})
                               (#'sp/resolve-model "t2" :openrouter)))))))
      (finally (delete-test-db conn)))))

(deftest the-openrouter-model-definition-ships-and-is-decided
  ;; Registered at runtime is not enough: `every-services-path-is-decided`
  ;; iterates the SNAPSHOT's definitions, so a runtime-only path escapes it.
  (let [f (io/file "../config/system-import.normalized.20260821.json")
        defs (when (.exists f)
               (->> (get-in (json/parse-string (slurp f) true) [:data :definitions])
                    (map (juxt :config-def/path identity))
                    (into {})))]
    (is (contains? defs "services.openrouter.api-key") "positive control: the reader sees the sibling")
    (is (= "string" (:config-def/value-type (get defs "services.openrouter.model")))
        "services.openrouter.model is not in the committed snapshot")
    (is (contains? ds/globally-defaultable-paths "services.openrouter.model")
        "like services.lmstudio.model, a model name has a correct global default")))
