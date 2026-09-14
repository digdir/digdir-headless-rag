(ns digdir.setup.demo-tenant
  "Platform defaults for the shipped demo tenant (#476).

   `digdir.setup.demo-dataset` ships the demo DATASET — the corpus, the
   materialization contract and the reranker budget. It does not give the demo
   tenant a PLATFORM tree, and that is the gap this closes.

   WHY IT MATTERS, AND WHY IT IS URGENT RATHER THAN TIDY. `digdir.rag.typesense`
   resolves Typesense per tenant from three platform paths. When a tenant has
   none, the resolver today falls back to a hardcoded list of two other tenant
   names — so the demo tenant does not fail, it SILENTLY BORROWS another
   tenant's Typesense credentials (#476). #476 removes that fallback, at which
   point a tenant with no platform values fails loudly instead. Either way the
   demo tenant needs its own values; after #476 it needs them to work at all.

   ⚠️ SECRETS ARE NOT AUTHORED HERE, DELIBERATELY. This namespace seeds only the
   three non-secret values. `services.typesense.api-key-admin` is
   `:encrypted? true` and continues to arrive from the environment through
   `digdir.config.env-bridge/seed-config-from-env!`, which reads
   `TYPESENSE_API_KEY_ADMIN` at run time. Nothing in this file is a credential,
   and nothing in it is encrypted — the config master key is itself still an
   open question (#288), so an authored template is the wrong place for a
   secret even when the storage layer would encrypt it.

   ⚠️ ORDERING, AND WHY `seed!` NOW CALLS THE BRIDGE ITSELF. `seed-config-from-env!`
   returns `:no-platform-default-node` unless the tenant's Platform/default node
   already exists, so bridging must happen AFTER the bootstrap here — which is
   precisely why it cannot be left to a separate caller that might run first.

   It previously was left to one, and there wasn't one (#494). This docstring
   said the key \"continues to arrive from the environment\" while nothing on the
   demo path ever called the bridge: the only automatic caller is
   `digdir.e2e.seed`, scoped to `#{:azure-openai}`. The mapping existed, the
   function worked when invoked by hand, and materialization still failed with
   `Tenant 'demo' is missing Typesense config: api-key-admin`.

   ⚠️ FINDING THE MECHANISM IS NOT EVIDENCE THAT IT RUNS. Everything about that
   docstring read as working. `demo-tenant-test/seed!-bridges-the-environment`
   asserts the CALL HAPPENS, not that the function exists."
  (:require [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.setup.common :as common]
            [digdir.setup.demo-dataset :as demo-dataset]
            [digdir.setup.workflow :as workflow]))

(def demo-tenant
  "The tenant the shipped dataset is seeded under. Taken from
   `demo-dataset` rather than restated, so the two cannot drift apart."
  demo-dataset/demo-tenant)

(def typesense-host
  "The newcomer compose's own Typesense container.

   `docker-compose.newcomer.yml` runs Typesense as the service `typesense` on
   8108, so this is the address that works on a clean install with nothing else
   configured. A deployment that runs Typesense elsewhere sets
   `TYPESENSE_API_HOST`, which the env bridge writes over this value."
  "typesense:8108")

(def typesense-tls?
  "TLS off, because the compose container serves plain HTTP on the docker
   network. `TYPESENSE_API_TLS` overrides it for a deployment that terminates
   TLS itself."
  false)

(def collection-prefix
  "A prefix that is the demo tenant's own rather than another tenant's.

   ⚠️ READ THIS BEFORE RELYING ON IT. This platform path currently has NO
   production consumer. Collection NAMES are built by
   `digdir.pipeline.collections/pipeline-collection-names` from the DATASET-level
   `:collection-prefix`, which `demo-dataset/dataset-values` already sets to
   `demo_norquad_`. So the isolation that actually keeps the demo's collections
   out of another tenant's namespace is already in place one level down, and
   this value is seeded for tenant self-consistency rather than to deliver it.

   It is seeded anyway for two reasons. A tenant whose platform tree omits a
   value its siblings all carry reads as an oversight, and if this path is ever
   wired up, the demo tenant should already be correct rather than inheriting
   the compose default — which is `digdir`, i.e. exactly the tenant #476 says
   we silently borrow from.

   ⚠️ And it is overwritable: `TYPESENSE_COLLECTION_PREFIX` is bridged to this
   path and the newcomer compose defaults it to `digdir`, so env bridging run
   after `seed!` replaces this value with that default. Harmless while nothing
   reads the path; a live trap for whoever wires it up."
  "demo_")

(def typesense-platform-values
  "The platform values this template authors, as `path -> value`.

   ⚠️ THIS SET IS NOT THE SAME SET AS THE RESOLVER'S, AND THE EARLIER NOTE HERE
   GOT THE RELATIONSHIP WRONG. It said these paths were duplicated from
   `digdir.rag.typesense`, which invited a collapse of the form
   `authored = required`. That would not survive contact with either side:

     authored here          {api-host, api-tls, collection-prefix}
     read by the resolver   {api-host, api-tls, api-key-admin}
     REQUIRED by it         {api-host, api-key-admin}  (`required-platform-keys`)

   Three of those disagree on purpose. `api-tls` is authored and NOT required —
   its absence is a default (plain http), not a missing precondition.
   `collection-prefix` is authored and the resolver never reads it at all. And
   `api-key-admin` is required and deliberately NEVER authored, because it is a
   credential and arrives from the environment.

   So the invariant that actually holds is a COVERAGE one, and it is what
   `demo-tenant-plus-env-covers-every-required-platform-key` asserts:

       authored ∪ env-supplied  ⊇  required-platform-keys

   derived on all three sides — `required-platform-keys` from
   `digdir.rag.typesense`, the env-supplied set from the env-bridge binding
   table, and the authored set from this map. A fourth required key therefore
   fails the demo template automatically instead of leaving it silently short.

   `services.typesense.api-key-admin` is absent by design — see the namespace
   docstring."
  {"services.typesense.api-host" typesense-host
   "services.typesense.api-tls" typesense-tls?
   "services.typesense.collection-prefix" collection-prefix})

(defn seed!
  "Give the demo tenant its own Platform/default node and Typesense values.

   Two steps, and the order is the point:

   1. `bootstrap-tenant-platform-tree!` with NO `:platform-values`, so the
      tenant receives the full platform defaults tree. Passing values here
      would REPLACE that tree rather than add to it — the argument is
      `(or platform-values (resolved-platform-default-values …))` — which would
      leave the demo tenant holding three values and nothing else.
   2. The authored values written onto that node individually, mirroring what
      `env-bridge/seed-config-from-env!` does, so this composes with the env
      bridge instead of competing with it.

   3. `env-bridge/seed-config-from-env!` for this tenant, which is what actually
      supplies `services.typesense.api-key-admin` from `TYPESENSE_API_KEY_ADMIN`.
      Unscoped deliberately: a deployment that sets any bridged variable means
      it for this tenant, and scoping to Typesense would reintroduce the
      same class of gap for the next secret.

   Idempotent: the bootstrap creates-or-updates and `set-node-value!` is an
   upsert. Returns what was written, never a value — the same discipline the
   env bridge follows."
  ([] (seed! demo-tenant))
  ([tenant-id]
   (workflow/bootstrap-tenant-platform-tree! tenant-id {:tenant-name tenant-id})
   (let [conn (or (config-db/get-conn)
                  (throw (ex-info "Config database connection not available"
                                  {:tenant tenant-id})))
         node (or (config-db/get-config-node-by-tenant-config-key
                    @conn tenant-id :platform "default")
                  (throw (ex-info "Platform/default node missing after bootstrap"
                                  {:tenant tenant-id})))
         master-key (config-core/get-master-key)
         actions (reduce-kv
                   (fn [acc path value]
                     (assoc acc path
                            (config-db/set-node-value!
                              conn
                              {:root :platform
                               :tenant tenant-id
                               :node-id (:config.node/id node)
                               :path path
                               :value value
                               :master-key master-key})))
                   {}
                   typesense-platform-values)]
     {:tenant tenant-id
      :node-id (:config.node/id node)
      :paths-written (vec (sort (keys typesense-platform-values)))
      :actions actions
      ;; Returned so a caller can SHOW what the environment supplied. The
      ;; failure this closes was invisible partly because nothing reported
      ;; that the bridge had not run.
      :env-bridged (env-bridge/seed-config-from-env! conn tenant-id)})))

(defn -main
  "Seed the demo tenant's platform tree, as a `-main` on the jar the image ships.

   ## The gap this closes (#493)

   The capability already shipped: this namespace is on the production
   classpath, and calling `seed!` off the jar works. It simply had no door.
   `bb demo-tenant` was the only caller, and a container has no `bb` and no
   source tree — `/app` holds one file, `app.jar`. So a fresh deployment could
   configure nothing, and `POST .../execute` had no dataset to materialize.

   Same shape and the same reason as `digdir.setup.first-admin` (#436),
   `digdir.setup.bootstrap` (#496) and `digdir.setup.config-cli` (#508):

       docker compose run --rm --no-deps --entrypoint java digdir-rag \\
         -cp /app/app.jar clojure.main -m digdir.setup.demo-tenant

   Seeds NO dataset — see `digdir.setup.demo-dataset` for that half."
  [& _args]
  (println)
  (println "digdir — demo tenant (platform tree)")
  (println "====================================")
  (common/refuse-if-server-running! "digdir.setup.demo-tenant")
  (let [result (seed!)
        bridged (:env-bridged result)]
    (println (str "  ✔ tenant '" (:tenant result) "' — "
                  (count (:paths-written result)) " platform values"))
    (doseq [p (:paths-written result)]
      (println (str "      " p " (" (name (get (:actions result) p :unchanged)) ")")))
    (when (seq (:paths-written bridged))
      (println)
      (println (str "  ✔ from the environment — " (count (:paths-written bridged)) " values"))
      (doseq [p (:paths-written bridged)]
        (println (str "      " p " (" (name (get (:actions bridged) p :unchanged)) ")"))))
    (println)
    (println "  This tenant has NO dataset yet. To add the shipped demo dataset:")
    (println)
    (println "      ... clojure.main -m digdir.setup.demo-dataset")
    (println)
    ;; Explicit, because a -main that returns normally still leaves the JVM
    ;; waiting on non-daemon threads the config DB starts.
    (flush)
    (common/exit! 0)))
