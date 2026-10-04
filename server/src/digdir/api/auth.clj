(ns digdir.api.auth
  "THE one decision on which tenant and dataset an API key may act in.

   Every API-key door calls `authorize-scope!` at the point its tenant (and
   dataset, when it names one) is fixed - the REST handlers, execute's dataset
   selection, and the MCP, `/api/tools` and `/v1` tool calls. No door decides
   it on its own (a census pins that).

   The tenant axis FAILS CLOSED (the project owner's decision): a key reaches exactly the tenants
   it is granted (`api-keys/granted-tenants`, which `wrap-api-key-auth` puts on
   the request). An EMPTY grant is no tenant, never every tenant. A deliberate
   all-tenant key - an operator's, or a single-deployment key relying on the
   `TENANT` default - carries the explicit `:access-policy/all-tenants?` marker.

   The dataset axis: a door that names a dataset requires it in the key's
   dataset scopes, unless the key is marked all-tenant. A tenant grant is not a
   dataset grant."
  (:require [clojure.string :as str]
            [digdir.config.api-keys :as api-keys]))

(defn- present [s] (some-> s str str/trim not-empty))

(defn- scope-key [{:keys [tenant dataset-config-key]}] [(present tenant) (present dataset-config-key)])

(defn- granted
  "The request key's granted tenants: THE union (`api-keys/granted-tenants`)
   of the tenants `wrap-api-key-auth` attached and the
   tenants of the key's dataset scopes and config-node grants - a grant naming
   a tenant is a grant IN it."
  [ring-req]
  (set (keep present (api-keys/granted-tenants (:api-key/granted-tenants ring-req)
                                               (map :tenant (:api-key/dataset-scopes ring-req))
                                               (map :api-key.allowed-config-key/tenant (:api-key/allowed-config-keys ring-req))))))

(defn- config-grant-authorizes?
  "Whether the key's config grants authorize the dataset node `node-id` - the
   SAME decision as the config axis, `api-keys/require-allowed-config-key!`
  A key with no config grant authorizes nothing here."
  [ring-req tenant {:keys [conn node-id]}]
  (boolean
   (when-let [grants (seq (:api-key/allowed-config-keys ring-req))]
     (try (api-keys/require-allowed-config-key! conn grants {:root :dataset :tenant tenant :node-id (force node-id)})
          (catch clojure.lang.ExceptionInfo e
            (if (= 403 (:status (ex-data e))) nil (throw e)))))))

(defn all-tenants?
  "Whether the request's key carries the explicit all-tenant marker."
  [ring-req]
  (true? (:api-key/all-tenants? ring-req)))

(defn authorize-scope!
  "Answer the tenant the request may act in, or refuse. `scope` is `{:tenant t}`,
   `{:dataset-ref {:tenant :dataset-config-key}}`, or both (they must agree).
   ONLY the two CONFIG doors add `:dataset-node {:conn c :node-id id-or-delay}`:
   there the dataset axis is also satisfied by a `:dataset`-root config
   grant that authorizes that dataset's node - decided by
   `require-allowed-config-key!`, the config axis's own decision. A config
   grant is config access, never data access: the data doors do not pass it.
   - no tenant → 400 `:tenant-required`;
   - a tenant and a dataset ref naming different tenants → 400 `:scope-mismatch`;
   - a tenant the key is not granted, and no marker → 403 `:tenant-not-granted`;
   - a dataset not in the key's dataset scopes, and no marker → 403
     `:dataset-not-granted`."
  [ring-req {:keys [tenant dataset-ref dataset-node]}]
  (let [ref-tenant (present (:tenant dataset-ref))
        tenant (or (present tenant) ref-tenant)
        marked? (all-tenants? ring-req)]
    (when-not tenant
      (throw (ex-info "Name the tenant: every request acts in one tenant" {:status 400 :reason :tenant-required})))
    (when (and ref-tenant (not= tenant ref-tenant))
      (throw (ex-info (str "The request names tenant " tenant " and a dataset of tenant " ref-tenant)
                      {:status 400 :reason :scope-mismatch :tenant tenant :dataset-tenant ref-tenant})))
    (when-not (or marked? (contains? (granted ring-req) tenant))
      (throw (ex-info (str "This API key is not granted tenant: " tenant)
                      {:status 403 :reason :tenant-not-granted :tenant tenant})))
    (when (and dataset-ref (not marked?)
               (not (contains? (set (map scope-key (:api-key/dataset-scopes ring-req))) (scope-key dataset-ref)))
               (not (and dataset-node (config-grant-authorizes? ring-req tenant dataset-node))))
      (throw (ex-info "API key is not allowed to access the selected dataset"
                      {:status 403 :reason :dataset-not-granted :dataset-scope (select-keys dataset-ref [:tenant :dataset-config-key])})))
    tenant))
