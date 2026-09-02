(ns digdir.api.doc-fidelity-smoke-test
  "The narrow half of the documentation guard: the handful of claims that only a
   BOOTED SERVER can answer.

   THE QUESTION THIS RETIRES: for the few documented behaviours that live above
   or beside the MCP transport — the shared auth middleware, CORS, and whether a
   handler can serialize what it returns — does the server do what the
   documentation says?

   WHY IT IS SEPARATE FROM `digdir.api.doc-fidelity-test`. That namespace drives
   `handle-mcp-request` directly: fast, deterministic, and it sweeps every
   published example. But a transport test answers *does this request pass MCP
   validation*, and the documentation promises *does this work against the
   server*. Those are different questions, and four things fall in the gap:
   preflight rejections, absent CORS headers, the auth middleware's 401 body
   shape, and a handler that returns 200-worthy data it cannot encode. Each is a
   real published claim, and none of them exists at the transport layer.

   So: breadth there, depth here, and deliberately few assertions here because a
   booted stack costs more than a function call.

   WHAT IT DOES NOT ANSWER: retrieval quality, or whether any agent produces a
   correct answer. No dataset is materialized and no LLM is called. It also does
   not test production — a proxy or CDN in front of a deployment could add CORS
   headers this test cannot see.

   The known-broken register works exactly as it does in the sibling namespace:
   an unlisted failure fails the test, and a listed behaviour that has been fixed
   also fails the test, so an entry cannot outlive its own fix."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.agents.db :as agents-db]
            [digdir.api.http :as api-http]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.skills.api :as skills-api]))

(def ^:private state (atom nil))

(defn- booted? [] (some? @state))
(defn- base-url [] (:base-url @state))
(defn- api-key [] (:api-key @state))
(defn- convo-key [] (:convo-key @state))

