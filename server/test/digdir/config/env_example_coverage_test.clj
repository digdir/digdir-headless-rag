(ns digdir.config.env-example-coverage-test
  "`.env.example` must name every variable a newcomer has no default for.

   THE INVARIANT, derived rather than listed:

     for every path that is `:deployment-specific?`
     AND has an env-bridge binding
     -> `.env.example` must name that variable

   Both sides already exist as data — `deployment-specific-paths` and the
   env-bridge binding table — so this is an INTERSECTION, not an enumeration,
   and a future path marked deployment-specific is covered the day it is added.

   ## The defect this was written for

   #486 stopped shipping the `digdir` tenant. Every Azure value lived on that
   tenant's node, so the snapshot correctly went from shipping them to shipping
   none. But `.env.example` named only two of the Azure variables, and the Azure
   path needs three — `api-key`, `api-endpoint` AND `deployment-name`. A
   newcomer hit an Azure failure with nothing telling them which variable was
   missing. **The values were removed and the documented path that replaces them
   was not completed.**

   ## The shape, which outlives the instance

   Two artefacts that must agree, changed by different PRs, with nothing
   checking the agreement — the same class as `:ownership :inherit` versus
   `:deployment-specific?`, guarded two changes earlier. The lesson is not
   \"remember .env.example\": it is that whenever removing a value is correct,
   something must assert that the replacement route is documented, because the
   removal and the documentation are always separate edits."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.deployment-specific :as ds]
            [digdir.config.env-bridge :as env-bridge]))

(def ^:private env-example-path "../.env.example")

(defn- named-variables
  "Every variable `.env.example` names, whether assigned or commented out — a
   commented example still tells a reader the variable exists, which is the
   property under test."
  []
  (let [f (io/file env-example-path)]
    (when (.exists f)
      (into #{} (map second) (re-seq #"(?m)^\s*#?\s*([A-Z][A-Z_0-9]*)=" (slurp f))))))

(defn- required-variables
  "Variables for paths with no correct global default that CAN be supplied from
   the environment. Derived from both tables; neither is restated here."
  []
  (into #{}
        (keep (fn [b]
                (when (ds/deployment-specific? (:path b))
                  (:env-var b))))
        env-bridge/env-config-bindings))

(deftest env-example-names-every-variable-with-no-default
  (let [named (named-variables)
        required (required-variables)]

    (testing "both sides of the intersection can be seen"
      ;; An absence proves nothing if the needle cannot hit. If `.env.example`
      ;; moved, or the regex stopped matching, `named` would be empty and the
      ;; assertion below would report every variable missing — loud. The
      ;; dangerous direction is the other one: `required` coming back empty
      ;; would make this pass forever.
      (is (some? named)
          (str ".env.example not found at " env-example-path))
      (is (<= 5 (count named))
          (str "only " (count named) " variables parsed out of .env.example; "
               "the reader is probably broken, and this guard would pass on an "
               "unreadable file"))
      (is (<= 5 (count required))
          (str "only " (count required) " deployment-specific paths have env "
               "bindings; if this is 0 the guard asserts nothing")))

    (testing "every variable with no shippable default is documented"
      (let [missing (sort (set/difference required named))]
        (is (empty? missing)
            (str "these have NO correct global default and CAN be supplied from "
                 "the environment, so the product ships no value for them — but "
                 ".env.example does not name them, which leaves a newcomer with "
                 "a failure and no next step: " (pr-str missing)))))))

(deftest env-example-covers-the-azure-path-specifically
  ;; A named case alongside the derived invariant, because this is the one that
  ;; actually reached a person. The Azure path reads three values from config —
  ;; see the `true ->` branch on `env-bridge/first-query-bindings` — and only
  ;; two were documented, so the missing one surfaced as an LLM failure rather
  ;; than as a missing setting.
  (let [named (named-variables)]
    (testing "the control: the reader sees Azure variables at all"
      (is (some #(str/starts-with? % "AZURE_OPENAI_") named)
          "no AZURE_OPENAI_* variable parsed; the assertion below is vacuous"))
    (testing "all three variables the Azure path reads from config are named"
      (doseq [v ["AZURE_OPENAI_API_KEY"
                 "AZURE_OPENAI_API_ENDPOINT"
                 "AZURE_OPENAI_DEPLOYMENT_NAME"]]
        (is (contains? named v)
            (str v " is required for the Azure path and is not named in "
                 ".env.example"))))))
