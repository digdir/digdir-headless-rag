(ns digdir.api.doc-fidelity-test
  "Every request our documentation tells a reader to send must actually be
   accepted by the server.

   THE QUESTION THIS RETIRES: does a published `/api/mcp` example satisfy the
   transport's request-metadata contract — i.e. would a stranger copying it get
   past validation? It is answered by extracting the fenced examples and
   replaying them, not by reading them.

   THE QUESTION IT DOES NOT ANSWER, and the reason a sibling smoke test exists:
   this drives `handle-mcp-request` directly, so everything above and beside the
   transport is invisible here — the auth middleware's 401 shape, CORS and
   preflight, handler serialization failures, and every non-MCP endpoint. Those
   live in `digdir.api.doc-fidelity-smoke-test`, which pays for a booted stack to
   see them. Breadth here, where the volume and the determinism are; depth there,
   narrowly.

   It also does not answer whether a request that passes validation produces a
   correct ANSWER. `invoke-tool` and `list-tools-response` are stubbed, and no
   dataset or agent is involved. \"The documented call is well-formed\" is the
   whole claim.

   WHY EXTRACTION RATHER THAN TRANSCRIPTION. A test that checks only the examples
   somebody remembered to copy has the same blind spot as the documentation it is
   guarding — which is how #226 fixed this exact `-32020` in `docs/onboarding.md`
   while leaving it in three files it edited on the same day. Everything under
   `docs/api/**` is swept, so a new example is covered the moment it is written.

   THE KNOWN-BROKEN LIST is the register of what is broken today, in code rather
   than in a thread. It exists so this test can land green while #331's fixes are
   still in flight. Two rules keep it honest, both enforced below:
     - an example that fails and is NOT listed fails the test, so the list cannot
       grow silently;
     - an example that PASSES while still listed also fails the test, so a stale
       entry cannot survive its own fix. That second rule is what makes each
       #331 sub-issue's acceptance a measurement: delete your entry, watch it go
       red, land your fix, watch it go green."
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.mcp.transport :as transport]))

;; ---------------------------------------------------------------------------
;; The register of published examples that do not work today
;; ---------------------------------------------------------------------------

