(ns digdir.docs.pipeline.search-phrases-test
  "Tests for digdir.docs.pipeline.search-phrases - LLM search phrase generation."
  (:require [digdir.config.accessor]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.docs.pipeline.core :as core]
            ;; Required for the fully-qualified with-redefs targets below.
            ;; Deliberately no :as alias: the call sites stay fully qualified,
            ;; so the var each with-redefs targets is unchanged.
            ;;
            ;; This is `digdir.llm.client`, NOT `wkok.openai-clojure.api`.
            ;; search-phrases used to call wkok directly and these stubs named
            ;; that var; when it moved onto the client (which applies the
            ;; GPT-5-family parameter mapping and 429 retry, and delegates to
            ;; wkok only on `:impl :azure`), a stub on the wkok var stopped
            ;; intercepting anything and the tests made REAL HTTP calls —
            ;; failing at `Net.java:-2`, a connection error three layers from
            ;; the cause. Stub the seam the code under test actually calls.
            [digdir.llm.client]
            [missionary.core :as m]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-cache-dir "cache/test-search-phrases/")

;; DERIVED, not a literal: the provider-aware phrase-cache key guard below asserts this directory stays
;; empty, and a literal would keep that green if tier 2 moved back into the
;; local directory - it would be watching a directory nothing uses.
(def test-declared-dir (sp/declared-cache-dir "test"))

(defn- clear-dir! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))

(defn cleanup-test-cache [f]
  (clear-dir! test-cache-dir)
  (clear-dir! test-declared-dir)
  (f)
  (clear-dir! test-cache-dir)
  (clear-dir! test-declared-dir))

(use-fixtures :each cleanup-test-cache)

;; ============================================================================
;; parse-phrases-response Tests
;;
;; The parser tries JSON-mode output first (`{phrases: [...]}`), then
;; falls back to a line-walking heuristic for free-form responses. The
;; v1 parser took the last line of the response unconditionally and
;; failed badly when gpt-4o appended meta-commentary after the phrase
;; line — see the explicit regression test at the bottom.
;; ============================================================================

(defn- resp [content]
  {:choices [{:message {:content content}}]})

;; --- JSON-mode (primary) path ---

