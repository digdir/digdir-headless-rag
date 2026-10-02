(ns digdir.config.import-atomicity-test
  "an import is ONE transaction. Every phase's real code runs against a
   `config-db/speculation`, and `import-data` commits what it recorded once. So
   a refused import writes NOTHING, and the refusal names every problem the
   message scan can find, not only the first one a phase stopped at.

   Two oracles, so a green case cannot hide a changed outcome:
   - a refused import leaves the target's datoms exactly as they were;
   - a successful import leaves exactly the store the same phases leave when
     applied one at a time against the connection (the behaviour from before the atomic-import fix),
     compared entity by entity through each identity attribute."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.audit :as audit]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.ops.clone :as ops-clone]
            [digdir.config.ops.sync :as ops-sync]
            [digdir.config.round-trip :as rt]
            [digdir.config.schema :as schema]))

;; =============================================================================
;; A source with every kind of entity an import carries
;; =============================================================================

(def ^:private path-a "services.atomicity.a")
(def ^:private path-b "services.atomicity.b")
(def ^:private tenant "atomicity-tenant")

(defn- define! [conn path]
  (config-db/upsert-definition! conn {:path path
                                      :root :platform
                                      :value-type :string
                                      :encrypted? false
                                      :category :services
                                      :service :other
                                      :sensitivity :internal
                                      :function :settings}))

(defn- source!
  "Two definitions; a platform tree of a base node and a leaf node under it,
   each holding a value; a dataset with a pipeline."
  [conn]
  (define! conn path-a)
  (define! conn path-b)
  (ops-bootstrap/bootstrap-config-tree! conn {:root :platform
                                              :tenant tenant
                                              :base-values {path-a "base-a"}
                                              :leaf-values {path-b "leaf-b"}})
  (config-db/create-dataset! conn {:dataset-id "atomicity-dataset" :name "Atomicity dataset"})
  (config-db/create-dataset-pipeline! conn {:pipeline-id "atomicity-pipeline" :dataset-id "atomicity-dataset"})
  conn)

(defn- with-conns*
  "Call `f` with `n` fresh config stores, released afterwards."
  [n f]
  (let [conns (vec (repeatedly n rt/fresh-config-conn))]
    (try (apply f conns)
         (finally (run! rt/release! conns)))))

;; =============================================================================
;; The oracles
;; =============================================================================

(defn- datoms [db]
  (set (map (juxt :e :a :v) (d/datoms db :eavt))))

(def ^:private identity-attrs
  "Every identity attribute the config schema declares, DERIVED. A hand list
   of seven let the oracle go blind one entity kind at a time: with one of
   them removed, the comparisons still passed."
  (vec (sort (keep #(when (= :db.unique/identity (:db/unique %)) (:db/ident %))
                   schema/config-migration-schema))))

(def ^:private entity-kinds
  "The kinds of entity an import carries; a compared store must hold each."
  [:config-def/path :config.node/id :config.value/id :dataset/id :dataset.pipeline/id :tenant/id])

