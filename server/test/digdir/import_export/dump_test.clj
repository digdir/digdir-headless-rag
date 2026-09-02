(ns digdir.import-export.dump-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.api-keys :as api-keys-db]
            [digdir.config.db :as config-db]
            [digdir.config.schema :as config-schema]
            [digdir.data.db :as data-db]
            [digdir.import-export.dump :as dump]
            [digdir.import-export.entities.api-keys :as api-key-entities])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- create-config-test-db []
  (let [cfg {:store {:backend :mem :id (str "dump-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data config-schema/config-migration-schema})
      conn)))

(defn- create-main-test-db []
  (let [cfg {:store {:backend :mem :id (str "dump-test-main-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data data-db/dh-schema})
      conn)))

(defn- delete-test-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- temp-dump-dir ^File []
  (.toFile (Files/createTempDirectory
            "dump-test-"
            (into-array FileAttribute []))))

(def ^:private sample-defs
  [{:path "skills.retrieval.top-k"
    :root :runtime
    :value-type :number
    :description "Maximum number of chunks returned per retrieval call"}
   {:path "skills.rerank.rag.enabled"
    :root :runtime
    :value-type :boolean
    :description "Whether to rerank chunks before synthesis"}
   {:path "services.azure-openai.api-key"
    :root :platform
    :value-type :string
    :encrypted? true
    :description "Azure OpenAI API key"}])

(defn- seed-nodes!
  "Seed a parent and two children. Returns the seeded node-id sequence."
  [conn]
  (config-db/register-tenant! conn "acme")
  (config-db/create-config-node! conn
                                 {:root :runtime
                                  :tenant "acme"
                                  :node-id "runtime/acme/root"
                                  :label "Acme Root"
                                  :tenant-config-key "root"
                                  :system-managed? false})
  (config-db/create-config-node! conn
                                 {:root :runtime
                                  :tenant "acme"
                                  :node-id "runtime/acme/child-a"
                                  :label "Child A"
                                  :tenant-config-key "child-a"
                                  :parent-id "runtime/acme/root"})
  (config-db/create-config-node! conn
                                 {:root :runtime
                                  :tenant "acme"
                                  :node-id "runtime/acme/child-b"
                                  :label "Child B"
                                  :tenant-config-key "child-b"
                                  :parent-id "runtime/acme/root"})
  ["runtime/acme/root" "runtime/acme/child-a" "runtime/acme/child-b"])

(deftest config-nodes-roundtrip
  (testing "nodes export and import preserves identity, parent wiring, and tenant grouping"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)]
      (try
        (config-db/upsert-definitions-batch! src-conn sample-defs)
        (let [seeded-ids (seed-nodes! src-conn)]
          (dump/export-system src-conn src-main-conn dump-dir {})
          (is (.exists (io/file dump-dir "01-config-nodes.yaml")))
          (dump/import-system dst-conn dst-main-conn dump-dir {})
          (let [src-nodes (->> seeded-ids
                               (map #(config-db/get-config-node @src-conn %)))
                dst-nodes (->> seeded-ids
                               (map #(config-db/get-config-node @dst-conn %)))]
            (testing "every seeded node landed in the destination"
              (is (every? some? dst-nodes)
                  (str "missing nodes: "
                       (vec (keep-indexed (fn [i n] (when-not n (nth seeded-ids i)))
                                          dst-nodes)))))
            (testing "parent wiring restored"
              (let [child-a (config-db/get-config-node @dst-conn "runtime/acme/child-a")
                    parent-id (get-in child-a [:config.node/parent :config.node/id])]
                (is (= "runtime/acme/root" parent-id))))
            (testing "labels and roots preserved"
              (doseq [[src dst] (map vector src-nodes dst-nodes)]
                (is (= (:config.node/label src) (:config.node/label dst)))
                (is (= (:config.node/root src) (:config.node/root dst)))
                (is (= (:config.node/tenant src) (:config.node/tenant dst)))))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest config-values-roundtrip
  (testing "values across multiple tenants round-trip with correct grouping"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)
          value-defs [{:path "app.foo" :root :runtime :value-type :string}
                      {:path "app.bar" :root :runtime :value-type :string}]
          tenants ["acme" "globex"]
          values [{:tenant "acme" :node-id "runtime/acme/root" :path "app.foo" :value "acme-foo-v1"}
                  {:tenant "acme" :node-id "runtime/acme/root" :path "app.bar" :value "acme-bar-v1"}
                  {:tenant "globex" :node-id "runtime/globex/root" :path "app.foo" :value "globex-foo-v1"}]]
      (try
        (config-db/upsert-definitions-batch! src-conn value-defs)
        (doseq [tenant tenants]
          (config-db/register-tenant! src-conn tenant)
          (config-db/create-config-node! src-conn
                                         {:root :runtime
                                          :tenant tenant
                                          :node-id (str "runtime/" tenant "/root")
                                          :label (str tenant " root")
                                          :tenant-config-key "root"}))
        (doseq [v values]
          (config-db/set-node-value! src-conn (assoc v :root :runtime)))
        (let [{:keys [files]} (dump/export-system src-conn src-main-conn dump-dir {})
              by-name (into {} (map (juxt :filename :count) files))]
          (testing "one file per tenant under 04-config-values/"
            (is (= 2 (get by-name "04-config-values/acme.yaml")))
            (is (= 1 (get by-name "04-config-values/globex.yaml")))))
        (is (.exists (io/file dump-dir "04-config-values" "acme.yaml")))
        (is (.exists (io/file dump-dir "04-config-values" "globex.yaml")))
        (dump/import-system dst-conn dst-main-conn dump-dir {})
        (testing "every seeded value comes back with the same raw"
          (doseq [v values]
            (let [reloaded (config-db/get-node-value @dst-conn :runtime
                                                     (:tenant v) (:node-id v) (:path v))]
              (is (some? reloaded)
                  (str "missing value: " (:tenant v) " " (:path v)))
              (is (= (:value v) (:config.value/raw reloaded))
                  (str "raw mismatch at " (:tenant v) " " (:path v))))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(def ^:private test-master-key "test-master-key-32-chars-padding!")

(deftest api-keys-hashed-storage-roundtrip
  (testing "API-key exports contain only a digest and remain usable after import"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)
          plaintext-key "rag_test_secret_value_12345"
          {:keys [api-key-id]} (api-keys-db/store-api-key
                                src-main-conn plaintext-key
                                "Test Key" "test-user-id")]
      (try
        (dump/export-system src-conn src-main-conn dump-dir
                            {:master-key test-master-key})
        (testing "export does not leak the plaintext"
          (let [yaml-str (slurp (io/file dump-dir "08-api-keys.yaml"))]
            (is (not (str/includes? yaml-str plaintext-key)))
            (is (str/includes? yaml-str (api-keys-db/api-key-digest plaintext-key)))))
        (dump/import-system dst-conn dst-main-conn dump-dir
                            {:master-key test-master-key})
        (testing "import stores no plaintext and preserves authentication"
          (let [reloaded (d/q '[:find (pull ?e [:api-key/id :api-key/key :api-key/key-digest :api-key/name]) .
                                :in $ ?id
                                :where [?e :api-key/id ?id]]
                              @dst-main-conn api-key-id)]
            (is (nil? (:api-key/key reloaded)))
            (is (= (api-keys-db/api-key-digest plaintext-key)
                   (:api-key/key-digest reloaded)))
            (is (= "Test Key" (:api-key/name reloaded)))
            (is (= api-key-id
                   (:api-key-id (api-keys-db/validate-api-key dst-main-conn plaintext-key))))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest legacy-plaintext-api-key-import-is-hashed-before-storage
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)
        plaintext "rag_legacy_dump_secret"
        record {:api-key/id "legacy-import-key"
                :api-key/key plaintext
                :api-key/name "Legacy imported key"
                :api-key/created 1
                :api-key/created-by "import"
                :api-key/revoked false
                :api-key/scopes [:query]}]
    (try
      (is (= {:created 1 :skipped 0 :overwritten 0}
             (api-key-entities/import-api-keys! config-conn main-conn [record] :skip)))
      (let [stored (d/pull @main-conn '[:api-key/key :api-key/key-digest]
                           [:api-key/id "legacy-import-key"])]
        (is (nil? (:api-key/key stored)))
        (is (= (api-keys-db/api-key-digest plaintext)
               (:api-key/key-digest stored))))
      (is (= "legacy-import-key"
             (:api-key-id (api-keys-db/validate-api-key main-conn plaintext))))
      (finally
        (delete-test-db config-conn)
        (delete-test-db main-conn)))))

(defn- date-ms-utc
  "Convert YYYY-MM-DD UTC midnight to epoch ms."
  [date-str]
  (.toEpochMilli (.toInstant (.atStartOfDay (java.time.LocalDate/parse date-str)
                                            java.time.ZoneOffset/UTC))))

(deftest conversations-date-partition-roundtrip
  (testing "conversations partition by created-date across multiple .jsonl files"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)
          d1-ts (date-ms-utc "2026-04-15")
          d2-ts (date-ms-utc "2026-04-16")
          ;; agent-id is required by import-conversations! (assoc'd
          ;; unconditionally; nil rejected by datahike). Same applies to
          ;; message/voice, /completion, /kind — must all be non-nil.
          agent-id "builtin/agent-rag-agent"
          base-msg {:message/voice "user"
                    :message/completion false
                    :message/kind "kind/markdown"}
          mk-msg (fn [id text role created]
                   (merge base-msg
                          {:message/id id :message/text text :message/role role
                           :message/created created :message/chunks []}))
          convos [{:conversation/id "c1"
                   :conversation/topic "First chat"
                   :conversation/created d1-ts
                   :conversation/agent-id agent-id
                   :conversation/messages
                   [(mk-msg "m1-1" "hello" "user" d1-ts)
                    (mk-msg "m1-2" "hi back" "assistant" (inc d1-ts))]}
                  {:conversation/id "c2"
                   :conversation/topic "Second chat same day"
                   :conversation/created (+ d1-ts 3600000)
                   :conversation/agent-id agent-id
                   :conversation/messages
                   [(mk-msg "m2-1" "another" "user" (+ d1-ts 3600000))]}
                  {:conversation/id "c3"
                   :conversation/topic "Next day chat"
                   :conversation/created d2-ts
                   :conversation/agent-id agent-id
                   :conversation/messages
                   [(mk-msg "m3-1" "morning" "user" d2-ts)]}]]
      (try
        (d/transact src-main-conn {:tx-data convos})
        (let [{:keys [files]} (dump/export-system src-conn src-main-conn
                                                  dump-dir {})
              by-name (into {} (map (juxt :filename :count) files))]
          (testing "two date-partitioned .jsonl files written"
            (is (= 2 (get by-name "conversations/2026-04-15.jsonl")))
            (is (= 1 (get by-name "conversations/2026-04-16.jsonl")))))
        (is (.exists (io/file dump-dir "conversations" "2026-04-15.jsonl")))
        (is (.exists (io/file dump-dir "conversations" "2026-04-16.jsonl")))
        (dump/import-system dst-conn dst-main-conn dump-dir {})
        (testing "every conversation comes back with topic + messages intact"
          (doseq [convo convos]
            (let [reloaded (d/q '[:find (pull ?e [:conversation/id
                                                  :conversation/topic
                                                  {:conversation/messages
                                                   [:message/id :message/text :message/role]}]) .
                                  :in $ ?id
                                  :where [?e :conversation/id ?id]]
                                @dst-main-conn (:conversation/id convo))
                  reloaded-msg-ids (set (map :message/id (:conversation/messages reloaded)))
                  expected-msg-ids (set (map :message/id (:conversation/messages convo)))]
              (is (= (:conversation/topic convo) (:conversation/topic reloaded))
                  (str "topic mismatch for " (:conversation/id convo)))
              (is (= expected-msg-ids reloaded-msg-ids)
                  (str "message id mismatch for " (:conversation/id convo))))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest conversation-type-keyword-coerced-on-import
  (testing "JSON round-trip drops keyword type; import must coerce string -> keyword
            for schema-:db.type/keyword attrs so the AVET index stays consistent
            (otherwise queries like [?e :conversation/type :playground] choke when
            the index range mixes types)"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)
          created-at (date-ms-utc "2026-04-20")
          agent-id "builtin/agent-rag-agent"
          ;; Source-side message values are KEYWORDS (matching how live
          ;; runtime code writes them in digdir.data.db). The dump-export
          ;; will JSON-encode them to strings; the importer must coerce
          ;; back so the AVET index isn't split across two types.
          mk-msg (fn [id text]
                   {:message/id id :message/text text :message/role :user
                    :message/voice :user :message/completion false
                    :message/kind :kind/markdown
                    :message/created created-at :message/chunks []})
          convos [{:conversation/id "with-kw-type"
                   :conversation/topic "kw"
                   :conversation/created created-at
                   :conversation/agent-id agent-id
                   :conversation/type :playground
                   :conversation/view-mode :focused
                   :conversation/messages [(mk-msg "m1" "hi")]}]]
      (try
        (d/transact src-main-conn {:tx-data convos})
        (dump/export-system src-conn src-main-conn dump-dir {})
        (dump/import-system dst-conn dst-main-conn dump-dir {})
        (testing "conversation-level keyword attrs"
          (let [reloaded (d/q '[:find (pull ?e [:conversation/type :conversation/view-mode]) .
                                :in $ ?id :where [?e :conversation/id ?id]]
                              @dst-main-conn "with-kw-type")]
            (is (= :playground (:conversation/type reloaded))
                ":conversation/type must come back as a keyword, not the string \"playground\"")
            (is (= :focused (:conversation/view-mode reloaded))
                ":conversation/view-mode must come back as a keyword, not the string \"focused\"")))
        (testing "message-level keyword-ish attrs (role/voice/kind)"
          (let [reloaded (d/q '[:find (pull ?m [:message/role :message/voice :message/kind]) .
                                :in $ ?id :where [?m :message/id ?id]]
                              @dst-main-conn "m1")]
            (is (= :user (:message/role reloaded))
                ":message/role must come back as keyword to match live-runtime writes")
            (is (= :user (:message/voice reloaded)))
            (is (= :kind/markdown (:message/kind reloaded)))))
        (testing "literal-keyword AVET queries work without ISeq errors"
          (is (= 1 (count (d/q '[:find [?e ...]
                                 :where [?e :conversation/type :playground]]
                               @dst-main-conn))))
          (is (= 1 (count (d/q '[:find [?m ...]
                                 :where [?m :message/role :user]]
                               @dst-main-conn)))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest timestamps-preserved-roundtrip
  (testing "config entities preserve created-at/updated-at across export+import on a fresh DB"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)
          ;; Pick distinct, non-now timestamps so any "use System/currentTimeMillis"
          ;; regression shows up immediately.
          def-ts 1700000000000
          node-ts 1700000111111
          val-ts-created 1700000222222
          val-ts-updated 1700000333333
          dataset-ts 1700000444444
          pipeline-ts 1700000555555]
      (try
        ;; Seed source with explicit timestamps via direct datahike transact
        ;; so we can prove the IMPORT preserves them, not just that creation
        ;; happens to use the input value.
        (d/transact src-conn
                    {:tx-data [{:config-def/path "app.foo"
                                :config-def/root :runtime
                                :config-def/value-type :string
                                :config-def/encrypted? false
                                :config-def/multiline? false
                                :config-def/created-at def-ts}]})
        (config-db/register-tenant! src-conn "acme")
        (d/transact src-conn
                    {:tx-data [{:config.node/id "runtime/acme/root"
                                :config.node/root :runtime
                                :config.node/tenant "acme"
                                :config.node/label "acme root"
                                :config.node/tenant-config-key "root"
                                :config.node/system-managed? false
                                :config.node/enabled? true
                                :config.node/created-at node-ts
                                :config.node/updated-at node-ts}]})
        (d/transact src-conn
                    {:tx-data [{:config.value/id "runtime:acme:runtime/acme/root:app.foo"
                                :config.value/root :runtime
                                :config.value/tenant "acme"
                                :config.value/node [:config.node/id "runtime/acme/root"]
                                :config.value/definition [:config-def/path "app.foo"]
                                :config.value/raw "v1"
                                :config.value/created-at val-ts-created
                                :config.value/updated-at val-ts-updated}]})
        (d/transact src-conn
                    {:tx-data [{:dataset/id "ds-test"
                                :dataset/name "Test"
                                :dataset/enabled? true
                                :dataset/created-at dataset-ts
                                :dataset/updated-at dataset-ts}]})
        (d/transact src-conn
                    {:tx-data [{:dataset.pipeline/id "pl-test"
                                :dataset.pipeline/dataset [:dataset/id "ds-test"]
                                :dataset.pipeline/enabled? true
                                :dataset.pipeline/created-at pipeline-ts
                                :dataset.pipeline/updated-at pipeline-ts}]})
        (dump/export-system src-conn src-main-conn dump-dir {})
        (dump/import-system dst-conn dst-main-conn dump-dir {})
        (testing "config-def created-at preserved"
          (let [d (config-db/get-definition @dst-conn "app.foo")]
            (is (= def-ts (:config-def/created-at d)))))
        (testing "config-node created-at + updated-at preserved"
          (let [n (config-db/get-config-node @dst-conn "runtime/acme/root")]
            (is (= node-ts (:config.node/created-at n)))
            (is (= node-ts (:config.node/updated-at n)))))
        (testing "config-value created-at + updated-at preserved"
          (let [v (config-db/get-node-value @dst-conn :runtime "acme"
                                            "runtime/acme/root" "app.foo")]
            (is (= val-ts-created (:config.value/created-at v)))
            (is (= val-ts-updated (:config.value/updated-at v)))))
        (testing "dataset created-at + updated-at preserved"
          (let [d (config-db/get-dataset-record @dst-conn "ds-test")]
            (is (= dataset-ts (:dataset/created-at d)))
            (is (= dataset-ts (:dataset/updated-at d)))))
        (testing "dataset-pipeline created-at + updated-at preserved"
          (let [p (config-db/get-dataset-pipeline @dst-conn "pl-test")]
            (is (= pipeline-ts (:dataset.pipeline/created-at p)))
            (is (= pipeline-ts (:dataset.pipeline/updated-at p)))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest pin-of-version-preserved-roundtrip
  (testing ":config.value/pin-of-version survives export+import on a fresh DB"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)
          pin-version 42]
      (try
        ;; Seed defs + node so the import resolves.
        (config-db/upsert-definitions-batch!
         src-conn [{:path "app.pinned" :root :runtime :value-type :string}])
        (config-db/register-tenant! src-conn "acme")
        (config-db/create-config-node! src-conn
                                       {:root :runtime
                                        :tenant "acme"
                                        :node-id "runtime/acme/root"
                                        :label "acme root"
                                        :tenant-config-key "root"})
        ;; Direct transact so we can stamp pin-of-version explicitly.
        (d/transact src-conn
                    {:tx-data [{:config.value/id "runtime:acme:runtime/acme/root:app.pinned"
                                :config.value/root :runtime
                                :config.value/tenant "acme"
                                :config.value/node [:config.node/id "runtime/acme/root"]
                                :config.value/definition [:config-def/path "app.pinned"]
                                :config.value/raw "pinned-value"
                                :config.value/created-at 1700000000000
                                :config.value/updated-at 1700000000000
                                :config.value/pin-of-version pin-version}]})
        (dump/export-system src-conn src-main-conn dump-dir {})
        (dump/import-system dst-conn dst-main-conn dump-dir {})
        (let [v (config-db/get-node-value @dst-conn :runtime "acme"
                                          "runtime/acme/root" "app.pinned")]
          (is (some? v) "value should exist post-import")
          (is (= pin-version (:config.value/pin-of-version v))
              "pin-of-version should round-trip"))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest pin-of-version-unpin-on-overwrite
  (testing "overwriting a pinned value with a dump that lacks the pin retracts the existing pin"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)]
      (try
        ;; Source: an UNPINNED value
        (config-db/upsert-definitions-batch!
         src-conn [{:path "app.unpinned" :root :runtime :value-type :string}])
        (config-db/register-tenant! src-conn "acme")
        (config-db/create-config-node! src-conn
                                       {:root :runtime
                                        :tenant "acme"
                                        :node-id "runtime/acme/root"
                                        :label "acme root"
                                        :tenant-config-key "root"})
        (config-db/set-node-value! src-conn
                                   {:root :runtime
                                    :tenant "acme"
                                    :node-id "runtime/acme/root"
                                    :path "app.unpinned"
                                    :value "v1"})
        ;; Destination: pre-seed the SAME value WITH a pin (simulating a tenant
        ;; that pinned in the past).
        (config-db/upsert-definitions-batch!
         dst-conn [{:path "app.unpinned" :root :runtime :value-type :string}])
        (config-db/register-tenant! dst-conn "acme")
        (config-db/create-config-node! dst-conn
                                       {:root :runtime
                                        :tenant "acme"
                                        :node-id "runtime/acme/root"
                                        :label "acme root"
                                        :tenant-config-key "root"})
        (d/transact dst-conn
                    {:tx-data [{:config.value/id "runtime:acme:runtime/acme/root:app.unpinned"
                                :config.value/root :runtime
                                :config.value/tenant "acme"
                                :config.value/node [:config.node/id "runtime/acme/root"]
                                :config.value/definition [:config-def/path "app.unpinned"]
                                :config.value/raw "stale"
                                :config.value/created-at 1
                                :config.value/updated-at 1
                                :config.value/pin-of-version 99}]})
        (dump/export-system src-conn src-main-conn dump-dir {})
        (dump/import-system dst-conn dst-main-conn dump-dir
                            {:on-conflict :overwrite})
        (let [v (config-db/get-node-value @dst-conn :runtime "acme"
                                          "runtime/acme/root" "app.unpinned")]
          (is (= "v1" (:config.value/raw v)))
          (is (nil? (:config.value/pin-of-version v))
              "existing pin should be retracted when dump value is unpinned"))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))

(deftest config-defs-roundtrip
  (testing "export then import on a fresh DB recovers the same definitions"
    (let [src-conn (create-config-test-db)
          dst-conn (create-config-test-db)
          src-main-conn (create-main-test-db)
          dst-main-conn (create-main-test-db)
          dump-dir (temp-dump-dir)]
      (try
        (config-db/upsert-definitions-batch! src-conn sample-defs)
        (let [{:keys [files]} (dump/export-system src-conn src-main-conn dump-dir {})
              by-name (into {} (map (juxt :filename :count) files))]
          (is (= 3 (get by-name "00-config-defs.yaml")))
          (is (= 0 (get by-name "01-config-nodes.yaml")))
          (is (= 0 (get by-name "02-config-datasets.yaml")))
          (is (= 0 (get by-name "03-config-dataset-pipelines.yaml"))))
        (is (.exists (io/file dump-dir "manifest.yaml")))
        (doseq [f ["00-config-defs.yaml" "01-config-nodes.yaml"
                   "02-config-datasets.yaml" "03-config-dataset-pipelines.yaml"]]
          (is (.exists (io/file dump-dir f)) (str "missing file: " f)))
        (dump/import-system dst-conn dst-main-conn dump-dir {})
        (let [src-defs (config-db/get-all-definitions @src-conn)
              dst-defs (config-db/get-all-definitions @dst-conn)
              by-path #(into {} (map (juxt :config-def/path identity) %))
              src-by-path (by-path src-defs)
              dst-by-path (by-path dst-defs)]
          (testing "same set of definition paths"
            (is (= (set (keys src-by-path)) (set (keys dst-by-path)))))
          (testing "core fields match for each definition"
            (doseq [path (keys src-by-path)]
              (let [src (get src-by-path path)
                    dst (get dst-by-path path)]
                (is (= (:config-def/root src) (:config-def/root dst))
                    (str "root mismatch at " path))
                (is (= (:config-def/value-type src) (:config-def/value-type dst))
                    (str "value-type mismatch at " path))
                (is (= (:config-def/encrypted? src) (:config-def/encrypted? dst))
                    (str "encrypted? mismatch at " path))
                (is (= (:config-def/description src) (:config-def/description dst))
                    (str "description mismatch at " path))))))
        (finally
          (delete-test-db src-conn)
          (delete-test-db dst-conn)
          (delete-test-db src-main-conn)
          (delete-test-db dst-main-conn))))))
