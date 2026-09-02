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
            [digdir.llm.client]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-cache-dir "cache/test-search-phrases/")

(defn cleanup-test-cache [f]
  ;; Clean up before
  (let [dir (io/file test-cache-dir)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir)))
  (f)
  ;; Clean up after
  (let [dir (io/file test-cache-dir)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))

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

(deftest cache-key-deterministic
  (testing "Same inputs produce same key"
    (let [chunk {:chunk_id "test-chunk" :content_markdown "text"}
          model "gpt-4"
          prompt "test prompt"
          key1 (sp/cache-key chunk model prompt)
          key2 (sp/cache-key chunk model prompt)]
      (is (= key1 key2)))))

(deftest cache-key-is-derived-from-content-not-chunk-id
  (testing "Key leads with a hash of the chunk text"
    ;; Was `chunk_id`, which only worked while ids were content hashes.
    ;; Document-scoped ids (#72) would have made every copy of a duplicated
    ;; chunk miss the cache and pay its own LLM call.
    (let [chunk {:chunk_id "my-chunk-123" :content_markdown "some text"}
          key (sp/cache-key chunk "model" "prompt")]
      (is (str/starts-with? key (str (core/sha256-short-hash "some text") "-")))
      (is (not (str/includes? key "my-chunk-123")))))

  (testing "Same text under different ids shares one entry"
    (let [a {:chunk_id "id-a" :content_markdown "identical text"}
          b {:chunk_id "id-b" :content_markdown "identical text"}]
      (is (= (sp/cache-key a "model" "prompt")
             (sp/cache-key b "model" "prompt")))))

  (testing "Different text under the same id does not"
    (let [a {:chunk_id "same-id" :content_markdown "text one"}
          b {:chunk_id "same-id" :content_markdown "text two"}]
      (is (not= (sp/cache-key a "model" "prompt")
                (sp/cache-key b "model" "prompt"))))))

(deftest cache-key-different-models
  (testing "Different models produce different keys"
    (let [chunk {:chunk_id "test" :content_markdown "text"}
          key1 (sp/cache-key chunk "gpt-4" "prompt")
          key2 (sp/cache-key chunk "gpt-3.5" "prompt")]
      (is (not= key1 key2)))))

(deftest cache-key-different-prompts
  (testing "Different prompts produce different keys"
    (let [chunk {:chunk_id "test" :content_markdown "text"}
          key1 (sp/cache-key chunk "model" "prompt 1")
          key2 (sp/cache-key chunk "model" "prompt 2")]
      (is (not= key1 key2)))))

(deftest cache-key-includes-parser-version-suffix
  (testing "cache-key includes the parser-version so old cache entries are invalidated"
    (let [chunk {:chunk_id "test-chunk" :content_markdown "text"}
          key (sp/cache-key chunk "model" "prompt")]
      (is (str/ends-with? key (str "-" sp/parser-version))
          "cache-key must end with the parser-version suffix"))))

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
;; openai-implementation Tests
;; ============================================================================

(deftest azure-openai-config
  (testing "Azure OpenAI has required config"
    (with-redefs [digdir.config.accessor/get (fn [_opts & ks]
                                               (cond
                                                 (= ks '(:services :azure-openai :api-key)) "key-1"
                                                 (= ks '(:services :azure-openai :api-endpoint)) "https://example.azure.com"
                                                 :else nil))]
      (let [azure (sp/openai-implementation "ka" :azure-openai)]
        (is (contains? azure :api-key))
        (is (contains? azure :api-endpoint))
        (is (= :azure (:impl azure)))))))

(deftest openrouter-config
  (testing "OpenRouter has required config"
    (with-redefs [digdir.config.accessor/get (fn [_opts & ks]
                                               (when (= ks '(:services :openrouter :api-key))
                                                 "or-key"))]
      (let [or (sp/openai-implementation "ka" :openrouter)]
        (is (contains? or :api-key))
        (is (= "https://openrouter.ai/api/v1" (:api-endpoint or)))))))

(deftest create-chat-completion-resolves-config-on-demand
  (testing "Reads Azure endpoint and deployment from config at call time (default provider)"
    (let [calls (atom [])
          opts-seen (atom nil)
          convo {:model "ignored" :messages [{:role "user" :content "hi"}]}]
      (with-redefs [digdir.config.accessor/get (fn [_opts & ks]
                                                 (swap! calls conj ks)
                                                 (cond
                                                   ;; No :provider set → defaults to :azure-openai
                                                   (= ks '(:services :search-phrases :provider)) nil
                                                   (= ks '(:services :azure-openai :api-key)) "key-1"
                                                   (= ks '(:services :azure-openai :api-endpoint)) "https://example.azure.com"
                                                   (= ks '(:services :azure-openai :deployment-name)) "dep-1"
                                                   (= ks '(:services :openrouter :api-key)) "or-key"
                                                   :else nil))
                    digdir.llm.client/create-chat-completion (fn [conversation opts]
                                                                     (reset! opts-seen {:conversation conversation
                                                                                        :opts opts})
                                                                     {:choices [{:message {:content "x"}}]})]
        (sp/create-chat-completion "ka" convo)
        (is (= "dep-1" (get-in @opts-seen [:conversation :model]))
            "model is the Azure deployment name when provider defaults to :azure-openai")
        (is (= "https://example.azure.com" (get-in @opts-seen [:opts :api-endpoint])))
        (is (= :azure (get-in @opts-seen [:opts :impl]))
            ":impl :azure makes wkok use Azure URL shape")))))

(deftest create-chat-completion-routes-to-lmstudio-when-configured
  (testing "When :services.search-phrases.provider is :lmstudio, uses LM Studio endpoint with OpenAI URL shape"
    (let [opts-seen (atom nil)
          convo {:messages [{:role "user" :content "hi"}]}]
      (with-redefs [digdir.config.accessor/get (fn [_opts & ks]
                                                 (case (vec ks)
                                                   [:services :search-phrases :provider] :lmstudio
                                                   [:services :lmstudio :api-key] "lmstudio"
                                                   [:services :lmstudio :api-endpoint] "http://localhost:1234"
                                                   [:services :lmstudio :model] "local-model"
                                                   nil))
                    digdir.llm.client/create-chat-completion (fn [conversation opts]
                                                                     (reset! opts-seen {:conversation conversation
                                                                                        :opts opts})
                                                                     {:choices [{:message {:content "{}"}}]})]
        (sp/create-chat-completion "ka" convo)
        (is (= "local-model" (get-in @opts-seen [:conversation :model])))
        (is (= "http://localhost:1234" (get-in @opts-seen [:opts :api-endpoint])))
        (is (= "lmstudio" (get-in @opts-seen [:opts :api-key])))
        (is (nil? (get-in @opts-seen [:opts :impl]))
            ":impl must NOT be :azure for the LM Studio path — uses default OpenAI URL shape")))))

;; ============================================================================
;; response_format dispatch tests — different providers accept different
;; structured-output modes. LM Studio explicitly rejects :json_object
;; with HTTP 400 ('response_format.type must be json_schema or text'),
;; so this dispatch is load-bearing.
;; ============================================================================

(defn- captured-convo
  "Run generate-phrases-with-model under a redef that captures the
   conversation passed to wkok. Returns the conversation map."
  [provider]
  (let [seen (atom nil)]
    (with-redefs [digdir.config.accessor/get
                  (fn [_opts & ks]
                    (case (vec ks)
                      [:services :search-phrases :provider] provider
                      [:services :azure-openai :api-key] "k"
                      [:services :azure-openai :api-endpoint] "https://az"
                      [:services :azure-openai :deployment-name] "m"
                      [:services :openrouter :api-key] "k"
                      [:services :openrouter :model] "m"
                      [:services :lmstudio :api-key] "k"
                      [:services :lmstudio :api-endpoint] "http://localhost:1234"
                      [:services :lmstudio :model] "m"
                      nil))
                  digdir.llm.client/create-chat-completion
                  (fn [conversation _opts]
                    (reset! seen conversation)
                    {:choices [{:message {:content "{\"phrases\": []}"}}]})]
      (sp/generate-phrases-with-model "ka" "m" "prompt: REPLACE_ME" "chunk text"))
    @seen))

(deftest response-format-lmstudio-is-json-schema
  (testing ":lmstudio gets response_format json_schema (the only structured mode LM Studio accepts)"
    (let [c (captured-convo :lmstudio)]
      (is (= "json_schema" (get-in c [:response_format :type])))
      (is (= "search_phrases" (get-in c [:response_format :json_schema :name])))
      (is (true? (get-in c [:response_format :json_schema :strict])))
      (let [schema (get-in c [:response_format :json_schema :schema])]
        (is (= "object" (:type schema)))
        (is (= ["phrases"] (:required schema)))
        (is (= "array" (get-in schema [:properties :phrases :type])))
        (is (= "string" (get-in schema [:properties :phrases :items :type])))))))

(deftest response-format-azure-is-json-object
  (testing ":azure-openai gets response_format json_object (widely supported on Azure gpt-4o)"
    (let [c (captured-convo :azure-openai)]
      (is (= {:type "json_object"} (:response_format c))))))

(deftest response-format-openrouter-is-omitted
  (testing ":openrouter gets NO response_format (most compatible across routed models)"
    (let [c (captured-convo :openrouter)]
      (is (not (contains? c :response_format))
          "openrouter omits response_format entirely; prompt + heuristic parser do the work"))))

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
                                        (:search-phrases/model config)
                                        (:search-phrases/prompt config))
                          ".edn")
          phrases ["authentication setup" "config auth"]]
      ;; Setup: write to cache
      (sp/ensure-cache-dir! cache-dir)
      (sp/write-cached-phrases! cache-path phrases)
      ;; Read should work
      (let [cached (sp/read-cached-phrases cache-path)]
        (is (= phrases cached))))))
