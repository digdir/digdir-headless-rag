(ns digdir.boot.placeholder-secrets-test
  "The boot check refuses on a placeholder secret, and can be seen doing it.

   Three properties, and they fail in different ways:

     1. It REFUSES when a placeholder is present, and SUCCEEDS when it is not —
        both directions observed, because a check that always refuses and a
        check that always passes are equally useless and only one of them is
        noticed.
     2. It can SEE the environment. A check that reads nothing cannot find
        anything, and reports the same clean result as a correctly configured
        machine. This is the non-vacuity control and it is the reason
        `check!` returns `:checked`.
     3. The derivation still covers `.env.example`. The marker is one string
        rather than a copy of six values; if a placeholder is ever written in
        another shape, that has to fail here rather than silently fall outside
        the check."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.placeholder-secrets :as ph]))

(defn- env-stub
  "A fake environment. Same arity as `System/getenv`, because a stub's arity is
   a claim about the producer."
  [m]
  (fn [^String n] (get m n)))

;;; ---------------------------------------------------------------------------
;;; 1. The non-vacuity control — can it see anything at all?
;;; ---------------------------------------------------------------------------

(deftest the-check-can-observe-the-environment
  (testing "it examines a non-empty set of variables"
    ;; If both registries came back empty, every assertion below would pass on
    ;; a machine full of placeholders. `:checked` is in the return value for
    ;; exactly this reason.
    (let [{:keys [checked]} (ph/check! (env-stub {}))]
      (is (<= 10 checked)
          (str "only " checked " secret variables examined; the registries are "
               "probably not resolving, and this guard would pass forever"))))

  (testing "and it reads through the seam it claims to"
    ;; A positive control on the READER, not on the result: prove the lookup is
    ;; actually consulted, so a check that ignored the environment could not
    ;; masquerade as a clean one.
    (let [seen (atom #{})
          spy (fn [n] (swap! seen conj n) nil)]
      (ph/placeholder-violations spy)
      (is (contains? @seen "JWT_SECRET")
          "the check never asked the environment for JWT_SECRET")
      (is (contains? @seen "CONFIG_MASTER_KEY"))
      (is (contains? @seen "TYPESENSE_API_KEY_ADMIN")
          "the three variables the issue names must all be examined"))))

;;; ---------------------------------------------------------------------------
;;; 2. Both directions
;;; ---------------------------------------------------------------------------

(deftest refuses-when-a-placeholder-is-present
  (testing "a placeholder JWT secret refuses the boot"
    (let [e (try (ph/check! (env-stub {"JWT_SECRET" "changeme-jwt-secret-generate-with-openssl-rand"}))
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "boot must not proceed with a published signing key")
      (is (= ["JWT_SECRET"] (:violations (ex-data e)))
          "and it must name which variable, so the operator knows what to fix")
      (testing "without ever carrying the value"
        ;; The rule `digdir.secrets` states, applied where every value in scope
        ;; is by definition a credential somebody forgot to change.
        (is (not (str/includes? (str (ex-data e)) "generate-with-openssl-rand"))
            "ex-data leaked the secret value")
        (is (not (str/includes? (.getMessage e) "generate-with-openssl-rand"))
            "the message leaked the secret value")))))

(deftest succeeds-when-no-placeholder-is-present
  (testing "real-looking values boot fine"
    ;; The other direction. Without this the check could refuse unconditionally
    ;; and every 'it refused' observation above would still hold.
    (let [r (ph/check! (env-stub {"JWT_SECRET" "P6xq0Zt1mA9vK3sR7wYb2NfLdQeHgUj4"
                                  "CONFIG_MASTER_KEY" "8sWv2QpL0nZx5TcRb9YkMd3FhJa7Ge1U"
                                  "TYPESENSE_API_KEY_ADMIN" "cD4mR8wQ2yL6zP0nX5tKb7VfHjS3Ga9E"}))]
      (is (empty? (:violations r)))
      (is (false? (:overridden? r)))
      (is (<= 10 (:checked r)) "and it still examined the full set"))))

(deftest every-secret-variable-is-covered-not-just-the-famous-three
  ;; The set is derived from two registries, so this asserts the derivation
  ;; rather than a list: any secret-bearing variable holding a placeholder is
  ;; caught, including ones added after this test was written.
  (let [vars (ph/secret-env-vars)]
    (testing "the derived set is populated and includes both registries"
      (is (<= 10 (count vars)))
      (is (contains? vars "CONFIG_MASTER_KEY") "tier 0, from digdir.secrets")
      (is (contains? vars "TYPESENSE_API_KEY_ADMIN") "tier 1, from env-bridge"))
    (testing "each one is individually detected"
      (doseq [v vars]
        (is (= [v] (ph/placeholder-violations (env-stub {v "changeme-something"})))
            (str v " holds a placeholder and was not detected"))))))

;;; ---------------------------------------------------------------------------
;;; 3. The escape hatch, which must be explicit and never quiet
;;; ---------------------------------------------------------------------------

(deftest the-override-is-explicit-and-loud
  (let [placeholder {"JWT_SECRET" "changeme-jwt-secret"}]
    (testing "it boots when the override is exactly \"true\""
      (let [r (ph/check! (env-stub (assoc placeholder ph/override-env-var "true")))]
        (is (= ["JWT_SECRET"] (:violations r))
            "the violation is still reported, not swallowed")
        (is (true? (:overridden? r)))))

    (testing "casing is forgiven — the value must SAY true, not be typed one way"
      (doseq [v ["TRUE" "True" "  tRuE  "]]
        (let [r (ph/check! (env-stub (assoc placeholder ph/override-env-var v)))]
          (is (true? (:overridden? r))
              (str "override value " (pr-str v) " should engage the override")))))

    (testing "and NOT on anything else — no silent default"
      (doseq [v ["1" "yes" "" "false" "truthy"]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ph/check! (env-stub (assoc placeholder ph/override-env-var v))))
            (str "override value " (pr-str v) " should not disable the check"))))

    (testing "and absent means refuse"
      (is (thrown? clojure.lang.ExceptionInfo (ph/check! (env-stub placeholder)))))))

;;; ---------------------------------------------------------------------------
;;; 4. The derivation still covers the file it was derived from
;;; ---------------------------------------------------------------------------

(deftest placeholder-marker-covers-env-example
  ;; The anti-drift guard, and the reason the marker is not a second copy of
  ;; `.env.example`: if a placeholder is ever written in another shape, this
  ;; fails and names the choice rather than the check quietly missing it.
  (let [f (io/file "../.env.example")
        assigned (when (.exists f)
                   (->> (slurp f)
                        (re-seq #"(?m)^([A-Z][A-Z_0-9]*)=(.+)$")
                        (map (fn [[_ k v]] [k (str/trim v)]))
                        (remove (fn [[_ v]] (str/blank? v)))))]

    (testing "the instrument can see the file and its values"
      (is (.exists f) ".env.example not found; this guard would assert nothing")
      (is (<= 3 (count assigned))
          (str "only " (count assigned) " assigned values parsed from "
               ".env.example; the reader is probably broken")))

    (testing "every assigned placeholder matches the marker the check looks for"
      (let [outside (->> assigned
                         (remove (fn [[_ v]]
                                   (str/includes? (str/lower-case v) ph/placeholder-marker)))
                         (map first)
                         sort)]
        (is (empty? outside)
            (str "these .env.example values are assigned but do not contain '"
                 ph/placeholder-marker "', so the boot check cannot see them: "
                 (pr-str outside)
                 ". Either write them in the `changeme-…` convention, or teach "
                 "digdir.boot.placeholder-secrets the new shape — but do not "
                 "leave them uncovered."))))))
