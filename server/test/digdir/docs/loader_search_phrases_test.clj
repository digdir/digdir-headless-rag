(ns digdir.docs.loader-search-phrases-test
  "The KUDOS loader's search-phrase path uses the canonical cache key
   AND the canonical parser from `digdir.docs.pipeline.search-phrases`.

   The two travel together. The key's `parser-version` says which parser
   produced an entry; a key that claims \"v2\" over an entry parsed by the old
   last-line parser (\"took the LAST line unconditionally ... 99.9% of cached
   responses were captured meta-commentary\", per `parse-phrases-response`)
   would launder v1 output as v2. So the loader parses with
   `parse-phrases-response` and keys with `cache-key`.

   An empty result is cached only when it is a fact about the CHUNK: the
   primary model answered in full and the parser found no phrases. That
   negative entry lives under the same key, so it names its parser version
   like any other entry. An empty result that a failure produced is a fact
   about the MOMENT, and is never cached.

   Every case drives the loader's real `mk-distill-search-phrases-t` with the
   chat client and the cache write stubbed; the chunk content is unique per
   case so the cache lookup always misses."
  (:require [clojure.string :as str]
            [digdir.config.accessor]
            [clojure.test :refer [deftest is]]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.search-phrases :as sp]
            [missionary.core :as m]))

(def ^:private prompt "Search phrases for this chunk, comma separated on the last line.\n<chunk>\nREPLACE_ME\n</chunk>")

(defn- reply
  "A chat-completion response, in the shape `digdir.llm.client` returns. A
   reply that ran to completion has `finish_reason` \"stop\"."
  ([content] (reply content "stop"))
  ([content finish-reason]
   {:choices [{:message {:role "assistant" :content content}
               :finish_reason finish-reason}]}))

(def ^:private tenant-identity
  "The identity the loader's PRIMARY call sends for this tenant: the provider
   decision, and the model the provider puts on the wire. The key names both
  so a test that drives the real distill task
   must supply a provider decision - there is no key without one."
  {:provider :azure :model "dep-1"})

(defn- stub-accessor
  "One fully credentialed tenant on the Azure branch, so the decision and the
   model sent are the fixed part and the responses are the variable."
  [_opts & ks]
  (case (vec ks)
    [:services :llm :provider] :azure
    [:services :azure-openai :deployment-name] (:model tenant-identity)
    [:services :azure-openai :model-name] (:model tenant-identity)
    [:services :azure-openai :api-key] "key-1"
    [:services :azure-openai :api-endpoint] "https://example.azure.com"
    nil))

(defn- distill
  "Run the loader's distill task for a fresh chunk. `responses` maps model →
   a response map, or a Throwable to throw. Returns what the loader produced,
   which models it called in order, the opts each call passed, and
   what it wrote to the cache."
  [responses]
  (let [chunk {:content_markdown (str "chunk-" (random-uuid))}
        kview {:search-phrases/model "primary-model"
               :search-phrases/fallback-model "fallback-model"
               :search-phrases/prompt prompt
               :tenant "loader-test-tenant"}
        calls (atom [])
        call-opts (atom [])
        writes (atom [])
        real-spit spit]
    ;; `with-redefs` is global, so the stub sees EVERY `spit` in the JVM while
    ;; it is in place — telemetry's background aggregate writer included.
    ;; Capture only the loader's cache writes; pass the rest through.
    (with-redefs [digdir.config.accessor/get stub-accessor
                  loader/create-chat-completion
                  (fn [_tenant convo & [opts]]
                    (swap! calls conj (:model convo))
                    (swap! call-opts conj opts)
                    (let [r (get responses (:model convo))]
                      (if (instance? Throwable r) (throw r) r)))
                  spit (fn [f content & opts]
                         (if (str/starts-with? (str f) "cache/search-phrases/")
                           (swap! writes conj {:path (str f) :content content})
                           (apply real-spit f content opts)))]
      (let [result (m/? (loader/mk-distill-search-phrases-t kview chunk))]
        {:chunk chunk :phrases (:search-phrases result) :calls @calls :opts @call-opts :writes @writes}))))

;; ---------------------------------------------------------------------------
;; The key
;; ---------------------------------------------------------------------------

(deftest the-loader-writes-under-the-canonical-cache-key
  ;; The comment above the old inline key said it "Mirrors
  ;; digdir.docs.pipeline.search-phrases/cache-key"; it omitted
  ;; parser-version. The property the comment asserted is now the test.
  (let [{:keys [chunk writes]} (distill {"primary-model" (reply "alpha beta, gamma delta, epsilon zeta")})]
    (is (= 1 (count writes)) "absolute: exactly one cache write")
    (is (= (str "cache/search-phrases/" (sp/cache-key chunk tenant-identity prompt) ".edn")
           (:path (first writes))))
    (is (str/ends-with? (:path (first writes)) (str "-" sp/parser-version ".edn"))
        "the key names the parser that produced the entry")))

