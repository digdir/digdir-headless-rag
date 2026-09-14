(ns digdir.setup.setup-env-script-test
  "`scripts/setup-env.sh` holds the only second copy in this design, and this is
   what stops it drifting.

   The host script cannot read a Clojure def. It runs on a machine with no
   toolchain — that is its entire purpose — so `PLACEHOLDER_MARKER` and the
   list of generated variables are necessarily restated in shell. Everywhere
   else in #488/#489 a second copy was avoidable and was avoided; here it is
   not, so the remedy is a guard asserting the two agree, the same remedy used
   where `.env.example`'s convention had to agree with the boot check.

   ⚠️ The failure this prevents is quiet in the worst way. If the marker drifts,
   the shell script stops recognising placeholders and reports a `.env` as
   fully configured — and the boot check then refuses, on a machine whose setup
   script just said everything was fine. The two halves would disagree about
   the same file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.placeholder-secrets :as ph]
            [digdir.config.env-bridge :as env-bridge]))

(def ^:private script-path "../scripts/setup-env.sh")

(defn- script []
  (let [f (io/file script-path)]
    (when (.exists f) (slurp f))))

(defn- shell-var
  "The value assigned to a top-level `NAME=\"…\"` in the script."
  [src name*]
  (second (re-find (re-pattern (str "(?m)^" name* "=\"([^\"]*)\"")) src)))

