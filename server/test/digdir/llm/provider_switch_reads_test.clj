(ns digdir.llm.provider-switch-reads-test
  "Every place CODE names the provider switch, and what it does
   with it.

   The Azure-switch default mismatch was two reads of `services.azure-openai.use-azure-openai-api` with
   OPPOSITE defaults; it was reported checked-and-clear from the site with the
   default while the site without one was the one that ran. The fix made
   `accessor/use-azure-openai?` the intended single read. On `267718dd` it is
   not the only one: three routing branches read the raw key directly. Phase 1
   must REDUCE the routing reads to one — this namespace makes that checkable.

   ## Why a census and not a grep

   A text grep cannot tell a read from a write (`setup/llm.clj` WRITES the same
   path), from a docstring that explains the switch, or from a println. So the
   source is parsed (rewrite-clj) and only mentions in CODE are counted:
   comments, `#_`, `(comment …)` forms, docstrings and prose strings that merely
   contain the name are excluded. Three spellings count as mentions — the
   keyword `:use-azure-openai-api`, the dotted string, and the bare string (the
   vector spelling) — plus the env var `AZURE_OPENAI_USE_AZURE`, so a direct
   `System/getenv` of it is a mention too.

   Every mention must be accounted for in `classified`, per FILE and COUNT. A
   new read anywhere — a new file, or a second mention in a file already listed
   — changes the census and turns this red, forcing someone to classify it.

   ## What Phase 1 changes here

   The three `:routing-read` rows marked PHASE 1 REMOVES are deleted, and the
   accessor row moves to wherever `digdir.llm.provider` puts the one read.
   `routing-reads-reduce-to-one` then asserts 1 instead of 4. That edit is the
   done-condition, and it is the only edit Phase 1 should make to this file.

   ## Residual blind spots, stated

   A path assembled from pieces that are not themselves one of the needles
   (e.g. a format string) is prose to this census. So is a read through a
   symbol defined in ANOTHER namespace than the one that spells the path — the
   defining file is counted, the reading file is not."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(def ^:private needle-keywords #{:use-azure-openai-api})

(def ^:private needle-strings
  #{"services.azure-openai.use-azure-openai-api"
    "use-azure-openai-api"
    "AZURE_OPENAI_USE_AZURE"})

