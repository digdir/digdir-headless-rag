(ns digdir.rag.typesense
  "Shared utilities for Typesense API connections"
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]))

(def ^:private default-typesense-tenants
  ["digdir" "public-sector-knowledge"])

(defn- resolve-tenant-ts-settings
  [tenant]
  (when-not (str/blank? tenant)
    (try
      (let [host (cfg/get-platform-value [:services :typesense :api-host]
                                         {:tenant tenant :default nil})
            tls (cfg/get-platform-value [:services :typesense :api-tls]
                                        {:tenant tenant :default nil})
            api-key (cfg/get-platform-value [:services :typesense :api-key-admin]
                                            {:tenant tenant :default nil})]
        (when host
          {:uri (if (true? tls)
                  (str "https://" host)
                  (str "http://" host))
           :key api-key}))
      (catch clojure.lang.ExceptionInfo e
        ;; A fresh DB has no tenant root nodes yet, which makes the platform
        ;; resolver throw rather than honor :default nil. Treat that as
        ;; "no settings yet" so this namespace can load during bootstrap and
        ;; import workflows. Other errors propagate.
        (if (= :tenant-root-missing (:kind (ex-data e)))
          nil
          (throw e))))))

(defn make-ts-settings
  "Create Typesense connection settings map.

   Options:
     :tenant - Explicit tenant for config resolution

   Returns a map with :uri and :key for connecting to Typesense."
  ([]
   (make-ts-settings {}))
  ([opts]
   (let [tenant (:tenant opts)]
     (if tenant
       (resolve-tenant-ts-settings tenant)
       (some resolve-tenant-ts-settings default-typesense-tenants)))))

(def ts-admin
  "Default Typesense admin connection settings.

   Wrapped in a try so this namespace can load when there's no DB to query
   yet — happens on cljs release builds (no bootstrap config in the build
   container) and on fresh-DB bootstrap workflows. Runtime callers passing
   an explicit tenant still get the precise :tenant-root-missing handling
   inside resolve-tenant-ts-settings."
  (try
    (make-ts-settings)
    (catch Throwable _ nil)))
