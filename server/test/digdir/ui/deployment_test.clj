(ns digdir.ui.deployment-test
  "Guards for the Diagnostics tab's deployment-identity panel.

   THE QUESTION THIS RETIRES: when a value is missing, does the panel say so?

   That is the whole risk here. A panel that answers `what is running` is read
   precisely when someone doubts what is running, so its failure mode is not a
   crash — it is rendering something plausible-looking and wrong. A blank field,
   a dash, or a confident `dev` all read as an answer. Every absent case below
   therefore asserts on the *reason* being shown, not merely on not-throwing.

   The absent paths are tested first and in more detail than the happy path,
   because in this repository the manifest is build-generated and VERSION is
   deploy-stamped: absent is what a developer sees every day, and present is
   the rarer case."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.ui.deployment :as sut]))

(defn- unknown?
  "An absent value must name itself unknown AND give a reason."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (str/starts-with? s "unknown — ")
       (> (count s) (count "unknown — "))))

(deftest version-absent-is-named-not-blank
  (testing "nil, empty and whitespace-only VERSION all render as plainly unknown"
    (doseq [raw [nil "" "   " "\t" "\n  \n"]]
      (is (= sut/version-absent (sut/render-version raw))
          (str "VERSION " (pr-str raw) " must render as unknown, not as a blank field"))
      (is (unknown? (sut/render-version raw))))))

(deftest version-present-is-shown-verbatim
  (testing "a real SHA survives, trimmed"
    (is (= "91b8151" (sut/render-version "91b8151")))
    (is (= "91b8151" (sut/render-version "  91b8151\n"))))
  (testing "a present value is never mistaken for an absent one"
    (is (not (unknown? (sut/render-version "91b8151"))))))

(deftest electric-version-distinguishes-its-three-absences
  (testing "no resource at all"
    (is (= sut/electric-version-absent (sut/render-electric-version nil))))
  (testing "a manifest that exists but carries no version"
    (is (= sut/electric-version-empty (sut/render-electric-version "{}")))
    (is (= sut/electric-version-empty
           (sut/render-electric-version "{:hyperfiddle/electric-user-version \"\"}")))
    (is (= sut/electric-version-empty
           (sut/render-electric-version "{:hyperfiddle/electric-user-version \"  \"}"))))
  (testing "a manifest that cannot be parsed"
    (is (= sut/electric-version-unreadable (sut/render-electric-version "{{{ not edn"))))
  (testing "all three name themselves unknown, and say three different things"
    (let [reasons [sut/electric-version-absent
                   sut/electric-version-empty
                   sut/electric-version-unreadable]]
      (is (every? unknown? reasons))
      (is (= 3 (count (distinct reasons)))
          "a convenient shared message would report the wrong reason for two of these"))))

(deftest electric-version-present-is-shown-verbatim
  (is (= "v0.1-42-gabc1234-dirty"
         (sut/render-electric-version
          "{:hyperfiddle/electric-user-version \"v0.1-42-gabc1234-dirty\"}")))
  (testing "the namespaced-map literal the build actually writes"
    ;; Verbatim from resources/electric-manifest.edn after `bb build-client`
    ;; on 498cd7d. build.clj assoc's a NAMESPACED keyword, so pr-str emits
    ;; #:hyperfiddle{...} rather than the long form -- and a test written only
    ;; against the long form would never have touched the shape that ships.
    (is (= "498cd7d-dirty"
           (sut/render-electric-version
            "#:hyperfiddle{:electric-user-version \"498cd7d-dirty\"}"))))
  (testing "other manifest keys do not interfere"
    (is (= "v0.1-0-gdeadbee"
           (sut/render-electric-version
            (str "{:client-version-hash \"abc\" "
                 ":hyperfiddle/electric-user-version \"v0.1-0-gdeadbee\"}"))))))

(deftest started-at-is-a-time-or-plainly-unknown
  (is (= sut/started-at-absent (sut/render-started-at nil)))
  (is (unknown? (sut/render-started-at nil)))
  (is (= "1970-01-01T00:00:00Z" (sut/render-started-at 0)))
  (is (= "2026-08-31T00:00:00Z" (sut/render-started-at 1788134400000))))

