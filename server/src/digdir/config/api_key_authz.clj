(ns digdir.config.api-key-authz
  "THE authorization of an API-key operation, for BOTH doors: the console
   HTTP handlers (`digdir.api.routes.handlers`) and the Electric key panel
   (`digdir.config.ui.api-keys`). Two doors into one room answer alike because
   they call this one fn.

   Its own namespace, not `digdir.config.api-keys`: the admin decision is the
   ONE console guard, `digdir.config.ui.common/ensure-config-ui-admin!`, and the
   key store should not require the console UI's namespace."
  (:require [digdir.config.ui.common :as common]))

(def key-ops
  "Every API-key operation. Each needs an ADMIN and
   nothing more: any admin may perform any of them on any key, through either
   door. `:api-key/created-by` is attribution only; it grants nothing."
  #{:list :create :set-all-tenants :revoke :rotate :replace-allowed-config-keys})

(defn authorize-key-op!
  "Refuse `op` unless `actor` is an admin (the ONE console guard, 403
   `:not-admin`). `key-info` (the key as stored; nil for `:list`/`:create`) is
   not consulted: who created a key does not decide who may manage it.
   Returns true."
  [db actor op _key-info]
  (when-not (key-ops op)
    (throw (ex-info "Unknown API-key operation" {:status 500 :op op})))
  (common/ensure-config-ui-admin! db actor)
  true)