;; ---------------------------------------------------------------------------
;; The parser
;; ---------------------------------------------------------------------------

(deftest meta-commentary-after-the-phrase-line-is-not-captured
  ;; The exact v1 failure: the model appends commentary after the phrase line,
  ;; and a last-line parser stores the commentary as the phrases.
  (let [response (reply "alpha beta, gamma delta, epsilon zeta\n\n3. **keywords only**.")
        {:keys [phrases writes]} (distill {"primary-model" response})]
    (is (= ["alpha beta" "gamma delta" "epsilon zeta"] phrases))
    (is (= (sp/parse-phrases-response response) phrases) "exactly what the canonical parser produces")
    (is (= (pr-str phrases) (:content (first writes))) "and that is what is cached")))

(deftest a-clean-phrase-line-parses-the-same-as-before
  ;; The GREEN case: a well-behaved response is unaffected by the change.
  (let [{:keys [phrases calls]} (distill {"primary-model" (reply "alpha beta, gamma delta")})]
    (is (= ["alpha beta" "gamma delta"] phrases))
    (is (= ["primary-model"] calls))))

;; ---------------------------------------------------------------------------
;; When the fallback model runs, and what is cached — pinned, because it
;; changes output and LLM spend
;; ---------------------------------------------------------------------------

(deftest an-error-response-goes-to-the-fallback-model
  ;; No :choices — the shape a provider returns when it rejects the request.
  (let [{:keys [phrases calls]} (distill {"primary-model" {:error "Unexpected endpoint or method"}
                                          "fallback-model" (reply "from fallback, second phrase")})]
    (is (= ["primary-model" "fallback-model"] calls))
    (is (= ["from fallback" "second phrase"] phrases))))

(deftest a-thrown-call-goes-to-the-fallback-model
  (let [{:keys [phrases calls]} (distill {"primary-model" (ex-info "HTTP 500" {:status 500})
                                          "fallback-model" (reply "from fallback, second phrase")})]
    (is (= ["primary-model" "fallback-model"] calls))
    (is (= ["from fallback" "second phrase"] phrases))))

(deftest the-fallback-names-its-model-and-the-primary-does-not
  ;; The primary's model is overwritten with the provider's own, as it
  ;; always was: the loader names no model for it. The fallback names its own
  ;; (`{:model fallback-model}`), and the tenant's resolved provider keeps it -
  ;; where the loader's OpenRouter route used to take three fallback names
  ;; instead. `provider-selector-pins-test` pins what `{:model ...}` does; this
  ;; pins that the distill task is what sends it, and only for the fallback.
  (let [{:keys [calls opts]} (distill {"primary-model" (ex-info "HTTP 500" {:status 500})
                                       "fallback-model" (reply "from fallback, second phrase")})
        [primary fallback] opts]
    (is (= ["primary-model" "fallback-model"] calls) "POSITIVE CONTROL: both calls were made")
    (is (nil? (:model primary)) "the primary names no model: the provider's own is sent")
    (is (= "fallback-model" (:model fallback)) "the fallback names its own model")))

(deftest a-reply-with-no-content-does-not-invoke-the-fallback-and-is-not-cached
  ;; The old last-line parser threw a NullPointerException on nil content,
  ;; which the loader's catch turned into a fallback call — a fallback by
  ;; accident. The canonical parser reads nil content as "no phrases": no
  ;; fallback, and (as in the pipeline) nothing frozen into the cache, so the
  ;; next run retries.
  (let [{:keys [phrases calls writes]} (distill {"primary-model" (reply nil)
                                                 "fallback-model" (reply "from fallback, second phrase")})]
    (is (= ["primary-model"] calls))
    (is (= [] phrases))
    (is (empty? writes))))

(deftest an-unparseable-answer-from-the-primary-is-cached-as-a-negative-entry
  ;; A single phrase with no comma, or a line of prose: the canonical
  ;; heuristic finds no phrase list. The old parser cached the whole last line
  ;; as a "phrase". This caches `[]`: no phrases — under the canonical key, so
  ;; the entry names the parser that judged it.
  (doseq [content ["Altinn" "I could not find any keywords in this text"]]
    (let [{:keys [chunk phrases calls writes]} (distill {"primary-model" (reply content)})]
      (is (= [] phrases) content)
      (is (= ["primary-model"] calls) content)
      (is (= [{:path (str "cache/search-phrases/" (sp/cache-key chunk tenant-identity prompt) ".edn")
               :content "[]"}]
             writes)
          (str content ": one negative entry, under the canonical key")))))

