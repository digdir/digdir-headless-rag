(ns digdir.setup
  "Interactive setup wizard for initial system configuration."
  (:require [digdir.setup.common :as setup-common]
            [digdir.setup.config :as setup-config]
            [digdir.setup.workflow :as setup-workflow]))

(defn print-header []
  (setup-common/print-header))

(defn print-section [title]
  (setup-common/print-section title))

(defn prompt [message]
  (setup-common/prompt message))

(defn prompt-required [message]
  (setup-common/prompt-required message))

(defn prompt-yn [message default]
  (setup-common/prompt-yn message default))

(defn check-env-vars []
  (setup-common/check-env-vars))

(defn check-database-connection []
  (setup-common/check-database-connection))

(defn get-db-config []
  (setup-common/get-db-config))

(defn reset-database! []
  (setup-common/reset-database!))

(defn get-global-config
  "Get a setup-time default Platform config value from the internal defaults tree."
  [path]
  (setup-common/get-global-config path))

(defn prompt-with-default [message default]
  (setup-common/prompt-with-default message default))

(defn prompt-secret [message]
  (setup-common/prompt-secret message))

(defn ensure-config-definition!
  [path opts]
  (setup-config/ensure-config-definition! path opts))

(defn set-global-config!
  [path value]
  (setup-config/set-global-config! path value))

(defn ensure-email-config-definitions!
  []
  (setup-config/ensure-email-config-definitions!))

(defn ensure-typesense-config-definitions!
  []
  (setup-config/ensure-typesense-config-definitions!))

(defn ensure-azure-openai-config-definitions!
  []
  (setup-config/ensure-azure-openai-config-definitions!))

(defn ensure-other-services-config-definitions!
  []
  (setup-config/ensure-other-services-config-definitions!))

(defn ensure-pipeline-config-definitions!
  []
  (setup-config/ensure-pipeline-config-definitions!))

(defn ensure-skill-config-definitions!
  []
  (setup-config/ensure-skill-config-definitions!))

(defn ensure-all-config-definitions!
  []
  (setup-config/ensure-all-config-definitions!))

(defn setup-typesense-defaults
  []
  (setup-config/setup-typesense-defaults))

(def default-runtime-bootstrap-agent-id
  setup-workflow/default-runtime-bootstrap-agent-id)

(def default-runtime-bootstrap-values
  setup-workflow/default-runtime-bootstrap-values)

(defn setup-email-config []
  (setup-workflow/setup-email-config))

(defn resolve-file-path
  [path]
  (setup-workflow/resolve-file-path path))

(defn prompt-for-import-file
  []
  (setup-workflow/prompt-for-import-file))

(defn do-import-from-file!
  [file on-conflict]
  (setup-workflow/do-import-from-file! file on-conflict))

(defn setup-import-config []
  (setup-workflow/setup-import-config))

(defn get-all-users []
  (setup-workflow/get-all-users))

(defn get-admin-users []
  (setup-workflow/get-admin-users))

(defn create-admin-user!
  [email]
  (setup-workflow/create-admin-user! email))

(defn valid-email?
  [email]
  (setup-workflow/valid-email? email))

(defn add-admin-user-interactive!
  []
  (setup-workflow/add-admin-user-interactive!))

(defn setup-admin-users []
  (setup-workflow/setup-admin-users))

(defn show-legacy-migration-option []
  (setup-workflow/show-legacy-migration-option))

(defn get-existing-tenants
  []
  (setup-workflow/get-existing-tenants))

(defn bootstrap-tenant-platform-tree!
  [& args]
  (apply setup-workflow/bootstrap-tenant-platform-tree! args))

(defn bootstrap-tenant-dataset-tree!
  [& args]
  (apply setup-workflow/bootstrap-tenant-dataset-tree! args))

(defn bootstrap-tenant-runtime-tree!
  [& args]
  (apply setup-workflow/bootstrap-tenant-runtime-tree! args))

(defn setup-llm-provider []
  (setup-workflow/setup-llm-provider))

(defn setup-tenant-config []
  (setup-workflow/setup-tenant-config))

(defn show-summary []
  (setup-workflow/show-summary))

(defn -main
  [& args]
  (apply setup-workflow/-main args))
