(ns digdir.setup.setup-env-run-test
  "RUNS `scripts/setup-env.sh` and reads what it did.

   `setup-env-script-test` reads the script as TEXT, and a text reading cannot
   see the one property the setup-env provider-prompt issue is about: which prompts a given ANSWER leads to.
   The defect was a gate that did not exist - answering \"not Azure\" asked for
   nothing the answer needs - and a gate is behaviour, so these tests execute
   the real script in a throwaway directory and read its transcript and the
   `.env` it wrote.

   ## No terminal, on purpose, and ASSERTED rather than assumed

   The script prompts through `/dev/tty` when it can OPEN it, and treats every
   answer as blank when it cannot. A child of this JVM inherits its session, so
   from a developer's terminal the script opens that terminal, prints a prompt
   and waits for a keypress - the suite would hang, not fail. On a CI runner
   there is no controlling terminal and it would not. Measured both ways for
   the same JVM child reports `TTY-OPENS` under a pty and `NO-TTY`
   without one.

   So every run goes through `perl -MPOSIX` `setsid`, which leaves the child
   with no controlling terminal in both places, and
   `the-harness-cannot-reach-a-terminal` asserts that with the script's own
   probe. A missing perl is a FAILURE with a message, never a skip: a check
   that skips where perl is absent is blind exactly there and reads as green.

   And every run goes through EACH shell a `#!/bin/sh` might be here (`shells`),
   which must agree. This suite first ran on macOS only, where `/bin/sh` is not
   dash, and was green over a script that dash killed on its first line.

   With no terminal every answer is blank, so a variable the script offers
   shows up as its SKIPPED line - which carries the message the script chose
   for it. That is what makes list placement observable: the wrong list prints
   the wrong message. The provider answer is supplied by pre-setting it in
   `.env`, which the script reads through the same `current_value` a typed
   answer is written to.

   Names only, never values: nothing here prints what the script generated."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.provider-switch :as provider-switch]
            [digdir.config.env-bridge :as env-bridge])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

(def ^:private script-path "../scripts/setup-env.sh")
(def ^:private example-path "../.env.example")

(def ^:private detach
  "Run the rest of the argv with no controlling terminal, or die saying why."
  ["perl" "-MPOSIX" "-e"
   (str "defined(POSIX::setsid()) or die qq(setsid failed: $!\\n); "
        "exec @ARGV or die qq(exec failed: $!\\n)")])

(def ^:private tty-probe
  "The script's own test for a usable terminal: OPEN it, not `[ -e ]`, and in a
   subshell - see the script for why."
  "(: >/dev/tty) 2>/dev/null && echo TTY-OPENS || echo NO-TTY")

