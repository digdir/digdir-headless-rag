;; Audit: does the PRODUCTION-assembled agent config actually carry the proven
;; levers (snippet-AB prompt + search-snippets, no-tool-call guard, rerank
;; windowing), or did those only ever exist as sweep-fixture overlays?
;; Reproduces runner.clj's prod-base = build-rag-skill-params(dataset, {}, agent-skill-params).
(require '[digdir.config.db :as cfg-db]
         '[digdir.config.core :as cfg-core]
         '[digdir.agents.db :as agents-db]
         '[digdir.api.util :as api-util]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(let [conn (cfg-db/get-conn)
      master-key (cfg-core/get-master-key)
      tenant "digdir" ds-key "default" agent-id "builtin/agent-rag-agent"
      ds-cfg (cfg-db/get-dataset-by-ref @conn {:tenant tenant :dataset-config-key ds-key} master-key)
      agent (try (agents-db/get-agent @conn agent-id) (catch Exception _ nil))
      agent-sp (or (:skill-params agent) {})
      prod (api-util/build-rag-skill-params ds-cfg {} agent-sp)
      agent-cfg (:builtin/agent prod)
      retr-cfg (:builtin/retrieval prod)
      sys (:system-prompt agent-cfg)
      ;; the proven snippet prompt fixture, for comparison
      fixtures (try (edn/read-string (slurp "test/fixtures/sweep/agent_prompts.edn")) (catch Exception _ {}))
      snippet-fixture (:qwen-adapted-snippet fixtures)]
  (println "================ AGENT CONFIG AUDIT (tenant=digdir dataset=default agent=" agent-id ") ================")
  (println "dataset resolved:" (boolean ds-cfg) " | chunks-collection:" (:chunks-collection ds-cfg))
  (println "agent record found:" (boolean agent))
  (println)
  (println "--- LEVER 1: snippet-AB ---")
  (println "  :search-snippets =" (:search-snippets agent-cfg))
  (println "  system-prompt present:" (boolean sys) " | length:" (count (str sys)))
  (println "  system-prompt mentions 'snippet=':" (boolean (and sys (str/includes? sys "snippet="))))
  (println "  system-prompt == proven :qwen-adapted-snippet fixture:"
           (= (str/trim (str sys)) (str/trim (str snippet-fixture))))
  (when (and sys snippet-fixture (not= (str/trim (str sys)) (str/trim (str snippet-fixture))))
    (println "  (system-prompt differs from fixture — first 160 chars of each:)")
    (println "    PROD :" (subs (str sys) 0 (min 160 (count (str sys)))))
    (println "    FIXTURE:" (subs (str snippet-fixture) 0 (min 160 (count (str snippet-fixture))))))
  (println)
  (println "--- LEVER 2: no-tool-call guard ---")
  (println "  :no-tool-call-retry =" (:no-tool-call-retry agent-cfg))
  (println)
  (println "--- LEVER 3: rerank passage-windowing (Lever A) + retrieval keys ---")
  (println "  retrieval keys:" (sort (keys (or retr-cfg {}))))
  (doseq [k (sort (keys (or retr-cfg {})))]
    (when (re-find #"(?i)rerank|window|passage|colbert|rrf" (name k))
      (println "   " k "=>" (get retr-cfg k))))
  (println)
  (println "--- full :builtin/agent keys ---")
  (println "  " (sort (keys (or agent-cfg {}))))
  (println "  enrichment-search-targets:" (get-in retr-cfg [:enrichment-search-targets]))
  (System/exit 0))
