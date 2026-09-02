(ns digdir.import-export.registry
  "Shared import/export entity registry."
  (:require [digdir.import-export.entities.api-keys :as api-keys]
            [digdir.import-export.entities.agents :as agents]
            [digdir.import-export.entities.config :as config]
            [digdir.import-export.entities.conversations :as conversations]
            [digdir.import-export.entities.folders :as folders]
            [digdir.import-export.entities.users :as users]))

(def ^:private system-entity-registry
  [{:key :config
    :depends-on []
    :export-fn (fn [config-conn _ opts]
                 (config/export-config-data config-conn {:master-key (:master-key opts)
                                                         :export-password (:export-password opts)
                                                         :include-audit? (:include-audit? opts true)}))
    :preview-fn (fn [config-conn _ normalized opts on-conflict]
                  (config/preview-import-config-data config-conn normalized {:master-key (:master-key opts)
                                                                             :export-password (:export-password opts)
                                                                             :on-conflict on-conflict}))
    :apply-fn (fn [config-conn _ normalized opts on-conflict]
                (config/import-config-data! config-conn normalized {:master-key (:master-key opts)
                                                                    :export-password (:export-password opts)
                                                                    :on-conflict on-conflict}))}
   {:key :users
    :depends-on [:config]
    :export-fn (fn [config-conn _ _]
                 {:users (users/export-users @config-conn)})
    :preview-fn (fn [config-conn _ normalized _ on-conflict]
                  (users/preview-import-users @config-conn (get-in normalized [:data :users]) on-conflict))
    :apply-fn (fn [config-conn _ normalized _ on-conflict]
                (users/import-users! config-conn (get-in normalized [:data :users]) on-conflict))}
   {:key :agents
    :depends-on [:config]
    :export-fn (fn [config-conn _ _]
                 {:agents (agents/export-agents @config-conn)})
    :preview-fn (fn [config-conn _ normalized _ on-conflict]
                  (agents/preview-import-agents @config-conn (get-in normalized [:data :agents]) on-conflict))
    :apply-fn (fn [config-conn _ normalized _ _]
                (agents/import-agents! config-conn (get-in normalized [:data :agents])))}
   {:key :folders
    :depends-on []
    :export-fn (fn [_ main-conn _]
                 {:folders (folders/export-folders @main-conn)})
    :preview-fn (fn [_ main-conn normalized _ on-conflict]
                  (folders/preview-import-folders @main-conn (get-in normalized [:data :folders]) on-conflict))
    :apply-fn (fn [_ main-conn normalized _ on-conflict]
                (folders/import-folders! main-conn (get-in normalized [:data :folders]) on-conflict))}
   {:key :api-keys
    :depends-on [:config :agents]
    :export-fn (fn [_ main-conn _]
                 {:api-keys (api-keys/export-api-keys @main-conn)})
    :preview-fn (fn [_ main-conn normalized _ on-conflict]
                  (api-keys/preview-import-api-keys @main-conn (get-in normalized [:data :api-keys]) on-conflict))
    :apply-fn (fn [config-conn main-conn normalized _ on-conflict]
                (api-keys/import-api-keys! config-conn main-conn (get-in normalized [:data :api-keys]) on-conflict))}
   {:key :conversations
    :depends-on [:users :agents :folders]
    :export-fn (fn [_ main-conn _]
                 {:conversations (conversations/export-conversations @main-conn)})
    :preview-fn (fn [_ main-conn normalized _ on-conflict]
                  (conversations/preview-import-conversations @main-conn (get-in normalized [:data :conversations]) on-conflict))
    :apply-fn (fn [_ main-conn normalized _ on-conflict]
                (conversations/import-conversations! main-conn (get-in normalized [:data :conversations]) on-conflict))}])

(defn- assert-entity-order!
  [entities]
  (loop [seen #{}
         [entity & more] entities]
    (when entity
      (let [missing-deps (seq (remove seen (:depends-on entity)))]
        (when missing-deps
          (throw (ex-info "System import/export entity registry is ordered incorrectly"
                          {:entity-key (:key entity)
                           :missing-deps (vec missing-deps)
                           :seen (vec seen)}))))
      (recur (conj seen (:key entity)) more))))

(def ordered-system-entities
  (let [entities system-entity-registry]
    (assert-entity-order! entities)
    entities))
