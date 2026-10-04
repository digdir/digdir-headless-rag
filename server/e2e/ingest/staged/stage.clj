;; The STAGED pre-migration store. Run by the image's own jar, BEFORE any
;; product code has connected to the store:
;;
;;   java -cp app.jar clojure.main /staged/stage.clj
;;
;; It writes what a deployment from before the April/May retraction migrations
;; held: definitions at paths those migrations retract, values for the paths
;; that may carry one, and audit rows naming every one of them. It opens the
;; store DIRECTLY with the config `init-db!` would use, and never through
;; `get-conn`: the first product call that connects runs `prepare-store!`, which
;; runs the migrations and marks them applied - so it must find this state.
;;
;; THE STARTING STATE IS CONSTRUCTED BY US. The migrations that then run are the
;; product's own, on its own boot path; this file only decides what they find.
;;
;; Writes one EDN map to /exchange/staged.edn, and exits non-zero if the
;; premise does not hold: a migration marker already present means `init-db!`
;; ran, and the migrations would find nothing to retract.
(require '[datahike.api :as d]
         '[digdir.config.audit :as audit]
         '[digdir.config.core :as config-core]
         '[digdir.config.db :as config-db]
         '[digdir.config.ops.bootstrap :as ops-bootstrap]
         '[digdir.config.schema :as schema])

(def staged-tenant
  "Values for the retracted paths live on a tenant of their own, so the demo
   tenant the setup steps seed later is the product's, not ours."
  "staged-legacy")

(def env-migrated
  "Retracted by 2026-04-24-remove-env-migrated-paths (one-shot), which drops
   the definitions AND their values and keeps the audit rows. Each carries a
   value: that is the shape that broke restores."
  {"services.auth.use-db" [:boolean true]
   "services.auth.session-max-age" [:integer 3600]
   "services.rate-limiting.trust-x-forwarded-for" [:boolean false]})

(def definition-only
  "Retracted by the post-definition migrations 2026-04-27-rerank-mode-split and
   2026-05-08-retract-skills-retrieval-enabled. Both REFUSE to boot while an
   active value exists at the path, so a deployment that booted past them held
   the definition and its audit history, and no value."
  {"skills.rerank.max-chunk-length" :integer
   "skills.rerank.max-total-length" :integer
   "skills.rerank.max-context-length" :integer
   "skills.rerank.context.top-k" :integer
   "skills.rerank.context.max-chunk-length" :integer
   "skills.retrieval.enabled" :boolean})

(defn- define! [conn path value-type]
  (config-db/upsert-definition! conn {:path path
                                      :root :platform
                                      :value-type value-type
                                      :encrypted? false
                                      :category :services
                                      :service :other
                                      :sensitivity :internal
                                      :function :settings}))

(defn- audited-edit! [conn path value]
  (audit/log-global-change! conn {:action :global-edit
                                  :path path
                                  :tenant "__global__"
                                  :global-version 1
                                  :changelog "staged pre-migration history"
                                  :new-value value
                                  :user-email "staged@example.test"}))

(defn- markers [db]
  (d/q '[:find [?id ...] :where [_ :digdir.migration/id ?id]] db))

(let [bootstrap (config-core/load-bootstrap-config)
      cfg (get bootstrap (:db-env bootstrap))
      _ (when-not cfg (throw (ex-info "no store config: DATAHIKE_FILE_PATH unset?" {})))
      _ (when (d/database-exists? cfg)
          (throw (ex-info "the store already exists: staging must run before ANY boot" {:cfg (:store cfg)})))
      _ (d/create-database cfg)
      conn (d/connect cfg)]
  (d/transact conn {:tx-data schema/config-migration-schema})
  (doseq [[path [value-type _]] env-migrated] (define! conn path value-type))
  (doseq [[path value-type] definition-only] (define! conn path value-type))
  (ops-bootstrap/bootstrap-config-tree! conn {:root :platform
                                              :tenant staged-tenant
                                              :base-values (update-vals env-migrated second)})
  (let [audit-ids (into (sorted-map)
                        (for [[path v] (concat (update-vals env-migrated second)
                                               (update-vals definition-only (constantly "edited, since unset")))]
                          [path (audited-edit! conn path v)]))
        db @conn
        report {:tenant staged-tenant
                :defined (into (sorted-map)
                               (for [p (concat (keys env-migrated) (keys definition-only))]
                                 [p (some? (config-db/get-definition db p))]))
                :audit-ids audit-ids
                :migration-markers (vec (markers db))
                :init-db-ran? (some? (some-> (resolve 'digdir.data.db/!conn) deref deref))}]
    (d/release conn)
    ;; A FILE, not stdout: Datahike's writer thread logs to stdout as the
    ;; connection closes, and it landed in the middle of a printed report.
    (spit "/exchange/staged.edn" (pr-str report))
    (System/exit (if (and (empty? (:migration-markers report))
                          (not (:init-db-ran? report))
                          (every? true? (vals (:defined report))))
                   0
                   1))))
