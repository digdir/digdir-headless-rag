(ns digdir.boot.provider-switch-test
  "The boot refusal for a complete Azure credential set with no provider switch.

   ## The pair is the point, not the refusal

   A check that only proves its RED case is indistinguishable from one that
   refuses everything. The two green cases here are load-bearing:

     RED    three Azure values, switch unset   -> refuses, naming the variable
     GREEN  three Azure values, switch true    -> boots
     GREEN  no Azure values, switch unset      -> boots (deliberate local path)

   The second green is the one that matters most. Unset is the CORRECT state for
   someone running an OpenAI-compatible server, so a check that fired on an
   unset switch alone would break a working configuration in order to fix a
   broken one — worse than the defect.

   ## And unset is not false

   `explicit-false-with-credentials-boots` guards the discrimination the whole
   fix rests on. Resolving the switch with a `:default` would collapse `nil` and
   `false` into one value and make the check fire on a deliberate choice."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.provider-switch :as provider-switch]
            [digdir.config.accessor :as accessor]))

(def ^:private key-path "services.azure-openai.api-key")
(def ^:private endpoint-path "services.azure-openai.api-endpoint")
(def ^:private deployment-path "services.azure-openai.deployment-name")
(def ^:private switch-path "services.azure-openai.use-azure-openai-api")

(defn- with-config
  "Run `f` with platform resolution stubbed to `tenant->path->value`.

   Stubs `accessor/get-platform-value`, which is the function the production
   code calls and the same one the RUNTIME resolves this switch through — a stub
   on a different door would prove nothing about this one."
  [tenant->path->value f]
  (with-redefs [accessor/get-platform-value
                (fn [path {:keys [tenant]}] (get-in tenant->path->value [tenant path]))]
    (f)))

(def ^:private azure-credentials
  {key-path "a-key" endpoint-path "https://example.invalid" deployment-path "a-deployment"})

;; ---------------------------------------------------------------------------
;; RED
;; ---------------------------------------------------------------------------

(deftest complete-azure-credentials-with-an-unset-switch-refuse
  (testing "The state a real user reached: three values supplied, nothing asked
            for the switch, every query then failing about the OTHER provider."
    (with-config {"demo" azure-credentials}
      (fn []
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"Refusing to start"
              (provider-switch/check! ["demo"])))))))

(deftest the-refusal-names-the-variable-and-the-value-to-set
  (testing "\"something is inconsistent\" is not actionable."
    (with-config {"demo" azure-credentials}
      (fn []
        (let [e (try (provider-switch/check! ["demo"]) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) "AZURE_OPENAI_USE_AZURE")
              "the refusal must name the variable an operator can set")
          (is (str/includes? (ex-message e) "=true")
              "and the value to set it to")
          (is (= ["demo"] (:violations (ex-data e)))))))))

;; ---------------------------------------------------------------------------
;; GREEN — the cases that must keep working
;; ---------------------------------------------------------------------------

(deftest complete-azure-credentials-with-the-switch-set-boots
  (with-config {"demo" (assoc azure-credentials switch-path true)}
    (fn []
      (let [summary (provider-switch/check! ["demo"])]
        (is (= [] (:violations summary)))
        (is (= 1 (:checked summary)))))))

(deftest no-azure-credentials-and-an-unset-switch-boots
  (testing "The deliberate local path. Unset is correct here, and a check that
            refused it would be worse than the bug it fixes."
    (with-config {"demo" {}}
      (fn []
        (is (= [] (:violations (provider-switch/check! ["demo"]))))))))

(deftest explicit-false-with-credentials-boots
  (testing "Credentials kept while running locally is a real combination, and
            `false` is a choice. Only ABSENCE is the contradiction."
    (with-config {"demo" (assoc azure-credentials switch-path false)}
      (fn []
        (is (= [] (:violations (provider-switch/check! ["demo"]))))))))

(deftest an-incomplete-credential-set-is-not-this-defect
  (testing "A lone endpoint is a leftover, not a decision to use Azure."
    (with-config {"demo" {endpoint-path "https://example.invalid"}}
      (fn []
        (is (= [] (:violations (provider-switch/check! ["demo"]))))))))

(deftest a-blank-credential-counts-as-absent
  (testing "An exported-but-empty value is what a half-filled .env bridges in."
    (with-config {"demo" (assoc azure-credentials key-path "   ")}
      (fn []
        (is (= [] (:violations (provider-switch/check! ["demo"]))))))))

;; ---------------------------------------------------------------------------
;; Shape and safety
;; ---------------------------------------------------------------------------

(deftest the-pure-predicate-covers-the-three-cases
  (testing "Stated directly on the predicate, so the discrimination is legible
            without the stubbing above."
    (is (true? (provider-switch/contradiction?
                 {:credentials ["k" "e" "d"] :switch nil})))
    (is (false? (provider-switch/contradiction?
                  {:credentials ["k" "e" "d"] :switch true})))
    (is (false? (provider-switch/contradiction?
                  {:credentials [nil nil nil] :switch nil})))))