(defn- store-shape
  "Every entity with an identity attribute, keyed by that identity, as its
   attributes with references replaced by the identity they point at. The
   clock metadata `created-at` and `updated-at` is dropped: an import takes it
   from the payload when present and from the clock otherwise. Every other
   `*-at` (`deleted-at`, …) is STATE, compared as present or absent, since
   its instant is the clock's."
  [db]
  (let [ident (fn [e] (some (fn [a] (when-let [v (get (d/entity db e) a)] [a v])) identity-attrs))
        ref-ident (fn [v] (if (and (map? v) (:db/id v)) (ident (:db/id v)) v))
        clean (fn [m] (into {} (for [[k v] m
                                     :when (not= k :db/id)
                                     :when (not (#{"created-at" "updated-at"} (name k)))]
                                 [k (cond (str/ends-with? (name k) "-at") (some? v)
                                          (and (coll? v) (not (map? v))) (set (map ref-ident v))
                                          :else (ref-ident v))])))]
    (into {} (for [a identity-attrs
                   e (d/q [:find '[?e ...] :where ['?e a]] db)]
               [(ident e) (clean (d/pull db '[*] e))]))))

(defn- missing-kinds
  "PREMISE for a store comparison: the entity kinds `shape` lacks. An oracle
   that compared nothing would otherwise pass."
  [shape kinds]
  (vec (for [k kinds :when (not-any? #(= k (first %)) (keys shape))] k)))

(defn- value-raw
  "The stored raw value of the (active) value at `path`."
  [db path]
  (d/q '[:find ?raw . :in $ ?p
         :where [?d :config-def/path ?p] [?v :config.value/definition ?d] [?v :config.value/raw ?raw]
         (not [?v :config.value/deleted-at])]
       db path))

(defn- change-value!
  "Set the value at `path` on the node that holds it, as an operator would."
  [conn path value]
  (let [node-id (d/q '[:find ?nid . :in $ ?p
                       :where [?d :config-def/path ?p] [?v :config.value/definition ?d]
                       [?v :config.value/node ?n] [?n :config.node/id ?nid]]
                     @conn path)]
    (config-db/set-node-value! conn {:root :platform :tenant tenant :node-id node-id
                                     :path path :value value :master-key nil})))

(defn- sequential-import!
  "The behaviour from before the atomic-import fix, as the oracle: the same phases, applied one at a
   time against the connection itself."
  [conn backup opts]
  ((resolve 'digdir.config.ops.sync/import-phases!) conn (merge ((resolve 'digdir.config.ops.sync/import-payload) backup) {:on-conflict :skip} opts)))

(defn- refusal
  "What `import-data` threw, or nil."
  [conn backup]
  (try (ops-sync/import-data conn backup {:on-conflict :skip})
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- without [backup k pred]
  (update-in backup [:data k] (fn [rows] (vec (remove pred rows)))))

(defn- row-of? [k v] (fn [row] (= v (get row k))))

;; =============================================================================
;; Refused imports write nothing, and name every problem
;; =============================================================================

(deftest a-value-whose-definition-is-missing-is-refused-and-nothing-is-written
  (with-conns* 2 (fn [src tgt]
    (source! src)
    (let [backup (without (rt/default-backup src) :definitions (row-of? "config-def/path" path-b))
          before (datoms @tgt)
          e (refusal tgt backup)]
      (testing "PREMISE: the backup carries the value but not its definition"
        (is (some #(= path-b (get % "config.value/definition-path")) (get-in backup [:data :node-values]))))
      (is (some? e) "the import was not refused")
      (testing "the import wrote nothing: not the definitions, nodes or other values the phases before it would have written"
        (is (= before (datoms @tgt))))
      (testing "the refusal says nothing from this import was written, and names the orphan"
        (is (str/includes? (ex-message e) "nothing from this import was written"))
        (is (str/includes? (ex-message e) path-b)))))))

(deftest every-orphan-is-named-not-only-the-first
  (with-conns* 2 (fn [src tgt]
    (source! src)
    (let [backup (-> (rt/default-backup src)
                     (without :definitions (row-of? "config-def/path" path-a))
                     (without :definitions (row-of? "config-def/path" path-b)))
          e (refusal tgt backup)]
      (is (some? e))
      (testing "the phases stop at the FIRST orphan; the message names BOTH"
        (is (str/includes? (ex-message e) path-a))
        (is (str/includes? (ex-message e) path-b))
        (is (= 2 (count (:problems (ex-data e)))) (pr-str (:problems (ex-data e)))))))))

(deftest a-node-whose-parent-is-missing-is-refused-and-nothing-is-written
  (with-conns* 2 (fn [src tgt]
    (source! src)
    (let [backup (rt/default-backup src)
          child (first (filter #(get % "config.node/parent-id") (get-in backup [:data :nodes])))
          parent-id (get child "config.node/parent-id")
          backup (-> backup
                     (without :nodes (row-of? "config.node/id" parent-id))
                     (without :node-values (row-of? "config.value/node-id" parent-id)))
          before (datoms @tgt)
          e (refusal tgt backup)]
      (testing "PREMISE: a child node whose parent the backup no longer carries"
        (is (some? parent-id))
        (is (not-any? (row-of? "config.node/id" parent-id) (get-in backup [:data :nodes]))))
      (is (some? e) "the import was not refused")
      (is (= before (datoms @tgt)) "a refused import wrote something")
      (is (str/includes? (str (ex-message e)) parent-id))))))

(deftest a-pipeline-whose-dataset-is-missing-is-refused-and-nothing-is-written
  (with-conns* 2 (fn [src tgt]
    (source! src)
    (let [backup (without (rt/default-backup src) :datasets (row-of? "dataset/id" "atomicity-dataset"))
          before (datoms @tgt)
          e (refusal tgt backup)]
      (testing "PREMISE: the pipeline stays, its dataset is gone"
        (is (some (row-of? "dataset.pipeline/dataset-id" "atomicity-dataset") (get-in backup [:data :dataset-pipelines]))))
      (is (some? e) "the import was not refused")
      (is (= before (datoms @tgt)) "a refused import wrote something")
      (is (str/includes? (str (ex-message e)) "atomicity-dataset"))))))

;; =============================================================================
;; Imports that must succeed, exactly as they did one phase at a time
;; =============================================================================

(defn- equivalent-to-sequential
  "Import `backup` atomically into one fresh target and phase by phase into
   another; return both store shapes."
  [backup opts]
  (with-conns* 2 (fn [atomic sequential]
    (ops-sync/import-data atomic backup (merge {:on-conflict :skip} opts))
    (sequential-import! sequential backup opts)
    [(store-shape @atomic) (store-shape @sequential)])))

(deftest a-full-backup-restores-exactly-as-it-did-one-phase-at-a-time
  (with-conns* 1 (fn [src]
    (source! src)
    (let [[atomic sequential] (equivalent-to-sequential (rt/default-backup src) {})]
      (testing "PREMISE: the compared store holds every kind of entity"
        (is (empty? (missing-kinds atomic entity-kinds))))
      (is (= sequential atomic))))))

(deftest a-tenant-export-restores-exactly-as-it-did-one-phase-at-a-time
  (with-conns* 1 (fn [src]
    (source! src)
    (let [[atomic sequential] (equivalent-to-sequential (ops-sync/export-tenant src tenant {:include-audit? true}) {})]
      (testing "PREMISE: the compared store holds the tenant's definitions, nodes and values"
        (is (empty? (missing-kinds atomic [:config-def/path :config.node/id :config.value/id :tenant/id]))))
      (is (= sequential atomic))))))

(deftest a-reimport-into-its-source-decides-the-conflict-as-one-phase-at-a-time-did
  ;; The source moves on after its backup, so the re-import has something to
  ;; decide, and a commit that wrote nothing would be SEEN: under :overwrite
  ;; the backup's value must come back. (Under :skip a re-import of existing
  ;; entities is a near no-op by design; there the oracle, not the value,
  ;; carries the check.)
  (doseq [on-conflict [:skip :overwrite]]
    (testing (str "on-conflict " on-conflict)
      (with-conns* 2 (fn [a b]
        (source! a)
        (source! b)
        (let [backup (rt/default-backup a)
              exported (value-raw @a path-a)]
          (change-value! a path-a "changed-after-the-backup")
          (change-value! b path-a "changed-after-the-backup")
          (let [changed (value-raw @a path-a)]
            (testing "PREMISE: the value changed after the backup was taken"
              (is (some? exported))
              (is (not= exported changed)))
            (ops-sync/import-data a backup {:on-conflict on-conflict})
            (sequential-import! b backup {:on-conflict on-conflict})
            (testing "the conflict was decided: :skip keeps the store's value, :overwrite restores the backup's"
              (is (= (if (= :overwrite on-conflict) exported changed) (value-raw @a path-a))))
            (let [shape (store-shape @a)]
              (testing "PREMISE: the compared store holds every kind of entity"
                (is (empty? (missing-kinds shape entity-kinds))))
              (is (= (store-shape @b) shape))))))))))

(deftest a-clone-still-succeeds-and-matches-one-phase-at-a-time
  ;; A clone sends :definitions [] and relies on the definitions already in the
  ;; store. A pre-flight rule "a value's definition must be IN THE PAYLOAD"
  ;; refuses every value of it (measured on the atomic-import fix); this is the case that must
  ;; stay green.
  (with-conns* 2 (fn [a b]
    (source! a)
    (source! b)
    (let [payload (:import-data (#'ops-clone/build-clone-import-payload b tenant "atomicity-copy" {}))
          {:keys [result]} (ops-clone/clone-tenant! a tenant "atomicity-copy" {})]
      (testing "PREMISE: the clone carries values and no definitions"
        (is (empty? (get-in payload [:data :definitions])))
        (is (seq (get-in payload [:data :node-values]))))
      (is (pos? (get-in result [:node-values :created])))
      (sequential-import! b payload {})
      (let [shape (store-shape @a)
            copied? (fn [kind] (some #(and (= kind (first %)) (str/includes? (str (second %)) "atomicity-copy")) (keys shape)))]
        (testing "PREMISE: the compared store holds the CLONE's nodes and values, not only the source's"
          (is (copied? :config.node/id))
          (is (copied? :config.value/id))
          (is (empty? (missing-kinds shape entity-kinds))))
        (is (= (store-shape @b) shape)))))))

(deftest the-oracle-keeps-soft-delete-state
  ;; `deleted-at` is STATE, not clock metadata: a store where a value is
  ;; soft-deleted must not compare equal to one where it is live, or the
  ;; equivalence oracle could miss a resurrected or a lost value.
  (with-conns* 2 (fn [a b]
    (source! a)
    (source! b)
    (let [node-id (d/q '[:find ?nid . :in $ ?p
                         :where [?d :config-def/path ?p] [?v :config.value/definition ?d]
                         [?v :config.value/node ?n] [?n :config.node/id ?nid]]
                       @a path-b)]
      (is (= (store-shape @a) (store-shape @b)) "PREMISE: two identical sources compare equal")
      (config-db/delete-node-value! a {:root :platform :tenant tenant :node-id node-id :path path-b})
      (is (not= (store-shape @a) (store-shape @b))
          "a soft-deleted value compares equal to a live one")))))

(deftest an-audit-row-whose-definition-was-retracted-is-not-a-problem
  ;; The retraction migrations keep such rows on purpose, and the restore
  ;; leaves them without a reference. A validator that called them
  ;; orphans would turn a correct backup into a refused one.
  (with-conns* 2 (fn [src tgt]
    (define! src "services.auth.use-db")
    (let [id (audit/log-global-change! src {:action :global-edit
                                                           :path "services.auth.use-db"
                                                           :tenant "__global__"
                                                           :new-value true})
          _ (#'config-db/remove-env-migrated-paths! src)
          backup (rt/default-backup src)]
      (testing "PREMISE: the backup names a path it does not define"
        (is (= "services.auth.use-db" (get (rt/backup-audit-rows backup) id)))
        (is (nil? (config-db/get-definition @src "services.auth.use-db"))))
      (is (empty? ((resolve 'digdir.config.ops.sync/import-problems) @tgt ((resolve 'digdir.config.ops.sync/import-payload) backup)))
          "the message scan calls a deliberately kept audit row a problem")
      (is (nil? (refusal tgt backup)) "the import refused a correct backup")))))
