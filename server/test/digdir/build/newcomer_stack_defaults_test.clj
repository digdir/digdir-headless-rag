(ns digdir.build.newcomer-stack-defaults-test
  "The newcomer stack's defaults must name what the demo seeders actually create.

   THE DEFECT (#499). `docker-compose.newcomer.yml` defaulted
   `DATASET_CONFIG_KEY` to `public-docs` while `digdir.setup.demo-dataset`
   creates `norquad-docs`. A newcomer who did everything right — fetch the
   corpus, seed, bring the stack up — got

     {\"error\":{\"code\":-32603,\"message\":\"Dataset ref does not match any configured dataset\"}}

   and setting `DATASET_CONFIG_KEY=norquad-docs` cleared it immediately.

   WHY IT SURVIVED, AND WHY THIS TEST IS SHAPED THIS WAY. The two values live in
   different artefacts — a YAML file and a Clojure `def` — with nothing asserting
   they agree. Same family as #488 (`.env.example` versus the shipped-value set)
   and #497 (a name computed in two places). A guard has to READ BOTH ARTEFACTS;
   anything that reads only one restates a value instead of comparing two."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.setup.demo-dataset :as demo-dataset]
            [digdir.setup.demo-tenant :as demo-tenant]))

(def ^:private compose-path
  "Tests run with `server/` as the working directory; the compose file is at the root."
  "../docker-compose.newcomer.yml")

(defn- compose-default
  "The `${VAR:-default}` fallback the compose file declares for `var`, or nil."
  [compose var]
  (some->> (re-find (re-pattern (str (java.util.regex.Pattern/quote var)
                                     ":\\s*\\$\\{" (java.util.regex.Pattern/quote var)
                                     ":-([^}]*)\\}"))
                    compose)
           second
           str/trim))

(deftest newcomer-defaults-name-what-the-seeders-create
  (let [compose (slurp compose-path)]
    (testing "POSITIVE CONTROL — the file is readable and the extractor works, so a
              nil below means the variable is absent rather than the regex failing"
      (is (str/includes? compose "DATASET_CONFIG_KEY")
          "the compose file must declare the variable at all")
      (is (some? (compose-default compose "TENANT"))
          "the extractor must find a default it is known to have"))

    (testing "the dataset key defaults to the dataset the demo seeder creates"
      (is (= demo-dataset/demo-dataset-id (compose-default compose "DATASET_CONFIG_KEY"))
          (str "compose defaults DATASET_CONFIG_KEY to "
               (pr-str (compose-default compose "DATASET_CONFIG_KEY"))
               " but digdir.setup.demo-dataset creates "
               (pr-str demo-dataset/demo-dataset-id)
               " — a newcomer following the documented steps gets"
               " \"Dataset ref does not match any configured dataset\" (#499)")))

    (testing "and the tenant defaults to the tenant the demo seeder creates"
      ;; Correct today. Asserted anyway: it is the same class of disagreement one
      ;; field over, and it would fail exactly as silently.
      (is (= demo-tenant/demo-tenant (compose-default compose "TENANT"))))))
