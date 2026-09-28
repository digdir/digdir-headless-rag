;; Kobler hele Kudos-korpuset (Benjamins ferdig indekserte KUDOS_preprod_v4 på
;; typesense-test.digdir.cloud, 10 064 dokumenter) til den lokale stacken som
;; en egen tenant. Kjøres av seed.sh ved siden av, som leser .env.benjamin og
;; stopper backenden først — les den, ikke kjør denne direkte.
;;
;; Ingen innlasting: samlingene finnes alt. Typesense, ColBERT og Azure kommer
;; fra miljøet til denne ene kjøringen, via den vanlige env-broen, og skrives
;; bare på denne tenanten. Tenant `demo` og de lokale datasettene røres ikke.
;;
;; Miljø: KUDOS_TENANT, KUDOS_DATASET, KUDOS_COLLECTION_PREFIX,
;; KUDOS_DOCS_COLLECTION, KUDOS_CHUNKS_COLLECTION, KUDOS_PHRASES_COLLECTION,
;; pluss TYPESENSE_API_HOST/_TLS/_KEY_ADMIN, COLBERT_API_URL/_KEY og Azure-
;; variablene fra .env.
;;
;; Beregnet på en database der tenanten ikke finnes. En ny kjøring over en
;; tenant som alt er seedet er ikke prøvd.
(require '[clojure.string :as str]
         '[digdir.setup.workflow :as workflow]
         '[digdir.setup.common :as common]
         '[digdir.config.db :as config-db]
         '[digdir.config.core :as config-core]
         '[digdir.config.env-bridge :as env-bridge]
         '[digdir.pipeline.collections :as collections]
         '[digdir.mcp.tools :as tools]
         '[digdir.rag.retrieval :as retrieval])

(defn- env! [k]
  (let [v (System/getenv k)]
    (when (str/blank? v)
      (println "✘ mangler" k)
      (flush)
      (common/exit! 1))
    v))

(def tenant (or (System/getenv "KUDOS_TENANT") "kudos"))
(def dataset (or (System/getenv "KUDOS_DATASET") "kudos-full"))
(def collection-names
  {:docs-collection (env! "KUDOS_DOCS_COLLECTION")
   :chunks-collection (env! "KUDOS_CHUNKS_COLLECTION")
   :phrases-collection (env! "KUDOS_PHRASES_COLLECTION")})

(println "seeder tenant" tenant "med datasett" dataset "…")
(let [conn (config-db/get-conn)]
  (when-not conn (throw (ex-info "ingen config-conn" {})))
  (try (config-db/register-tenant! conn tenant {:name "Kudos (hele korpuset)"})
       (println "  ✔ tenant registrert")
       (catch Exception e (println "  · tenant fantes:" (.getMessage e))))
  (workflow/bootstrap-tenant-platform-tree! tenant {:tenant-name "Kudos (hele korpuset)"})
  (println "  ✔ plattformtre")
  (let [r (env-bridge/seed-config-from-env! conn tenant
                                            {:services #{:azure-openai :typesense :colbert}})]
    (println "  ✔ fra miljøet:" (:paths-written r))
    (when (:error r) (throw (ex-info "env-broen feilet" r))))

  (workflow/bootstrap-tenant-dataset-tree! tenant "default" dataset
    {:dataset-id dataset
     :dataset-values (merge {:name "Kudos (hele korpuset)"
                             :source-type :kudos
                             :collection-prefix (or (System/getenv "KUDOS_COLLECTION_PREFIX")
                                                    "KUDOS_preprod_v4_")}
                            collection-names)})
  (println "  ✔ datasett")

  ;; bootstrap-tenant-dataset-tree! legger samlingsnavnene på
  ;; materialiseringsnoden, men kjøretiden leser dem fra datasettets base-node.
  ;; Uten dette søker den med samling nil: 0 treff og «One or more search
  ;; parameters are malformed». Samme kall som rørledningen gjør etter en
  ;; innlasting.
  (collections/track-pipeline-collections!
    conn tenant "default" dataset dataset collection-names (config-core/get-master-key))
  (println "  ✔ samlingsnavn på base-noden"))

(workflow/bootstrap-tenant-runtime-tree! tenant
  {:dataset-id dataset
   :runtime-values (merge workflow/default-runtime-bootstrap-values
                          {:rerank-rag-max-chunk-length 4000})})
(println "  ✔ kjøretidstre")

;; Prøv før vi sier ferdig: kjøretiden må se samlingene, og et frasesøk mot
;; Benjamins Typesense må gi treff.
(let [cfg (tools/load-dataset-config {:tenant tenant :dataset-config-key dataset})
      hits (count (doall (retrieval/lookup-search-phrases-similar
                          (:phrases-collection cfg) (:docs-collection cfg)
                          ["DFØ årsrapport 2024"] nil {:tenant tenant :limit 20})))]
  (println "  kjøretiden ser:" (pr-str (select-keys cfg [:docs-collection :chunks-collection :phrases-collection])))
  (println "  frasesøk «DFØ årsrapport 2024»:" hits "treff")
  (flush)
  (common/exit! (if (pos? hits) 0 1)))
