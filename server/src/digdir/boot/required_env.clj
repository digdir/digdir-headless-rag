(ns digdir.boot.required-env
  "Refuse to boot when a `:tier :boot` variable has no value (#521).

   ## The declaration was prose

   `digdir.config.env-bridge` says of its tiers:

   > `:tier` — `:boot` (the server refuses to start without it) …

   Six variables carried that word and nothing derived a presence check from
   it. `bindings-for-tier :boot` had two consumers, both the setup CLI's
   MISSING/OK table — a REPORT, not a gate. The one boot-time refusal keyed to
   these variables, `digdir.boot.placeholder-secrets/check!`, fires on the
   value still saying `changeme`; it says nothing about a value being absent.

   Measured before this landed, with all six absent: that check returned
   `{:checked 14 :violations [] :overridden? false}` and the server started.

   ## Derived, and that is the whole point

   This namespace holds NO list of variable names. It reads `:tier` and
   `:alternative-group` off `env-config-bindings` at call time, so the sentence
   and the behaviour cannot drift: a seventh `:boot` row is enforced the day it
   lands, and a row that stops being boot-critical stops being enforced by
   changing its `:tier` — one edit, in the place that already claimed to say
   it. A second hand-maintained list of boot keys is the defect class this is
   closing, not a way to close it.

   The database pointer is why a flat presence check will not do. A deployment
   picks the file store OR Postgres, so requiring all six would refuse every
   correct environment — a guard that fires on everything. That alternation now
   lives in the table as `:alternative-group :database` rather than in a
   comment, and the derivation reads it.

   ⚠️ **NEVER LOG A VALUE.** Only variable NAMES leave this namespace, in the
   message and in `ex-data`, exactly as `digdir.boot.placeholder-secrets` and
   `digdir.secrets` require."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.secrets :as secrets]))

(def override-env-var
  "The explicit escape hatch, mirroring `DIGDIR_ALLOW_PLACEHOLDER_SECRETS`.

   Same policy as its sibling and for the same reason: strict `\"true\"` only,
   never silent, every missing variable still logged at WARN. A check people
   cannot live with is a check they remove. It exists so the DEFAULT can be
   refusal — which is what `:tier :boot` has claimed all along."
  "DIGDIR_ALLOW_MISSING_BOOT_ENV")

(defn override-engaged?
  ([] (override-engaged? secrets/*env-lookup*))
  ;; Case-INSENSITIVE, matching `digdir.setup.common`'s seed guard and the
  ;; RAG env flags. An escape hatch is reached for under pressure, and a
  ;; `TRUE` that silently fails to engage presents as "the documented override
  ;; does not work" — the operator is already dealing with a refusal and now
  ;; has a second, invisible one. The value is still required to SAY true;
  ;; only its casing is forgiven.
  ([lookup] (= "true" (some-> (lookup override-env-var) str/trim str/lower-case))))

(defn- describe-group
  "One line per unsatisfied alternative group, naming each option's gap.

   Says which CHOICE is open rather than listing six variables flatly, because
   a reader told to set all four database variables would be following an
   instruction that is wrong for both backends."
  [{:keys [group options]}]
  (str "no complete option for " (name group) " — "
       (str/join "; or "
                 (map (fn [{:keys [service missing]}]
                        (str (name service) " needs " (str/join ", " missing)))
                      options))))

(defn violations
  "The boot requirements this environment fails, as human-readable lines.

   Empty when the environment satisfies the declaration. Never carries a value."
  [requirements]
  (into (mapv #(str % " is not set") (:missing requirements))
        (map describe-group)
        (:unsatisfied-groups requirements)))

(defn check!
  "Refuse to start if any `:tier :boot` variable has no value.

   Returns a summary map when it does not refuse — `{:checked n :violations
   [...] :overridden? bool}`, the same shape its sibling returns so a boot log
   reads consistently. `:checked` is what makes a vacuous run visible: 0 means
   the table came back empty and this examined nothing, which would otherwise
   look identical to a clean environment.

   Throws `ex-info` rather than calling `System/exit`, so the boot path decides
   how to die and a test can observe the refusal."
  ([] (check! secrets/*env-lookup*))
  ([lookup]
   (let [requirements (env-bridge/boot-requirements lookup)
         vs (violations requirements)
         summary {:checked (:checked requirements)
                  :violations vs
                  :overridden? (override-engaged? lookup)}]
     (cond
       (empty? vs)
       summary

       (override-engaged? lookup)
       (do
         ;; Loud on the way past. An opt-out that says nothing is a silent
         ;; default wearing a different name.
         (log/warn (str "MISSING BOOT CONFIGURATION, allowed by "
                        override-env-var "=true: " (str/join "; " vs)
                        ". These are declared :tier :boot — the server is "
                        "starting without configuration it says it requires."))
         summary)

       :else
       (throw (ex-info
                (str "Refusing to start: " (count vs)
                     " boot requirement(s) unmet — " (str/join "; " vs)
                     ". These are declared :tier :boot in "
                     "digdir.config.env-bridge, which means the server does "
                     "not start without them. Set them, or set "
                     override-env-var "=true to boot anyway on a machine "
                     "where that is acceptable.")
                ;; Names only. Never the values.
                {:violations vs
                 :checked (:checked requirements)
                 :missing (:missing requirements)
                 :unsatisfied-groups (:unsatisfied-groups requirements)
                 :override-env-var override-env-var}))))))
