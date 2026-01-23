(ns digdir.config.validator
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; ============================================================================
;; Config Validation Specs
;; ============================================================================

;; Basic types
(s/def ::non-empty-string (s/and string? #(not (str/blank? %))))
(s/def ::port (s/and int? #(< 0 % 65536)))
(s/def ::boolean boolean?)
(s/def ::keyword keyword?)
(s/def ::uuid-string (s/and string? #(re-matches #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" %)))

;; Database configuration
(s/def ::backend keyword?)
(s/def ::id ::non-empty-string)
(s/def ::schema-flexibility keyword?)
(s/def ::keep-history? ::boolean)
(s/def ::allow-unsafe-config ::boolean)
(s/def ::path ::non-empty-string)
(s/def ::dbtype ::non-empty-string)
(s/def ::host ::non-empty-string)
(s/def ::dbname ::non-empty-string)
(s/def ::table ::non-empty-string)
(s/def ::user (s/nilable ::non-empty-string))
(s/def ::password (s/nilable ::non-empty-string))
(s/def ::jdbcUrl (s/nilable ::non-empty-string))

(s/def ::store (s/keys :req-un [::backend]
                       :opt-un [::id ::path ::dbtype ::port ::dbname ::table ::user ::password ::jdbcUrl]))

(s/def ::db-config (s/keys :req-un [::store ::schema-flexibility]
                           :opt-un [::keep-history? ::allow-unsafe-config]))

(s/def ::mem ::db-config)
(s/def ::local ::db-config)
(s/def ::remote ::db-config)
(s/def ::distributed map?)

(s/def ::db-env keyword?)
(s/def ::db (s/keys :req-un [::mem ::local ::remote ::distributed]))

;; Chat entity configuration
(s/def ::image ::non-empty-string)
(s/def ::docs-collection ::non-empty-string)
(s/def ::chunks-collection ::non-empty-string)
(s/def ::phrases-collection ::non-empty-string)
(s/def ::phrase-gen-prompt ::non-empty-string)
;; New kebab-case prompt property names
(s/def ::prompt-query-relax ::non-empty-string)
(s/def ::prompt-rag-generate ::non-empty-string)
;; Legacy names for backward compatibility during migration
(s/def ::promptRagQueryRelax ::non-empty-string)
(s/def ::promptRagGenerate ::non-empty-string)

(s/def ::entity (s/keys :req-un [::id ::image
                                 ::docs-collection ::chunks-collection ::phrases-collection
                                 ::phrase-gen-prompt]
                        ;; Accept both old and new property names during migration
                        :opt-un [::promptRagQueryRelax ::promptRagGenerate
                                 ::prompt-query-relax ::prompt-rag-generate]))

(s/def ::entities (s/coll-of ::entity :kind vector?))
(s/def ::default-entity-id ::uuid-string)

;; Filter value configuration
(s/def ::type keyword?)
(s/def ::expanded? ::boolean)
(s/def ::selected-options set?)
(s/def ::field ::non-empty-string)

(s/def ::filter-field (s/keys :req-un [::type ::expanded? ::selected-options ::field]))
(s/def ::fields (s/coll-of ::filter-field :kind vector?))
(s/def ::default-filter-value (s/keys :req-un [::type ::fields]))

(s/def ::chat (s/keys :req-un [::entities ::default-entity-id]
                      :opt-un [::default-filter-value]))

;; Service configurations
(s/def ::api-host ::non-empty-string)
(s/def ::api-key (s/nilable ::non-empty-string))
(s/def ::api-key-admin (s/nilable ::non-empty-string))
(s/def ::api-tls (s/nilable ::boolean))

(s/def ::typesense (s/keys :req-un [::api-host ::api-key ::api-key-admin]
                           :opt-un [::api-tls]))

(s/def ::use-azure-openai-api ::boolean)
(s/def ::api-endpoint ::non-empty-string)
(s/def ::api-version ::non-empty-string)
(s/def ::deployment-name ::non-empty-string)
(s/def ::model-name ::non-empty-string)
(s/def ::available-models (s/coll-of string? :kind vector?))
(s/def ::llm-select? ::boolean)

(s/def ::azure-openai (s/keys :req-un [::use-azure-openai-api ::api-endpoint ::api-key
                                       ::api-version ::deployment-name ::model-name
                                       ::available-models ::llm-select?]))

(s/def ::openrouter (s/keys :req-un [::api-key]))

(s/def ::api-url ::non-empty-string)
(s/def ::colbert (s/keys :req-un [::api-url ::api-key]))

(s/def ::project-id (s/nilable ::non-empty-string))
(s/def ::region ::non-empty-string)
(s/def ::scaleway-tem (s/keys :req-un [::api-key ::project-id ::region]))

(s/def ::trust-x-forwarded-for ::boolean)
(s/def ::rate-limiting (s/keys :req-un [::trust-x-forwarded-for]))

;; DEPRECATED: These are legacy auth configuration options
;; Domain whitelist and admin emails are replaced by the permissions system
;; See digdir.config.permissions for the new ABAC-based access control
(s/def ::use-db (s/nilable ::boolean))
(s/def ::approved-domains (s/coll-of string? :kind vector?))  ;; DEPRECATED
(s/def ::admin-user-emails (s/nilable string?))               ;; DEPRECATED

(s/def ::jwt-secret (s/nilable ::non-empty-string))
(s/def ::jwt-cookie-max-age (s/and int? pos?))
(s/def ::session-max-age (s/and int? pos?))
(s/def ::jwt-token-expiry-hours (s/and int? pos?))
(s/def ::secure-cookies? (s/nilable ::boolean))
(s/def ::auth (s/keys :req-un [::jwt-secret]
                      :opt-un [::use-db ::approved-domains ::admin-user-emails
                               ::jwt-cookie-max-age ::session-max-age ::jwt-token-expiry-hours
                               ::secure-cookies?]))

(s/def ::services (s/keys :req-un [::typesense ::azure-openai ::openrouter ::colbert
                                   ::scaleway-tem ::rate-limiting ::auth]))

;; Root config spec
(s/def ::config (s/keys :req-un [::db-env ::db ::chat ::services]))

;; ============================================================================
;; Validation Functions
;; ============================================================================

(defn validate-config
  "Validates a config map against the config spec.
   Returns {:valid? true} if valid,
           {:valid? false :errors [...]} if invalid."
  [config]
  (if (s/valid? ::config config)
    {:valid? true}
    {:valid? false
     :errors (s/explain-data ::config config)}))

(defn validate-config!
  "Validates a config map and throws an exception if invalid.
   Returns the config if valid."
  [config]
  (if (s/valid? ::config config)
    config
    (throw (ex-info "Invalid configuration"
                    {:errors (s/explain-str ::config config)}))))

(defn validate-critical-values
  "Validates critical configuration values that MUST be present.
   Returns a vector of error messages for missing critical values."
  [config]
  (let [errors []]
    (cond-> errors
      (empty? (get-in config [:chat :entities]))
      (conj "Configuration error: No chat entities defined. At least one entity is required.")

      (not (get-in config [:chat :default-entity-id]))
      (conj "Configuration error: No default entity ID specified. :default-entity-id is required.")

      (not (get-in config [:db :remote :store :jdbcUrl]))
      (conj "Configuration error: Database :jdbcUrl not configured.")

      (not (get-in config [:db :remote :store :table]))
      (conj "Configuration error: Database remote table not configured.")

      (not (get-in config [:services :auth :jwt-secret]))
      (conj "Configuration error: JWT secret not configured. Set JWT_SECRET environment variable."))))

(defn validate-and-report
  "Validates config and prints friendly error messages.
   Returns the config if valid, throws exception if invalid."
  [config]
  (println "Validating configuration...")

  ;; Check critical values first
  (let [critical-errors (validate-critical-values config)]
    (when (seq critical-errors)
      (println "\n❌ Critical configuration errors:")
      (doseq [error critical-errors]
        (println "  -" error))
      (throw (ex-info "Critical configuration values missing" {:errors critical-errors}))))

  ;; Then validate full spec
  (let [result (validate-config config)]
    (if (:valid? result)
      (do
        (println "✅ Configuration is valid!")
        config)
      (do
        (println "\n❌ Configuration validation failed:")
        (println (s/explain-str ::config config))
        (throw (ex-info "Configuration validation failed" result))))))

(comment
  ;; Test validation
  (require '[digdir.config.core :as config])
  (validate-and-report @config/!config))