(def ^:private shells
  "Every distinct shell here that a newcomer's `/bin/sh` might be, by real path.

   The script says `#!/bin/sh`, and that is bash-as-sh on macOS and dash on
   Debian and Ubuntu. They differ exactly where it matters: with no terminal, the
   script's first tty probe made dash EXIT 2 silently and bash-as-sh carry on.
   Every run here was on macOS, so the suite was green until CI ran it on dash.
   So each script run goes through every shell found, and the runs must agree.
   On Ubuntu both paths are dash, which leaves one shell. Finding neither is a
   FAILURE, like a missing perl."
  (delay (->> ["/bin/sh" "/bin/dash"]
              (map io/file)
              (filter #(.exists ^java.io.File %))
              (map #(.getCanonicalPath ^java.io.File %))
              distinct
              vec)))

(defn- run-detached
  "`argv` under `detach`, in `dir`, with `env` added. {:exit :out}, or
   {:error msg} when it could not be started or did not finish."
  [argv dir env]
  (try
    (let [pb (doto (ProcessBuilder. ^java.util.List (into detach argv))
               (.directory (io/file dir))
               (.redirectErrorStream true))
          _ (let [e (.environment pb)] (doseq [[k v] env] (.put e k v)))
          p (.start pb)
          _ (.close (.getOutputStream p))
          out (future (slurp (.getInputStream p)))]
      (if (.waitFor p 60 TimeUnit/SECONDS)
        {:exit (.exitValue p) :out @out}
        (do (.destroyForcibly p)
            {:error (str "timed out after 60s - if it was waiting for input, the detach "
                         "did not take and it was prompting a terminal: " (pr-str argv))})))
    (catch java.io.IOException e
      {:error (str "could not start `perl`, which these tests need to run the script "
                   "with no controlling terminal. Install perl; do not skip these tests. "
                   (.getMessage e))})))

(def ^:private generated-vars
  "What the script generates afresh on every run, read from the script itself,
   so two runs' random secrets are not mistaken for two behaviours."
  (delay (some-> (re-find #"(?m)^GENERATED_VARS=\"([^\"]*)\"" (slurp script-path))
                 second str/trim (str/split #"\s+") set)))

(defn- mask-generated
  "`env` with every generated variable's value replaced by a marker."
  [env]
  (some->> env
           str/split-lines
           (map (fn [l] (let [k (first (str/split l #"=" 2))]
                          (if (contains? @generated-vars k) (str k "=<generated>") l))))
           (str/join "\n")))

(defn- env-text
  "The `.env` a run starts from: `example`, changed by `preset`, which is how an
   earlier answer or a hand edit reaches the script.

     a string                      raw lines, appended
     a map of variable -> value    `VAR=value` lines, appended
     a map with ::first-line, ::replace and/or ::append
                                   raw text put BEFORE the example (a BOM has to
                                   be on line 1 to be a BOM); variable -> value
                                   replacing that variable's line IN the example
                                   (an appended line would come second, and
                                   `current_value` reads the first); raw lines
                                   appended

   A ::replace naming a variable the example does not assign throws: a replace
   that silently did nothing would make its test vacuous."
  [example preset]
  (cond
    (string? preset) (str example preset)
    (some qualified-keyword? (keys preset))
    (let [{::keys [first-line replace append]} preset
          replaced (reduce-kv
                     (fn [s k v]
                       (let [re (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote k) "=.*$"))]
                         (when-not (re-find re s)
                           (throw (ex-info (str "::replace: the example assigns no " k) {:var k})))
                         (str/replace-first s re (str/re-quote-replacement (str k "=" v)))))
                     example replace)]
      (str first-line replaced append))
    :else (str example (apply str (map (fn [[k v]] (str k "=" v "\n")) preset)))))

(defn- run-script
  "Run the real script, under every shell in `shells`, in a fresh directory
   holding a copy of `.env.example`. The runs must AGREE - the same transcript
   and the same `.env` apart from generated secrets - or this is an error naming
   the shells.

   `preset` is nil (no `.env`: the script creates it from the example) or
   anything `env-text` takes. Returns {:exit :out :env} or {:error msg}."
  [preset]
  (letfn [(run-in [sh]
            (let [dir (.toFile (Files/createTempDirectory "setup-env-run" (make-array FileAttribute 0)))
                  example (slurp example-path)]
              (spit (io/file dir ".env.example") example)
              (when preset
                (spit (io/file dir ".env") (env-text example preset)))
              (let [r (run-detached [sh (.getCanonicalPath (io/file script-path))]
                                    dir
                                    ;; Relative, so the transcript is the same in any
                                    ;; directory; explicit, so an ENV_FILE in the
                                    ;; developer's own environment cannot redirect a write.
                                    {"ENV_FILE" ".env" "EXAMPLE_FILE" ".env.example"})
                    env-file (io/file dir ".env")]
                (cond-> (assoc r :shell sh)
                  (.exists env-file) (assoc :env (slurp env-file))))))]
    (let [rs (mapv run-in @shells)
          bad (first (filter #(or (:error %) (not= 0 (:exit %))) rs))]
      (cond
        (empty? rs) {:error "found neither /bin/sh nor /bin/dash to run the script with"}
        ;; Name the shell: "exit 2, no output" means little until you know WHICH.
        bad (cond-> bad
              (:error bad) (update :error #(str "under " (:shell bad) ": " %))
              (not (:error bad)) (assoc :error (str "under " (:shell bad) ", setup-env.sh exited "
                                                    (:exit bad) ":\n" (:out bad))))
        (apply not= (map (juxt :out (comp mask-generated :env)) rs))
        {:error (str "the shells disagree about the same input - "
                     (str/join " vs " (map :shell rs)) ":\n"
                     (str/join "\n----\n" (map :out rs)))}
        :else (first rs)))))

(defn- ran
  "`r` when the script ran to completion, else nil after ONE failure naming why.

   Callers stop on nil. Carrying on would read a transcript that does not exist
   and bury the one FAIL that says what is wrong under a pile of ERRORs. The nil
   is explicit: `(is false …)` returns FALSE, which `when-some` would carry on with."
  [r]
  (cond
    (:error r) (do (is false (:error r)) nil)
    (not= 0 (:exit r)) (do (is false (str "setup-env.sh exited " (:exit r) ":\n" (:out r))) nil)
    :else r))

(defn- offered
  "The message the script printed on the line for `v`, or nil when it printed
   no line for `v` at all - i.e. it never offered it."
  [out v]
  (second (re-find (re-pattern (str "(?m)^ {6}" (java.util.regex.Pattern/quote v) "  (.*)$"))
                   out)))

(defn- env-value [env v]
  (second (re-find (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote v) "=(.*)$")) env)))

;; ---------------------------------------------------------------------------
;; What the OpenAI-compatible path needs, from the code that decides it -
;; not restated here, so a test cannot agree with a copy of itself.
;; ---------------------------------------------------------------------------

(def ^:private credential-vars
  "The branch's credentials: `resolve` refuses without them. Named from the
   boot guard's own table, through the bridge that seeds them."
  (delay (->> (get-in provider-switch/branch-credentials [:openai-compatible :credentials])
              (keep env-bridge/env-var-for-path)
              sort vec)))

(def ^:private model-var
  (delay (env-bridge/env-var-for-path "services.llm.model")))

(def ^:private skip-says
  "What skipping each costs, measured for the setup-env provider-prompt issue: without a credential the first
   LLM call refuses (the server still starts); without the model the request
   goes out naming NO model - what a server does with that was not measured,
   so the message must not claim a refusal."
  {:credential #"first LLM call refuses"
   :model #"no model"})

(def ^:private other-lists-say
  "The messages of the lists these do NOT belong to. Either one on these lines
   is a variable in the wrong list: both were false for them."
  [#"still needs a value before the LLM path works" #"reranking stays off"])

(def ^:private ambiguity-warning
  "The line the script prints when the switch is written in a shape it cannot be
   certain compose reads the same way."
  #"(?m)^  ⚠ AZURE_OPENAI_USE_AZURE is written in a way docker compose may read$")

(deftest the-harness-cannot-reach-a-terminal
  (testing "instrument check: through the detach, the script's own /dev/tty probe cannot open a
            terminal - otherwise every run below prompts a developer's terminal and blocks"
    (is (seq @shells) "found neither /bin/sh nor /bin/dash - nothing below can run")
    (doseq [sh @shells]
      (testing (str "under " sh)
        (let [r (run-detached [sh "-c" tty-probe] "." {})]
          (if (:error r)
            (is false (:error r))
            (is (= "NO-TTY" (str/trim (:out r)))
                (str "the detached child could still open /dev/tty, or its shell died: " (pr-str r))))))))
  (testing "instrument check: on a machine that HAS dash, it is one of the shells the script runs under"
    (when (.exists (io/file "/bin/dash"))
      (is (some #(= (.getCanonicalPath (io/file "/bin/dash")) %) @shells))))
  (testing "instrument check: /bin/sh ITSELF is one of the shells - it is what a newcomer's `#!/bin/sh` runs,
            and CI cannot stand in for it on macOS, because Ubuntu's /bin/sh is dash"
    (when (.exists (io/file "/bin/sh"))
      (is (some #(= (.getCanonicalPath (io/file "/bin/sh")) %) @shells))))
  (testing "instrument check: two shells by PATH are two by BEHAVIOUR - exactly one reports $BASH_VERSION.
            `shells` dedups by path, and macOS /bin/sh runs whatever /private/var/select/sh names; if that
            were dash, both entries would be dash and nothing else would say so. (A zsh-as-sh would trip this
            too, which is right: it is a third shell this harness does not model.)"
    (when (< 1 (count @shells))
      (let [bash? (fn [sh]
                    (let [r (run-detached [sh "-c" "echo \"${BASH_VERSION:-none}\""] "." {})]
                      (not= "none" (str/trim (str (:out r))))))
            vs (mapv bash? @shells)]
        (is (= #{true false} (set vs)) (str "shells " (pr-str @shells) " report bash? " (pr-str vs))))))
  (testing "instrument check: the names these tests look for were derived, and are the three the setup-env provider-prompt issue names"
    (is (= ["OPENAI_API_ENDPOINT" "OPENAI_API_KEY"] @credential-vars))
    (is (= "AZURE_OPENAI_MODEL_NAME" @model-var))))

(deftest a-non-azure-answer-is-asked-for-what-that-answer-needs
  (when-some [{:keys [out]} (ran (run-script {"AZURE_OPENAI_USE_AZURE" "false"}))]
    (testing "the endpoint and the key are offered"
      (doseq [v @credential-vars]
        (is (some? (offered out v)) (str v " was never offered after a non-Azure answer"))))
    (testing "and the model is offered"
      (is (some? (offered out @model-var)) (str @model-var " was never offered after a non-Azure answer")))
    (testing "each is skipped with the message that is TRUE for it"
      (doseq [v @credential-vars]
        (is (re-find (:credential skip-says) (str (offered out v)))
            (str v ": " (pr-str (offered out v)))))
      (is (re-find (:model skip-says) (str (offered out @model-var)))
          (str @model-var ": " (pr-str (offered out @model-var)))))
    (testing "and none carries another list's message"
      (doseq [v (conj @credential-vars @model-var)
              re other-lists-say]
        (is (not (re-find re (str (offered out v))))
            (str v " is skipped with another list's message: " (pr-str (offered out v))))))))

(deftest a-skipped-provider-is-asked-too
  (testing "Unset routes :openai-compatible at runtime (`provider/selected-provider`, measured for
            the setup-env provider-prompt fix), so a user who skips the question has that path with none of its values. The
            script must mean by an unset switch what the runtime means."
    (when-some [{:keys [out]} (ran (run-script nil))]
      (is (str/includes? out "AZURE_OPENAI_USE_AZURE  SKIPPED")
          "precondition: the provider question was skipped")
      (doseq [v (conj @credential-vars @model-var)]
        (is (some? (offered out v)) (str v " was never offered with the provider unset"))))))

(def ^:private azure-golden
  "The transcript `c2e4ec1a`'s script printed for this exact run - the Azure
   path, before the setup-env provider-prompt issue. Pinned, so any change to what an Azure user sees is a
   diff against a file, not a sentence."
  "test/fixtures/setup-env/azure-answer.transcript")

(deftest an-azure-answer-is-not-asked-for-the-other-providers-values
  (when-some [{:keys [out]} (ran (run-script {"AZURE_OPENAI_USE_AZURE" "true"}))]
    (testing "none of the OpenAI-compatible values is offered"
      (doseq [v (conj @credential-vars @model-var)]
        (is (nil? (offered out v)) (str v " was offered on the Azure path: " (pr-str (offered out v))))))
    (testing "and the transcript is byte-identical to the one before the setup-env provider-prompt issue"
      (let [f (io/file azure-golden)]
        (is (.exists f) (str "pinned transcript missing: " azure-golden))
        (when (.exists f)
          (is (= (slurp f) out) "the Azure path's transcript changed"))))))

(deftest the-azure-placeholder-is-cleared-only-off-the-azure-path
  (testing "not Azure: the placeholder the bootstrap refuses to seed on is gone, and the screen says so.
            A blank Azure key passes every boot refusal on this path (measured for the setup-env provider-prompt issue)."
    (when-some [{:keys [out env]} (ran (run-script {"AZURE_OPENAI_USE_AZURE" "false"}))]
      (is (= "" (env-value env "AZURE_OPENAI_API_KEY"))
          "AZURE_OPENAI_API_KEY is not blank after a non-Azure answer")
      (is (re-find #"(?m)^ {6}AZURE_OPENAI_API_KEY  cleared" out)
          "the key was changed without saying so")))
  (testing "Azure: it is left for the Azure prompt, exactly as before"
    (when-some [{:keys [env]} (ran (run-script {"AZURE_OPENAI_USE_AZURE" "true"}))]
      (is (str/includes? (str (env-value env "AZURE_OPENAI_API_KEY")) "changeme")
          "the Azure path's placeholder was touched"))))

(def ^:private real-looking-key
  "Synthetic, and deliberately free of the placeholder marker: it stands for a
   key someone actually typed. Never a real secret - a failing `is` prints it."
  "synthetic-azure-key-614")

(deftest the-clear-only-ever-touches-the-placeholder
  (testing "On every non-Azure answer - the one branch that clears - a key that is NOT the placeholder
            survives, and the screen does not claim it was cleared. The bound on the setup-env and compose .env parsing issue's severity rests on
            this: a misread switch could cost a placeholder, never a credential. It was MEASURED for the setup-env provider-prompt issue
            and guarded by nothing until the setup-env non-Azure prompt fix."
    (doseq [[label switch] [["false" "AZURE_OPENAI_USE_AZURE=false\n"]
                            ["unset" ""]
                            ["blank" "AZURE_OPENAI_USE_AZURE=\n"]]]
      (testing label
        ;; ::replace, not an appended line: `current_value` reads the FIRST
        ;; assignment, and the example's own `changeme` line comes first.
        (when-some [{:keys [out env]} (ran (run-script {::replace {"AZURE_OPENAI_API_KEY" real-looking-key}
                                                        ::append switch}))]
          (is (some? (offered out (first @credential-vars)))
              "precondition: the non-Azure branch ran - otherwise the key survives trivially")
          (is (= real-looking-key (env-value env "AZURE_OPENAI_API_KEY"))
              "a key that is not the placeholder was changed")
          (is (not (re-find #"(?m)^ {6}AZURE_OPENAI_API_KEY  cleared" out))
              "the screen says a real key was cleared"))))))

(deftest the-closing-warning-is-true-for-a-non-azure-user
  (when-some [{:keys [out]} (ran (run-script {"AZURE_OPENAI_USE_AZURE" "false"}))]
    (testing "it does not say the server will refuse to start over Azure values this user does not need"
      (is (not (re-find #"Still placeholder or empty:.*AZURE_OPENAI" out))
          (str "closing warning names Azure values to a non-Azure user:\n" out)))
    (testing "and it names the non-Azure values still unset, with what that costs"
      ;; ADMIN_USER_EMAILS has a `⚠ Still unset:` warning of its own, so pick the
      ;; one that names the credentials, and read the line under it.
      (let [warnings (re-seq #"(?m)^  ⚠ Still unset:((?: [A-Z_]+)+)\n    (.*)$" out)
            [_ names cost] (first (filter (fn [[_ names]] (str/includes? names (first @credential-vars)))
                                          warnings))]
        (doseq [v @credential-vars]
          (is (and names (str/includes? names v))
              (str v " missing from the closing warnings: " (pr-str (map first warnings)))))
        (is (and cost (re-find (:credential skip-says) cost))
            (str "the closing warning does not say what the unset credentials cost: " (pr-str cost)))))))

;; ---------------------------------------------------------------------------
;; The provider answer, read the way the BRIDGE reads it.
;;
;; The script has to decide "is this Azure?" from the text in `.env`, and the
;; env-bridge decides the same thing when it seeds `services.llm.provider`. Two
;; parses of one value are two doors into one room, so this runs the real
;; script over a shared table and compares it with `env-bridge/coerce-value`,
;; row by row, each row its own label.
;; ---------------------------------------------------------------------------

(def ^:private provider-answers
  ["false" "FALSE" "no" "No" "0" "off" "" "true" "TRUE" "1" "yes" "on" "maybe" " true "])

(defn- bridge-says-azure? [raw]
  (= :azure (env-bridge/coerce-value {:value-type :provider-switch} raw)))

(deftest the-script-reads-the-provider-answer-the-way-the-bridge-does
  (testing "instrument check: the table reaches BOTH answers on the bridge side"
    (is (some bridge-says-azure? provider-answers))
    (is (some (complement bridge-says-azure?) provider-answers)))
  (doseq [raw provider-answers]
    (testing (str "AZURE_OPENAI_USE_AZURE=" (pr-str raw))
      (when-some [{:keys [out]} (ran (run-script {"AZURE_OPENAI_USE_AZURE" raw}))]
        (let [asked? (every? #(some? (offered out %)) (conj @credential-vars @model-var))
              none? (not-any? #(some? (offered out %)) (conj @credential-vars @model-var))]
          (is (or asked? none?) "the script offered SOME of the non-Azure values, not all or none")
          (is (= (not (bridge-says-azure? raw)) asked?)
              (str "the bridge seeds " (env-bridge/coerce-value {:value-type :provider-switch} raw)
                   " from this, but the script " (if asked? "asked" "did not ask")
                   " for the OpenAI-compatible values"))
          ;; Every row here is ONE bare word on the canonical line, so certain.
          ;; Without this, a guard that wrongly refused `1`, `yes` or `on` passed:
          ;; a refusal does not ask for the OpenAI-compatible values either, which
          ;; is all the Azure rows above can see.
          (is (not (re-find ambiguity-warning out))
              "a bare word on the canonical line was refused as ambiguous"))))))

;; ---------------------------------------------------------------------------
;; What the script may ACT on. docker compose, not the script, turns
;; `.env` into the variable the runtime sees, and it reads more shapes than the
;; script's `current_value` does. Measured with Compose 5.5.1: quotes and a
;; trailing ` # comment` are stripped, `${X:-…}` is interpolated, an `export `
;; prefix, a leading space or spaces around `=` still assign, and the LAST of
;; two assignments wins. One branch of the script clears the Azure key's
;; placeholder, so a misread switch changed an Azure user's `.env`.
;; ---------------------------------------------------------------------------

(def ^:private switch-var "AZURE_OPENAI_USE_AZURE")

(defn- certain-reading
  "What the switch certainly means, judged from the whole `.env`'s text:
   `:unset`, the bare value, or ::ambiguous.

   Certain is defined POSITIVELY on BOTH sides. Unset means the NAME appears on
   no line that is not a comment. Set means exactly one such line, spelled
   `AZURE_OPENAI_USE_AZURE=` at the start, whose value is one bare word
   (surrounding blanks allowed: compose and the bridge both trim them). Every
   other shape is ambiguous, including shapes nobody listed.

   ⚠️ This oracle once defined unset as \"no line matched a regex of assignment
   spellings\" - the SAME regex the script used. So `KEY: value`, a BOM and a bare
   name were unset to both and cleared by both, and the corpus stayed green over
   them: a test whose oracle copies the rule under test can only find
   disagreements between the copies, never a case both get wrong.
   Presence by NAME is a different rule from the script's value check, and it
   fails safe: a line it over-counts makes a run ambiguous, never cleared."
  [env]
  (let [named (filter #(and (str/includes? % "AZURE_OPENAI_USE_AZURE") (not (re-find #"^[ \t]*#" %)))
                      (str/split-lines env))]
    (cond
      (empty? named) :unset
      (not= 1 (count named)) ::ambiguous
      :else (or (second (re-matches #"AZURE_OPENAI_USE_AZURE=[ \t]*([A-Za-z0-9._-]*)[ \t\r]*"
                                    (first named)))
                ::ambiguous))))

(defn- verdict
  "The branch the script may take for a file: :azure, :non-azure, or :ambiguous."
  [env]
  (let [r (certain-reading env)]
    (cond
      (= ::ambiguous r) :ambiguous
      (or (= :unset r) (not (bridge-says-azure? r))) :non-azure
      :else :azure)))

(defn- without-ambiguity-warning
  "The transcript with the ambiguity warning's block removed."
  [out]
  (str/replace out #"\n\n  ⚠ AZURE_OPENAI_USE_AZURE is written[^\n]*(\n    [^\n]*)*" ""))

(defn- acted?
  "What the run DID: whether it cleared the Azure placeholder and whether it
   ran the OpenAI-compatible block."
  [{:keys [out env]}]
  {:cleared? (= "" (env-value env "AZURE_OPENAI_API_KEY"))
   :block? (some? (offered out (first @credential-vars)))
   :warned? (boolean (re-find ambiguity-warning out))})

(def ^:private compose-reads-as-true
  "Lines Docker Compose 5.5.1 was MEASURED to hand the container as `true` (Azure),
   with `docker compose config` on an `env_file: .env` service - six of them
   unconditionally, and the bare name ONLY when compose's own shell exports the
   variable as `true`; unexported, compose hands the container nothing. The script
   cannot see that shell, so it must act on neither branch either way. The expectation for
   them comes from that measurement, not from the script's rule or the corpus
   oracle - so if both drift back to the same wrong rule together, this still
   fails. The corpus cannot: it judges the script by an oracle, and an oracle can
   share a blind spot with the rule it checks.
   Each entry is a raw preset, or {::first-line …} where line 1 matters."
  ["AZURE_OPENAI_USE_AZURE: true\n"
   "AZURE_OPENAI_USE_AZURE:true\n"
   "  AZURE_OPENAI_USE_AZURE : true\n"
   "AZURE_OPENAI_USE_AZURE=false\nAZURE_OPENAI_USE_AZURE: true\n"
   "export AZURE_OPENAI_USE_AZURE: true\n"
   {::first-line "\uFEFFAZURE_OPENAI_USE_AZURE=true\n"}
   ;; compose inherits a bare name's value from its own shell; measured with it exported as true
   "AZURE_OPENAI_USE_AZURE\n"])

(deftest a-line-compose-reads-as-azure-is-never-cleared
  (doseq [preset compose-reads-as-true]
    (testing (pr-str preset)
      (when-some [r (ran (run-script preset))]
        (is (= {:cleared? false :block? false :warned? true} (acted? r))
            "compose can hand the container `true` for this line, so the non-Azure branch must not run")))))

(deftest a-switch-compose-may-read-differently-is-acted-on-by-neither-branch
  (doseq [raw ["\"true\"" "'false'" "true # x"]]
    (testing (str switch-var "=" raw)
      (when-some [{:keys [out] :as r} (ran (run-script (str switch-var "=" raw "\n")))]
        (is (= {:cleared? false :block? false :warned? true} (acted? r)))
        (testing "and it prints what c2e4ec1a printed, plus only the naming line"
          (is (= (slurp azure-golden) (without-ambiguity-warning out))))))))

(def ^:private switch-corpus
  "Generated shapes of the switch line - not the three above, but each way a
   person, an editor or a copy-paste writes it, crossed with the values that
   decide a branch, plus values chosen to be awkward. Deterministic, so a
   failure names a line that reproduces.

   Each AXIS is covered in full; the full product of axes is not. Once a line
   is non-canonical, every wrapper on it is ambiguous for the same reason, so
   the product would add runs (~180ms each) without adding a verdict. A few
   crossings are kept so that an axis check made conditional on another would
   still be seen."
  (let [values ["true" "false" ""]
        wraps [identity
               #(str "\"" % "\"")
               #(str "'" % "'")
               #(str % " # zebra614")
               #(str "  " % "  ")
               #(str "${UNSET_614:-" % "}")
               #(str % "\r")]
        assigns ["  " "\t" "export "]]
    (vec (concat
           ;; the canonical assignment, with every wrapper
           (for [v values w wraps] (str switch-var "=" (w v) "\n"))
           ;; every non-canonical way to assign, bare value
           (for [v values
                 line [(str "  " switch-var "=" v) (str "\t" switch-var "=" v)
                       (str "export " switch-var "=" v)
                       (str switch-var " = " v) (str switch-var " =" v)]]
             (str line "\n"))
           ;; crossings
           (for [a assigns] (str a switch-var "=\"true\"\n"))
           ;; two assignments: compose takes the LAST, `current_value` the first
           (for [first-v ["true" "false"] v values]
             (str switch-var "=" first-v "\n" switch-var "=" v "\n"))
           ;; values with characters compose treats specially, or that are not ASCII
           (for [v ["tr ue" "true=1" "$HOME" "tru\\e" "`true`" "trué" "on;" "yes!"]]
             (str switch-var "=" v "\n"))
           ;; REGRESSION: spellings compose reads as `true` (the bare
           ;; name only when compose's shell exports it) that an
           ;; assignment-shape regex did not know, so they fell into "unset" and
           ;; were CLEARED. `KEY: value` is how a compose `environment:` block is
           ;; written, so it arrives by copy-paste.
           [(str switch-var ": true\n")
            (str switch-var ":true\n")
            (str "  " switch-var " : true\n")
            (str switch-var "=false\n" switch-var ": true\n")
            (str "export " switch-var ": true\n")
            {::first-line (str "\uFEFF" switch-var "=true\n")}   ; a BOM makes line 1 non-canonical
            (str switch-var "\n")]                              ; a bare name: compose inherits its shell's value
           ;; CONTROLS for the comment exclusion: compose ignores all three, so
           ;; these ARE unset, and must still be acted on as non-Azure
           [(str "# " switch-var "=true\n")
            (str "  # " switch-var "=true\n")
            (str "\t# " switch-var "=true\n")]))))

(defn- corpus-verdict
  "The verdict for a corpus entry, judged on the whole `.env` the script sees."
  [entry]
  (verdict (env-text (slurp example-path) entry)))

(deftest the-script-never-clears-an-azure-key-unless-certain-the-answer-is-non-azure
  (testing "instrument check: the corpus reaches all three verdicts"
    (let [vs (set (map corpus-verdict switch-corpus))]
      (is (= #{:azure :non-azure :ambiguous} vs) (pr-str vs))))
  (testing "instrument check: an untouched .env.example reads as unset - it names the switch only in comments"
    (is (= :unset (certain-reading (slurp example-path)))))
  (doseq [line switch-corpus]
    (testing (pr-str line)
      (when-some [{:keys [out] :as r} (ran (run-script line))]
        (let [v (corpus-verdict line)
              {:keys [cleared? block? warned?]} (acted? r)]
          (is (= (= :non-azure v) cleared?)
              (str "verdict " v ", but the Azure placeholder was " (if cleared? "CLEARED" "kept")))
          (is (= (= :non-azure v) block?)
              (str "verdict " v ", but the OpenAI-compatible block " (if block? "ran" "did not run")))
          (is (= (= :ambiguous v) warned?)
              (str "verdict " v ", but the ambiguity warning " (if warned? "was" "was not") " printed"))
          (testing "and the warning names the variable, never its value"
            (is (not (re-find #"zebra614|UNSET_614" out)))))))))
