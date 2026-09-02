(ns digdir.rag.typesense
  "Shared utilities for Typesense API connections"
  (:require [digdir.config.accessor :as cfg]
            [digdir.config.db :as config-db]
            [digdir.config.core :as config-core]))

(defn make-ts-settings
  "Create Typesense connection settings map.

   Options:
     :tenant - Explicit tenant for config resolution (default: from env var)
     :environment - Explicit environment for config resolution (default: from env var)

   Returns a map with :uri and :key for connecting to Typesense."
  ([]
   (make-ts-settings {}))
  ([opts]
   (let [tenant (:tenant opts)
         environment (:environment opts)]
     (if (or tenant environment)
       ;; Use explicit tenant/environment - resolve directly from DB
       (let [conn (config-db/get-conn)
             db @conn
             effective-tenant (or tenant (config-core/get-tenant))
             effective-env (or environment (config-core/get-environment))
             master-key (config-core/get-master-key)
             resolved-config (config-db/load-resolved-config db effective-tenant effective-env nil master-key)
             typesense-config (get-in resolved-config [:services :typesense])
             host (:api-host typesense-config)
             tls (:api-tls typesense-config)
             api-key (:api-key-admin typesense-config)]
         {:uri (when host
                 (if (true? tls)
                   (str "https://" host)
                   (str "http://" host)))
          :key api-key})
       ;; No explicit scope - use global cache (existing behavior)
       (let [host (cfg/get :services :typesense :api-host)
             tls (cfg/get :services :typesense :api-tls)
             api-key (cfg/get :services :typesense :api-key-admin)]
         {:uri (when host
                 (if (true? tls)
                   (str "https://" host)
                   (str "http://" host)))
          :key api-key})))))

(def ts-admin
  "Default Typesense admin connection settings."
  (make-ts-settings))