(def known-broken
  "Published examples that fail validation as written. Each entry names the file,
   its ordinal among that file's `/api/mcp` examples, the JSON-RPC method, the
   error code the server returns, and the issue that removes it.

   `:method` is recorded so that reordering or replacing an example cannot
   silently re-point an entry at a different request — a mismatch fails loudly.

   EMPTY SINCE #337. All seven original entries were the same defect — the
   request-metadata headers `2026-07-28` requires have been mandatory since
   #146, and those examples predated it — and each was deleted as its fix
   landed, which is the acceptance this register was built to make measurable.

   An empty register does NOT make this test vacuous, and that was checked
   rather than assumed: the loop below runs over every EXTRACTED example, not
   over the entries, so a newly-broken example still fails on the
   `rejected and not listed` branch. Both rules were re-proved red against the
   empty register (#342) — a bogus entry for a passing example, and a required
   header stripped from a live one."
  [])

;; ---------------------------------------------------------------------------
;; Extraction
;; ---------------------------------------------------------------------------

(def ^:private docs-root
  "Tests run from the `server` directory."
  "docs/api")

(defn- markdown-files []
  (->> (file-seq (io/file docs-root))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
       (sort-by #(.getPath ^java.io.File %))))

(defn- shell-blocks
  "Bodies of fenced bash/sh/shell blocks, in document order."
  [text]
  (map second (re-seq #"(?s)```(?:bash|sh|shell)\n(.*?)```" text)))

(defn- curl-invocations
  "One entry per `curl` invocation. A single fenced block often holds several."
  [block]
  (->> (str/split block #"(?m)^(?=curl\b)")
       (map str/trim)
       (filter #(str/starts-with? % "curl"))))

(defn- curl-headers
  "Ring-shaped header map: lower-cased names, as Ring itself supplies them."
  [cmd]
  (into {}
        (for [[_ h] (re-seq #"-H\s+\"([^\"]*)\"" cmd)
              :let [i (str/index-of h ":")]
              :when i]
          [(str/lower-case (str/trim (subs h 0 i)))
           (str/trim (subs h (inc i)))])))

(defn- curl-body [cmd]
  (second (re-find #"(?s)-d\s+'(.*?)'" cmd)))

(defn- mcp-examples
  "Every `/api/mcp` request published under `docs/api/**`, in document order,
   numbered per file so an entry in `known-broken` can name one."
  []
  (->> (for [f (markdown-files)
             :let [text (slurp f)]
             block (shell-blocks text)
             cmd (curl-invocations block)
             :when (str/includes? cmd "/api/mcp")]
         {:file (.getPath ^java.io.File f)
          :headers (curl-headers cmd)
          :body-str (curl-body cmd)})
       (group-by :file)
       (mapcat (fn [[_ xs]] (map-indexed #(assoc %2 :ordinal %1) xs)))
       (sort-by (juxt :file :ordinal))
       vec))

;; ---------------------------------------------------------------------------
;; Replay
;; ---------------------------------------------------------------------------

(def ^:private request-metadata-error-codes
  "`HeaderMismatch` and `UnsupportedProtocolVersionError` — the two ways the
   transport refuses a request before it can become a tool call."
  #{-32020 -32022})

(defn- replay
  [{:keys [headers body-str]}]
  (let [resp (transport/handle-mcp-request
               {:request-method :post
                :uri "/api/mcp"
                :headers headers
                :body body-str
                :api-key/id "doc-fidelity"})
        parsed (try (json/parse-string (:body resp) true) (catch Exception _ nil))
        code (get-in parsed [:error :code])]
    {:status (:status resp)
     :code code
     :rejected? (boolean (and (= 400 (:status resp))
                              (contains? request-metadata-error-codes code)))}))

(defn- method-of [{:keys [body-str]}]
  (try (get (json/parse-string body-str) "method") (catch Exception _ nil)))

(defn- entry-for [{:keys [file ordinal]}]
  (first (filter #(and (= file (:file %)) (= ordinal (:ordinal %))) known-broken)))

(defn- label [{:keys [file ordinal]} method]
  (format "%s [mcp example %d: %s]" file ordinal (or method "<unparsed>")))

(defn- with-stubs* [f]
  ;; The claim is "well-formed", not "answers correctly": stubbing dispatch keeps
  ;; this hermetic — no database, no agent registry, no LLM.
  (with-redefs [mcp-tools/invoke-tool (fn [_ _ _ _] {:result {:content [] :isError false}})
                mcp-tools/list-tools-response (fn [_] {:tools []})]
    (f)))

;; ---------------------------------------------------------------------------

(deftest every-published-example-is-parseable
  (testing "an example we cannot parse is an example we are not checking"
    (doseq [ex (mcp-examples)]
      (is (some? (:body-str ex))
          (str (label ex nil) " — no `-d` payload could be extracted"))
      (when (:body-str ex)
        (is (some? (method-of ex))
            (str (label ex nil) " — payload is not JSON with a `method`"))))))

(deftest published-examples-satisfy-the-request-metadata-contract
  (with-stubs*
    (fn []
      (doseq [ex (mcp-examples)
              :let [method (method-of ex)
                    result (replay ex)
                    entry (entry-for ex)]]
        (cond
          ;; Fails, and we said it would.
          (and (:rejected? result) entry)
          (do (is (= (:method entry) method)
                  (str (label ex method) " — known-broken entry records method "
                       (pr-str (:method entry)) "; the example now sends "
                       (pr-str method) ". Re-point or remove the entry."))
              (is (= (:code entry) (:code result))
                  (str (label ex method) " — known-broken entry records code "
                       (:code entry) "; the server returned " (:code result) ".")))

          ;; Fails, and we did not. The list must not grow silently.
          (:rejected? result)
          (is false
              (str (label ex method) " is rejected by the transport with "
                   (:code result) " and is not on the known-broken list. "
                   "Either fix the example or add an entry and say why."))

          ;; Passes, but is still listed. This is the acceptance test for a fix.
          entry
          (is false
              (str (label ex method) " now passes validation but is still on the "
                   "known-broken list. Delete the entry — that deletion is the "
                   "acceptance for issue #" (:issue entry) "."))

          :else
          (is true))))))

;; ---------------------------------------------------------------------------
;; The machine-readable contract, against the same transport
;;
;; `openapi.yaml` is called the authoritative contract by three other documents,
;; so a client generated from it is a published path in exactly the sense the
;; rest of this namespace measures — it just is not a fenced example.
;;
;; SCOPE: the method list and the notification status only. Whether the spec
;; declares the request-metadata HEADERS is #344's guard, which reads them out of
;; `validate-request` rather than hard-coding them as this does.
;; ---------------------------------------------------------------------------

(def openapi-known-broken
  "Claims `openapi.yaml` makes about `/api/mcp` that the transport contradicts.
   Cleared by #338. Same two rules as `known-broken`: an unlisted mismatch fails,
   and a listed claim that has been corrected also fails."
  #{})

(defn- supported-methods
  "The `Supported methods: ...` sentence from the /api/mcp description.

   Read as a sentence rather than searched for as a substring, because the two
   are not the same question. The first version of this check asserted that the
   description did not CONTAIN the word `initialize` — and the correct fix names
   `initialize` in order to say the server no longer answers it. A check whose
   passing condition the right fix violates is a broken check, not a strict one."
  [described]
  (or (second (re-find #"(?s)Supported methods:(.*?)\." described)) ""))

(defn- mcp-operation []
  (-> (slurp (io/file docs-root "openapi.yaml"))
      yaml/parse-string
      (get-in [:paths (keyword "/api/mcp") :post])))

(defn- notification-status
  "The status the transport actually returns for a JSON-RPC notification."
  []
  (:status (transport/handle-mcp-request
             {:request-method :post :uri "/api/mcp"
              :headers {"mcp-protocol-version" "2026-07-28" "mcp-method" "ping"}
              :body (json/generate-string {:jsonrpc "2.0" :method "ping"})
              :api-key/id "doc-fidelity"})))

(deftest openapi-describes-the-mcp-server-we-run
  (let [op (mcp-operation)
        described (str (:description op))
        responses (set (map name (keys (:responses op))))
        actual-notification (notification-status)
        methods (supported-methods described)
        results {:names-server-discover (str/includes? methods "server/discover")
                 :omits-initialize (not (str/includes? methods "initialize"))
                 :lists-only-implemented-methods
                 (every? #(str/includes? methods %) ["tools/list" "tools/call" "ping"])
                 :notification-is-202 (contains? responses (str actual-notification))}]
    (doseq [[claim holds?] results
            :let [listed? (contains? openapi-known-broken claim)]]
      (cond
        (and (not holds?) listed?)
        (is true)

        (not holds?)
        (is false
            (str "openapi.yaml /api/mcp fails check " claim
                 " and is not on openapi-known-broken. Fix the spec or add it."))

        listed?
        (is false
            (str "openapi.yaml /api/mcp now satisfies " claim
                 " but is still on openapi-known-broken. Delete the entry — that "
                 "deletion is the acceptance for issue #338."))

        :else (is true)))
    (testing "the transport still answers a notification the way we recorded"
      (is (= 202 actual-notification)
          "openapi.yaml documents 204; this pins what the server really does"))))

;; ---------------------------------------------------------------------------
;; Non-shell samples — a PRESENCE check, and deliberately labelled as one
;;
;; The replay above extracts bash only, because a curl invocation can be turned
;; back into an HTTP request and a JavaScript or Python snippet cannot without
;; running it. But `getting-started.md` carries the same `tools/call` three
;; times, in bash, JS and Python, so the bash replay passing says nothing about
;; the other two — and #337 fixed all three while only one of them was measured.
;;
;; This closes the gap it can reach and no more. It asserts the header NAMES
;; appear in the snippet. It cannot tell whether the values are right, whether
;; they match the body, or whether the request would be accepted.
;;
;; A presence check answers "is it there", never "is it right". Recorded that
;; way so the next reader does not mistake this for the replay.
;; ---------------------------------------------------------------------------

(defn- code-blocks
  "Bodies of fenced blocks in `langs`, in document order."
  [text langs]
  (->> (re-seq #"(?s)```([a-zA-Z]+)\n(.*?)```" text)
       (filter (fn [[_ lang _]] (contains? langs (str/lower-case lang))))
       (map (fn [[_ lang body]] {:lang (str/lower-case lang) :body body}))))

(defn- mcp-snippets []
  (for [f (markdown-files)
        :let [text (slurp f)]
        {:keys [lang body]} (code-blocks text #{"javascript" "js" "python" "py"})
        :when (str/includes? body "/api/mcp")]
    {:file (.getPath ^java.io.File f) :lang lang :body body}))

(deftest non-shell-samples-name-the-required-headers
  (testing "JS and Python samples carry the headers the bash examples were fixed for"
    (doseq [{:keys [file lang body]} (mcp-snippets)
            :let [needs (cond-> ["MCP-Protocol-Version" "Mcp-Method"]
                          (str/includes? body "tools/call") (conj "Mcp-Name"))]
            header needs]
      (is (str/includes? body header)
          (str file " (" lang " sample) does not mention " header
               ". The bash examples in this file are replayed and would catch a "
               "missing header; this sample is not, so it is checked by name only.")))))

;; ---------------------------------------------------------------------------
;; The header contract, both directions (#344)
;;
;; This is the hole that produced #337. `request_doc_drift_test` walks routes
;; that declare `:parameters`, and MCP's header rules are enforced in
;; `digdir.mcp.transport`, outside that map — so no existing guard could see
;; them, and `openapi.yaml` went a full protocol migration without mentioning
;; any of the three.
;;
;; It reads `transport/request-metadata-contract` rather than re-stating the
;; rules. A test that re-implements the thing it guards drifts from it exactly
;; as the documentation did — which is the whole argument of this board applied
;; to the test itself.
;;
;; BOTH DIRECTIONS, and the second is the point. #338 added the spec's header
;; declarations BY HAND, so today they agree only because one person wrote both
;; sides. A guard that checked only "enforced but undeclared" would pass while
;; leaving exactly the condition it was built for.
;; ---------------------------------------------------------------------------

(defn- declared-headers
  "{header-name {:required bool :enum vec-or-nil}} from openapi.yaml."
  []
  (into {}
        (for [p (:parameters (mcp-operation))
              :when (= "header" (:in p))]
          [(:name p) {:required (boolean (:required p))
                      :enum (some-> (get-in p [:schema :enum]) vec)}])))

(defn- enforced-headers
  "The same shape, from the contract the server actually executes."
  []
  (into {}
        (for [r transport/request-metadata-contract]
          [(:header r) {:required (= :always (:required r))
                        :enum (some-> (:allowed-values r) vec)}])))

(deftest openapi-declares-exactly-the-enforced-request-metadata-headers
  (let [declared (declared-headers)
        enforced (enforced-headers)]
    (is (seq enforced)
        "the contract is empty — every comparison below would be vacuous")

    (testing "every header the server enforces is declared in the spec"
      (is (empty? (set/difference (set (keys enforced)) (set (keys declared))))
          (str "enforced but undeclared: "
               (pr-str (sort (set/difference (set (keys enforced)) (set (keys declared)))))
               ". A client generated from the spec omits it and is rejected "
               "with -32020 — this is #337's defect, one layer up.")))

    (testing "every header the spec declares is actually enforced"
      (is (empty? (set/difference (set (keys declared)) (set (keys enforced))))
          (str "declared but not enforced: "
               (pr-str (sort (set/difference (set (keys declared)) (set (keys enforced)))))
               ". The spec's header block was written by hand in #338; a name "
               "here that the server does not read is a claim nothing backs.")))

    (doseq [[hname enforced-spec] enforced
            :let [declared-spec (get declared hname)]
            :when declared-spec]
      (testing hname
        (is (= (:required enforced-spec) (:required declared-spec))
            (str hname ": the contract makes it "
                 (if (:required enforced-spec) "always required" "conditionally required")
                 " and the spec declares required=" (:required declared-spec)
                 ". A conditional header is `required: false` in OpenAPI, which "
                 "cannot express \"only for tools/call\" — the description carries "
                 "that, and the description is not checked."))
        (when (:enum enforced-spec)
          (is (= (:enum enforced-spec) (:enum declared-spec))
              (str hname ": contract allows " (pr-str (:enum enforced-spec))
                   ", spec declares " (pr-str (:enum declared-spec)))))))))

;; ---------------------------------------------------------------------------
;; The method list, both directions (#368)
;;
;; Stated three times: `transport/implemented-methods`, the `Mcp-Method` enum,
;; and the operation description. Until #368 only the first was executable, so
;; the other two were hand-maintained copies with nothing to check them against
;; — the same shape #344 removed from the header contract, one noun over.
;;
;; Reads `(keys implemented-methods)` rather than re-listing the methods. A test
;; that re-states what it guards drifts from it exactly as the documentation
;; did.
;; ---------------------------------------------------------------------------

(defn- enum-methods
  "The Mcp-Method parameter's declared enum, as a set."
  []
  (some->> (:parameters (mcp-operation))
           (filter #(= "Mcp-Method" (:name %)))
           first :schema :enum set))

(def ^:private expected-implemented-method-count
  "Pinned so a shrinking map cannot pass as agreement. A method removed from
   dispatch and from both documents together would otherwise be invisible."
  4)

(deftest openapi-declares-exactly-the-implemented-methods
  (let [implemented (set (keys transport/implemented-methods))
        declared (enum-methods)
        described (supported-methods (str (:description (mcp-operation))))]
    (is (= expected-implemented-method-count (count implemented))
        (str "dispatch implements " (count implemented) " methods, expected "
             expected-implemented-method-count ": " (pr-str (sort implemented))
             ". If a method was added or removed, update the count in the same "
             "commit."))
    (is (seq declared)
        "Mcp-Method declares no enum — the comparison below would be vacuous")

    (testing "the enum matches what dispatch actually routes"
      (is (= implemented declared)
          (str "implemented " (pr-str (sort implemented))
               ", enum declares " (pr-str (sort declared))
               ". A method in the enum that dispatch does not route is a 404 a "
               "generated client cannot explain; one dispatch routes and the enum "
               "omits is invisible to every client built from this spec.")))

    (testing "the prose method list matches too"
      (doseq [m implemented]
        (is (str/includes? described m)
            (str "`" m "` is routed by dispatch and absent from the "
                 "\"Supported methods\" sentence.")))
      (doseq [m ["initialize" "notifications/initialized"]]
        (is (not (str/includes? described m))
            (str "`" m "` is named as supported and dispatch does not route it."))))))