(deftest collect-wires-each-reader-to-its-own-field
  (testing "every field is present and non-blank whatever the environment"
    (let [d (sut/collect)]
      (is (= #{:version :electric-version :started-at :built-at} (set (keys d))))
      (doseq [[k v] d]
        (is (string? v) (str k " must be a string"))
        (is (not (str/blank? v)) (str k " must never render blank")))))
  (testing "fields are not crossed: each value is its own source's, absent or not"
    ;; Unconditional on purpose. An `(when (unknown? x) (is ...))` stops
    ;; asserting the moment x stops being unknown -- so the very change that
    ;; makes an absent value look plausible also silently removes the check
    ;; that would have caught it. Measured: it dropped the suite from 35
    ;; assertions to 34 without a single failure.
    (let [{:keys [version electric-version]} (sut/collect)]
      (is (or (= version (System/getenv "VERSION"))
              (str/includes? version "VERSION"))
          "version field must hold VERSION's value or explain VERSION's absence")
      (is (or (not (unknown? electric-version))
              (str/includes? electric-version "electric-manifest.edn"))
          "electric field must hold a version or explain the manifest's absence"))))

;; ============================================================================
;; #426 — the image build stamp
;; ============================================================================

(deftest render-built-at-reports-absent-and-malformed-distinctly
  (testing "a missing stamp says so, and never renders as a date"
    (is (= sut/built-at-absent (sut/render-built-at nil)))
    (is (= sut/built-at-absent (sut/render-built-at ""))
        "an empty export is the ordinary way for a shell variable to be set and say nothing")
    (is (= sut/built-at-absent (sut/render-built-at "   "))))
  (testing "a malformed stamp is reported as malformed, not displayed as though it were a date"
    ;; The whole value of this field is that an operator can trust it. A stamp
    ;; that renders a plausible-looking date from an unparseable value is worse
    ;; than no field, because it is acted on.
    (is (= sut/built-at-unreadable (sut/render-built-at "not-a-date")))
    (is (= sut/built-at-unreadable (sut/render-built-at "2026-13-45")))
    (is (= sut/built-at-unreadable (sut/render-built-at "1756800000"))
        "epoch seconds are not ISO-8601 and must not be silently accepted"))
  (testing "a well-formed stamp survives"
    (is (= "2026-09-02T00:17:06Z" (sut/render-built-at "2026-09-02T00:17:06Z"))))
  (testing "POSITIVE CONTROL — absent and unreadable are different strings, or the
            assertions above would pass against one message covering both"
    (is (not= sut/built-at-absent sut/built-at-unreadable))))

(defn- env-vars-by-stage
  "Map of stage name -> set of env var names that stage declares with ENV.

   ⚠️ STAGE-AWARE ON PURPOSE, AND THE PREVIOUS VERSION IS WHY. It asked
   `(re-find #\"ENV BUILD_TIMESTAMP=\" dockerfile)` over the whole file, which
   has no notion of which stage the match was in — and ENV DOES NOT CROSS A
   `FROM`. Both pairs sat in the build stage, the runtime container therefore
   had neither, and all four assertions passed. Its own failure message said
   `so the running process cannot read it`, which is precisely the property it
   did not check.

   Measured on the deployed test service before this fix: VERSION was UNSET
   while LANG — declared in the runtime stage — was present, which is what makes
   that a reading rather than a broken probe."
  [dockerfile]
  (->> (str/split-lines dockerfile)
       (reduce (fn [{:keys [stage acc]} line]
                 (if-let [[_ name] (re-matches #"(?i)FROM\s+\S+\s+AS\s+(\S+)\s*" line)]
                   {:stage name :acc acc}
                   (if-let [[_ var] (re-matches #"(?i)ENV\s+([A-Z_][A-Z0-9_]*)=.*" line)]
                     {:stage stage :acc (update acc stage (fnil conj #{}) var)}
                     {:stage stage :acc acc})))
               {:stage nil :acc {}})
       :acc))

(deftest the-runtime-stage-declares-the-vars-the-process-reads
  (let [dockerfile (slurp (io/file ".." "server.Dockerfile"))
        by-stage (env-vars-by-stage dockerfile)
        runtime (get by-stage "runtime" #{})]
    (is (contains? by-stage "runtime")
        "no stage named `runtime` was parsed out of server.Dockerfile — if the
         stage was renamed this guard is checking nothing and must be rewritten,
         not deleted")
    (is (contains? runtime "LANG")
        "POSITIVE CONTROL — LANG is known to be a runtime-stage ENV. If this
         fails the parser is broken and the assertions below prove nothing")
    (is (contains? runtime "VERSION")
        (str "the runtime stage does not declare ENV VERSION, so the running "
             "container will not have it and the Diagnostics `Server commit` row "
             "will read `unknown` forever. ENV does not cross a FROM — declaring "
             "it only in the build stage is the defect this test exists for. "
             "Runtime stage currently declares: " (pr-str (sort runtime))))
    (is (contains? runtime "BUILD_TIMESTAMP")
        (str "the runtime stage does not declare ENV BUILD_TIMESTAMP, so the "
             "`Image built` row will read `unknown` forever. Runtime stage "
             "currently declares: " (pr-str (sort runtime))))))

(deftest the-deploy-supplies-both-build-args
  ;; Separate from the stage check: the Dockerfile can be perfectly staged and
  ;; the values still never arrive, because deploy.yml passes nothing in.
  (let [deploy-yml (slurp (io/file ".." "deploy.yml"))]
    (is (re-find #"VERSION:" deploy-yml)
        "deploy.yml supplies no VERSION build arg")
    (is (re-find #"BUILD_TIMESTAMP:" deploy-yml)
        "deploy.yml supplies no BUILD_TIMESTAMP build arg")))