(def ^:private doc-heads '#{defn defn- defmacro def defonce ns defmulti defprotocol defrecord deftype})

(def ^:private skipped-tags #{:whitespace :newline :comma :comment :uneval})

(defn- code-children [node]
  (remove #(skipped-tags (n/tag %)) (n/children node)))

(defn- head-symbol [node]
  (let [h (first (code-children node))]
    (when (and h (= :token (n/tag h)))
      (let [v (n/sexpr h)] (when (symbol? v) v)))))

(defn- docstring? [parent node]
  (when (and parent (= :list (n/tag parent)))
    (let [cs (vec (code-children parent))
          head (head-symbol parent)]
      (and (doc-heads head)
           (= 2 (.indexOf cs node))
           ;; (def name "doc" value) has a docstring; (def name "value") does not
           (or (not= 'def head) (= 4 (count cs)))))))

(defn- mentions
  "Every needle mention under `node`, tagged :code, :docstring, :prose or
   :comment-form. `#_` and `;` comments are skipped outright."
  ([node] (mentions node nil :code))
  ([node parent ctx]
   (let [tag (n/tag node)]
     (cond
       (skipped-tags tag) []
       (and (= :list tag) (= 'comment (head-symbol node)))
       (mapcat #(mentions % node :comment-form) (n/children node))
       (n/inner? node) (mapcat #(mentions % node ctx) (n/children node))
       :else
       (let [v (try (n/sexpr node) (catch Exception _ ::unreadable))
             row (:row (meta node))]
         (cond
           (and (keyword? v) (needle-keywords v)) [{:row row :form v :ctx ctx}]
           (and (string? v) (needle-strings v))
           [{:row row :form v :ctx (if (docstring? parent node) :docstring ctx)}]
           (and (string? v) (some #(str/includes? v %) needle-strings))
           [{:row row :form :prose :ctx (if (docstring? parent node) :docstring :prose)}]
           :else []))))))

(defn- source-files
  ([] (source-files ["src" "src-dev"]))
  ([roots]
   (->> roots
        (mapcat #(file-seq (io/file %)))
        (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))
        (sort-by str))))

(defn- census-with
  "{:files n :failures [[file msg]] :mentions {file [mention …]}} over `roots`
   (src + src-dev unless given), collecting what `mention-fn` finds in each
   parsed file."
  ([mention-fn] (census-with mention-fn ["src" "src-dev"]))
  ([mention-fn roots]
   (reduce (fn [acc f]
             (let [path (str f)]
               (try
                 (let [ms (vec (mention-fn (p/parse-file-all f)))]
                   (cond-> (update acc :files inc)
                     (seq ms) (assoc-in [:mentions path] ms)))
                 (catch Exception e
                   (update acc :failures conj [path (ex-message e)])))))
           {:files 0 :failures [] :mentions {}}
           (source-files roots))))

(def ^:private census (delay (census-with mentions)))

(defn- code-counts
  "{file -> number of CODE mentions}, files with none omitted."
  [{:keys [mentions]}]
  (into (sorted-map)
        (keep (fn [[f ms]] (let [k (count (filter #(= :code (:ctx %)) ms))] (when (pos? k) [f k]))))
        mentions))

;; ---------------------------------------------------------------------------
;; The classification — hand-typed on purpose: it is the independent half
;; ---------------------------------------------------------------------------

(def ^:private classified
  {"src/digdir/llm/provider.clj"
   {:mentions 1 :kind :routing-read
    :why "THE read: `configured-provider`. Everything else asks `selected-provider` or `resolve`"}

   "src/digdir/boot/provider_switch.clj"
   {:mentions 1 :kind :metadata
    :why "switch-env-var: the variable named in the boot refusal; no read. The check itself reads
          through provider/configured-provider (Phase 1 of the provider-resolver change deleted its own switch-path read)"}

   "src/digdir/config/verify.clj"
   {:mentions 1 :kind :allowlisted-read
    :why "the shared-vs-default services issue diagnostic (unreachable-provider-decision): a value set where the runtime cannot
          see it; the decision itself comes from provider/configured-provider. FLIPPED by the provider-resolver change from a
          presence check in runtime-required-service-paths, which under read-both would report a
          legacy-configured tenant unconfigured while the runtime routes it (the Azure-switch default mismatch at the verifier)"}

   "src/digdir/config/env_bridge.clj"
   {:mentions 1 :kind :write
    :why "Seeding table row: AZURE_OPENAI_USE_AZURE, the legacy spelling, now seeds
          services.llm.provider; the boolean path is no longer written"}

   "src/digdir/setup/config.clj"
   {:mentions 1 :kind :definition
    :why "config-def registration"}

   "src/digdir/config/deployment_specific.clj"
   {:mentions 1 :kind :metadata
    :why "Membership in the deployment-specific key set"}})

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest the-census-reads-every-source-file
  (let [{:keys [files failures mentions]} @census]
    (is (< 200 files) "src + src-dev were actually walked")
    (is (empty? failures) (str "files the census could not parse are files it cannot vouch for: " failures))
    (testing "POSITIVE CONTROL: the needle hits the one read we know exists"
      (is (some #(and (= :code (:ctx %)) (= :use-azure-openai-api (:form %)))
                (get mentions "src/digdir/llm/provider.clj"))))
    (testing "and the exclusions are exercised on real source, not only in theory"
      (is (some #(= :docstring (:ctx %)) (mapcat val mentions)) "docstring mentions exist and were set aside")
      (is (some #(= :prose (:ctx %)) (mapcat val mentions)) "prose mentions exist and were set aside"))))

(deftest every-code-mention-of-the-switch-is-classified
  (let [actual (code-counts @census)
        expected (into (sorted-map) (map (fn [[f {:keys [mentions]}]] [f mentions])) classified)
        new-files (set/difference (set (keys actual)) (set (keys expected)))
        gone-files (set/difference (set (keys expected)) (set (keys actual)))]
    (is (empty? new-files)
        (str "UNCLASSIFIED code mentions of the provider switch — a new read is how the Azure-switch default mismatch happened. "
             "Classify each in `classified`: "
             (pr-str (select-keys (:mentions @census) new-files))))
    (is (empty? gone-files)
        (str "classified files with no code mention left — delete their rows: " (pr-str gone-files)))
    (is (= expected actual)
        "per-file counts: a second mention in an already-listed file is a new read too")))

(deftest routing-reads-reduce-to-one
  (testing "Four routing reads on 267718dd; Phase 1 of the provider-resolver change reduced them to exactly
            one, provider/configured-provider, inside digdir.llm.provider."
    (let [routing (into (sorted-set) (keep (fn [[f {:keys [kind]}]] (when (= :routing-read kind) f))) classified)]
      (is (= #{"src/digdir/llm/provider.clj"} routing) (str routing)))))

(deftest the-census-cannot-be-satisfied-by-documentation
  (testing "The instrument, on source whose answer is known: one real read among
            every way of merely talking about the switch."
    (let [src (str "(ns x \"ns doc: use-azure-openai-api\")\n"
                   "; comment: :use-azure-openai-api\n"
                   "#_(cfg/get {} :services :azure-openai :use-azure-openai-api)\n"
                   "(comment (cfg/get {} :services :azure-openai :use-azure-openai-api))\n"
                   "(defn f \"reads services.azure-openai.use-azure-openai-api\" [t]\n"
                   "  (println \"set use-azure-openai-api false\")\n"
                   "  (cfg/get {:tenant t} :services :azure-openai :use-azure-openai-api))\n"
                   "(def p \"services.azure-openai.use-azure-openai-api\")\n"
                   "(def v [\"services\" \"azure-openai\" \"use-azure-openai-api\"])\n"
                   "(defn g [] (System/getenv \"AZURE_OPENAI_USE_AZURE\"))\n")
          ms (mentions (p/parse-string-all src))
          code (filter #(= :code (:ctx %)) ms)]
      (is (= [7 8 9 10] (mapv :row code))
          "the keyword read, the def'd path, the vector spelling and the env read — nothing else")
      (is (= #{:docstring :prose :comment-form :code} (set (map :ctx ms)))
          "the excluded mentions were SEEN and classified, not missed"))))

;; ===========================================================================
;; The second needle: services.llm.provider
;; ===========================================================================
;;
;; Phase 2 of the provider-resolver change makes `services.llm.provider` the PRIMARY provider decision, with the
;; boolean above as its fallback. "One read of the provider decision" has to
;; cover this key too, or the census guards only the half Phase 2 demotes.
;;
;; A SEQUENCE needle, not a token needle: a bare `:provider` is a key in every
;; `provider/resolve` spec and provenance record. A mention is any of
;;   - the keywords `:services :llm :provider`, consecutive in one form;
;;   - the strings `"services" "llm" "provider"`, consecutive in one form;
;;   - the string `"services.llm.provider"`, exactly.
;; Docstrings, comments, `#_`, `(comment …)` and prose that merely CONTAINS the
;; dotted path do not count — the same rule as the first needle, with the same
;; residual blind spot (a path assembled from pieces).

(def ^:private llm-provider-keywords [:services :llm :provider])
(def ^:private llm-provider-strings ["services" "llm" "provider"])
(def ^:private llm-provider-dotted "services.llm.provider")

(defn- token-value
  "The value of a leaf node. rewrite-clj tags a MULTI-LINE string `:multi-line`,
   not `:token` — accepting only `:token` made this needle blind to every
   docstring and multi-line code string (the real-source exclusion check caught it)."
  [node]
  (when (#{:token :multi-line} (n/tag node))
    (try (n/sexpr node) (catch Exception _ ::unreadable))))

(defn- contains-run? [xs run]
  (boolean (some #(= run %) (partition (count run) 1 xs))))

(defn- llm-provider-mentions
  ([node] (llm-provider-mentions node nil :code))
  ([node parent ctx]
   (let [tag (n/tag node)]
     (cond
       (skipped-tags tag) []
       (and (= :list tag) (= 'comment (head-symbol node)))
       (mapcat #(llm-provider-mentions % node :comment-form) (n/children node))
       (n/inner? node)
       (let [vals (mapv token-value (code-children node))
             row (:row (meta node))]
         (concat (when (#{:list :vector} tag)
                   (cond-> []
                     (contains-run? vals llm-provider-keywords) (conj {:row row :form :keyword-run :ctx ctx})
                     (contains-run? vals llm-provider-strings) (conj {:row row :form :string-run :ctx ctx})))
                 (mapcat #(llm-provider-mentions % node ctx) (n/children node))))
       :else
       (let [v (token-value node)
             row (:row (meta node))]
         (cond
           (= v llm-provider-dotted)
           [{:row row :form v :ctx (if (docstring? parent node) :docstring ctx)}]
           (and (string? v) (str/includes? v llm-provider-dotted))
           [{:row row :form :prose :ctx (if (docstring? parent node) :docstring :prose)}]
           :else []))))))

(def ^:private llm-census (delay (census-with llm-provider-mentions)))

(def ^:private llm-classified
  "Every file whose CODE names services.llm.provider. Hand-typed on purpose, as
   `classified` is: the census checks the per-file COUNTS, and a reviewer checks
   the KIND. `exactly-one-routing-read-of-services-llm-provider` pins which file
   holds the routing read."
  {"src/digdir/llm/provider.clj"
   {:mentions 1 :kind :routing-read
    :why "THE read of services.llm.provider: `provider-keys`, read by
          configured-provider(-with-trace). The boolean switch is its fallback"}

   "src/digdir/config/env_bridge.clj"
   {:mentions 1 :kind :write
    :why "Seeding row: AZURE_OPENAI_USE_AZURE, the legacy spelling, seeds
          services.llm.provider. Env is a write path, never read back"}

   "src/digdir/setup/llm.clj"
   {:mentions 1 :kind :write
    :why "Setup wizard writes services.llm.provider (provider-path)"}

   "src/digdir/config/verify.clj"
   {:mentions 1 :kind :allowlisted-read
    :why "the shared-vs-default services issue diagnostic: a value set where the runtime cannot see it. The decision
          itself comes from provider/configured-provider. Mirrors verify's row under
          the switch needle"}

   "src/digdir/setup/config.clj"
   {:mentions 1 :kind :definition
    :why "config-def registration"}

   "src/digdir/config/deployment_specific.clj"
   {:mentions 1 :kind :metadata
    :why "Membership in the deployment-specific key set"}})

(deftest the-llm-provider-census-reads-and-can-hit
  (let [{:keys [files failures mentions]} @llm-census]
    (is (< 200 files) "src + src-dev were actually walked")
    (is (empty? failures) (str "files the census could not parse: " failures))
    (testing "POSITIVE CONTROL: the needle hits the registration we know exists"
      (is (some #(and (= :code (:ctx %)) (= llm-provider-dotted (:form %)))
                (get mentions "src/digdir/setup/config.clj"))))
    (testing "and the documentation exclusion is exercised on real source"
      (is (some #(= :docstring (:ctx %)) (mapcat val mentions))
          "provider.clj's docstring names the key and is set aside"))))

(deftest every-code-mention-of-services-llm-provider-is-classified
  (let [actual (code-counts @llm-census)
        expected (into (sorted-map) (map (fn [[f {:keys [mentions]}]] [f mentions])) llm-classified)
        new-files (set/difference (set (keys actual)) (set (keys expected)))
        gone-files (set/difference (set (keys expected)) (set (keys actual)))]
    (is (empty? new-files)
        (str "UNCLASSIFIED code mentions of services.llm.provider — a read of the provider decision "
             "outside digdir.llm.provider is the Azure-switch default mismatch again. Classify each in `llm-classified`: "
             (pr-str (select-keys (:mentions @llm-census) new-files))))
    (is (empty? gone-files)
        (str "classified files with no code mention left — delete their rows: " (pr-str gone-files)))
    (is (= expected actual)
        "per-file counts: a second mention in an already-listed file is a new read too")))

(deftest exactly-one-routing-read-of-services-llm-provider
  (testing "the provider-resolver change's done-condition for the new key: exactly one routing read, and it is
            digdir.llm.provider (the one function that reads the provider decision). Before
            Phase 2 there were none. This pins WHICH file; `every-code-mention-…` pins that every
            code mention is classified at all. The two work as a pair, and a routing read filed
            under the wrong :kind is what this one catches."
    (let [routing (into (sorted-set) (keep (fn [[f {:keys [kind]}]] (when (= :routing-read kind) f))) llm-classified)]
      (is (= #{"src/digdir/llm/provider.clj"} routing) (str routing)))))

(deftest the-sequence-needle-cannot-be-satisfied-by-documentation-or-a-bare-provider
  (testing "The second instrument, on source whose answer is known."
    (let [src (str "(ns x \"ns doc: services.llm.provider\")\n"
                   "; comment: (cfg/get t :services :llm :provider)\n"
                   "#_(cfg/get t :services :llm :provider)\n"
                   "(comment (cfg/get t :services :llm :provider))\n"
                   "(defn f \"reads services.llm.provider\" [t]\n"
                   "  (println \"set services.llm.provider to :azure\")\n"
                   "  (cfg/get {:tenant t} :services :llm :provider))\n"
                   "(def p \"services.llm.provider\")\n"
                   "(def v [\"services\" \"llm\" \"provider\"])\n"
                   "(defn g [spec] (:provider spec))\n"
                   "(defn h [t] (cfg/get {:tenant t} :services :llm :model :provider))\n")
          ms (llm-provider-mentions (p/parse-string-all src))
          code (filter #(= :code (:ctx %)) ms)]
      (is (= [7 8 9] (mapv :row code))
          "the keyword run, the dotted def and the string run — not a bare :provider, not a broken run")
      (is (= #{:docstring :prose :comment-form :code} (set (map :ctx ms)))
          "the excluded mentions were SEEN and classified, not missed")))
  (testing "a MULTI-LINE string is a leaf too (rewrite-clj tags it :multi-line, not :token)"
    (is (= [:docstring]
           (mapv :ctx (llm-provider-mentions
                       (p/parse-string-all "(defn k\n  \"line one\n   names services.llm.provider\"\n  [])")))))))

;; ===========================================================================
;; The third needle: the MODEL's two paths
;; ===========================================================================
;;
;; Phase 4 makes `services.llm.model` the model, with
;; `services.azure-openai.model-name` as its legacy spelling and fallback. The
;; same rule as the provider decision applies, for the same reason: every site
;; that ASKS about the model must ask about both keys, and only ONE site may
;; read them to route. A raw presence check of the legacy path alone reports a
;; migrated tenant unconfigured while the runtime routes it — the Azure-switch default mismatch at the
;; verifier, in `config/verify`'s own words.
;;
;; Two sequence needles in one census, because the two paths travel together:
;;   - `:services :llm :model` / `"services" "llm" "model"` / "services.llm.model"
;;   - `:services :azure-openai :model-name` / the string run / the dotted path
;; Docstrings, comments, `#_`, `(comment …)` and prose that merely CONTAINS a
;; path do not count — the same rule, and the same residual blind spot.

(def ^:private model-needles
  [{:keywords [:services :llm :model]
    :strings ["services" "llm" "model"]
    :dotted "services.llm.model"}
   {:keywords [:services :azure-openai :model-name]
    :strings ["services" "azure-openai" "model-name"]
    :dotted "services.azure-openai.model-name"}])

(defn- path-mentions
  "A mention function for `needles`, each {:keywords :strings :dotted}: the
   keyword run, the string run and the dotted path count, prose that merely
   CONTAINS the dotted path does not."
  [needles]
  (fn mentions
    ([node] (mentions node nil :code))
    ([node parent ctx]
     (let [tag (n/tag node)]
       (cond
         (skipped-tags tag) []
         (and (= :list tag) (= 'comment (head-symbol node)))
         (mapcat #(mentions % node :comment-form) (n/children node))
         (n/inner? node)
         (let [vals (mapv token-value (code-children node))
               row (:row (meta node))]
           (concat (when (#{:list :vector} tag)
                     (vec (for [{:keys [keywords strings dotted]} needles
                                [run form] [[keywords :keyword-run] [strings :string-run]]
                                :when (contains-run? vals run)]
                            {:row row :form form :path dotted :ctx ctx})))
                   (mapcat #(mentions % node ctx) (n/children node))))
         :else
         (let [v (token-value node)
               row (:row (meta node))]
           (cond
             (some #(= v (:dotted %)) needles)
             [{:row row :form v :path v :ctx (if (docstring? parent node) :docstring ctx)}]
             (and (string? v) (some #(str/includes? v (:dotted %)) needles))
             [{:row row :form :prose :ctx (if (docstring? parent node) :docstring :prose)}]
             :else [])))))))

(def ^:private model-mentions (path-mentions model-needles))

(def ^:private model-census (delay (census-with model-mentions)))

(def ^:private model-classified
  "Every file whose CODE names either model path. Hand-typed, as the two above
   are: the census checks the per-file COUNTS and a reviewer checks the KIND.
   `exactly-one-routing-read-of-the-model` pins which file routes."
  {"src/digdir/llm/provider.clj"
   {:mentions 2 :kind :routing-read
    :why "THE read of both model paths: `model-keys` and `legacy-model-keys`,
          read by configured-model(-with-trace). Everything else asks the provider namespace"}

   "src/digdir/config/verify.clj"
   {:mentions 2 :kind :allowlisted-read
    :why "`model-decision-paths`, for REPORTING ONLY: which path holds a value the runtime
          cannot see (the shared-vs-default services issue's shape). The decision comes from provider/configured-model.
          Phase 4 REMOVED the legacy path from runtime-required-service-paths, where a
          presence check would report a migrated tenant unconfigured (the Azure-switch default mismatch at the verifier)"}

   "src/digdir/config/env_bridge.clj"
   {:mentions 1 :kind :write
    :why "Seeding row: AZURE_OPENAI_MODEL_NAME, the legacy VARIABLE name, now seeds
          services.llm.model - the same shape as AZURE_OPENAI_USE_AZURE
          seeding services.llm.provider. Env is a write path, never read back"}

   "src/digdir/setup/llm.clj"
   {:mentions 1 :kind :write
    :why "The setup wizard writes the model it probed to services.llm.model (model-path)"}

   "src/digdir/setup/config.clj"
   {:mentions 2 :kind :definition
    :why "config-def registration: services.llm.model (new in Phase 4) and the legacy
          services.azure-openai.model-name, which keeps its definition while it is the fallback"}

   "src/digdir/config/deployment_specific.clj"
   {:mentions 2 :kind :metadata
    :why "Membership in the globally-defaultable key set, one row per path"}})

(deftest the-model-census-reads-and-can-hit
  (let [{:keys [files failures mentions]} @model-census]
    (is (< 200 files) "src + src-dev were actually walked")
    (is (empty? failures) (str "files the census could not parse: " failures))
    (testing "POSITIVE CONTROL: the needle hits the registration we know exists"
      (is (some #(and (= :code (:ctx %)) (= "services.azure-openai.model-name" (:form %)))
                (get mentions "src/digdir/setup/config.clj"))))
    (testing "and the documentation exclusion is exercised on real source"
      (is (some #(= :docstring (:ctx %)) (mapcat val mentions))
          "a docstring names a model path and is set aside"))))

(deftest every-code-mention-of-a-model-path-is-classified
  (let [actual (code-counts @model-census)
        expected (into (sorted-map) (map (fn [[f {:keys [mentions]}]] [f mentions])) model-classified)
        new-files (set/difference (set (keys actual)) (set (keys expected)))
        gone-files (set/difference (set (keys expected)) (set (keys actual)))]
    (is (empty? new-files)
        (str "UNCLASSIFIED code mentions of a model path — a site that asks about the model "
             "without going through digdir.llm.provider is the Azure-switch default mismatch's shape. Classify each in "
             "`model-classified`: " (pr-str (select-keys (:mentions @model-census) new-files))))
    (is (empty? gone-files)
        (str "classified files with no code mention left — delete their rows: " (pr-str gone-files)))
    (is (= expected actual)
        "per-file counts: a second mention in an already-listed file is a new read too")))

(deftest exactly-one-routing-read-of-the-model
  (testing "Phase 4 of the provider-resolver change's done-condition: exactly one routing read of the model, in
            digdir.llm.provider. Before Phase 4 there were three sites reading the legacy
            path to decide something (the resolver, the boot guard's configured signal and
            the sweep runner) plus a presence check in the verifier."
    (let [routing (into (sorted-set) (keep (fn [[f {:keys [kind]}]] (when (= :routing-read kind) f))) model-classified)]
      (is (= #{"src/digdir/llm/provider.clj"} routing) (str routing)))))

(deftest the-model-needle-cannot-be-satisfied-by-documentation-or-a-bare-model
  (testing "The third instrument, on source whose answer is known."
    (let [src (str "(ns x \"ns doc: services.llm.model\")\n"
                   "; comment: (cfg/get t :services :llm :model)\n"
                   "#_(cfg/get t :services :azure-openai :model-name)\n"
                   "(comment (cfg/get t :services :llm :model))\n"
                   "(defn f \"reads services.azure-openai.model-name\" [t]\n"
                   "  (println \"set services.llm.model instead\")\n"
                   "  (cfg/get {:tenant t} :services :llm :model))\n"
                   "(def p \"services.azure-openai.model-name\")\n"
                   "(def v [\"services\" \"llm\" \"model\"])\n"
                   "(defn g [spec] (:model spec))\n"
                   "(defn h [t] (cfg/get {:tenant t} :services :llm :provider))\n"
                   "(defn i [t] (cfg/get {:tenant t} :services :lmstudio :model))\n")
          ms (model-mentions (p/parse-string-all src))
          code (filter #(= :code (:ctx %)) ms)]
      (is (= [7 8 9] (mapv :row code))
          "the keyword run, the dotted def and the string run — not a bare :model, not another service's :model")
      (is (= #{:docstring :prose :comment-form :code} (set (map :ctx ms)))
          "the excluded mentions were SEEN and classified, not missed")))
  (testing "a MULTI-LINE string is a leaf too"
    (is (= [:docstring]
           (mapv :ctx (model-mentions
                       (p/parse-string-all "(defn k\n  \"line one\n   names services.llm.model\"\n  [])")))))))

;; ---------------------------------------------------------------------------
;; The seven removed paths: nothing in code names them
;; ---------------------------------------------------------------------------
;;
;; Phase 3 of the provider-resolver change and the loader-fallback change took away every reader of seven paths; Phase 4 removes
;; their definitions, deployment-specific rows and env-bridge rows
;; (`digdir.config.removed-definitions-test` pins that absence). What is left to
;; pin is NON-READERSHIP, and it is stronger than that: no CODE mention at all,
;; by any of the three spellings, because a registration, a seeding row and a
;; read are all code mentions and none should remain. Documentation may still
;; name them; that is history, and the same exclusion rule as the censuses
;; above sets it aside.
;;
;; All four source roots, not the two the censuses above walk: `src-prod` holds
;; the production boot, and a read there is still a read.
;;
;; This is the ONLY guard against a read coming back. The absence tests cannot
;; see reads by design: with `nothing-in-code-names-a-removed-path` disabled, a
;; restored read passed the whole suite (the inert-config-definitions removal's sabotage).

(def ^:private removed-paths
  "The same seven as `digdir.config.removed-definitions-test/removed-paths`."
  #{"services.search-phrases.provider" "services.self-improvement.provider"
    "services.lmstudio.api-key" "services.lmstudio.api-endpoint" "services.lmstudio.model"
    "services.openrouter.model" "services.openrouter.api-key"})

(defn- needle-for
  "{:keywords :strings :dotted} for one dotted path."
  [dotted]
  (let [parts (str/split dotted #"\.")]
    {:keywords (mapv keyword parts) :strings parts :dotted dotted}))

(def ^:private removed-roots ["src" "src-dev" "src-prod" "src-build"])

(def ^:private removed-census
  (delay (census-with (path-mentions (mapv needle-for (sort removed-paths))) removed-roots)))

(deftest the-census-needles-are-exactly-the-removed-definitions
  ;; Two hand-typed copies of the seven: a typo here blinds that path's needle,
  ;; and the aggregate "some hit" control cannot see it.
  (is (= removed-paths @(requiring-resolve 'digdir.config.removed-definitions-test/removed-paths))))

(deftest the-removed-path-census-reads-every-root-and-can-hit
  (let [{:keys [files failures mentions]} @removed-census]
    (is (< 200 files) "the source roots were actually walked")
    (is (empty? failures) (str "files the census could not parse: " failures))
    (testing "every root is walked, including the production boot"
      (doseq [root removed-roots]
        (is (some #(str/starts-with? (str %) (str root "/")) (source-files removed-roots)) root)))
    (testing "POSITIVE CONTROL: the same instrument, given a sibling that stays, hits it in CODE
              in both spellings - so a zero below is not a blind needle"
      (let [sibling (census-with (path-mentions [(needle-for "services.self-improvement.model")]) removed-roots)
            code (filter #(= :code (:ctx %)) (mapcat val (:mentions sibling)))]
        (is (some #(= "services.self-improvement.model" (:form %)) code) "the dotted registration")
        (is (some #(= :keyword-run (:form %)) code) "a keyword-run read")))
    (testing "and the needles for the seven DO hit real source - in documentation, which is set aside"
      (is (some #(not= :code (:ctx %)) (mapcat val mentions))
          "no mention of any of the seven anywhere: the census would read the same with its needles broken"))))

(deftest nothing-in-code-names-a-removed-path
  (let [counts (code-counts @removed-census)]
    (is (empty? counts)
        (str "code still names a removed path - a read, a registration or a seeding row: "
             (pr-str (into (sorted-map)
                           (map (fn [[f ms]] [f (vec (distinct (keep :path (filter #(= :code (:ctx %)) ms))))]))
                           (select-keys (:mentions @removed-census) (keys counts))))))))
