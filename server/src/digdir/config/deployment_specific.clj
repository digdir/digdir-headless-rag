(ns digdir.config.deployment-specific
  "Which config paths have NO correct global default.

   A path is DEPLOYMENT-SPECIFIC when there is no value that is right for every
   installation, so a shipped value is always wrong for somebody. Two rules
   follow from that one fact, which is why the field is named for the reason
   rather than for either consequence:

     no global default   — `__global__` must not hold a value for it
     no shipped value    — the committed snapshot must not carry one

   It is roughly the complement of `:config-def/ownership :inherit`, which marks
   paths that DO have a live global baseline tenants override. Read that
   mechanism alongside this one: `:inherit` says *there is a right answer for
   everyone*, this says *there cannot be*.

   ## Why the decision lives here rather than on 40 inline definition maps

   The requirement is that a NEW service path forces an answer instead of
   inheriting one. Both shapes achieve that. This one puts every answer in a
   single reviewable table, so the decision is one diff to read rather than
   forty scattered flags — and `deployment-specific-coverage-test` fails when a
   `services.*` path appears in neither set, which is the forcing function.

   The boolean still lands ON the definition (`:config-def/deployment-specific?`,
   stamped by `digdir.setup.config/ensure-config-definition!`), because export,
   import and any consumer read definitions rather than this namespace.

   ## The set was DERIVED, not taken

   Four paths were suggested as a starting point. Reading all 40 `services.*`
   definitions produced 18, and the extra 14 fall into two groups that the
   original four did not suggest:

   - EVERY CREDENTIAL. There is no correct global default for a secret, so each
     `api-key`, the JWT secret and the Typesense admin key qualify by the same
     definition as the endpoints. Not every deployment-specific path is a
     credential, but every credential is deployment-specific.
   - DEPLOYMENT IDENTITY: who administers this installation, which domains may
     log in, which Scaleway tenancy it bills to.

   Nothing outside `services.*` qualified. The only other platform-root
   definition is `system.io-validation.enabled`, a feature flag with a perfectly
   good global default, and no runtime- or dataset-root path is deployment
   identity — `pipeline.source.*` names what we INGEST, which is the opposite
   side of this line."
  (:require [clojure.string :as str]))

(def deployment-specific-paths
  "Paths with no correct global default. Grouped by why, because the grouping is
   the argument."
  #{;; --- Where a service lives -------------------------------------------
    "services.azure-openai.api-endpoint"
    "services.colbert.api-url"
    "services.lmstudio.api-endpoint"
    "services.marker.api-url"
    "services.typesense.api-host"

    ;; --- Credentials. No secret has a correct global default -------------
    "services.auth.jwt-secret"
    "services.azure-openai.api-key"
    "services.colbert.api-key"
    "services.lmstudio.api-key"
    "services.marker.api-key"
    "services.openrouter.api-key"
    "services.scaleway-tem.api-key"
    "services.typesense.api-key-admin"

    ;; --- Who this installation IS ----------------------------------------
    "services.auth.admin-user-emails"
    "services.auth.approved-domains"
    "services.auth.cookie-domain"
    "services.scaleway-tem.project-id"

    ;; --- ⚠️ THE ONE I AM LEAST SURE OF, flagged rather than buried --------
    ;; `deployment-name` is the name YOU gave a model deployment inside YOUR
    ;; Azure resource, so a shipped value is only right for whoever shipped it —
    ;; which is the definition. The argument the other way is that it is
    ;; conventionally the model's own name, making a shipped value a weak but
    ;; real default. It is marked deployment-specific on the definition rather
    ;; than the convention; if that is wrong, MOVING THIS ONE LINE to the set
    ;; below is the whole change, and the guards follow automatically.
    "services.azure-openai.deployment-name"})

(def globally-defaultable-paths
  "`services.*` paths that DO have a correct global default — recorded
   explicitly so that a path is never merely *absent* from the set above.
   Absence must mean *undecided*, or the forcing function does not force."
  #{"services.auth.approved-domains-enabled"
    "services.auth.jwt-cookie-max-age"
    "services.auth.jwt-token-expiry-hours"
    "services.auth.secure-cookies?"
    "services.auth.session-max-age"
    "services.auth.use-db"
    "services.azure-openai.api-version"
    "services.azure-openai.model-name"
    "services.azure-openai.use-azure-openai-api"
    "services.judge.enabled"
    "services.judge.model"
    "services.lmstudio.model"
    "services.marker.retry-delays-ms"
    "services.marker.timeout-ms"
    "services.rate-limiting.trust-x-forwarded-for"
    "services.scaleway-tem.from-email"
    "services.scaleway-tem.region"
    "services.search-phrases.provider"
    "services.self-improvement.model"
    "services.self-improvement.provider"
    "services.self-improvement.reasoning-effort"
    "services.typesense.api-tls"
    ;; A prefix namespaces one installation's collections, so it is deployment
    ;; SHAPED — but a shipped default works and is not wrong for anyone, which
    ;; is the test. `demo_norquad_` and `digdir_rag_` coexist happily.
    "services.typesense.collection-prefix"})

(defn deployment-specific?
  "Whether `path` has no correct global default."
  [path]
  (contains? deployment-specific-paths (str path)))

(defn decision-required?
  "Whether `path` must appear in one of the two sets above. Scoped to
   `services.*`: that is where deployment identity lives, established by
   reading every definition rather than assumed."
  [path]
  (str/starts-with? (str path) "services."))

(defn undecided-paths
  "`services.*` paths from `all-paths` that appear in neither set — the ones a
   new service would land in, and what the coverage guard reports."
  [all-paths]
  (->> all-paths
       (filter decision-required?)
       (remove deployment-specific-paths)
       (remove globally-defaultable-paths)
       sort
       vec))