(defn- boot-fixture
  "Boots the production middleware stack on an ephemeral port. Skips the whole
   namespace when the config database is unavailable, the same way
   `digdir.api.integration-test` skips when its environment is not configured —
   an unrunnable check should say so rather than fail as though it measured
   something."
  [f]
  (let [conn (try (config-db/get-conn) (catch Throwable _ nil))]
    (if-not conn
      (println "Skipping doc-fidelity smoke tests: no config database")
      (do
        (skills-api/initialize!)
        (agents-db/seed-builtin-agents! conn)
        (let [k (:api-key (api-keys/create-api-key!
                            conn "doc-fidelity-smoke" "smoke"
                            {:scopes #{:query} :user-email "doc-fidelity@test"}))
              ;; A SECOND key, granting exactly one agent. `POST /api/conversations`
              ;; resolves an agent through `select-request-agent!`, which reads an
              ;; empty `:agent-refs` as DENIED and more than one as ambiguous - so
              ;; the unrestricted key above cannot create a conversation at all
              ;; (that asymmetry is #349). Exactly one grant is the only
              ;; configuration that reaches these handlers.
              agent-id (:id (first (agents-db/list-enabled-agents @conn)))
              convo-key (:api-key (api-keys/create-api-key!
                                    conn "doc-fidelity-convo" "smoke"
                                    {:scopes #{:query} :user-email "doc-fidelity@test"
                                     :agent-refs [agent-id]}))
              server (api-http/start-server!
                       (fn [_] nil)
                       {:port 0 :host "127.0.0.1"
                        :resources-path "public" :manifest-path "manifest.edn"})
              port (-> server (.getConnectors) first .getLocalPort)]
          (reset! state {:server server :api-key k :convo-key convo-key
                         :base-url (str "http://127.0.0.1:" port)})
          (try (f)
               (finally (.stop server) (reset! state nil))))))))

(use-fixtures :once boot-fixture)

(defn- GET [path headers]
  (http/get (str (base-url) path) {:headers headers :throw-exceptions false}))

(defn- cors-headers [resp]
  (into {} (filter (fn [[k _]] (str/starts-with? (str/lower-case (str k)) "access-control"))
                   (:headers resp))))

;; ---------------------------------------------------------------------------

(defn- markdown-files-under [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
       (sort-by #(.getPath ^java.io.File %))))

(deftest auth-401-renders-in-the-calling-surface-s-error-shape
  ;; MEASURED, and it contradicts the documentation in the safer-looking
  ;; direction. `authentication.md` and `endpoints/openai-compat.md` both state
  ;; that a 401 body is a bare string on EVERY surface, and `openai-compat.md`
  ;; goes further: "A client that assumes `error.message` will read `undefined`
  ;; on a 401 — check the status code."
  ;;
  ;; That was true until `130ee8a` (2026-08-21) made the 401 render per surface,
  ;; precisely because `undefined` is what a new integrator sees first (#137).
  ;; The commit touched the handler and two test namespaces and no documentation,
  ;; so the warning outlived the defect it warned about.
  ;;
  ;; This pins the WIRE, which is the deliberate contract; #331 corrects the
  ;; prose. If someone later "fixes" the server back to a bare string on /v1 to
  ;; match the docs, this goes red — which is the direction that would actually
  ;; hurt a caller.
  (when (booted?)
    (testing "/api/* returns the platform's string shape"
      (doseq [path ["/api/datasets" "/api/conversations"]]
        (let [r (GET path {})
              body (json/parse-string (:body r) true)]
          (is (= 401 (:status r)) path)
          (is (string? (:error body))
              (str path " — platform surfaces return a string; got "
                   (pr-str (:error body)))))))
    (testing "/v1/* returns OpenAI's object shape, so error.message resolves"
      (let [r (GET "/v1/models" {})
            body (json/parse-string (:body r) true)]
        (is (= 401 (:status r)))
        (is (= "invalid_api_key" (get-in body [:error :code])))
        (is (string? (get-in body [:error :message]))
            "an OpenAI SDK reads error.message here; a bare string would be undefined")))))

(deftest browsers-cannot-reach-this-api
  ;; Pinned because #333 decided AGAINST CORS rather than merely lacking it: the
  ;; key is a server-side credential and browsers must proxy. A decision that is
  ;; not tested is indistinguishable from an omission later — which is the whole
  ;; argument of `considered-divergences.md`.
  (when (booted?)
    (testing "a CORS preflight cannot succeed: it carries no credentials by spec"
      (let [r (http/options (str (base-url) "/v1/chat/completions")
                            {:headers {"Origin" "https://third-party.example"
                                       "Access-Control-Request-Method" "POST"
                                       "Access-Control-Request-Headers" "authorization,content-type"}
                             :throw-exceptions false})]
        (is (= 401 (:status r)))
        (is (empty? (cors-headers r)))))
    (testing "no Access-Control-* headers on an authenticated success either"
      (let [r (GET "/v1/models" {"Authorization" (str "Bearer " (api-key))
                                 "Origin" "https://third-party.example"})]
        (is (= 200 (:status r)))
        (is (empty? (cors-headers r))
            "if this fails, something now adds CORS and #333 needs revisiting")))))

(deftest conversations-require-the-documented-user-header
  ;; `endpoints/conversations.md` marks `X-User-Id` required on every public
  ;; conversation endpoint and says there is no unscoped list-all mode.
  (when (booted?)
    (testing "omitting X-User-Id is refused rather than silently unscoped"
      (let [r (GET "/api/conversations" {"X-API-Key" (api-key)})]
        (is (= 400 (:status r)))))
    (testing "supplying it succeeds"
      (let [r (GET "/api/conversations" {"X-API-Key" (api-key)
                                         "X-User-Id" "doc-fidelity-smoke"})]
        (is (= 200 (:status r)))))))

;; ---------------------------------------------------------------------------

(def known-broken
  "Documented endpoints that do not work when the server is actually running.
   Transport-level tests cannot see these: the request is well-formed and the
   failure happens in the handler.

   Empty since #350. The single entry was `GET /api/modes` returning 500 — a
   function object in one skill graph that Jackson could not encode. The
   endpoint was deleted rather than fixed, because `tools/list` and
   GET /v1/models already are the discovery surface it was meant to be. The
   defect it exposed survives the deletion on a screen that ships, and is
   tracked at #348 rather than here: it is not a documented-endpoint failure any
   more.

   `digdir.api.routes.compiled-router-test/deleted-routes-stay-deleted` is what
   holds the deletion; this register is for endpoints we still publish."
  [])

(deftest documented-endpoints-respond
  (when (booted?)
    (doseq [{:keys [path status issue documented-in]} known-broken]
      (testing (str path " is a known-broken documented endpoint")
        (let [r (GET path {"X-API-Key" (api-key)})]
          (is (= status (:status r))
              (str path " is recorded as returning " status " (" documented-in
                   "). It returned " (:status r) ". If it is fixed or deleted, "
                   "remove this entry — that removal is the acceptance for issue #"
                   issue ".")))))))

;; ---------------------------------------------------------------------------
;; What the wire advertises vs what the prose says (#340)
;;
;; These are not greps. Each reads the live `tools/list` advertisement and
;; compares the documentation against it, so the assertion moves when the wire
;; moves rather than when someone edits a literal in this file.
;;
;; Scope: the tool NAME form, the required argument NAME, and whether an
;; advertised `outputSchema` is mentioned at all. It does not compare the
;; schemas themselves.
;; ---------------------------------------------------------------------------

(defn- advertised-tools []
  (let [r (http/post (str (base-url) "/api/mcp")
                     {:headers {"Content-Type" "application/json"
                                "X-API-Key" (api-key)
                                "MCP-Protocol-Version" "2026-07-28"
                                "Mcp-Method" "tools/list"}
                      :body (json/generate-string
                              {:jsonrpc "2.0" :id 1 :method "tools/list"})
                      :throw-exceptions false})]
    (get-in (json/parse-string (:body r) true) [:result :tools])))

(def ^:private expected-argument-tables
  "`getting-started.md` and `endpoints/mcp.md` each carry one `tools/call`
   argument table. Pinned for the reason #339 taught: an extractor that stops
   matching looks exactly like documentation with nothing wrong."
  2)

(defn- argument-tables
  "[{:file :required-fields}] for every `tools/call` arguments table under
   docs/api, where `:required-fields` are the field names marked `yes`."
  []
  (for [f (markdown-files-under "docs/api")
        :let [text (slurp f)
              section (second (re-find #"(?is)\n#+ [^\n]*tools/call[^\n]*arguments?[^\n]*\n(.*?)(?:\n#+ |\z)" text))]
        :when section]
    {:file (.getPath ^java.io.File f)
     :required-fields (set (map second (re-seq #"(?m)^\|\s*`([^`]+)`\s*\|[^|]*\|\s*yes\s*\|" section)))}))

(deftest documented-required-argument-matches-the-advertised-one
  (when (booted?)
    (let [tools (advertised-tools)
          advertised (set (get-in (first tools) [:inputSchema :required]))
          tables (argument-tables)]
      (is (seq tools) "tools/list returned nothing — the comparison below would be vacuous")
      (is (= expected-argument-tables (count tables))
          (str "found " (count tables) " tools/call argument tables, expected "
               expected-argument-tables ": " (pr-str (mapv :file tables))))
      (doseq [{:keys [file required-fields]} tables]
        (is (= advertised required-fields)
            (str file " marks " (pr-str (sort required-fields))
                 " as required; tools/list advertises "
                 (pr-str (sort advertised))
                 ". Both are accepted by the server, so this is drift rather "
                 "than breakage — but a reader cannot tell which is canonical."))))))

(deftest documented-tool-names-use-the-advertised-form
  (when (booted?)
    (let [names (map :name (advertised-tools))]
      (is (seq names) "tools/list returned no tool names")
      (is (every? #(not (str/includes? % "/")) names)
          (str "tools/list advertises a name containing a slash: " (pr-str names)
               " — #122 removed it from the public identifier."))
      (doseq [f (markdown-files-under "docs/api")
              :let [hits (re-seq #"[a-z][a-z0-9-]*/[a-z0-9-]+__[a-z0-9-]+" (slurp f))]]
        (is (empty? hits)
            (str (.getPath ^java.io.File f) " shows a wire identifier in the "
                 "slash form " (pr-str (vec hits)) ". tools/list advertises the "
                 "dot form; #122 removed the slash because the identifier "
                 "becomes a URL path segment."))))))

(deftest an-advertised-output-schema-is-documented
  (when (booted?)
    (let [tools (advertised-tools)
          with-output (filter :outputSchema tools)]
      (is (seq tools) "tools/list returned nothing")
      (when (seq with-output)
        ;; A boolean, deliberately, rather than `(is (str/includes? corpus ...))`:
        ;; clojure.test prints the failing form's arguments, and the corpus is
        ;; every markdown file under docs/api. The first version of this check
        ;; emitted 94KB of documentation into the failure output and buried the
        ;; three real findings above it.
        (let [mentioned? (boolean (some #(str/includes? (slurp %) "structuredContent")
                                        (markdown-files-under "docs/api")))]
          (is mentioned?
              (str (count with-output) " of " (count tools) " advertised tools "
                   "carry an outputSchema, and no document under docs/api "
                   "mentions structuredContent. A client is told the shape "
                   "exists by the wire and nowhere by us.")))))))
;; Documented response shapes vs what the handlers emit
;;
;; A SLICE of #345 (B4), not #345. It covers the four conversation endpoints,
;; because that is where #339's defect lives and because a defect that has been
;; fixed but is unguarded is indistinguishable from one that was never broken.
;; The rest of the public surface is still checked only structurally -
;; `response-schema-coverage-test` asserts a shape is DECLARED and resolves, and
;; says in its own docstring that it does not verify the shape matches its
;; handler. **Do not close #345 on the strength of this.**
;;
;; The expectations are EXTRACTED from `endpoints/conversations.md`, not
;; transcribed here, for the reason #342 exists: a check that encodes what
;; someone remembered has the blind spot of the document it guards.
;;
;; This is a key-set comparison. It does not check value types, nullability, or
;; nested message shapes - so it is stronger than a presence check and weaker
;; than a schema. Stated so the next reader does not take it for either.
;; ---------------------------------------------------------------------------

(def ^:private conversations-doc "docs/api/endpoints/conversations.md")

(defn- doc-sections
  "Split the endpoint doc into `## ` sections, keeping the heading."
  [text]
  (->> (str/split text #"(?m)^## ")
       (drop 1)
       (map (fn [chunk]
              (let [[heading & _] (str/split-lines chunk)]
                {:heading (str/trim heading) :body chunk})))))

(defn- documented-success-shape
  "The key set of the first `### Success Response` JSON example in a section,
   plus the key set of a nested `conversation` object when one is present."
  [{:keys [heading body]}]
  ;; The fence is not necessarily adjacent to the heading - a section may
  ;; explain the shape in prose first. An earlier version required adjacency,
  ;; and adding one such sentence silently removed two endpoints from this
  ;; check: it went GREEN by looking at less. `expected-documented-sections`
  ;; below is the guard against that happening again quietly.
  (when-let [raw (second (re-find #"(?s)### Success Response.*?```json\n(.*?)```" body))]
    (when-let [verb+path (re-find #"`(GET|POST|PUT|DELETE) (/api/conversations[^`]*)`" body)]
      (let [parsed (json/parse-string raw true)]
        {:heading heading
         :method (nth verb+path 1)
         :path (nth verb+path 2)
         :top (set (map name (keys parsed)))
         :conversation (when-let [c (or (:conversation parsed)
                                        (first (:conversations parsed)))]
                         (set (map name (keys c))))}))))

(defn- documented-shapes []
  (->> (slurp (io/file conversations-doc))
       doc-sections
       (keep documented-success-shape)))

(defn- drive-conversation-endpoints
  "Exercise create -> get -> update -> list -> delete once, returning the live
   bodies keyed by the documented section heading."
  []
  (let [H {"X-API-Key" (convo-key) "X-User-Id" "doc-fidelity-smoke"
           "Content-Type" "application/json"}
        post (http/post (str (base-url) "/api/conversations")
                        {:headers H :throw-exceptions false
                         :body (json/generate-string {:title "doc-fidelity" :tags ["a"]})})
        created (json/parse-string (:body post) true)
        cid (or (:id created) (get-in created [:conversation :id]))
        get- (http/get (str (base-url) "/api/conversations/" cid)
                       {:headers H :throw-exceptions false})
        put (http/put (str (base-url) "/api/conversations/" cid)
                      {:headers H :throw-exceptions false
                       :body (json/generate-string {:title "doc-fidelity-2"})})
        list- (http/get (str (base-url) "/api/conversations?page_size=5&page_index=0")
                        {:headers H :throw-exceptions false})
        del (http/delete (str (base-url) "/api/conversations/" cid)
                         {:headers H :throw-exceptions false})]
    {"Create Conversation" (json/parse-string (:body post) true)
     "Get Conversation" (json/parse-string (:body get-) true)
     "Update Conversation" (json/parse-string (:body put) true)
     "List Conversations" (json/parse-string (:body list-) true)
     "Delete Conversation" (json/parse-string (:body del) true)}))

(defn- live-shape [body]
  {:top (set (map name (keys body)))
   :conversation (when-let [c (or (:conversation body) (first (:conversations body)))]
                   (set (map name (keys c))))})

(def ^:private expected-documented-sections
  "How many `/api/conversations` sections carry a success-response example:
   list, create, get, update, delete.

   Asserted because the extractor going quiet is indistinguishable from the
   documentation being correct. This check already went green once by matching
   two fewer sections than it should have."
  5)

(deftest the-extractor-still-sees-every-documented-endpoint
  (let [found (documented-shapes)]
    (is (= expected-documented-sections (count found))
        (str "extracted " (count found) " documented success shapes from "
             conversations-doc " but expected " expected-documented-sections
             ": " (pr-str (mapv :heading found))
             ". If a section was added or removed, update the count; if one is "
             "simply no longer being matched, fix the extractor rather than "
             "the number."))))

(deftest documented-response-shapes-match-what-handlers-emit
  (when (booted?)
    (let [live (drive-conversation-endpoints)]
      (doseq [{:keys [heading method path top conversation]} (documented-shapes)
              :let [body (get live heading)]
              :when body]
        (testing (str method " " path " (" heading ")")
          (let [actual (live-shape body)]
            (is (= top (:top actual))
                (str method " " path " — documented top-level keys "
                     (pr-str (sort top)) ", emitted " (pr-str (sort (:top actual)))
                     ". A caller reading a documented key that is not emitted gets "
                     "`undefined` and a 200, which is why this is the silent one."))
            (when conversation
              (is (= conversation (:conversation actual))
                  (str method " " path " — documented conversation-object keys "
                       (pr-str (sort conversation)) ", emitted "
                       (pr-str (sort (:conversation actual))) ".")))))))))

;; ---------------------------------------------------------------------------
;; Documented response shapes beyond conversations (#345)
;;
;; SCOPE, DECIDED DELIBERATELY AND STATED HERE BECAUSE A GUARD LIKE THIS READS
;; AS MORE THAN IT IS. What follows compares the KEY SET and the VALUE TYPE of
;; a documented example against what the handler emits, one level into arrays.
;;
;; That is strictly more than the conversations slice above, which compares key
;; sets only. It is still NOT "response correctness". Specifically open after
;; every assertion here passes:
;;
;;   nullability          a field documented as a string and emitted as null
;;                        passes if some run emits a string
;;   deep nesting         only the first level inside an array is compared
;;   empty collections    an array the server returns empty cannot have its
;;                        item shape observed at all — counted below rather
;;                        than silently skipped, because a comparison over
;;                        nothing is the failure mode this file has shipped
;;                        twice
;;   value semantics      that `created` is a unix timestamp rather than any
;;                        integer, that an `id` resolves to anything
;;   /v1/chat/completions and /api/mcp tools/call results, which need a live
;;                        model and a materialized dataset
;;   the whole /console-api surface, which needs an operator JWT
;;
;; The first two of those are reachable with more work. The last two are not
;; reachable in a unit test at all, and they are the highest-traffic surfaces —
;; so the coverage here is thinnest exactly where it would matter most. Said
;; plainly rather than left for a reader to infer from what is absent.
;; ---------------------------------------------------------------------------

(defn- shape
  "Key set and value types, one level into arrays. `[]` marks an array whose
   item shape could not be observed."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [(name k) (shape v)])) x)
    (sequential? x) (if (seq x) [(shape (first x))] [])
    (string? x) "string"
    (integer? x) "int"
    (float? x) "float"
    (boolean? x) "bool"
    (nil? x) "null"
    :else (str (type x))))

(defn- documented-example
  "The first fenced JSON block after `marker` in `doc`."
  [doc marker]
  (let [text (slurp (io/file doc))
        i (str/index-of text marker)]
    (when i
      (when-let [raw (second (re-find #"(?s)```json\n(.*?)```" (subs text i)))]
        (json/parse-string raw true)))))

(def ^:private response-shape-cases
  "Endpoint, the document that describes its response, and the marker the
   example follows. Extracted rather than transcribed: a check that encodes
   what someone remembered has the blind spot of the document it guards."
  [{:label "GET /v1/models"
    :doc "docs/api/endpoints/openai-compat.md"
    :marker "## `GET /v1/models`"
    :path "/v1/models" :auth :bearer}
   {:label "GET /api/datasets"
    :doc "docs/api/endpoints/datasets.md"
    :marker "## List Datasets"
    :path "/api/datasets" :auth :api-key}
   {:label "GET /api/conversations/:id — documented 404 body"
    :doc "docs/api/endpoints/conversations.md"
    :marker "### Error Response (404)"
    :path "/api/conversations/no-such-conversation" :auth :conversations}])

(def ^:private expected-response-shape-cases 3)

(def ^:private expected-unobservable
  "Cases whose documented example contains an array the live server returns
   empty, so the item shape cannot be compared. Pinned, because an unobservable
   comparison that is not counted is indistinguishable from a passing one.

   `/api/datasets` is here because no dataset is materialized in the test
   database — the ENVELOPE is compared, the dataset object inside it is not."
  #{"GET /api/datasets"})

(defn- drive-case [{:keys [path auth]}]
  (GET path (case auth
              :bearer {"Authorization" (str "Bearer " (api-key))}
              :api-key {"X-API-Key" (api-key)}
              :conversations {"X-API-Key" (api-key) "X-User-Id" "doc-fidelity-smoke"})))

(defn- unobservable? [documented actual]
  (boolean (some (fn [[k v]] (and (vector? v) (seq v)
                                  (= [] (get actual k))))
                 documented)))

(deftest documented-response-shapes-beyond-conversations
  (when (booted?)
    (is (= expected-response-shape-cases (count response-shape-cases))
        "case list changed — update the count in the same commit")
    (let [results
          (doall
            (for [{:keys [label doc marker] :as c} response-shape-cases]
              (let [documented (documented-example doc marker)
                    _ (is (some? documented)
                          (str label ": no fenced JSON example found after "
                               (pr-str marker) " in " doc
                               " — the comparison below would be vacuous"))
                    actual (json/parse-string (:body (drive-case c)) true)
                    ds (shape documented)
                    as (shape actual)]
                (testing label
                  (if (unobservable? ds as)
                    (is (contains? expected-unobservable label)
                        (str label ": an array is empty on the wire so its item "
                             "shape is unobservable, and it is not in "
                             "expected-unobservable. Add it and say why, or seed "
                             "the fixture so the item can be compared."))
                    (is (= ds as)
                        (str label ": documented shape and emitted shape differ.\n"
                             "  documented " (pr-str ds) "\n"
                             "  emitted    " (pr-str as)))))
                {:label label :unobservable (unobservable? ds as)})))]
      (is (= expected-unobservable
             (set (map :label (filter :unobservable results))))
          (str "the set of unobservable cases moved: "
               (pr-str (set (map :label (filter :unobservable results))))
               ". A case that became observable should be compared, not excused.")))))

;; ---------------------------------------------------------------------------
;; #375 — openapi RESPONSE SCHEMA vs what the handler EMITS
;;
;; The third link in a chain that had two. `doc-fidelity` (above) compares the
;; markdown example to the handler; `response-schema-coverage-test` (#185)
;; checks a schema is declared and resolves, and says in its own docstring that
;; it "does not verify that a declared schema MATCHES its handler". Nothing
;; compared the schema to the emission, and they had already drifted:
;; `Conversation` declared `agent-id`/`user-id` against emitted
;; `agentId`/`userId`, and the envelope declared `page_index`/`page_size`
;; against emitted `pageIndex`/`pageSize` (#372, repaired in #374).
;;
;; NAMES, NOT TYPES. The drift that occurred was naming, and a type comparison
;; adds int-vs-float and null-vs-absent noise that would have to be excused
;; case by case — excuses are where a guard goes quietly blind.
;;
;; ⚠️ THE EXPECTATION MUST NOT TRAVEL INSIDE THE VALUE IT CHECKS. If this
;; compared only the keys the handler happens to emit, a handler that stopped
;; emitting a field would remove the comparison ALONG WITH the field and the
;; guard would go green on the loss it exists to catch. So the SCHEMA drives:
;; every declared property must appear, and `schema-resolved?` below fails
;; loudly when the schema side comes back empty — a broken `$ref` must not read
;; as agreement.
;; ---------------------------------------------------------------------------

(def ^:private openapi-doc "docs/api/openapi.yaml")

(defn- openapi []
  (yaml/parse-string (slurp (io/file openapi-doc)) :keywords false))

(defn- deref-ref
  "Follow a `$ref` into the document. One hop is enough for this spec; a chain
   would resolve to a map still carrying `$ref`, which `schema-names` treats as
   an unresolved schema rather than as an empty one."
  [spec s]
  (if-let [r (get s "$ref")]
    (get-in spec (vec (rest (str/split r #"/"))))
    s))

(defn- schema-names
  "Nested property-NAME tree of a schema, mirroring `live-names` below so the
   two are comparable. `[]` marks an array whose item schema is absent."
  [spec s]
  (let [s (deref-ref spec s)]
    (cond
      (get s "properties")
      (into (sorted-map)
            (map (fn [[k v]] [k (schema-names spec v)]))
            (get s "properties"))

      (= "array" (get s "type"))
      (if-let [items (get s "items")] [(schema-names spec items)] [])

      :else nil)))

(defn- live-names
  "Nested property-NAME tree of a live JSON body. Leaves are nil: this compares
   names, not types (see the note above)."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [(name k) (live-names v)])) x)
    (sequential? x) (if (seq x) [(live-names (first x))] [])
    :else nil))

(def ^:private schema-emission-cases
  "Endpoint, the openapi path/method/status its response is declared under, and
   how to authenticate. Only endpoints this fixture can actually drive."
  [{:label "GET /v1/models"
    :spec-path "/v1/models" :method "get" :status "200"
    :path "/v1/models" :auth :bearer}
   {:label "GET /api/datasets"
    :spec-path "/api/datasets" :method "get" :status "200"
    :path "/api/datasets" :auth :api-key}
   {:label "GET /api/conversations"
    :spec-path "/api/conversations" :method "get" :status "200"
    :path "/api/conversations" :auth :conversations}])

(def ^:private expected-schema-emission-cases 3)

(def ^:private expected-schema-unobservable
  "Cases where the schema declares an array whose items the live server returns
   empty, so the item schema cannot be compared. Counted, not skipped: an
   unobservable comparison that is not recorded is indistinguishable from a
   passing one.

   `/api/datasets` — no dataset is materialized in the test database, so the
   ENVELOPE is compared and the dataset object inside it is not.
   `GET /api/conversations` — no conversation exists for this caller, so the
   `Conversation` object itself is unchecked here. It IS compared by
   `documented-response-shapes-match-what-handlers-emit` above, against the
   markdown rather than against the schema."
  #{"GET /api/datasets" "GET /api/conversations"})

(defn- declared-response-schema [spec {:keys [spec-path method status]}]
  (get-in spec [ "paths" spec-path method "responses" status
                "content" "application/json" "schema"]))

(defn- unobservable-array? [declared live]
  (boolean (some (fn [[k v]] (and (vector? v) (seq v) (= [] (get live k))))
                 declared)))

(deftest openapi-response-schemas-match-what-handlers-emit
  (when (booted?)
    (is (= expected-schema-emission-cases (count schema-emission-cases))
        "case list changed — update the count in the same commit")
    (let [spec (openapi)
          results
          (doall
            (for [{:keys [label] :as c} schema-emission-cases]
              (let [raw (declared-response-schema spec c)
                    declared (schema-names spec raw)
                    live (live-names (json/parse-string (:body (drive-case c)) true))]
                (testing label
                  ;; The anti-vacuity guard. A missing path, an unresolvable
                  ;; $ref or a schema with no properties all yield an empty
                  ;; `declared`, and comparing empty to anything would pass by
                  ;; describing nothing.
                  (is (seq declared)
                      (str label ": the declared response schema resolved to "
                           (pr-str declared) " — nothing to compare. A broken "
                           "$ref or a moved path must fail here rather than "
                           "read as agreement."))
                  (when (seq declared)
                    (if (unobservable-array? declared live)
                      (is (contains? expected-schema-unobservable label)
                          (str label ": the schema declares an array the server "
                               "returned empty, so its item schema is "
                               "unobservable and the label is not in "
                               "expected-schema-unobservable."))
                      (is (= declared live)
                          (str label ": openapi schema and emitted body differ.\n"
                               "  declared " (pr-str declared) "\n"
                               "  emitted  " (pr-str live))))
                    ;; Envelope-level names are compared in BOTH directions even
                    ;; when the item shape is unobservable, so a renamed or
                    ;; dropped top-level field is still caught.
                    (is (= (set (keys declared)) (set (keys live)))
                        (str label ": top-level response field names differ.\n"
                             "  declared " (pr-str (sort (keys declared))) "\n"
                             "  emitted  " (pr-str (sort (keys live))))))
                  {:label label
                   :unobservable (unobservable-array? declared live)}))))]
      (is (= expected-schema-unobservable
             (set (map :label (filter :unobservable results))))
          (str "the set of unobservable schema cases moved: "
               (pr-str (set (map :label (filter :unobservable results))))
               ". A case that became observable should be compared, not excused.")))))
