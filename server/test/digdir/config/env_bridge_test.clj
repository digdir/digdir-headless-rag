(ns digdir.config.env-bridge-test
  "The env -> config bridge, and the invariants that keep the one table honest.

   The stubs return what the real helpers really return:
   `get-config-node-by-tenant-config-key` yields a node map carrying
   `:config.node/id`, and `set-node-value!` returns :created / :updated /
   :unchanged. Both shapes were read off the producers.

   Values never appear here either. The tests assert on PATHS, VARIABLE NAMES
   and coerced types; the one place a value is passed in, it is the literal
   string \"set\"."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.verify :as verify]
            [digdir.secrets :as secrets]
            [digdir.setup.common :as setup-common]))

(defn- with-env
  "Run `f` with the environment stubbed to `m`. Binds the same seam
   `digdir.secrets` reads through, which is the seam the bridge uses - a stub
   on a different door would prove nothing about this one."
  [m f]
  (binding [secrets/*env-lookup* (fn [k] (get m k))]
    (f)))

(defn- capture-writes
  "Run `f` with the config-DB writes captured instead of performed.

   Returns [result writes], where each write is {:path :value}. `node` nil
   stubs a tenant that has no Platform/default node."
  ([f] (capture-writes {:config.node/id "platform/t/default"} f))
  ([node f]
   (let [writes (atom [])]
     (with-redefs [config-db/get-config-node-by-tenant-config-key (fn [& _] node)
                   config-core/get-master-key (constantly "test-master-key")
                   config-db/set-node-value! (fn [_ opts]
                                               (swap! writes conj
                                                      (select-keys opts [:path :value]))
                                               :created)]
       [(f) @writes]))))

;; ---------------------------------------------------------------------------
;; The table itself
;; ---------------------------------------------------------------------------

(deftest table-is-internally-consistent
  (testing "no environment variable is declared twice"
    (let [names (mapv :env-var env-bridge/env-config-bindings)]
      (is (= (count names) (count (distinct names))))))

  (testing "no config path is claimed by two variables"
    ;; Two variables writing one path is a race whose winner is table order -
    ;; an ordering nobody would think to look at.
    (let [paths (keep :path env-bridge/env-config-bindings)]
      (is (= (count paths) (count (distinct paths))))))

  (testing "every bridged binding can actually be written"
    (doseq [b (filter env-bridge/bridged? env-bridge/env-config-bindings)]
      (is (string? (:path b)) (str (:env-var b) " must name a config path"))
      (is (contains? #{:string :boolean} (:value-type b))
          (str (:env-var b) " must declare a value-type the coercion knows"))))

  (testing "every binding says what it is, for the checklist"
    (doseq [b env-bridge/env-config-bindings]
      (is (seq (:what b)) (str (:env-var b) " must carry a description"))
      (is (contains? #{:boot :query :optional} (:tier b)))
      (is (contains? #{:config-db :environment :bootstrap} (:destination b))))))

(deftest the-bridge-never-writes-a-path-the-runtime-reads-from-the-environment
  ;; `digdir.config.db/env-migrated-paths` is the independent source: it is the
  ;; set the config layer already maintains for paths whose DB values are
  ;; ignored. Bridging one would produce the exact state that set exists to
  ;; describe - a value present, visible in the operator console, and never
  ;; read. Nobody would find that by looking at the bridge.
  (let [env-migrated @#'config-db/env-migrated-paths]
    (testing "no bridged binding targets an env-migrated path"
      (doseq [b (filter env-bridge/bridged? env-bridge/env-config-bindings)]
        (is (not (contains? env-migrated (:path b)))
            (str (:env-var b) " -> " (:path b) " is read from the environment,"
                 " so writing it to the config DB would be ignored"))))

    (testing "and a binding on an env-migrated path declares itself as such"
      (doseq [b env-bridge/env-config-bindings
              :when (contains? env-migrated (:path b))]
        (is (= :environment (:destination b))
            (str (:env-var b) " sits on an env-migrated path"))))))

(deftest every-runtime-required-path-can-be-supplied
  ;; The paths `digdir.config.verify` calls required are the ones whose absence
  ;; makes a subsystem unconfigured at runtime. If one of those has no
  ;; environment variable, a newcomer with the credential in hand still has no
  ;; supported way to get it in - which was the whole defect.
  (doseq [path verify/runtime-required-service-paths]
    (let [p (str/join "." (map name path))]
      (is (some? (env-bridge/env-var-for-path p))
          (str p " is required at runtime and nothing supplies it from the environment")))))

;; ---------------------------------------------------------------------------
;; Coercion
;; ---------------------------------------------------------------------------

(deftest coercion-matches-the-config-definitions-value-type
  (testing "a boolean path gets a boolean, not the string \"false\""
    ;; The string "false" is truthy in Clojure, so a boolean path left as a
    ;; string inverts the switch it controls.
    (let [b {:value-type :boolean}]
      (is (true? (env-bridge/coerce-value b "true")))
      (is (true? (env-bridge/coerce-value b "TRUE")))
      (is (true? (env-bridge/coerce-value b "1")))
      (is (false? (env-bridge/coerce-value b "false")))
      (is (false? (env-bridge/coerce-value b "no")))))

  (testing "a string path is trimmed"
    (is (= "sk-x" (env-bridge/coerce-value {:value-type :string} "  sk-x \n")))))

;; ---------------------------------------------------------------------------
;; The bridge
;; ---------------------------------------------------------------------------

(deftest writes-only-what-the-environment-supplies
  (testing "an unset variable produces no write"
    (let [[result writes] (capture-writes
                            #(with-env {} (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
      (is (= [] (:paths-written result)))
      (is (= [] writes))))

  (testing "a blank variable produces no write either"
    (let [[result writes] (capture-writes
                            #(with-env {"TYPESENSE_API_KEY_ADMIN" "   "}
                               (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
      (is (= [] (:paths-written result)))
      (is (= [] writes))))

  (testing "a set variable is written to its path, coerced"
    (let [[result writes] (capture-writes
                            #(with-env {"TYPESENSE_API_HOST" "search.example"
                                        "TYPESENSE_API_TLS" "true"}
                               (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
      (is (= ["services.typesense.api-host" "services.typesense.api-tls"]
             (:paths-written result)))
      (is (= [{:path "services.typesense.api-host" :value "search.example"}
              {:path "services.typesense.api-tls" :value true}]
             writes))
      (is (= {"services.typesense.api-host" :created
              "services.typesense.api-tls" :created}
             (:actions result))))))

(deftest a-variable-the-runtime-reads-directly-is-never-written
  ;; JWT_SECRET is set in every working environment. If the bridge wrote it,
  ;; every import would silently create a config value the runtime ignores.
  (let [[result writes] (capture-writes
                          #(with-env {"JWT_SECRET" "set" "ADMIN_USER_EMAILS" "set"}
                             (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
    (is (= [] (:paths-written result)))
    (is (= [] writes))))

(deftest the-services-filter-scopes-the-write
  ;; This is what keeps e2e writing exactly what it wrote before.
  (let [[result writes] (capture-writes
                          #(with-env {"AZURE_OPENAI_API_KEY" "set"
                                      "TYPESENSE_API_KEY_ADMIN" "set"}
                             (fn [] (env-bridge/seed-config-from-env!
                                      (atom :stub) "t" {:services #{:azure-openai}}))))]
    (is (= ["services.azure-openai.api-key"] (:paths-written result)))
    (is (= ["services.azure-openai.api-key"] (mapv :path writes)))))

(deftest a-missing-platform-default-node-is-reported-not-thrown
  ;; An import must not die because a tenant is shaped unexpectedly, but it
  ;; must not pretend it wrote anything either.
  (let [[result writes] (capture-writes
                          nil
                          #(with-env {"TYPESENSE_API_HOST" "search.example"}
                             (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t"))))]
    (is (= :no-platform-default-node (:error result)))
    (is (= [] (:paths-written result)))
    (is (= [] writes))))

(deftest one-failing-path-does-not-lose-the-others
  ;; `set-node-value!` throws when a definition is absent. Letting that escape
  ;; would abandon every later credential in the table.
  (let [writes (atom [])]
    (with-redefs [config-db/get-config-node-by-tenant-config-key
                  (fn [& _] {:config.node/id "platform/t/default"})
                  config-core/get-master-key (constantly "test-master-key")
                  config-db/set-node-value!
                  (fn [_ {:keys [path] :as opts}]
                    (if (= path "services.typesense.api-host")
                      (throw (ex-info "Config definition not found" {:path path}))
                      (do (swap! writes conj (select-keys opts [:path])) :created)))]
      (let [result (with-env {"TYPESENSE_API_HOST" "search.example"
                              "TYPESENSE_API_KEY_ADMIN" "set"}
                     (fn [] (env-bridge/seed-config-from-env! (atom :stub) "t")))]
        (is (= ["services.typesense.api-key-admin"] (:paths-written result)))
        (is (= [{:path "services.typesense.api-key-admin"}] @writes))
        (is (= [{:path "services.typesense.api-host"
                 :env-var "TYPESENSE_API_HOST"
                 :reason "Config definition not found"}]
               (:skipped result)))))))

;; ---------------------------------------------------------------------------
;; The shopping list
;; ---------------------------------------------------------------------------

(deftest the-shopping-list-distinguishes-three-different-answers
  (testing "a bridged path names the variable that supplies it"
    (let [line (env-bridge/supply-instruction "services.typesense.api-key-admin")]
      (is (re-find #"TYPESENSE_API_KEY_ADMIN" line))
      (is (re-find #"services\.typesense\.api-key-admin" line))))

  (testing "an env-migrated path says the DB value is not consulted"
    ;; Without this the reader sets JWT_SECRET, sees the value still reported,
    ;; and concludes the variable does not work.
    (let [line (env-bridge/supply-instruction "services.auth.jwt-secret")]
      (is (re-find #"JWT_SECRET" line))
      (is (re-find #"not consulted" line))))

  (testing "a path nothing supplies says so, and says what to do instead"
    (let [line (env-bridge/supply-instruction "services.judge.model")]
      (is (re-find #"no environment variable" line))
      (is (re-find #"bb config-set" line))))

  (testing "the list is de-duplicated and follows table order"
    (is (= [(env-bridge/supply-instruction "services.typesense.api-host")
            (env-bridge/supply-instruction "services.azure-openai.api-key")]
           (env-bridge/shopping-list ["services.azure-openai.api-key"
                                      "services.typesense.api-host"
                                      "services.azure-openai.api-key"])))))

(deftest presence-is-reported-without-the-value
  (with-env {"TYPESENSE_API_KEY_ADMIN" "set" "COLBERT_API_KEY" "  "}
    (fn []
      (is (true? (env-bridge/env-var-present? "TYPESENSE_API_KEY_ADMIN")))
      (is (false? (env-bridge/env-var-present? "COLBERT_API_KEY")))
      (is (= ["TYPESENSE_API_KEY_ADMIN"] (env-bridge/supplied-env-vars))))))

;; ---------------------------------------------------------------------------
;; The projection e2e depends on
;; ---------------------------------------------------------------------------

(deftest the-azure-projection-still-has-the-shape-e2e-reads
  (let [m (env-bridge/env-var->config-path :azure-openai)]
    (is (= 6 (count m)))
    (is (= "services.azure-openai.api-key" (get m "AZURE_OPENAI_API_KEY")))
    (is (= "services.azure-openai.use-azure-openai-api" (get m "AZURE_OPENAI_USE_AZURE")))))

(deftest credentials-are-marked-so-a-checklist-can-say-which-not-to-paste
  (is (true? (env-bridge/secret-env-var? "TYPESENSE_API_KEY_ADMIN")))
  (is (true? (env-bridge/secret-env-var? "AZURE_OPENAI_API_KEY")))
  (is (false? (env-bridge/secret-env-var? "TYPESENSE_API_HOST"))
      "a host is a setting, not a credential")
  (is (true? (env-bridge/secret-env-var? "SOMETHING_NOBODY_DECLARED"))
      "an unknown name defaults to secret - the safe direction")
  ;; The unknown-defaults-to-secret rule is safe but it is not TRUE, and this
  ;; table's whole purpose is being true. The database pointers were the rows
  ;; it mislabelled, which is why they live in the table now.
  (is (false? (env-bridge/secret-env-var? "DATAHIKE_FILE_PATH"))
      "a file path is not a credential")
  (is (true? (env-bridge/secret-env-var? "ADH_POSTGRES_PWD")))
  (is (true? (env-bridge/secret-env-var? "ADH_POSTGRES_URL"))
      "such URLs routinely embed the password"))

(deftest the-setup-table-lists-each-required-variable-exactly-once
  ;; The backend families are boot-tier AND chosen separately, so a naive
  ;; concat prints DATAHIKE_FILE_PATH twice.
  (let [output (with-out-str (setup-common/check-env-vars))
        lines (->> (str/split-lines output)
                   (keep #(second (re-find #"^  ([A-Z0-9_]+)\s" %))))]
    (is (seq lines) "the table must actually render, or this test is vacuous")
    (is (= (count lines) (count (distinct lines)))
        (str "duplicated rows: "
             (pr-str (->> lines frequencies (filter (fn [[_ n]] (> n 1))) (mapv first)))))
    (is (some #{"DATAHIKE_FILE_PATH"} lines))
    (is (some #{"TYPESENSE_API_KEY_ADMIN"} lines))
    (is (not (some #{"TYPESENSE_API_KEY"} lines))
        "the variable nothing in the running system consults is gone")))

(deftest variables-no-config-write-can-reach-are-in-the-table-and-never-bridged
  ;; #314 added OPENAI_API_ENDPOINT / OPENAI_API_KEY to the wizard's list with
  ;; the reason attached: `digdir.llm.client` reads them per call and there is
  ;; no `services.openai.*` definition for a value to live at. They belong in
  ;; the table for the checklist, and must never be written.
  (let [by-name (into {} (map (juxt :env-var identity)) env-bridge/env-config-bindings)]
    (doseq [n ["OPENAI_API_ENDPOINT" "OPENAI_API_KEY"]]
      (is (contains? by-name n) (str n " must appear in the one table"))
      (is (= :environment (:destination (get by-name n))))
      (is (nil? (:path (get by-name n)))
          "there is not even an ignored config path to name")))
  (is (empty? (filter #(and (env-bridge/bridged? %) (nil? (:path %)))
                      env-bridge/env-config-bindings))
      "nothing pathless is ever bridged"))

(deftest binding-for-path-does-not-match-the-pathless-rows
  ;; Several rows carry :path nil. A nil lookup matching the first of them
  ;; would make the shopping list name a confidently wrong variable.
  (is (nil? (env-bridge/binding-for-path nil)))
  (is (nil? (env-bridge/env-var-for-path nil))))

(deftest a-finding-keyed-by-variable-name-still-gets-the-right-instruction
  ;; Findings key on the config path where there is one and on the variable
  ;; name where there is not. Resolving only by path produced a line that was
  ;; wrong in both halves: it told the reader OPENAI_API_ENDPOINT was supplied
  ;; by no environment variable, and to set it with `bb config-set`.
  (let [line (env-bridge/supply-instruction "OPENAI_API_ENDPOINT")]
    (is (str/includes? line "OPENAI_API_ENDPOINT"))
    (is (not (str/includes? line "no environment variable"))
        "it IS an environment variable")
    (is (not (str/includes? line "bb config-set"))
        "and `bb config-set` cannot supply it - nothing reads a config value here"))
  (is (= [(env-bridge/supply-instruction "OPENAI_API_ENDPOINT")
          (env-bridge/supply-instruction "OPENAI_API_KEY")]
         (env-bridge/shopping-list ["OPENAI_API_KEY" "OPENAI_API_ENDPOINT"]))
      "and they keep their table order rather than falling into the unknown tail"))

(deftest the-llm-half-of-the-setup-screen-is-grouped-by-provider
  ;; Flat, the nine query-tier rows read as one Azure block - and two of the
  ;; six AZURE_OPENAI_* names are the variables the LOCAL path sets. A reader
  ;; following onboarding 4a met AZURE_OPENAI_USE_AZURE under a heading that
  ;; looked like it did not apply to them, which is the naming trap 4a exists
  ;; to defuse, reappearing on the screen whose job is to list these.
  (let [output (with-out-str (setup-common/check-env-vars))
        ;; The section a row falls under = the nearest preceding heading BLOCK,
        ;; joined. Headings here span more than one line, and a detector that
        ;; took only the first would have missed the half that carries the
        ;; warning - so it would pass while the screen said nothing.
        lines (vec (str/split-lines output))
        row? (fn [l] (re-find #"^  [A-Z0-9_]+\s" l))
        heading? (fn [l] (and (not (str/blank? l)) (not (row? l))))
        section-of (fn [var-name]
                     (let [idx (first (keep-indexed
                                        (fn [i l] (when (re-find (re-pattern (str "^  " var-name "\\s")) l) i))
                                        lines))
                           h (when idx (last (filter #(heading? (lines %)) (range idx))))]
                       (when h
                         (let [start (loop [i h]
                                       (if (and (pos? i) (heading? (lines (dec i))))
                                         (recur (dec i))
                                         i))]
                           (str/join " " (subvec lines start (inc h)))))))]
    (is (seq output) "the screen must render, or this test is vacuous")

    (testing "the local path's variables are marked as not-Azure"
      (let [sec (section-of "AZURE_OPENAI_MODEL_NAME")]
        (is (str/includes? sec "false"))
        (is (str/includes? sec "NOT Azure")
            "the name says Azure and the variable is not - say so here")
        (is (= sec (section-of "OPENAI_API_KEY"))
            "and it sits with the rest of that path")
        (is (= sec (section-of "OPENAI_API_ENDPOINT")))))

    (testing "the Azure-only credentials are in a different group"
      (is (not= (section-of "AZURE_OPENAI_API_KEY")
                (section-of "AZURE_OPENAI_MODEL_NAME"))
          "one is required only on the Azure path, the other only on the local one")
      (is (str/includes? (section-of "AZURE_OPENAI_API_KEY") "true")))

    (testing "the switch itself is above both groups, not inside one"
      (is (str/includes? (section-of "AZURE_OPENAI_USE_AZURE") "Needed for a real query")))

    (testing "the two the local path cannot work without are NOT filed as optional"
      ;; They sit at :optional in the table because only one path uses them.
      ;; Rendering that literally told a local-model reader they were optional.
      (doseq [v ["OPENAI_API_ENDPOINT" "OPENAI_API_KEY"]]
        (is (not (str/includes? (section-of v) "Optional"))
            (str v " is required on the path that uses it"))))))