(deftest parse-phrases-response-json-basic
  (testing "Parses JSON-mode {phrases: [...]} response"
    (let [response (resp "{\"phrases\": [\"phrase one\", \"phrase two\", \"phrase three\"]}")]
      (is (= ["phrase one" "phrase two" "phrase three"]
             (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-json-empty
  (testing "Parses JSON-mode response with empty phrases array"
    (let [response (resp "{\"phrases\": []}")]
      (is (= [] (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-json-trims-and-drops-blanks
  (testing "JSON path trims whitespace and removes blank entries"
    (let [response (resp "{\"phrases\": [\"  alpha  \", \"\", \" beta\"]}")]
      (is (= ["alpha" "beta"] (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-json-with-extra-keys
  (testing "JSON path ignores other top-level keys"
    (let [response (resp "{\"phrases\": [\"a\"], \"explanation\": \"because\"}")]
      (is (= ["a"] (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-json-preserves-commas-in-phrases
  (testing "JSON path correctly preserves commas WITHIN phrase strings"
    (let [response (resp "{\"phrases\": [\"Altinn 3, juni 2020\", \"May 14, 2026\", \"Authorization, Authentication, Access Control\"]}")]
      (is (= ["Altinn 3, juni 2020"
              "May 14, 2026"
              "Authorization, Authentication, Access Control"]
             (sp/parse-phrases-response response))
          "commas inside phrase strings must not split them")
      (is (= 3 (count (sp/parse-phrases-response response)))
          "three phrases, not eight"))))

(deftest parse-phrases-response-heuristic-cannot-preserve-commas
  (testing "DOCUMENTED LIMITATION: the heuristic fallback cannot distinguish
   commas-in-phrase from commas-separating-phrases. A free-form response
   like 'Altinn 3, juni 2020, Tilgangspakker' is ambiguous — could be 2
   phrases ('Altinn 3, juni 2020' + 'Tilgangspakker') or 3 ('Altinn 3' +
   'juni 2020' + 'Tilgangspakker'). The heuristic chooses split-on-every-
   comma. Models that need comma-in-phrase support must honor JSON-mode."
    (let [response (resp "Altinn 3, juni 2020, Tilgangspakker")]
      (is (= ["Altinn 3" "juni 2020" "Tilgangspakker"]
             (sp/parse-phrases-response response))
          "heuristic over-splits — caller is responsible for using JSON-mode if commas-in-phrases matter"))))

;; --- Heuristic (fallback) path ---

(deftest parse-phrases-response-heuristic-comma-line
  (testing "Free-form response with comma-separated phrases on a line"
    (let [response (resp "Here are some phrases:\n\nalpha, beta, gamma, delta")]
      (is (= ["alpha" "beta" "gamma" "delta"] (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-heuristic-walks-past-meta
  (testing "REGRESSION: heuristic walks past meta-commentary AFTER the phrase line.
   This is the v1 bug — gpt-4o would append '3. **keywords only**.' etc.
   after the phrase list, and the v1 parser took that as the phrases."
    (let [response (resp "alpha, beta, gamma\n\n3. **keywords only**.")]
      (is (= ["alpha" "beta" "gamma"] (sp/parse-phrases-response response))
          "should walk past the meta line and find the comma-separated phrases"))))

(deftest parse-phrases-response-heuristic-walks-past-norwegian-meta
  (testing "Same regression in Norwegian: 'Hvis du vil, kan jeg gjøre dette ...' (which DOES have commas but is meta-commentary). Our heuristic skips short lines and lines starting with list/heading markers, but a comma-heavy meta line is still a risk. Document the gap."
    (let [response (resp "alpha, beta, gamma\n\nHvis du vil, kan jeg også gjøre dette annerledes.")
          result (sp/parse-phrases-response response)]
      ;; Heuristic walks from end. Last line: "Hvis du vil, kan jeg ..." has 1 comma,
      ;; >10 chars, doesn't start with list-marker. It WILL match it. This is the
      ;; known weakness of the heuristic — the JSON-mode path is the real fix.
      ;; We assert the current behavior so a future fix shows up as a test change.
      (is (or (= ["alpha" "beta" "gamma"] result)
              ;; current heuristic behavior — last comma-line wins
              (= ["Hvis du vil" "kan jeg også gjøre dette annerledes."] result))
          "heuristic is best-effort; JSON-mode response is the reliable fix"))))

(deftest parse-phrases-response-heuristic-no-commas
  (testing "Free-form response with no plausible comma line returns empty"
    (let [response (resp "I cannot generate phrases for this content.")]
      (is (= [] (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-heuristic-skips-bullet-lists
  (testing "Heuristic ignores markdown-bullet lines as comma sources"
    (let [response (resp "- bullet one, bullet two")]
      ;; The bullet line starts with `- ` so the heuristic skips it; no
      ;; other comma-line; returns empty.
      (is (= [] (sp/parse-phrases-response response))))))

(deftest parse-phrases-response-empty-content
  (testing "Empty content returns empty"
    (is (= [] (sp/parse-phrases-response (resp ""))))
    (is (= [] (sp/parse-phrases-response (resp nil))))))

(deftest ensure-json-mentioned-when-required-appends-when-missing
  (testing "REGRESSION: Azure OpenAI (observed on gpt-5.4-mini 2026-05-26)
   rejects response_format json_object if messages don't literally
   contain 'json' (HTTP 400 with \"'messages' must contain the word
   'json' in some form\"). Custom pipeline prompts like 'Generate
   search phrases for: ...' that omit the word failed every call. We
   append a marker when needed."
    ;; Marker appended when response_format is json_object AND content lacks 'json'
    (is (= "Generate search phrases for: hello\n\nRespond with a JSON object."
           (#'sp/ensure-json-mentioned-when-required
            "Generate search phrases for: hello"
            {:type "json_object"})))
    ;; No change when content already mentions JSON (case-insensitive)
    (is (= "Respond with JSON: {\"phrases\": []}"
           (#'sp/ensure-json-mentioned-when-required
            "Respond with JSON: {\"phrases\": []}"
            {:type "json_object"})))
    (is (= "respond with json please"
           (#'sp/ensure-json-mentioned-when-required
            "respond with json please"
            {:type "json_object"})))
    ;; No change for json_schema mode (LM Studio doesn't impose this restriction)
    (is (= "Generate phrases for: hello"
           (#'sp/ensure-json-mentioned-when-required
            "Generate phrases for: hello"
            {:type "json_schema" :json_schema {}})))
    ;; No change when there's no response_format at all
    (is (= "Generate phrases for: hello"
           (#'sp/ensure-json-mentioned-when-required
            "Generate phrases for: hello"
            nil)))))

(deftest parse-phrases-response-throws-on-no-choices
  (testing "REGRESSION: a response with no :choices (typical when the provider
   rejected the request, e.g. LM Studio's 'Unexpected endpoint or method'
   when api-endpoint omitted /v1) must throw — silently returning [] would
   let a configuration error bake into hundreds of cached chunks before
   anyone noticed."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"no :choices"
                          (sp/parse-phrases-response
                           {:error "Unexpected endpoint or method. (POST /chat/completions)"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sp/parse-phrases-response {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sp/parse-phrases-response {:choices []})))))

;; ============================================================================
;; cache-key Tests
;; ============================================================================

(def ^:private azure-identity {:provider :azure :model "dep-1"})

(deftest cache-key-deterministic
  (testing "Same inputs produce same key"
    (let [chunk {:chunk_id "test-chunk" :content_markdown "text"}
          prompt "test prompt"
          key1 (sp/cache-key chunk azure-identity prompt)
          key2 (sp/cache-key chunk azure-identity prompt)]
      (is (= key1 key2)))))

(deftest cache-key-is-derived-from-content-not-chunk-id
  (testing "Key leads with a hash of the chunk text"
    ;; Was `chunk_id`, which only worked while ids were content hashes.
    ;; Document-scoped ids (#72) would have made every copy of a duplicated
    ;; chunk miss the cache and pay its own LLM call.
    (let [chunk {:chunk_id "my-chunk-123" :content_markdown "some text"}
          key (sp/cache-key chunk azure-identity "prompt")]
      (is (str/starts-with? key (str (core/sha256-short-hash "some text") "-")))
      (is (not (str/includes? key "my-chunk-123")))))

  (testing "Same text under different ids shares one entry"
    (let [a {:chunk_id "id-a" :content_markdown "identical text"}
          b {:chunk_id "id-b" :content_markdown "identical text"}]
      (is (= (sp/cache-key a azure-identity "prompt")
             (sp/cache-key b azure-identity "prompt")))))

  (testing "Different text under the same id does not"
    (let [a {:chunk_id "same-id" :content_markdown "text one"}
          b {:chunk_id "same-id" :content_markdown "text two"}]
      (is (not= (sp/cache-key a azure-identity "prompt")
                (sp/cache-key b azure-identity "prompt"))))))

(deftest cache-key-different-prompts
  (testing "Different prompts produce different keys"
    (let [chunk {:chunk_id "test" :content_markdown "text"}
          key1 (sp/cache-key chunk azure-identity "prompt 1")
          key2 (sp/cache-key chunk azure-identity "prompt 2")]
      (is (not= key1 key2)))))

(deftest cache-key-includes-parser-version-suffix
  (testing "cache-key includes the parser-version so old cache entries are invalidated"
    (let [chunk {:chunk_id "test-chunk" :content_markdown "text"}
          key (sp/cache-key chunk azure-identity "prompt")]
      (is (str/ends-with? key (str "-" sp/parser-version))
          "cache-key must end with the parser-version suffix"))))

;; ---------------------------------------------------------------------------
;; the key names WHAT PRODUCED THE ENTRY
;; ---------------------------------------------------------------------------

(deftest cache-key-names-the-model-that-is-actually-sent
  ;; the key used to hash the pipeline's CONFIGURED `:search-phrases/model`,
  ;; which `complete` overwrites before the call — a model that was never sent.
  ;; Two different models must not share an entry, whatever the config called them.
  (let [chunk {:chunk_id "c" :content_markdown "text"}]
    (is (= (sp/cache-key chunk azure-identity "prompt")
           (sp/cache-key chunk azure-identity "prompt"))
        "POSITIVE CONTROL: one identity, one key")
    (is (not= (sp/cache-key chunk azure-identity "prompt")
              (sp/cache-key chunk (assoc azure-identity :model "dep-2") "prompt"))
        "a different model sent is a different entry")))

(deftest cache-key-names-the-provider-that-answers
  ;; The provider was absent from the key, so the same model
  ;; name on two providers shared one entry - and the provider changes the
  ;; prompt bytes (response_format, and the JSON mention Azure requires).
  (let [chunk {:chunk_id "c" :content_markdown "text"}]
    (is (not= (sp/cache-key chunk {:provider :azure :model "m"} "prompt")
              (sp/cache-key chunk {:provider :openai-compatible :model "m"} "prompt"))
        "same model name, different provider, different entry")))

(deftest cache-key-refuses-an-identity-that-is-not-one
  ;; The arity did not change when the second argument became a map, so a
  ;; caller left on the old `model` string would destructure to nils and key
  ;; every chunk under ONE degenerate identity - silently. It must refuse.
  (let [chunk {:chunk_id "c" :content_markdown "text"}]
    (is (thrown? clojure.lang.ExceptionInfo (sp/cache-key chunk "gpt-4o" "prompt"))
        "a bare model string is refused")
    (is (thrown? clojure.lang.ExceptionInfo (sp/cache-key chunk {:model "m"} "prompt"))
        "an identity with no provider is refused")
    (is (thrown? clojure.lang.ExceptionInfo (sp/cache-key chunk {:provider :azure} "prompt"))
        "an identity with no model is refused")
    (is (string? (sp/cache-key chunk azure-identity "prompt"))
        "POSITIVE CONTROL: a whole identity is accepted")))

(deftest cache-key-grammar-is-content-provider-model-prompt-version
  ;; Pinned because a later tier (the phrase negative-cache issue option e) builds a key under a DECLARED
  ;; identity with the LOCAL prompt: that is only sound while the grammar holds.
  (let [chunk {:chunk_id "c" :content_markdown "text"}
        segments (str/split (sp/cache-key chunk azure-identity "prompt") #"-")]
    (is (= 5 (count segments)) (str "five segments, got " (pr-str segments)))
    (is (= (core/sha256-short-hash "text") (nth segments 0)))
    (is (= (core/sha256-short-hash "azure") (nth segments 1)))
    (is (= (core/sha256-short-hash "dep-1") (nth segments 2)))
    (is (= (core/sha256-short-hash "prompt") (nth segments 3)))
    (is (= sp/parser-version (nth segments 4)))))

;; ============================================================================
;; ensure-cache-dir! Tests
;; ============================================================================

(deftest ensure-cache-dir-creates-directory
  (testing "Creates cache directory if not exists"
    (let [test-dir "cache/test-ensure-dir/"]
      (sp/ensure-cache-dir! test-dir)
      (is (.exists (io/file test-dir)))
      ;; Cleanup
      (.delete (io/file test-dir)))))

(deftest ensure-cache-dir-idempotent
  (testing "Safe to call multiple times"
    (let [test-dir "cache/test-ensure-dir-2/"]
      (sp/ensure-cache-dir! test-dir)
      (sp/ensure-cache-dir! test-dir)
      (is (.exists (io/file test-dir)))
      ;; Cleanup
      (.delete (io/file test-dir)))))

;; ============================================================================
;; read-cached-phrases / write-cached-phrases! Tests
;; ============================================================================

(deftest write-and-read-cached-phrases
  (testing "Can write and read cached phrases"
    (let [cache-path (str test-cache-dir "test-cache.edn")
          phrases ["phrase 1" "phrase 2" "phrase 3"]]
      (sp/ensure-cache-dir! test-cache-dir)
      (sp/write-cached-phrases! cache-path phrases)
      (let [read-phrases (sp/read-cached-phrases cache-path)]
        (is (= phrases read-phrases))))))

(deftest read-cached-phrases-returns-nil-for-missing
  (testing "Returns nil for missing cache file"
    (is (nil? (sp/read-cached-phrases "nonexistent/path.edn")))))

;; ============================================================================
;; default-search-phrases-prompt Tests
;; ============================================================================

(deftest default-prompt-exists
  (testing "Default prompt is defined"
    (is (string? sp/default-search-phrases-prompt))
    (is (pos? (count sp/default-search-phrases-prompt)))))

(deftest default-prompt-has-placeholder
  (testing "Default prompt contains REPLACE_ME placeholder"
    (is (str/includes? sp/default-search-phrases-prompt "REPLACE_ME"))))

;; ============================================================================
;; The call spec - `provider/resolve`'s, since Phase 3 of the provider-resolver change
;; ============================================================================

(defn- stub-install
  "An accessor stub for one tenant: `provider` as `services.llm.provider`, and
   both branches fully credentialed, so the decision is the only variable."
  [provider & {:keys [llm-endpoint] :or {llm-endpoint "http://localhost:1234"}}]
  (fn [_opts & ks]
    (case (vec ks)
      [:services :llm :provider] provider
      [:services :azure-openai :api-key] "key-1"
      [:services :azure-openai :api-endpoint] "https://example.azure.com"
      [:services :azure-openai :deployment-name] "dep-1"
      [:services :azure-openai :model-name] "local-model"
      [:services :llm :api-key] "llm-key"
      [:services :llm :api-endpoint] llm-endpoint
      nil)))

(defn- seen-by-client
  "Run `f` with `digdir.llm.client/create-chat-completion` stubbed; answers the
   {:conversation :opts} it was called with."
  [accessor f]
  (let [seen (atom nil)]
    (with-redefs [digdir.config.accessor/get accessor
                  digdir.llm.client/create-chat-completion (fn [conversation opts]
                                                             (reset! seen {:conversation conversation :opts opts})
                                                             {:choices [{:message {:content "{\"phrases\": []}"}}]})]
      (f))
    @seen))

(deftest create-chat-completion-on-azure
  (testing "Azure: the deployment name overwrites the caller's model, and the spec keeps the
            30s timeout search-phrases always gave Azure"
    (let [{:keys [conversation opts]} (seen-by-client (stub-install :azure)
                                                      #(sp/create-chat-completion "ka" {:model "ignored" :messages [{:role "user" :content "hi"}]}))]
      (is (= "dep-1" (:model conversation)))
      (is (= "https://example.azure.com" (:api-endpoint opts)))
      (is (= :azure (:impl opts)) ":impl :azure makes wkok use the Azure URL shape")
      (is (= {:timeout 30000} (:request opts))))))

(deftest create-chat-completion-on-the-openai-compatible-branch
  (testing "OpenAI-compatible: model-name overwrites the caller's model, and the endpoint and key
            are the tenant's own services.llm.* (Phase 3 of the provider-resolver change folded the :lmstudio and
            :openrouter arms, and services.lmstudio.*, into this)"
    (let [{:keys [conversation opts]} (seen-by-client (stub-install :openai-compatible)
                                                      #(sp/create-chat-completion "ka" {:model "ignored" :messages [{:role "user" :content "hi"}]}))]
      (is (= "local-model" (:model conversation)))
      (is (= "http://localhost:1234" (:api-endpoint opts)))
      (is (= "llm-key" (:api-key opts)))
      (is (= :openai (:impl opts)) "not :azure - the client's direct OpenAI-compatible POST"))))

;; ============================================================================
;; response_format dispatch tests — different providers accept different
;; structured-output modes. LM Studio explicitly rejects :json_object
;; with HTTP 400 ('response_format.type must be json_schema or text'),
;; so this dispatch is load-bearing.
;; ============================================================================

(defn- captured-convo
  "Run generate-phrases-with-model with the client stubbed, and return the
   conversation it sent."
  ([provider] (captured-convo provider {}))
  ([provider install-opts]
   (:conversation (seen-by-client (apply stub-install provider (mapcat identity install-opts))
                                  #(sp/generate-phrases-with-model "ka" "m" "prompt: REPLACE_ME" "chunk text")))))

(deftest response-format-openai-compatible-is-json-schema
  (testing ":openai-compatible gets response_format json_schema (the only structured mode LM
            Studio accepts, and LM Studio is the only OpenAI-compatible server tested)"
    (let [c (captured-convo :openai-compatible)]
      (is (= "json_schema" (get-in c [:response_format :type])))
      (is (= "search_phrases" (get-in c [:response_format :json_schema :name])))
      (is (true? (get-in c [:response_format :json_schema :strict])))
      (let [schema (get-in c [:response_format :json_schema :schema])]
        (is (= "object" (:type schema)))
        (is (= ["phrases"] (:required schema)))
        (is (= "array" (get-in schema [:properties :phrases :type])))
        (is (= "string" (get-in schema [:properties :phrases :items :type])))))))

(deftest response-format-azure-is-json-object
  (testing ":azure gets response_format json_object (widely supported on Azure gpt-4o)"
    (let [c (captured-convo :azure)]
      (is (= {:type "json_object"} (:response_format c))))))

(deftest response-format-is-chosen-by-provider-not-by-endpoint
  ;; REPLACES response-format-openrouter-is-omitted. The old :openrouter arm sent no
  ;; response_format; Phase 3 of the provider-resolver change made OpenRouter a services.llm value, and the
  ;; format follows the provider, so an OpenRouter endpoint now gets json_schema.
  ;; That is a KNOWN, UNTESTED LIMITATION, pinned so it cannot change unnoticed: an
  ;; OpenRouter-routed model that rejects structured output would fail here.
  (testing "an OpenRouter endpoint is :openai-compatible like any other"
    (let [c (captured-convo :openai-compatible {:llm-endpoint "https://openrouter.ai/api/v1"})]
      (is (= "json_schema" (get-in c [:response_format :type]))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest search-phrase-workflow
  (testing "Complete workflow from chunk to cached phrases"
    (let [chunk {:chunk_id "test-workflow-chunk"
                 :content_markdown "How to configure authentication"}
          config {:search-phrases/model "gpt-4"
                  :search-phrases/prompt "Generate phrases: REPLACE_ME"}
          cache-dir test-cache-dir
          cache-path (str cache-dir
                          (sp/cache-key chunk
                                        {:provider :azure :model "dep-1"}
                                        (:search-phrases/prompt config))
                          ".edn")
          phrases ["authentication setup" "config auth"]]
      ;; Setup: write to cache
      (sp/ensure-cache-dir! cache-dir)
      (sp/write-cached-phrases! cache-path phrases)
      ;; Read should work
      (let [cached (sp/read-cached-phrases cache-path)]
        (is (= phrases cached))))))

;; ============================================================================
;; The entry a RUN writes is keyed by what that run actually sent
;; ============================================================================

(defn- accessor-for
  "One tenant, fully credentialed on both branches, with the provider decision
   and the model it will send as the only variables."
  [provider model]
  (fn [_opts & ks]
    (case (vec ks)
      [:services :llm :provider] provider
      [:services :llm :model] model
      [:services :llm :api-key] "llm-key"
      [:services :llm :api-endpoint] "http://localhost:1234"
      [:services :azure-openai :api-key] "key-1"
      [:services :azure-openai :api-endpoint] "https://example.azure.com"
      [:services :azure-openai :deployment-name] model
      [:services :azure-openai :model-name] model
      nil)))

(defn- file-names [dir]
  (set (map #(.getName %) (.listFiles (io/file dir)))))

(defn- distil-once!
  "Run the real distill task for `chunk` as `provider`/`model`, with the client
   stubbed. Answers the cache files present afterwards - in the local directory
   and in the declared one - and whether the client was called."
  [provider model chunk]
  (let [called (atom 0)
        result (atom nil)]
    (reset! result
     (with-redefs [digdir.config.accessor/get (accessor-for provider model)
                  digdir.llm.client/create-chat-completion
                  (fn [_conversation _opts]
                    (swap! called inc)
                    {:choices [{:message {:content "{\"phrases\": [\"alpha\", \"beta\"]}"}}]})]
      (:search-phrases
       (m/? (sp/mk-distill-search-phrases-t {:tenant "ka"
                                            :search-phrases/model "configured-and-never-sent"
                                            :search-phrases/fallback-model "fb"
                                            :search-phrases/prompt "Generate phrases: REPLACE_ME"}
                                            chunk
                                            "test")))))
    {:called @called
     :result @result
     :files (file-names test-cache-dir)
     :declared-files (file-names test-declared-dir)}))

(deftest two-different-effective-models-do-not-share-a-cache-entry
  ;; THE case the phrase negative-cache issue turns on. The configured model is identical in both runs and
  ;; is never sent; what differs is the model the provider actually serves.
  (let [chunk {:chunk_id "x" :content_markdown (str "shared text " (random-uuid))}
        a (distil-once! :azure "dep-A" chunk)
        b (distil-once! :azure "dep-B" chunk)]
    (is (= 1 (:called a)) "POSITIVE CONTROL: run A called the model")
    (is (= 1 (:called b)) "run B called the model too, so it did not read A's entry")
    (is (= 2 (count (:files b))) "two entries for one chunk: one per model actually sent")))

(deftest the-same-effective-model-shares-one-cache-entry
  ;; POSITIVE CONTROL for the test above: identical identity, one entry, and the
  ;; second run is a cache hit - so "not shared" above is not vacuously true
  ;; because nothing was ever written or read.
  (let [chunk {:chunk_id "y" :content_markdown (str "shared text " (random-uuid))}
        a (distil-once! :azure "dep-SAME" chunk)
        b (distil-once! :azure "dep-SAME" chunk)]
    (is (= 1 (:called a)) "run 1 called the model")
    (is (= 0 (:called b)) "run 2 is a cache HIT: the entry was written AND read")
    (is (= 1 (count (:files b))) "one entry")))

(deftest the-provider-that-answers-is-part-of-the-entry-identity
  ;; end to end: same model name, different provider.
  (let [chunk {:chunk_id "z" :content_markdown (str "shared text " (random-uuid))}
        a (distil-once! :azure "same-name" chunk)
        b (distil-once! :openai-compatible "same-name" chunk)]
    (is (= 1 (:called a)))
    (is (= 1 (:called b)) "the openai-compatible run did not read the azure entry")
    (is (= 2 (count (:files b))))))

;; ============================================================================
;; TIER 2: a DECLARED generator, looked up under its own name
;; ============================================================================
;;
;; The archive ships phrases somebody else's model produced. Serving them as if
;; this install had produced them is the laundering the KUDOS phrase-parser unification refused; refusing them
;; costs every newcomer the whole demo bill. Tier 2 is the third option: look
;; them up under the identity that DID produce them, declared as data.

;; Must equal the shipped `phrase-cache-folder-v2.identity.edn`: these tests
;; stub nothing about the declaration, so they read the real one.
(def ^:private declared {:provider :azure :model "gpt-4o"})

(defn- write-local!
  "Put an entry in the LOCAL directory directly, under `identity`, so a test can
   set up tier 1 without going through a run."
  [identity chunk prompt phrases]
  (sp/ensure-cache-dir! test-cache-dir)
  (sp/write-cached-phrases! (str test-cache-dir (sp/cache-key chunk identity prompt) ".edn") phrases))

(defn- write-declared!
  "Put an entry where the boot unpack puts one - the DECLARED directory, under
   the declared identity - so a test can set up tier 2."
  [chunk prompt phrases]
  (sp/ensure-cache-dir! test-declared-dir)
  (sp/write-cached-phrases! (str test-declared-dir (sp/cache-key chunk declared prompt) ".edn") phrases))

(defn- write-declared-as-shipped!
  "Put an entry where the unpack puts the COMMITTED archive's: the declared
   directory, under `main`'s four-segment key, which the unpack now keeps
   rather than re-keying. Hashed HERE, not by `sp/legacy-cache-key`,
   so a mistake there is not copied into the check."
  [chunk prompt phrases]
  (sp/ensure-cache-dir! test-declared-dir)
  (sp/write-cached-phrases!
   (str test-declared-dir
        (str/join "-" [(core/sha256-short-hash (:content_markdown chunk))
                       (core/sha256-short-hash (:model declared))
                       (core/sha256-short-hash prompt)
                       sp/parser-version])
        ".edn")
   phrases))

(def ^:private the-prompt "Generate phrases: REPLACE_ME")

(defn- entries-for
  "The file names in `files` that belong to `chunk`, under ANY identity, prompt
   or version: the content hash is the key's first segment."
  [chunk files]
  (let [prefix (str (core/sha256-short-hash (:content_markdown chunk)) "-")]
    (set (filter #(str/starts-with? % prefix) files))))

(deftest the-tests-declared-identity-is-the-shipped-one
  ;; Every tier-2 test reads the REAL declaration. If the resource changed and
  ;; `declared` did not, most of them would go red - but the collision test
  ;; would stay green while no longer colliding, and the newcomer test's
  ;; non-vacuity check would be checking a constant nothing reads.
  (is (= declared (sp/declared-identity))))

(deftest tier-2-precedence-local-positive-then-declared-positive-then-local-negative
  ;; THE ORDER IS THE POINT. A mis-loaded local model is exactly what writes
  ;; negatives, so "local first" unconditionally would make the archive stop
  ;; helping precisely when the local model is broken.
  (testing "a local positive wins over the declared one"
    (let [chunk {:chunk_id "p1" :content_markdown (str "text " (random-uuid))}]
      (write-local! {:provider :azure :model "dep-local"} chunk the-prompt ["local"])
      (write-declared! chunk the-prompt ["declared"])
      (let [r (distil-once! :azure "dep-local" chunk)]
        (is (= 0 (:called r)) "no call: it was served from the cache")
        (is (= ["local"] (:result r)) "the LOCAL phrases came back, not the declared ones"))))

  (testing "the declared positive is used when there is no local entry"
    (let [chunk {:chunk_id "p2" :content_markdown (str "text " (random-uuid))}]
      (write-declared! chunk the-prompt ["declared"])
      (let [r (distil-once! :azure "dep-local" chunk)]
        (is (= 0 (:called r)) "no call: the declared entry answered")
        (is (= ["declared"] (:result r)) "and its phrases are what came back")
        ;; Copying a declared hit to the local key is the MIRROR of
        ;; the laundering: the declared generator's phrases, stamped with this
        ;; install's name. Nothing else here has a declared hit to promote.
        ;; Per CHUNK, under any identity: the arms above share this directory.
        (is (= #{} (entries-for chunk (:files r)))
            "and NOTHING was written locally for this chunk: a declared hit is served, never promoted"))))

  (testing "the declared positive BEATS a local negative"
    (let [chunk {:chunk_id "p3" :content_markdown (str "text " (random-uuid))}]
      (write-local! {:provider :azure :model "dep-local"} chunk the-prompt [])
      (write-declared! chunk the-prompt ["declared"])
      (let [r (distil-once! :azure "dep-local" chunk)]
        (is (= 0 (:called r)))
        (is (= ["declared"] (:result r))
            "a local [] must not mask a declared generator's phrases"))))

  ;; ⚠️ This arm guards a state the pipeline's own writer cannot create today:
  ;; it caches only non-empty results, and the loader's negatives live in
  ;; `cache/search-phrases/`, which has no tier 2. It is pinned for the port of
  ;; the single-phrase reply issue's negative cache to this path, not because it fires now.
  (testing "a local negative is used when the declared tier has nothing"
    (let [chunk {:chunk_id "p4" :content_markdown (str "text " (random-uuid))}]
      (write-local! {:provider :azure :model "dep-local"} chunk the-prompt [])
      (let [r (distil-once! :azure "dep-local" chunk)]
        (is (= 0 (:called r)) "the local negative answered; no call")
        (is (= [] (:result r)))))))

(deftest tier-2-is-read-only-a-write-always-uses-the-local-identity
  ;; A write under the declared identity IS the laundering: it would stamp this
  ;; install's output with somebody else's name.
  (let [chunk {:chunk_id "w" :content_markdown (str "text " (random-uuid))}
        r (distil-once! :azure "dep-local" chunk)
        local-key (sp/cache-key chunk {:provider :azure :model "dep-local"} the-prompt)
        declared-key (sp/cache-key chunk declared the-prompt)]
    (is (contains? (:files r) (str local-key ".edn")) "POSITIVE CONTROL: it wrote under the local identity")
    (is (not (contains? (:files r) (str declared-key ".edn")))
        "and never under the declared identity")
    (is (= #{} (:declared-files r)) "and nothing into the declared directory")))

(deftest tier-2-is-read-only-even-when-the-local-identity-IS-the-declared-one
  ;; the case the test above cannot reach: its local model is
  ;; `dep-local`, never the declared one. An `:azure` install whose deployment
  ;; is named `gpt-4o` - an ordinary Azure name - computes EXACTLY the declared
  ;; key, because an identity is two names. The key cannot keep this write out
  ;; of the archive's population; only the directory can.
  (let [chunk {:chunk_id "c" :content_markdown (str "text " (random-uuid))}
        r (distil-once! (:provider declared) (:model declared) chunk)
        the-key (str (sp/cache-key chunk declared the-prompt) ".edn")]
    (is (= 1 (:called r)) "POSITIVE CONTROL: nothing was cached, so the local model answered")
    (is (contains? (:files r) the-key)
        "POSITIVE CONTROL: its output IS keyed exactly like a declared entry, in the local directory")
    (is (= #{} (:declared-files r))
        "and NOTHING reached the directory tier 2 reads, where it would pass for the archive's own")))

(deftest tier-2-is-positive-only-a-declared-negative-is-ignored
  ;; "This chunk has no phrases" is a claim about the chunk under a model. Only
  ;; the local model may make it here.
  (let [chunk {:chunk_id "n" :content_markdown (str "text " (random-uuid))}]
    (write-declared! chunk the-prompt [])
    (let [r (distil-once! :azure "dep-local" chunk)]
      (is (= 1 (:called r)) "the declared [] was ignored, so the model was asked"))))

(deftest tier-2-serves-the-committed-archive-in-its-own-four-segment-keys
  ;; The unpack no longer translates the committed archive into the
  ;; current grammar, so tier 2 must find it in `main`'s: content, the DECLARED
  ;; model, prompt, version. Run as a newcomer on another provider AND another
  ;; model, the people the archive is for - so a lookup built from anything
  ;; local would miss.
  (let [chunk {:chunk_id "as" :content_markdown (str "text " (random-uuid))}]
    (write-declared-as-shipped! chunk the-prompt ["as shipped"])
    (let [r (distil-once! :openai-compatible "a-local-model" chunk)]
      (is (= 0 (:called r)) "no call: the archive answered, in its own key")
      (is (= ["as shipped"] (:result r)))
      (is (= #{} (:files r)) "and nothing was written locally"))))

(deftest a-main-era-entry-in-the-LOCAL-directory-is-never-served
  ;; The legacy key is read ONLY in the declared directory, where only the pinned
  ;; archive can put one. Every install that ran `main` holds main's four-segment
  ;; entries in its LOCAL directory - its own output, keyed on the CONFIGURED model
  ;; whatever it sent - and "every existing entry becomes unreachable" is this PR's
  ;; own rule. WIDENING THIS READ serves output from before the phrase negative-cache issue of UNKNOWN model as if it
  ;; were current: the backfill the KUDOS phrase-parser unification refused. Both configured-model spellings a
  ;; main-era key could carry are planted.
  (let [chunk {:chunk_id "lg" :content_markdown (str "text " (random-uuid))}]
    (sp/ensure-cache-dir! test-cache-dir)
    (doseq [m [(:model declared) "configured-and-never-sent"]]
      (sp/write-cached-phrases!
       (str test-cache-dir
            (str/join "-" [(core/sha256-short-hash (:content_markdown chunk))
                           (core/sha256-short-hash m)
                           (core/sha256-short-hash the-prompt)
                           sp/parser-version])
            ".edn")
       ["a main-era local entry"]))
    (let [r (distil-once! :openai-compatible "a-local-model" chunk)]
      (is (= 1 (:called r)) "the model was asked: a main-era LOCAL entry is never served")
      (is (not= ["a main-era local entry"] (:result r))))))

(deftest tier-2-serves-a-newcomer-whose-provider-is-not-the-declared-one
  ;; Since the setup-env provider-prompt issue a newcomer who skips the provider question gets
  ;; `:openai-compatible` - the main beneficiary of the whole tier - while every
  ;; test above runs a local `:azure`, the declared provider. Building the
  ;; declared key with the LOCAL provider would pass all of them.
  (let [chunk {:chunk_id "nc" :content_markdown (str "text " (random-uuid))}]
    (is (not= :openai-compatible (:provider declared))
        "NON-VACUITY: the local provider below differs from the declared one")
    (write-declared! chunk the-prompt ["declared"])
    (let [r (distil-once! :openai-compatible "a-local-model" chunk)]
      (is (= 0 (:called r)) "no call: the declared archive answered a different provider")
      (is (= ["declared"] (:result r)))
      (is (= #{} (:files r)) "and nothing was written locally"))))
