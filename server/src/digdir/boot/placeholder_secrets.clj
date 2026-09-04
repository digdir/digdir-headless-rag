(ns digdir.boot.placeholder-secrets
  "Refuse to boot when a secret still holds its `.env.example` placeholder (#489).

   ## Why refusing, and not warning

   Decided by the PI. The reason it has to be refusal is the shape of the
   defect: most first-run problems LOOK configured and BEHAVE broken, so
   somebody investigates. This one looks configured and behaves **correctly —
   insecurely**. Nothing fails, nothing logs, nobody is ever prompted to look.
   A warning is read by the person who was already going to check.

   After this repository is open-sourced, `changeme-jwt-secret-…` is not a
   placeholder. It is a **published signing key**, and anyone with a clone can
   forge a session token.

   ## The list is DERIVED, and that is the point

   A second hand-maintained copy of `.env.example` drifts from the first, and
   the check then silently stops covering new variables — the failure #488 was
   about. So nothing here is hand-listed:

   - **WHICH variables are secrets** comes from the two registries that already
     answer that: `digdir.secrets/declared` (tier 0 — the master key, the JWT
     secret, the database password) and the `:secret? true` bindings in
     `digdir.config.env-bridge` (tier 1 — the Typesense admin key and the
     provider keys). Their union is computed at call time, so a secret added to
     either registry is covered the day it lands, with no list to remember.

   - **WHAT a placeholder looks like** is a single marker rather than a copy of
     the values. `.env.example` writes every placeholder as `changeme-…`, and
     `placeholder-marker-covers-env-example` asserts that it still does — so if
     somebody adds a placeholder in another shape, a test fails and names the
     choice instead of the check quietly missing it.

   ⚠️ **NEVER LOG A VALUE.** Only variable NAMES leave this namespace, in the
   message and in `ex-data`. `digdir.secrets` states the rule and it applies
   with particular force here, where every value in scope is by definition a
   credential somebody forgot to change."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.secrets :as secrets]))

(def placeholder-marker
  "The convention `.env.example` uses for every placeholder value.

   One marker rather than a copy of the six values, because the values change
   and the convention does not. `placeholder-marker-covers-env-example` fails if
   `.env.example` ever assigns a placeholder that does not contain it, which is
   what keeps this honest without duplicating the file."
  "changeme")

(def override-env-var
  "The explicit escape hatch, for a dev machine that wants to boot anyway.

   EXPLICIT, NEVER SILENT: strict `\"true\"` only, matching the
   `DIGDIR_RESPONSE_COERCION` precedent, and taking it still logs every
   offending variable at WARN. The issue proposes an opt-out precisely so that
   the default can be refusal without making a dev environment unusable — a
   check people cannot live with is a check they remove."
  "DIGDIR_ALLOW_PLACEHOLDER_SECRETS")

(defn secret-env-vars
  "Every environment variable that carries a secret, from both registries.

   Computed rather than stored, so it cannot go stale against either source."
  []
  (into (into (sorted-set)
              (map (comp :env-var val))
              secrets/declared)
        (comp (filter :secret?) (map :env-var))
        env-bridge/env-config-bindings))

(defn placeholder-violations
  "The NAMES of secret variables whose value still contains the placeholder
   marker. Sorted, and never carrying a value.

   Reads through `secrets/*env-lookup*` — the seam that namespace exposes
   because `System/getenv` cannot be redefined — so this is observable in a
   test without mutating the JVM's environment."
  ([] (placeholder-violations secrets/*env-lookup*))
  ([lookup]
   (into []
         (filter (fn [v]
                   (some-> (lookup v) str/lower-case (str/includes? placeholder-marker))))
         (secret-env-vars))))

(defn override-engaged?
  ([] (override-engaged? secrets/*env-lookup*))
  ([lookup] (= "true" (some-> (lookup override-env-var) str/trim))))

(defn check!
  "Refuse to boot if any secret still holds its placeholder.

   Returns a summary map when it does not refuse, so a caller can log or test
   what was examined — `{:checked n :violations [...] :overridden? bool}`. The
   count is what makes a vacuous run visible: `:checked 0` means the registries
   came back empty and this examined nothing.

   Throws `ex-info` rather than calling `System/exit` so the boot path decides
   how to die and a test can observe the refusal."
  ([] (check! secrets/*env-lookup*))
  ([lookup]
   (let [checked (secret-env-vars)
         violations (placeholder-violations lookup)
         summary {:checked (count checked)
                  :violations violations
                  :overridden? (override-engaged? lookup)}]
     (cond
       (empty? violations)
       summary

       (override-engaged? lookup)
       (do
         ;; Loud on the way past. An opt-out that says nothing is a silent
         ;; default wearing a different name.
         (log/warn (str "PLACEHOLDER SECRETS IN USE, allowed by "
                        override-env-var "=true: "
                        (str/join ", " violations)
                        ". These values are published in .env.example — anyone "
                        "with a clone of this repository holds them."))
         summary)

       :else
       (throw (ex-info
                (str "Refusing to start: " (count violations)
                     " secret(s) still hold their .env.example placeholder — "
                     (str/join ", " violations)
                     ". These values are public, so the system would run with "
                     "credentials anyone with a clone can read. Replace them, "
                     "or set " override-env-var "=true to boot anyway on a "
                     "machine where that is acceptable.")
                ;; Names only. Never the values.
                {:violations violations
                 :checked (count checked)
                 :override-env-var override-env-var}))))))