;; ---------------------------------------------------------------------------
;; ACROSS runs — which empty results are cached, and which are asked again
;; (the KUDOS phrase-parser unification review, the single-phrase reply issue)
;; ---------------------------------------------------------------------------
;;
;; The cost of an uncached empty result is decided by repetition: the same
;; chunk is requested again on every run. So an empty result is cached — as a
;; negative entry, `[]` — exactly when it is a fact about the chunk: the
;; primary model answered in full and the parser found no phrases. An empty
;; result that a failure produced is a fact about the moment, and is asked
;; again. The cache here is REAL, not stubbed, so a later run genuinely reads
;; what an earlier one wrote — the clean-list case is the positive control.

(defn- distill-runs
  "Run the distill task on ONE chunk once per entry of `parser-versions`, with
   a real cache in between; each run sees `sp/parser-version` as that entry.
   Returns, per run, the models called and the phrases produced. The cache
   file under every version used is removed afterwards."
  [responses parser-versions]
  (let [chunk {:content_markdown (str "chunk-" (random-uuid))}
        kview {:search-phrases/model "primary-model"
               :search-phrases/fallback-model "fallback-model"
               :search-phrases/prompt prompt
               :tenant "loader-test-tenant"}
        calls (atom [])
        cache-files (mapv (fn [v]
                            (with-redefs [sp/parser-version v
                                          digdir.config.accessor/get stub-accessor]
                              (java.io.File. (str "cache/search-phrases/" (sp/cache-key chunk tenant-identity prompt) ".edn"))))
                          (distinct parser-versions))
        run-once (fn [v]
                   (reset! calls [])
                   (with-redefs [sp/parser-version v]
                     (let [result (m/? (loader/mk-distill-search-phrases-t kview chunk))]
                       {:calls @calls :phrases (:search-phrases result)})))]
    (try
      (with-redefs [digdir.config.accessor/get stub-accessor
                    loader/create-chat-completion
                    (fn [_tenant convo & _]
                      (swap! calls conj (:model convo))
                      (let [r (get responses (:model convo))]
                        (if (instance? Throwable r) (throw r) r)))]
        (mapv run-once parser-versions))
      (finally (doseq [^java.io.File f cache-files] (.delete f))))))

(defn- distill-twice
  "Two runs of the distill task on ONE chunk, under the current parser."
  [responses]
  (distill-runs responses [sp/parser-version sp/parser-version]))

(deftest across-runs-a-clean-result-is-served-from-the-cache
  ;; POSITIVE CONTROL: proves the second run really reads what the first wrote,
  ;; so an empty second-run call list below means "cache hit", not "no cache".
  (let [[run1 run2] (distill-twice {"primary-model" (reply "alpha beta, gamma delta")})]
    (is (= ["primary-model"] (:calls run1)))
    (is (= [] (:calls run2)) "run 2 is a cache hit")
    (is (= ["alpha beta" "gamma delta"] (:phrases run2)))))

(deftest across-runs-an-unparseable-answer-is-not-requested-again
  ;; DETERMINISTIC EMPTINESS. The primary answered in full and the
  ;; parser found no phrase list: a fact about this chunk, so run 2 is a cache
  ;; hit on the negative entry. Each of these used to cost a primary call on
  ;; EVERY run.
  (doseq [content ["Altinn" "I could not find any keywords in this text"]]
    (let [[run1 run2] (distill-twice {"primary-model" (reply content)})]
      (is (= ["primary-model"] (:calls run1)) content)
      (is (= [] (:calls run2)) (str content ": run 2 is a cache hit on the negative entry"))
      (is (= [] (:phrases run2)) content))))

(deftest across-runs-a-primary-failure-is-never-negative-cached
  ;; TRANSIENT FAILURE — the class that must never be cached. The
  ;; primary threw, or came back with no :choices: a rate limit, a network
  ;; blip, a content filter that may be reconfigured. The fallback's reply is
  ;; the very one that IS cached when the primary gives it (above), but here it
  ;; exists only because the primary failed at that moment. Caching it would
  ;; freeze that failure forever, so BOTH models are asked again on run 2.
  (doseq [[label primary] [["no :choices" {:error "content_filter"}]
                           ["throws" (ex-info "HTTP 400" {:status 400})]]]
    (let [[run1 run2] (distill-twice {"primary-model" primary
                                      "fallback-model" (reply "Altinn")})]
      (is (= ["primary-model" "fallback-model"] (:calls run1)) label)
      (is (= ["primary-model" "fallback-model"] (:calls run2)) (str label ": both models asked again on run 2"))
      (is (= [] (:phrases run2)) label))))

