(ns digdir.rag.typesense
  "Shared utilities for Typesense API connections.

   ⚠️ THERE IS NO DEFAULT TENANT HERE, DELIBERATELY, AND RE-ADDING ONE WOULD
   RECREATE #476. This namespace used to expose a `ts-admin` value resolved at
   namespace load with no tenant, falling back to a hardcoded
   [\"digdir\" \"public-sector-knowledge\"]. The ingest path used it, so a
   pipeline running for ANY other tenant silently ingested using digdir's host
   and digdir's admin key. On a clean install it failed instead, with
   `MalformedURLException: no protocol: /collections`, because the URI resolved
   empty — which is how it was found.

   THE PRINCIPLE, because the defensive instinct here is exactly wrong:

     A DEFAULT IN THE RESOLVER ANSWERS FOR CALLERS IT HAS NEVER MET.
     A CONSTANT AT A CALL SITE ANSWERS ONLY FOR ITSELF.

   A resolver is a shared door. A default behind it serves every caller in the
   system, invisibly, including the ones written after it. A caller that names
   its own tenant is asserting something about itself, in one greppable place,
   attributable to whoever wrote it. The first is what was removed; the second
   is what `digdir.ui.main` now does, with a comment pointing at #479.

   So: a missing precondition is not permission. A tenant-less resolution
   THROWS and names what it lacked."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]))

(def required-platform-keys
  "The `services.typesense.*` keys a tenant MUST supply before settings resolve.

   ⚠️ THE SOURCE OF TRUTH, NOT A COPY OF ONE. `resolve-ts-settings` derives its
   missing-key check from this vector rather than testing three inline literals,
   so anything that needs to know what a tenant must provide can read it here
   instead of restating it. Adding a key makes it required everywhere at once —
   including in whatever asserts that a given tenant is completely configured,
   which is the point: a fourth requirement should fail those checks
   automatically rather than waiting to be noticed.

   `:api-tls` is deliberately absent — it is read, but its absence is a default
   (plain http), not a missing precondition."
  [:api-host :api-key-admin])

(defn- platform-value
  [tenant k]
  (cfg/get-platform-value [:services :typesense k] {:tenant tenant :default nil}))

(defn resolve-ts-settings
  "Typesense settings for `tenant`, or a map describing what is missing.

   Returns either `{:settings {:uri .. :key ..}}` or `{:missing [..] :reason ..}`
   so callers can report the cause rather than re-deriving it. Never falls back
   to another tenant."
  [tenant]
  (if (str/blank? tenant)
    {:missing [:tenant] :reason :no-tenant}
    (try
      (let [values (into {} (map (juxt identity #(platform-value tenant %)))
                         (conj required-platform-keys :api-tls))
            {host :api-host tls :api-tls api-key :api-key-admin} values
            ;; Derived from `required-platform-keys`, so a key added there is
            ;; required here without this function being touched. `:api-key-admin`
            ;; is in that vector rather than merely preferred because Typesense
            ;; rejects a keyless request with 401 (measured) — settings carrying
            ;; a nil key can only produce a confusing auth failure at the call
            ;; site instead of a named one here.
            missing (filterv #(str/blank? (str (get values %))) required-platform-keys)]
        (if (seq missing)
          {:missing missing :reason :incomplete-config}
          {:settings {:uri (if (true? tls)
                             (str "https://" host)
                             (str "http://" host))
                      :key api-key}}))
      (catch clojure.lang.ExceptionInfo e
        ;; A fresh DB has no tenant root nodes yet, which makes the platform
        ;; resolver throw rather than honor :default nil. Reported as a named
        ;; cause rather than swallowed to nil — "this tenant has no platform
        ;; tree" and "this tenant is misconfigured" are different problems and
        ;; used to be indistinguishable.
        (if (= :tenant-root-missing (:kind (ex-data e)))
          {:missing [:tenant-root] :reason :tenant-root-missing}
          (throw e))))))

(defn make-ts-settings
  "Typesense connection settings for `(:tenant opts)`.

   THROWS when no tenant is supplied, when the tenant has no platform tree, or
   when its `services.typesense` config lacks a host or an admin key. The
   ex-data names the tenant and the missing keys.

   There is no no-arg arity and no default tenant — see the namespace docstring
   and #476. A caller that does not know its tenant has a bug, or a design
   question (#479); it does not have a default."
  [opts]
  (let [tenant (:tenant opts)
        {:keys [settings missing reason]} (resolve-ts-settings tenant)]
    (or settings
        (throw (ex-info (case reason
                          :no-tenant
                          "Typesense settings requested with no tenant. There is no default tenant (#476) — pass {:tenant ...}."
                          :tenant-root-missing
                          (str "Tenant '" tenant "' has no platform config tree, so Typesense settings cannot be resolved.")
                          (str "Tenant '" tenant "' is missing Typesense config: "
                               (str/join ", " (map name missing))))
                        {:kind :typesense/settings-unresolved
                         :tenant tenant
                         :missing missing
                         :reason reason})))))
