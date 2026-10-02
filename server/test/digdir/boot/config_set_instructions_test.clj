(ns digdir.boot.config-set-instructions-test
  "The `bb config-set` commands the LLM refusals print must WORK when typed.

   `bb config-set` reads its value argument as EDN. A credential is a string,
   so it must reach the task as an EDN STRING, quotes included. Unquoted, a key
   is read as a symbol: the command fails, and the error prints the key and
   saves it to a temp report file.

   So these tests do not look for a pattern in the message. They hand each
   printed command to bash with `bb config-set` swapped for `printf`, which shows
   exactly the arguments the task would receive, and read the value argument with
   the task's own EDN reader. The value must come back as the same string.
   `the-unquoted-form-is-what-this-catches` is the control: the same pipeline
   over the unquoted form must NOT give back the string."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.provider-switch :as provider-switch]
            [digdir.config.accessor :as accessor]
            [digdir.llm.provider :as provider]
            [digdir.llm.provider-fixtures :as fx]
            [digdir.secrets :as secrets]))

(def ^:private switch-path "services.azure-openai.use-azure-openai-api")
(def ^:private model-path "services.azure-openai.model-name")
(def ^:private deployment-path "services.azure-openai.deployment-name")

;; Synthetic values shaped like real ones: a letter-first key, and a URL whose
;; `https:` an unquoted EDN read takes for a namespace.
(def ^:private synthetic-env
  {"OPENAI_API_KEY" "Zsyntheticconfigsetkey0notarealkey0abcdef0123456789"
   "OPENAI_API_ENDPOINT" "https://config-set-test.invalid/v1"
   "AZURE_OPENAI_API_KEY" "Ysyntheticazurekey0notarealkey0fedcba9876543210"
   "AZURE_OPENAI_API_ENDPOINT" "https://config-set-test-azure.invalid"})

(defn- read-value
  "The value as `bb config-set` reads it (bb.edn's `read-edn`, the same reader
   map), or ::unreadable."
  [s]
  (try (edn/read-string {:readers {'sorted/map identity}} s)
       (catch Exception _ ::unreadable)))

(defn- argv
  "The arguments bash hands `bb config-set` for `command`, typed with `env`
   loaded."
  [command env]
  (is (str/starts-with? command "bb config-set ") command)
  (let [{:keys [out exit err]} (shell/sh "bash" "-c"
                                         (str "printf '%s\\0' " (subs command (count "bb config-set ")))
                                         :env (assoc env "PATH" (System/getenv "PATH")))]
    (is (zero? exit) err)
    (str/split out #"\u0000")))

(defn- commands [message]
  (mapv second (re-seq #"`(bb config-set [^`]+)`" message)))

(defn- boot-refusal
  "The boot check's refusal over `tenant->path->value`, with `env` as the
   process environment (through the seam `digdir.secrets` reads)."
  [tenant->path->value env]
  (let [lookup (fn [tenant path] (get-in tenant->path->value [tenant path]))]
    (with-redefs [accessor/get-platform-value (fn [path {:keys [tenant]}] (lookup tenant path))
                  accessor/get (fn [{:keys [tenant]} & parts] (lookup tenant (str/join "." (map name parts))))]
      (binding [secrets/*env-lookup* (fn [k] (get env k))]
        (try (provider-switch/check! (vec (keys tenant->path->value))) nil
             (catch clojure.lang.ExceptionInfo e e))))))

(deftest the-boot-refusal-commands-pass-each-value-as-an-edn-string
  (let [e (boot-refusal {"upcompat" {switch-path false model-path "m"}
                         "upenv" {switch-path true deployment-path "d"}}
                        synthetic-env)
        violations (:credential-violations (ex-data e))
        cmds (commands (ex-message e))]
    (is (= 4 (count violations)) "both tenants, both credentials")
    (is (= (count violations) (count cmds)) "one command per unseeded credential")
    (doseq [[{:keys [tenant path env-var]} cmd] (map vector violations cmds)]
      (testing cmd
        (let [[p v t root k] (argv cmd synthetic-env)]
          (is (= [path tenant "platform" "default"] [p t root k]))
          (is (= (get synthetic-env env-var) (read-value v))
              "the variable's value reaches bb config-set as the same EDN string"))))
    (testing "the message names the variables, never their values"
      (doseq [v (vals synthetic-env)]
        (is (not (str/includes? (ex-message e) v)))))))

(deftest the-per-call-refusal-command-passes-the-value-as-an-edn-string
  (doseq [[values path] [[{switch-path true "services.azure-openai.api-key" nil
                           "services.azure-openai.api-endpoint" "https://a.invalid"
                           deployment-path "d"}
                          "services.azure-openai.api-key"]
                         [{"services.llm.provider" :openai-compatible
                           "services.llm.api-key" "k" "services.llm.api-endpoint" nil}
                          "services.llm.api-endpoint"]]]
    (let [e (try (fx/with-install values #(provider/resolve "t")) nil
                 (catch clojure.lang.ExceptionInfo e e))
          [cmd :as cmds] (commands (ex-message e))
          typed "https://typed-in.invalid/v1"]
      (testing path
        (is (= path (:path (ex-data e))))
        (is (= 1 (count cmds)))
        (let [[p v] (argv (str/replace cmd "<value>" typed) {})]
          (is (= path p))
          (is (= typed (read-value v))
              "the operator's value, typed where <value> stands, is read as that string"))))))

(deftest the-provider-choice-command-passes-a-keyword
  (let [e (boot-refusal {"t" {"services.azure-openai.api-key" "k"
                              "services.azure-openai.api-endpoint" "https://a.invalid"
                              deployment-path "d"}}
                        {})
        [cmd :as cmds] (commands (ex-message e))]
    (is (= 1 (count cmds)))
    (let [[p v t] (argv cmd {})]
      (is (= ["services.llm.provider" :azure "t"] [p (read-value v) t])))))

(deftest the-unquoted-form-is-what-this-catches
  ;; The control. Without it, a pipeline that turned every argument into a
  ;; string would pass the tests above whatever the message said.
  (let [[_ v] (argv "bb config-set services.llm.api-key $OPENAI_API_KEY t platform default" synthetic-env)]
    (is (not= (get synthetic-env "OPENAI_API_KEY") (read-value v))
        "an unquoted key is read as a symbol, not the string"))
  (let [[_ v] (argv "bb config-set services.llm.api-endpoint https://typed-in.invalid/v1 t platform default" {})]
    (is (not (string? (read-value v)))
        "an unquoted URL is not read as a string either")))