(defn- shell-list
  "The words of a top-level `NAME=\"a b c\"` assignment."
  [src name*]
  (some-> (shell-var src name*) str/trim (str/split #"\s+") set))

(deftest the-script-exists-and-is-readable
  ;; The control. Every assertion below reads this file; if the path is wrong
  ;; they would all pass against nil, which is the reassuring direction.
  (let [src (script)]
    (is (some? src) (str "not found at " script-path))
    (is (< 500 (count src))
        "script is suspiciously short; the reader is probably pointed at the wrong file")
    (is (str/starts-with? src "#!/bin/sh")
        "must be POSIX sh — the newcomer this exists for may not have bash")))

(deftest the-shell-marker-matches-the-clojure-marker
  (let [src (script)]
    (testing "the reader can find the assignment at all"
      (is (some? (shell-var src "PLACEHOLDER_MARKER"))
          "PLACEHOLDER_MARKER not found; a rename would make this guard vacuous"))
    (testing "and it is the same marker the boot check looks for"
      (is (= ph/placeholder-marker (shell-var src "PLACEHOLDER_MARKER"))
          (str "the host script and the boot check disagree about what a "
               "placeholder looks like. The script would report a .env as "
               "configured and the server would then refuse to start on it.")))))

(deftest generated-variables-are-real-secrets
  (let [src (script)
        generated (shell-list src "GENERATED_VARS")
        secrets (ph/secret-env-vars)]

    (testing "both sides are visible"
      (is (seq generated) "GENERATED_VARS not parsed from the script")
      (is (<= 10 (count secrets)) "the derived secret set is empty or tiny"))

    (testing "every variable the script generates is one the boot check guards"
      ;; A generated variable outside the checked set would be a secret nobody
      ;; verifies, and a checked variable the script cannot generate is a
      ;; newcomer stuck by hand — the first is the dangerous direction.
      (let [orphans (sort (remove secrets generated))]
        (is (empty? orphans)
            (str "the script generates " (pr-str orphans) ", which the boot "
                 "check does not consider a secret — either it is not one, or "
                 "the derived set is missing it"))))

    (testing "and the three the issue names are all generated rather than prompted"
      (doseq [v ["CONFIG_MASTER_KEY" "JWT_SECRET" "TYPESENSE_API_KEY_ADMIN"]]
        (is (contains? generated v)
            (str v " can be generated and must never be prompted for"))))))

(deftest prompted-variables-are-not-things-we-could-have-generated
  ;; The other half of "generate what can be generated; prompt only for what
  ;; cannot": nothing may appear in both lists, or the script would ask for
  ;; something it had already invented.
  (let [src (script)
        generated (shell-list src "GENERATED_VARS")
        prompted (shell-list src "PROMPTED_VARS")]
    (is (seq prompted) "PROMPTED_VARS not parsed from the script")
    (is (empty? (filter generated prompted))
        "a variable is both generated and prompted for")
    (testing "and the prompted ones are the Azure values, which cannot be invented"
      (is (every? #(str/starts-with? % "AZURE_OPENAI_") prompted)
          (str "unexpected prompted variables: "
               (pr-str (sort (remove #(str/starts-with? % "AZURE_OPENAI_") prompted))))))))

(deftest optional-prompts-are-declared-optional-in-clojure
  ;; #519. The script holds a SECOND COPY of names Clojure owns — the same
  ;; duplication `PLACEHOLDER_MARKER` and `GENERATED_VARS` already carry, and
  ;; the same remedy: assert it against the declaration rather than trusting it.
  ;;
  ;; The point is not that the names exist. It is that the script's promise
  ;; ("blank is a fine answer") matches what `env-bridge` declares. A variable
  ;; the product cannot actually run without must never be offered as skippable.
  (let [src (script)
        optional (shell-list src "OPTIONAL_PROMPTED_VARS")
        prompted (shell-list src "PROMPTED_VARS")
        generated (shell-list src "GENERATED_VARS")
        declared-optional (set (map :env-var (env-bridge/bindings-for-tier :optional)))]
    (is (seq optional) "OPTIONAL_PROMPTED_VARS not parsed from the script")
    (is (seq declared-optional)
        "no :tier :optional bindings found — the comparison below would be vacuous")

    (testing "every optionally-prompted variable is declared :tier :optional"
      (is (empty? (remove declared-optional optional))
          (str "the script offers these as skippable but env-bridge does not "
               "declare them optional: "
               (pr-str (sort (remove declared-optional optional))))))

    (testing "and none of them is also required or generated"
      (is (empty? (filter prompted optional))
          "a variable is both required-prompted and offered as skippable")
      (is (empty? (filter generated optional))
          "a variable is both generated and prompted for"))))

(deftest the-script-never-prints-a-secret-value
  ;; The rule `digdir.secrets` states, applied to the one place that handles
  ;; every secret in plaintext. Checked structurally: the reporting helper is
  ;; only ever called with a variable NAME.
  (let [src (script)]
    (is (not (re-find #"say .*\$\(generate_secret\)" src))
        "the script echoes a generated secret")
    (is (not (re-find #"say .*\$_v\b" src))
        "the script echoes a current value")
    (is (re-find #"Names only, never values" src)
        "the no-values rule should be stated where the values are in scope")))

;; ---------------------------------------------------------------------------
;; The commands the script PRINTS have to be runnable (#515b, found by Conduit
;; on a real container).
;;
;; It printed `docker compose exec server …`. No compose file in this
;; repository defines a service called `server` — docker-compose.newcomer.yml
;; defines typesense, digdir-rag and open-webui; docker-compose.dev.yml defines
;; typesense and postgres. And there is no default compose file at the root, so
;; the command fails at "no configuration file provided" before it ever gets as
;; far as the wrong service name. Two failures, both of which a newcomer reads
;; as "the product is broken".
;;
;; The service list is DERIVED from the compose file rather than restated, so
;; renaming a service breaks this test instead of silently breaking the
;; instruction.
;; ---------------------------------------------------------------------------

(def ^:private newcomer-compose "../docker-compose.newcomer.yml")

(defn- compose-services
  "Top-level service names in a compose file: two-space-indented keys under
   `services:`, stopping at the next top-level block."
  [path]
  (let [lines (str/split-lines (slurp path))
        after (->> lines (drop-while #(not (re-matches #"^services:\s*" %))) rest)
        body (take-while #(or (str/blank? %) (str/starts-with? % " ")) after)]
    (into #{} (keep #(second (re-matches #"^  ([A-Za-z0-9_.-]+):\s*" %))) body)))

(deftest the-compose-services-are-readable
  (testing "the instrument can see the file, or the guards below assert nothing"
    (is (.exists (io/file newcomer-compose)))
    (let [svcs (compose-services newcomer-compose)]
      (is (<= 2 (count svcs)) (str "parsed only " (pr-str svcs)))
      (is (contains? svcs "digdir-rag")
          "the newcomer stack should define digdir-rag"))))

(deftest every-service-the-script-names-actually-exists
  (let [src (script)
        svcs (compose-services newcomer-compose)
        named (->> (re-seq #"docker compose[^\"\n]*?(?:exec|run --rm) ([A-Za-z0-9_.-]+)" src)
                   (map second)
                   distinct)]
    (is (seq named)
        "no `docker compose … exec/run <service>` found — guard would be vacuous")
    (doseq [s named]
      (is (contains? svcs s)
          (str "the script prints `docker compose … exec " s "`, but no such "
               "service exists. Services are: " (pr-str (sort svcs))
               ". A printed command naming a service that does not exist fails "
               "for a reason the newcomer cannot diagnose.")))))

(deftest the-printed-commands-name-a-compose-file
  (testing "There is no default compose file at the repository root, so a bare
            `docker compose exec …` fails at 'no configuration file provided'
            before the service name is even considered."
    (let [src (script)]
      (doseq [line (->> (str/split-lines src)
                        (filter #(re-find #"docker compose.*(exec|run --rm) " %)))]
        (is (str/includes? line "-f ")
            (str "printed command has no -f and there is no default compose "
                 "file: " (str/trim line)))))))

(deftest the-printed-sequence-creates-an-admin
  (testing "digdir.setup.bootstrap seeds the tenant and does NOT create admin
            users; digdir.setup.first-admin does. The script asks for
            ADMIN_USER_EMAILS and then printed a next-step sequence that never
            consumed it — the capability existing while nothing on the
            documented path calls it (#532)."
    (let [src (script)]
      (is (str/includes? src "digdir.setup.first-admin")
          (str "the script prompts for ADMIN_USER_EMAILS but its printed "
               "next steps never create the account, so a newcomer who "
               "follows them exactly still cannot log in")))))

(defn- printed-commands
  "The commands the script actually PRINTS, with continuations joined.

   Measuring per source LINE is blind here and silently so: the script emits
   `docker compose … exec digdir-rag \\` and the `java -cp … digdir.setup.X`
   on two separate `say` lines, so no single line contains both halves and a
   per-line guard can never fire. This reconstructs the printed text first,
   which is the level the instruction exists at."
  [src]
  (let [printed (->> (str/split-lines src)
                     (keep #(second (re-matches #"^say \"(.*)\"$" %)))
                     (map #(str/replace % "\\\\" "\\")))]
    (loop [[l & more] printed acc [] cur nil]
      (cond
        (nil? l) (mapv str/trim (cond-> acc cur (conj cur)))
        (str/ends-with? (str/trim l) "\\")
        (recur more acc (str (or cur "") (str/replace (str/trim l) #"\\$" "") " "))
        cur (recur more (conj acc (str cur l)) nil)
        :else (recur more (conj acc l) nil)))))

(deftest the-printed-command-reader-can-see-both-halves
  (testing "instrument check: a joined command must contain the compose call
            AND the java call, or every guard below passes vacuously"
    (let [cmds (printed-commands (script))
          seeds (filter #(str/includes? % "digdir.setup.") cmds)]
      (is (seq seeds) "no printed command mentions a digdir.setup entry point")
      (is (some #(str/includes? % "docker compose") seeds)
          "continuations were not joined; the guards below would be blind"))))

(deftest no-setup-command-is-invoked-through-exec
  (testing "Every `digdir.setup.*` -main is a second-JVM writer. Run against a
            server that is up, the write does not survive and the command
            reports success anyway — so #538 makes them REFUSE with exit 1
            when a server answers /up. `docker compose exec` requires the
            service to be RUNNING, so a printed `exec` of a setup command is
            an instruction guaranteed to be refused. Seeding has to go through
            `run --rm`, before `up -d`."
    (doseq [cmd (filter #(str/includes? % "digdir.setup.")
                        (printed-commands (script)))]
      (is (not (re-find #"docker compose.* exec " cmd))
          (str "setup command invoked via exec: " cmd)))))

(deftest the-seeding-is-printed-before-the-server-starts
  (testing "Order is the fix, not better restart advice."
    (let [cmds (printed-commands (script))
          idx (fn [pred] (first (keep-indexed #(when (pred %2) %1) cmds)))
          i-seed (idx #(str/includes? % "digdir.setup.bootstrap"))
          i-admin (idx #(str/includes? % "digdir.setup.first-admin"))
          i-up (idx #(re-find #"yml up -d" %))]
      (is (and i-seed i-admin i-up)
          "expected printed bootstrap, first-admin and `up -d` steps")
      ;; Guarded: with a step missing an index is nil, and comparing it threw
      ;; a NullPointerException on top of the assertion that already failed.
      ;; An ERROR says less than a FAIL about what is wrong.
      (when (and i-seed i-admin i-up)
        (is (< i-seed i-up) "`up -d` is printed before the bootstrap seed")
        (is (< i-admin i-up) "`up -d` is printed before the admin is created")))))

(deftest the-provider-switch-variable-is-one-clojure-actually-reads
  (testing "PROVIDER_VARS is a fourth shell-side copy of a name Clojure owns.
            A typo here would write a variable into .env that nothing reads,
            and the symptom would be the very defect the prompt exists to
            prevent — no provider chosen, and a query failing about the other
            one. Checked against the binding table rather than a literal."
    (let [declared (set (map :env-var env-bridge/env-config-bindings))
          provider (shell-list (script) "PROVIDER_VARS")]
      (is (seq provider) "PROVIDER_VARS not parsed from the script")
      (doseq [v provider]
        (is (contains? declared v)
            (str v " is prompted for by setup-env.sh but is not an env-bridge "
                 "binding, so nothing would ever read it")))
      (testing "and it is the switch the accessor resolves, not a near-miss"
        (is (contains? provider "AZURE_OPENAI_USE_AZURE"))))))
