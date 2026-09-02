(ns digdir.rag.live-context
  (:require [clj-http.client :as http]
            [digdir.api.context :as api-ctx]
            [digdir.config.accessor :as cfg]
            [digdir.rag.typesense :as ts-utils]))

(def default-dataset-ref
  {:tenant "public-sector-knowledge"
   :dataset-config-key "dev"})

(def default-agent-id
  "builtin/agent-rag-agent")

(def default-materialization-id
  "kudos")

(defn resolve-live-dataset-context!
  ([]
   (resolve-live-dataset-context! default-dataset-ref))
  ([dataset-ref]
   (let [{:keys [config]} (api-ctx/resolve-dataset-context-by-ref! dataset-ref)
         tenant (:tenant dataset-ref)
         dataset-config-key (:dataset-config-key dataset-ref)
         selector {:tenant tenant
                   :tenant-config-key dataset-config-key}]
     {:dataset-ref dataset-ref
      :dataset-config config
      :collection-names {:docs-collection (:docs-collection config)
                         :chunks-collection (:chunks-collection config)
                         :phrases-collection (:phrases-collection config)}
      :docs-collection (:docs-collection config)
      :chunks-collection (:chunks-collection config)
      :phrases-collection (:phrases-collection config)
      :colbert-url (cfg/get-platform-value [:services :colbert :api-url]
                                           (assoc selector :default nil))
      :colbert-api-key (cfg/get-platform-value [:services :colbert :api-key]
                                               (assoc selector :default nil))
      :ts-opts {:tenant tenant
                :dataset-config-key dataset-config-key}})))

(defn typesense-reachable?
  [ts-opts]
  (try
    (let [settings (ts-utils/make-ts-settings ts-opts)
          uri (:uri settings)]
      (when uri
        (= 200 (:status (http/get (str uri "/health")
                                  {:headers {"X-TYPESENSE-API-KEY" (:key settings)}
                                   :conn-timeout 3000
                                   :socket-timeout 3000})))))
    (catch Exception _
      false)))