(deftest only-contradictory-tenants-are-named
  (testing "A mixed deployment refuses over the broken tenant and says which."
    (with-config {"broken" azure-credentials
                  "local" {}
                  "azure" (assoc azure-credentials switch-path true)}
      (fn []
        (let [e (try (provider-switch/check! ["broken" "local" "azure"]) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= ["broken"] (:violations (ex-data e))))
          (is (= 3 (:checked (ex-data e)))))))))

(deftest a-tenant-whose-config-cannot-be-read-is-not-refused
  (testing "A check that cannot answer must not answer. Refusing to boot over an
            unrelated resolution failure would be worse than the defect."
    (with-redefs [accessor/get-platform-value
                  (fn [& _] (throw (ex-info "config unavailable" {})))]
      (is (= [] (:violations (provider-switch/check! ["demo"])))))))

(deftest an-unreadable-tenant-is-not-counted-as-checked
  (testing "The summary is logged at boot by both entrypoints, so it is read as
            a health signal. `:checked` therefore has to count what was actually
            RESOLVED — counting the tenants offered instead reports
            `{:checked 3 :violations []}` for three tenants whose reads all
            threw, which is indistinguishable from three clean tenants. This is
            the assertion the sibling test above cannot make: it asserts only on
            `:violations`, and `:violations` is empty in BOTH cases."
    (with-redefs [accessor/get-platform-value
                  (fn [& _] (throw (ex-info "config unavailable" {})))]
      (let [summary (provider-switch/check! ["alpha" "beta" "gamma"])]
        (is (= 0 (:checked summary))
            "no tenant was resolved, so none was checked")
        (is (= 3 (:unreadable summary))
            "the three that could not be read are named, not silently dropped")
        (is (= [] (:violations summary)))))))

(deftest a-readable-tenant-is-still-counted
  (testing "Control for the test above: without this, `:checked` could be
            hard-wired to 0 and the unreadable case would still pass."
    (with-config {"demo" {}}
      (fn []
        (let [summary (provider-switch/check! ["demo"])]
          (is (= 1 (:checked summary)))
          (is (= 0 (:unreadable summary))))))))

(deftest the-override-engages-only-on-an-exact-true
  (testing "An escape hatch is reached for under pressure, and the failure mode
            of getting it wrong is a refusal to boot that looks like the hatch
            not working. What counts should be pinned rather than discovered.

            Casing is forgiven, matching `required-env`, `placeholder-secrets`
            and `digdir.setup.common`'s seed guard. The value must still SAY
            true — only its capitalisation is ignored, so nothing is loosened
            beyond the one thing an operator can plausibly get wrong."
    (doseq [[raw expected]
            [["true"      true]
             [" true "    true]   ; surrounding whitespace is trimmed
             ["\ttrue\n"  true]
             ["TRUE"      true]   ; case-insensitive, like the sibling checks
             ["True"      true]
             ["  tRuE  "  true]
             ["yes"       false]
             ["1"         false]
             ["false"     false]
             [""          false]
             [nil         false]]]
      (is (= expected (provider-switch/override-engaged? raw))
          (str "override-engaged? " (pr-str raw) " should be " expected)))))

(deftest the-refusal-carries-no-credential-value
  (testing "One of the three paths is an encrypted API key. It is read to ask
            whether it is there and for nothing else."
    (with-config {"demo" (assoc azure-credentials key-path "SUPER-SECRET-KEY")}
      (fn []
        (let [e (try (provider-switch/check! ["demo"]) nil
                     (catch clojure.lang.ExceptionInfo e e))
              text (str (ex-message e) " " (pr-str (ex-data e)))]
          (is (some? e))
          (is (not (str/includes? text "SUPER-SECRET-KEY"))
              "a credential reached the refusal")
          (is (not (str/includes? text "a-deployment"))
              "a configuration value reached the refusal"))))))

(deftest the-override-boots-and-still-reports
  (with-redefs [provider-switch/override-engaged? (constantly true)]
    (with-config {"demo" azure-credentials}
      (fn []
        (let [summary (provider-switch/check! ["demo"])]
          (is (true? (:overridden? summary)))
          (is (= ["demo"] (:violations summary))
              "the violations are reported, not suppressed"))))))

(deftest both-server-entrypoints-run-the-check
  (testing "A gate wired into only one entrypoint is a gate the other's
            operators do not have."
    (doseq [path ["src-dev/dev.cljc" "src-prod/prod.cljc"]]
      (is (str/includes? (slurp (java.io.File. path)) "provider-switch/check!")
          (str path " does not run the provider-switch check")))))
