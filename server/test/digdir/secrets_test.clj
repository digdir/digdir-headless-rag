(ns digdir.secrets-test
  "The three constraints from #22, each pinned by a test that fails if it
   regresses:

     1. resolution order is explicit — env wins, and a later backend is
        consulted ONLY where the environment is silent;
     2. a missing secret throws at the point of use, naming itself;
     3. no value ever escapes — not in a message, not in ex-data.

   The third is asserted on the WHOLE ex-data key set rather than on values,
   because a leaked value would arrive as a NEW key and value assertions
   cannot see a key they do not mention."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.secrets :as secrets]))

(def ^:private secret-value "sk-do-not-log-me-0123456789")

(defn- env-of [m] (fn [n] (get m n)))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(deftest resolves-from-the-environment
  (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" secret-value})]
    (is (= secret-value (secrets/get! :openai-api-key)))))

(deftest blank-and-whitespace-are-absent-not-present
  (testing "an empty or whitespace value is the same as unset"
    (doseq [v ["" "   " "\t"]]
      (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" v})]
        (is (thrown? clojure.lang.ExceptionInfo (secrets/get! :openai-api-key))
            (str "a blank value must not count as present: " (pr-str v)))))))

(deftest values-are-trimmed
  (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" (str "  " secret-value "\n")})]
    (is (= secret-value (secrets/get! :openai-api-key))
        "a trailing newline from a shell heredoc must not become part of the key")))

;; ---------------------------------------------------------------------------
;; Constraint 1 — resolution order is explicit, and env wins
;; ---------------------------------------------------------------------------

(deftest the-environment-wins-over-a-vault
  ;; Stated as a decision in the namespace docstring: every existing reader is
  ;; env-first, so any other order silently changes what a running system
  ;; resolves. This test is what makes that a fact rather than a comment.
  (let [vault-hits (atom 0)]
    (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" "from-env"})
              secrets/*resolution-order* [:env :azure-key-vault]]
      (with-redefs [secrets/azure-key-vault-resolver
                    (atom (fn [_] (swap! vault-hits inc) "from-vault"))]
        (is (= "from-env" (secrets/get! :openai-api-key)))
        (is (zero? @vault-hits)
            "the vault must not even be consulted when the environment answers")))))

(deftest a-later-backend-is-consulted-only-where-the-environment-is-silent
  (binding [secrets/*env-lookup* (env-of {})
            secrets/*resolution-order* [:env :azure-key-vault]]
    (with-redefs [secrets/azure-key-vault-resolver (atom (fn [_] "from-vault"))]
      (is (= "from-vault" (secrets/get! :openai-api-key))))))

(deftest github-actions-resolves-through-the-environment
  (testing "same mechanism as :env by design — GitHub exposes secrets as env vars"
    (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" secret-value})
              secrets/*resolution-order* [:github-actions]]
      (is (= secret-value (secrets/get! :openai-api-key))))))

(deftest an-unconfigured-vault-says-so-rather-than-returning-nothing
  (binding [secrets/*env-lookup* (env-of {})
            secrets/*resolution-order* [:azure-key-vault]]
    (with-redefs [secrets/azure-key-vault-resolver (atom nil)]
      (let [e (try (secrets/get! :openai-api-key)
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo e))
        (is (str/includes? (ex-message e) "no resolver is installed")
            "an unconfigured backend must not look like an absent secret")))))

(deftest an-unknown-backend-fails-loudly
  (binding [secrets/*resolution-order* [:hashicorp-vault]]
    (is (thrown? clojure.lang.ExceptionInfo (secrets/get! :openai-api-key)))))

;; ---------------------------------------------------------------------------
;; Constraint 2 — a missing secret throws, naming itself
;; ---------------------------------------------------------------------------

(deftest a-missing-secret-throws-and-names-itself
  (binding [secrets/*env-lookup* (env-of {})]
    (let [e (try (secrets/get! :openai-api-key)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e)
          "there is deliberately no arity that returns nil")
      (is (str/includes? (ex-message e) "openai-api-key") "names the secret")
      (is (str/includes? (ex-message e) "OPENAI_API_KEY") "names the variable to set"))))

(deftest an-undeclared-name-throws-rather-than-resolving-to-nothing
  ;; The protection for the 8 dynamic call sites. `(System/getenv computed-name)`
  ;; returns nil for a name nobody declared; this must not.
  (binding [secrets/*env-lookup* (env-of {"SOMETHING" "x"})]
    (let [e (try (secrets/get! :typo-in-a-computed-name)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (str/includes? (ex-message e) "not a declared secret"))
      (is (contains? (set (:declared (ex-data e))) :openai-api-key)
          "the error lists what IS declared, so a typo is self-correcting"))))

(deftest present?-answers-without-throwing-and-without-the-value
  (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" secret-value})]
    (is (true? (secrets/present? :openai-api-key))))
  (binding [secrets/*env-lookup* (env-of {})]
    (is (false? (secrets/present? :openai-api-key)))))

(deftest present?-does-not-collapse-a-typo-into-missing
  ;; THE THIRD CASE, and its absence is why the original slipped through: with
  ;; only set/unset asserted, `false` for an undeclared name looks correct.
  ;; A guard whose absent case and undeclared case are the same value cannot
  ;; tell them apart, and asserting only the two that agree proves nothing.
  ;;
  ;; What it costs when they collapse: `bb setup` prints MISSING for
  ;; :openai-api-kye and an operator sets a variable that is already set.
  (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" secret-value})]
    (is (thrown? clojure.lang.ExceptionInfo (secrets/present? :openai-api-kye))
        "an undeclared name must propagate, not read as absent")
    (is (false? (secrets/present? :anthropic-api-key))
        "a genuinely absent DECLARED secret is still false")))

(deftest present?-does-not-report-a-misconfigured-backend-as-missing
  ;; Same collapse, different cause: a resolution order naming a vault with no
  ;; resolver installed would otherwise report EVERY secret as MISSING.
  (binding [secrets/*env-lookup* (env-of {})
            secrets/*resolution-order* [:azure-key-vault]]
    (with-redefs [secrets/azure-key-vault-resolver (atom nil)]
      (is (thrown? clojure.lang.ExceptionInfo (secrets/present? :openai-api-key)))))
  (binding [secrets/*resolution-order* [:hashicorp-vault]]
    (is (thrown? clojure.lang.ExceptionInfo (secrets/present? :openai-api-key))
        "an unknown backend is a configuration error, not an absent secret")))

;; ---------------------------------------------------------------------------
;; Constraint 3 — no value ever escapes
;; ---------------------------------------------------------------------------

(deftest the-failure-carries-names-only
  (binding [secrets/*env-lookup* (env-of {})]
    (let [e (try (secrets/get! :openai-api-key)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= #{:reason :secret :env-var :tier :backends-tried} (set (keys (ex-data e))))
          "assert the WHOLE key set: a leaked value arrives as a new key, and
           value assertions cannot see a key they do not mention"))))

(deftest a-resolved-value-never-appears-in-a-message-or-ex-data
  ;; The value is present in the environment here, so if any diagnostic path
  ;; interpolated it, this would catch it.
  (binding [secrets/*env-lookup* (env-of {"OPENAI_API_KEY" secret-value})
            secrets/*resolution-order* [:env :hashicorp-vault]]
    (let [e (try (secrets/get! :openai-api-key)
                 (binding [secrets/*resolution-order* [:hashicorp-vault]]
                   (secrets/get! :openai-api-key))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (not (str/includes? (ex-message e) secret-value)))
      (is (not (str/includes? (pr-str (ex-data e)) secret-value))))))

(deftest redact-never-reveals
  (is (= "<absent>" (secrets/redact nil)))
  (is (= "<absent>" (secrets/redact "")))
  (let [r (secrets/redact secret-value)]
    (is (not (str/includes? r secret-value)))
    (is (str/includes? r "redacted"))))

;; ---------------------------------------------------------------------------
;; The declaration itself
;; ---------------------------------------------------------------------------

(deftest every-declared-secret-is-well-formed
  (is (seq (secrets/secret-names)))
  (doseq [[k spec] secrets/declared]
    (is (keyword? k))
    (is (= #{:env-var :tier :doc} (set (keys spec)))
        (str k " — assert the whole key set so a new field cannot be added silently"))
    (is (re-matches #"[A-Z][A-Z0-9_]*" (:env-var spec)) (str k " env-var shape"))
    (is (#{:bootstrap :runtime} (:tier spec))
        (str k " — :service secrets live in the config DB and must NOT be declared here"))))

(deftest tier-1-service-credentials-are-deliberately-absent
  (testing "the Typesense admin key is a config value; declaring it here would be a second door"
    (is (not-any? #(str/includes? (str/lower-case (name %)) "typesense")
                  (secrets/secret-names)))))