(deftest across-runs-a-fallback-that-answers-with-phrases-is-cached
  ;; PRESERVATION. The primary failed and the
  ;; fallback returned a phrase list: that list IS cached - the chunk is not
  ;; asked again on run 2. Only the fallback's EMPTY answer is never cached
  ;; (across-runs-a-primary-failure-is-never-negative-cached). A fix for the truncated phrase-reply caching issue
  ;; that writes only when the primary `settled?` would silently stop caching
  ;; this, and cost both models on every run.
  (doseq [[label primary] [["no :choices" {:error "content_filter"}]
                           ["throws" (ex-info "HTTP 400" {:status 400})]]]
    (let [[run1 run2] (distill-twice {"primary-model" primary
                                      "fallback-model" (reply "alpha, beta, gamma")})]
      (is (= ["primary-model" "fallback-model"] (:calls run1)) label)
      (is (= [] (:calls run2)) (str label ": run 2 is a cache hit on the fallback's phrases"))
      (is (= ["alpha" "beta" "gamma"] (:phrases run2)) label))))

(deftest across-runs-a-reply-with-no-content-is-not-negative-cached
  ;; Nil or blank content under :choices is not an answer. It is what an
  ;; output-side content filter returns, or a reasoning model that spent its
  ;; budget before the visible answer. Asked again on run 2.
  (doseq [content [nil "" "  \n "]]
    (let [[run1 run2] (distill-twice {"primary-model" (reply content)})]
      (is (= ["primary-model"] (:calls run1)) (pr-str content))
      (is (= ["primary-model"] (:calls run2)) (str (pr-str content) ": asked again on run 2")))))

(deftest across-runs-a-reply-that-did-not-finish-is-not-negative-cached
  ;; Only a reply that ran to completion — `finish_reason` "stop" — can say the
  ;; chunk has no phrases. One cut short by a content filter or the token
  ;; limit, or that does not say how it ended, says nothing about the chunk.
  ;; The content is the reply that IS cached when it finishes (above).
  (doseq [finish-reason ["content_filter" "length" nil]]
    (let [[run1 run2] (distill-twice {"primary-model" (reply "Altinn" finish-reason)})]
      (is (= ["primary-model"] (:calls run1)) (pr-str finish-reason))
      (is (= ["primary-model"] (:calls run2)) (str (pr-str finish-reason) ": asked again on run 2")))))

(deftest across-runs-a-reply-cut-short-that-still-parses-is-cached-as-if-complete
  ;; PRE-EXISTING, PINNED HERE, NOT FIXED HERE (found in review; filed as
  ;; the truncated phrase-reply caching issue). The rule above keeps an interrupted reply out of the
  ;; NEGATIVE cache, but not out of the positive one: a reply cut short can still
  ;; parse to a list — a PARTIAL one, if the token limit cut it — and that list
  ;; is cached like a complete answer, so the truncated list is frozen. The v1
  ;; loader did the same. Not caching it trades that for a per-run cost on chunks
  ;; that are cut short every time: the same trade as the failure rows, parked
  ;; with them. A nil finish reason is its own label because it does not say the
  ;; reply was interrupted, only that the provider did not say. Fixing this turns
  ;; the test red on purpose.
  (doseq [finish-reason ["length" "content_filter" nil]]
    (let [[run1 run2] (distill-twice {"primary-model" (reply "alpha beta, gamma delta" finish-reason)})]
      (is (= ["primary-model"] (:calls run1)) (pr-str finish-reason))
      (is (= [] (:calls run2)) (str (pr-str finish-reason) ": run 2 is a cache hit on the list as it was cut"))
      (is (= ["alpha beta" "gamma delta"] (:phrases run2)) (pr-str finish-reason)))))

(deftest a-negative-entry-binds-only-the-parser-version-that-wrote-it
  ;; A negative entry is a claim made BY A PARSER: "this parser finds no
  ;; phrases here". If it outlived that parser, no later parser could ever
  ;; revisit the chunk — the shape of the key that could not say which parser
  ;; wrote an entry. Run 2 is the control: under the version that wrote
  ;; it, the entry IS honoured. Run 3, under a new parser version, asks again.
  (let [v sp/parser-version
        [run1 run2 run3] (distill-runs {"primary-model" (reply "Altinn")} [v v "v-next-parser"])]
    (is (= ["primary-model"] (:calls run1)))
    (is (= [] (:calls run2)) "control: honoured under the parser version that wrote it")
    (is (= ["primary-model"] (:calls run3)) "a new parser version asks again")))
