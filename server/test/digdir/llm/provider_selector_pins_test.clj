(ns digdir.llm.provider-selector-pins-test
  "Where each of the four provider selectors sends a call. These began
   as the before-half of every later comparison, pinning the base branch
   INCLUDING THE DEFECTS; each phase that changes a selector flips the pins it
   names — no others.

     1  the provider decision (services.llm.provider, the boolean as fallback)
                                                   provider/selected-provider
     2  services.search-phrases.provider           RETIRED by Phase 3 of the provider-resolver change
     3  services.self-improvement.provider         RETIRED by Phase 3 of the provider-resolver change
     4  (none — hardcoded)                         RETIRED by Phase 3 of the provider-resolver change

   Since Phase 3, search-phrases, enrichment and the loader call
   `provider/resolve`, so all four sites follow selector 1.

   ## The defect, stated as a pin — INVERTED by Phase 3

   `use-azure-openai-api-false-still-sends-search-phrases-to-azure` passed on
   the base branch because it asserted the wrong behaviour. Its inversion,
   `use-azure-openai-api-false-sends-search-phrases-where-the-switch-says`, is
   the mission's done-condition.

   ## Unset is the interesting case, not false

   On a fresh install nothing sets any selector. Before Phase 3 they disagreed:
   selector 1 unset meant NOT Azure, selectors 2-4 unset meant Azure.
   `on-a-fresh-install-all-four-sites-agree` pins that they no longer do.

   ## Every routing pin has a positive control

   A pin that says \"always Azure\" passes just as well if the capture could only
   ever record Azure. So each selector is also driven down a non-Azure path it
   genuinely supports, and the capture must record THAT too.

   Seams: `digdir.llm.provider-fixtures` — config from a stubbed install whose
   definitions come from the committed snapshot plus runtime registration, and
   calls captured at the two chat leaves."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.llm.provider :as provider]
            [digdir.llm.provider-fixtures :as fx]
            [digdir.skills.builtin.summarization :as summarization]
            [digdir.skills.enrichment.propose-questions :as pq]))

;; ---------------------------------------------------------------------------
;; The install
;; ---------------------------------------------------------------------------

(def ^:private tenant "pin-tenant")

(def ^:private azure-endpoint "https://azure.pin.invalid")
(def ^:private openrouter-endpoint "https://openrouter.ai/api/v1")
(def ^:private llm-endpoint "https://llm.pin.invalid")

(def ^:private credentials
  "Every provider fully credentialed, so ROUTING is the only variable."
  {"services.azure-openai.api-key" "az-key"
   "services.azure-openai.api-endpoint" azure-endpoint
   "services.azure-openai.deployment-name" "az-deployment"
   "services.azure-openai.model-name" "generic-model"
   ;; the openai-compatible branch reads its own per-tenant pair.
   "services.llm.api-key" "llm-key"
   "services.llm.api-endpoint" llm-endpoint})

(def ^:private switch "services.azure-openai.use-azure-openai-api")
;; Retired by Phase 3 of the provider-resolver change, their definitions removed by Phase 4. The pins that
;; set them model an install that predates the removal, which keeps definition
;; and value: nothing reads either.
(def ^:private search-phrases-provider "services.search-phrases.provider")
(def ^:private self-improvement-provider "services.self-improvement.provider")

(def ^:private switch-states
  "Selector 1's three states. `:unset` is defined-with-no-value — the state a
   fresh install is in, since the snapshot defines the path and sets nothing."
  {:unset {} :false {switch false} :true {switch true}})

;; ---------------------------------------------------------------------------
;; The four call sites
;; ---------------------------------------------------------------------------

(defn- selector-1-call
  "One of the ~30 `(if (llm/use-azure-openai tenant) {… :impl :azure} <no opts>)`
   sites. Summarization is representative: model fallback and dispatch both
   branch on the switch."
  []
  (summarization/execute-summarization
   {:inputs {:content "A passage to summarise."} :parameters {} :skill-params {:tenant tenant}}))

(defn- selector-2-call []
  (sp/create-chat-completion tenant {:model "caller-model"
                                     :messages [{:role "user" :content "phrases for: a passage"}]}))

(defn- selector-3-call []
  (pq/execute-propose-questions
   {:inputs {:chunk-id "c1" :chunk-content "A passage." :doc-title "T" :doc-url "https://doc.pin.invalid"}
    :parameters {}
    :skill-params {:tenant tenant}}))

(defn- selector-4-call [model]
  (loader/create-chat-completion tenant {:model model
                                         :messages [{:role "user" :content "phrases for: a passage"}]}))

(defn- observe
  "Run `call` against the install `values` (merged over full credentials)."
  ([values call] (observe values {} {} call))
  ([values install-opts wire-opts call]
   (fx/with-install (merge credentials values) install-opts
     (fn [] (fx/with-wire (merge {:respond (constantly "Hva er dette?\nHvordan virker det?")} wire-opts)
              call)))))

(defn- sole-call
  "The one call `observed` made. Asserts it exists, is unique, and has the
   fields every routing assertion reads — zero calls would make them vacuous."
  [{:keys [calls error]}]
  (is (nil? error) (str "the call must complete: " (some-> error ex-message)))
  (is (= 1 (count calls)) "exactly one chat call")
  (let [c (first calls)]
    (is (keyword? (:branch c)) "the record names a branch")
    (is (string? (:endpoint c)) "the record names an endpoint")
    (is (string? (:model c)) "the record names a model")
    c))

(defn- at-azure? [c]
  (and (= :azure (:branch c)) (= azure-endpoint (:endpoint c))))

(defn- at-llm?
  "On the OpenAI-compatible branch, at the tenant's own `services.llm.api-endpoint`."
  [c]
  (and (= :openai (:branch c)) (= llm-endpoint (:endpoint c))))

;; ---------------------------------------------------------------------------
;; Selector 1 — the switch
;; ---------------------------------------------------------------------------

(deftest selector-1-resolves-unset-as-not-azure
  (testing "digdir.llm.provider/selected-provider — UNSET MEANS NOT AZURE"
    (doseq [[state values expected] [[:unset {} false] [:false {switch false} false] [:true {switch true} true]]]
      (is (= expected (fx/with-install values #(= :azure (provider/selected-provider tenant))))
          (str "switch " state))))
  (testing "a tenant with no platform tree has not chosen: not Azure"
    (is (false? (fx/with-install {switch true} {:tenant-tree? false} #(= :azure (provider/selected-provider tenant)))))))

(deftest selector-1-routes-a-skill-call-by-the-switch
  (testing "unset and false: the generic OpenAI-compatible branch, with model-name, and
            the tenant's OWN key and endpoint from services.llm.*. FLIPPED by the provider-resolver change:
            Phase 1 passed no credentials, so they were process-global (the client's
            OPENAI_API_KEY secret and OPENAI_API_ENDPOINT / default)."
    (doseq [state [:unset :false]]
      (let [c (sole-call (observe (switch-states state) selector-1-call))]
        (is (= :openai (:branch c)) (str "switch " state))
        (is (= llm-endpoint (:endpoint c)) (str "switch " state))
        (is (= "generic-model" (:model c)) (str "switch " state))
        (is (= :client (:via c)) (str "switch " state))
        (is (= :opts (:key-from c)) (str "switch " state " — key from config, not the OPENAI_API_KEY secret"))
        (is (= :opts (:endpoint-from c)) (str "switch " state " — endpoint from config, not the env")))))
  (testing "true: Azure, with the deployment name, key and endpoint from config"
    (let [c (sole-call (observe (switch-states :true) selector-1-call))]
      (is (at-azure? c))
      (is (= "az-deployment" (:model c)))
      (is (= :opts (:key-from c)))
      (is (= :opts (:endpoint-from c)))
      (is (false? (:key-rederived? c)) "both opts supplied: nothing re-derived")
      (is (false? (:endpoint-rederived? c)))))
  (testing "unset with no services.llm.api-key: refuses naming THAT path. FLIPPED by the provider-resolver change
            - it used to fail on the missing OPENAI_API_KEY secret (the Azure-switch default mismatch's symptom),
            and succeeded by borrowing the secret when present. The secret IS present
            here (with-wire's default), and it is not used."
    (let [{:keys [calls error]} (observe {"services.llm.api-key" nil} selector-1-call)]
      (is (empty? calls) "nothing reaches the wire, although the OPENAI_API_KEY secret is available")
      (is (some->> error ex-message (re-find #"services\.llm\.api-key"))
          (str "names the per-tenant path: " (some-> error ex-message))))))

(deftest a-missing-endpoint-refuses-at-the-wire-on-both-branches
  ;; Phase 2's done-condition covers the ENDPOINT as well as the key: a resolver
  ;; that threw only on the key would pass every key pin while a nil endpoint
  ;; went on to the client's old OPENAI_API_ENDPOINT/default or to wkok's
  ;; AZURE_OPENAI_API_ENDPOINT. Pinned at the WIRE, not only at `resolve`: a
  ;; call site that built its own opts would bypass resolve's refusal, and only
  ;; the wire sees that.
  (doseq [[label values path] [["openai-compatible" {switch false "services.llm.api-endpoint" nil} "services.llm.api-endpoint"]
                               ["azure" {switch true "services.azure-openai.api-endpoint" nil} "services.azure-openai.api-endpoint"]]]
    (let [{:keys [calls error]} (observe values selector-1-call)]
      (is (empty? calls) (str label ": a call went out to " (pr-str (mapv :endpoint calls))))
      (is (some->> error ex-message (re-find (re-pattern (java.util.regex.Pattern/quote path))))
          (str label ": names the path: " (some-> error ex-message))))))

(deftest azure-with-no-configured-key-refuses-rather-than-borrowing
  (testing "The Azure-side env door (the provider-resolver change scope, per round-1 decisions), FLIPPED by the provider-resolver change
            - Phase 2's done-condition. The tenant has no Azure key in config.
            Phase 1 sent the call on the Azure branch with `:api-key nil` and wkok read
            AZURE_OPENAI_API_KEY from the process: per-tenant silently process-global.
            The resolver now never returns a nil key; it refuses naming the path, and
            nothing reaches the wire."
    (let [{:keys [calls error]} (observe {switch true "services.azure-openai.api-key" nil} selector-1-call)]
      (is (empty? calls) (str "a call went out keyed from " (pr-str (mapv :key-from calls))))
      (is (some->> error ex-message (re-find #"services\.azure-openai\.api-key"))
          (str "names the path: " (some-> error ex-message))))))

;; ---------------------------------------------------------------------------
;; Selector 2 — search-phrases. RETIRED by Phase 3 of the provider-resolver change: it follows selector 1
;; ---------------------------------------------------------------------------

(deftest use-azure-openai-api-false-sends-search-phrases-where-the-switch-says
  (testing "INVERTED by Phase 3 of the provider-resolver change, and that inversion is the mission's done-condition.
            It was `use-azure-openai-api-false-still-sends-search-phrases-to-azure`: THE
            DEFECT, where the switch said not Azure and search-phrases went to Azure anyway."
    (let [values {switch false}]
      (is (false? (fx/with-install values #(= :azure (provider/selected-provider tenant))))
          "precondition: selector 1 answers NOT Azure for this install")
      (let [c (sole-call (observe values selector-2-call))]
        (is (at-llm? c) "search-phrases went to the tenant's OpenAI-compatible endpoint")
        (is (= "generic-model" (:model c)) "with the provider's model, still overwriting the caller's")
        (is (= [:opts :opts] ((juxt :key-from :endpoint-from) c)) "credentials from config")))))

(deftest selector-2-search-phrases-follows-the-switch
  (testing "INVERTED by Phase 3 of the provider-resolver change (was selector-2-search-phrases-ignores-the-switch).
            Both directions, so a site that always answered one of them cannot pass."
    (doseq [[state values] switch-states]
      (let [c (sole-call (observe values selector-2-call))]
        (if (= :true state)
          (do (is (at-azure? c) (str "switch " state " -> Azure"))
              (is (= "az-deployment" (:model c)) (str "switch " state)))
          (do (is (at-llm? c) (str "switch " state " -> the OpenAI-compatible branch"))
              (is (= "generic-model" (:model c)) (str "switch " state))))))))

(deftest selector-2-keyword-is-no-longer-read
  (testing "INVERTED by Phase 3 of the provider-resolver change (was selector-2-follows-its-own-keyword, the positive
            control that selector 2 could leave Azure). Every value the keyword took now
            routes where the switch says, in BOTH directions: a collapse that still read the
            keyword on one side of the switch cannot pass."
    (doseq [kw [:lmstudio :openrouter :azure-openai]]
      (is (at-azure? (sole-call (observe {switch true search-phrases-provider kw} selector-2-call)))
          (str "switch true, " search-phrases-provider " " kw))
      (is (at-llm? (sole-call (observe {switch false search-phrases-provider kw} selector-2-call)))
          (str "switch false, " search-phrases-provider " " kw)))))

(deftest openrouter-is-a-services-llm-value
  ;; REPLACES selector-2-openrouter-arm-reaches-openrouter and
  ;; selector-2-openrouter-arm-with-its-model-unset-fails-naming-the-path, whose
  ;; :openrouter arm Phase 3 of the provider-resolver change deleted. OpenRouter is no longer a code branch: a
  ;; tenant that wants it points services.llm.* at it, and search-phrases reaches it
  ;; the way it reaches any OpenAI-compatible server - with the tenant's own key and
  ;; model-name, which has a value wherever the provider is configured.
  (let [c (sole-call (observe {switch false
                               "services.llm.api-endpoint" openrouter-endpoint
                               "services.llm.api-key" "or-llm-key"}
                              selector-2-call))]
    (is (= :openai (:branch c)))
    (is (= openrouter-endpoint (:endpoint c)))
    (is (= "generic-model" (:model c)))
    (is (= [:client :opts :opts] ((juxt :via :key-from :endpoint-from) c)))))

(deftest search-phrases-never-borrows-a-key
  ;; REPLACES selector-2-openrouter-arm-never-borrows-the-openai-compatible-key.
  ;; What that pin held for one arm now holds for search-phrases as a whole:
  ;; its key comes from services.llm.* or nothing is sent. The OPENAI_API_KEY secret
  ;; IS available here (with-wire's default), and it is not used.
  ;; nil is the fresh-install state; "   " is what `bb config-set` of a blank value
  ;; stores. A guard weakened to `nil?` would pass the first and send the chunk out
  ;; under a blank bearer on the second.
  (doseq [[state k] [[:unset nil] [:blank "   "]]]
    (testing (str "with services.llm.api-key " (name state) ": zero calls, and the refusal names the path")
      (let [{:keys [calls error]} (observe {switch false "services.llm.api-key" k} selector-2-call)]
        (is (empty? calls)
            (str state ": a call reached the wire keyed from " (pr-str (mapv :key-from calls))))
        (is (some->> error ex-message (re-find #"services\.llm\.api-key"))
            (str state ": the refusal names the path: " (some-> error ex-message)))))))

;; ---------------------------------------------------------------------------
;; Selector 3 — self-improvement (enrichment). RETIRED by Phase 3 of the provider-resolver change
;; ---------------------------------------------------------------------------

(deftest selector-3-self-improvement-follows-the-switch
  (testing "INVERTED by Phase 3 of the provider-resolver change (was selector-3-self-improvement-ignores-the-switch)"
    (doseq [[state values] switch-states]
      (let [observed (observe values selector-3-call)
            c (sole-call observed)
            stamped (-> observed :result :outputs :provenance :provider)]
        (if (= :true state)
          (do (is (at-azure? c) (str "switch " state " -> Azure"))
              (is (= "az-deployment" (:model c)) (str "switch " state))
              (is (= :azure stamped) (str "switch " state " — and the stamped provenance agrees with the wire")))
          (do (is (at-llm? c) (str "switch " state " -> the OpenAI-compatible branch"))
              (is (= "generic-model" (:model c)) (str "switch " state))
              (is (= :client (:via c)) (str "switch " state " — through digdir.llm.client, no direct POST"))
              (is (= :openai-compatible stamped)
                  (str "switch " state " — and the stamped provenance agrees with the wire"))))))))

(deftest selector-3-keyword-is-no-longer-read
  (testing "INVERTED by Phase 3 of the provider-resolver change (was selector-3-follows-its-own-keyword, the positive
            control that :lmstudio posted directly to services.lmstudio.*, past the client).
            Every value the keyword took, in BOTH directions."
    (doseq [kw [:lmstudio :azure-openai]]
      (let [observed (observe {switch true self-improvement-provider kw} selector-3-call)]
        (is (at-azure? (sole-call observed)) (str "switch true, " kw ": Azure, whatever the keyword says"))
        (is (= :azure (-> observed :result :outputs :provenance :provider)) (str "switch true, " kw)))
      (let [c (sole-call (observe {self-improvement-provider kw} selector-3-call))]
        (is (at-llm? c) (str "switch unset, " kw ": the tenant's services.llm.* endpoint"))
        (is (= :client (:via c)) (str "switch unset, " kw ": through digdir.llm.client"))))))

(deftest selector-3-keeps-its-usage-level-model
  (testing "services.self-improvement.model is not a provider selector, and Phase 3 of the provider-resolver change keeps
            it: it still wins over the provider's default model, on either branch"
    (doseq [[state values] [[:false {switch false}] [:true {switch true}]]]
      (let [c (sole-call (observe (assoc values "services.self-improvement.model" "si-model") selector-3-call))]
        (is (= "si-model" (:model c)) (str "switch " state))))))

;; ---------------------------------------------------------------------------
;; Selector 4 — the document loader (no config at all)
;; ---------------------------------------------------------------------------

(deftest selector-4-loader-follows-the-switch
  (testing "INVERTED by Phase 3 of the provider-resolver change (was selector-4-loader-ignores-the-switch). The caller's
            model is still overwritten with the provider's own."
    (doseq [[state values] switch-states]
      (let [c (sole-call (observe values #(selector-4-call "gpt-4o")))]
        (if (= :true state)
          (do (is (at-azure? c) (str "switch " state " -> Azure"))
              (is (= "az-deployment" (:model c)) (str "switch " state " — the caller's model is overwritten")))
          (do (is (at-llm? c) (str "switch " state " -> the OpenAI-compatible branch"))
              (is (= "generic-model" (:model c)) (str "switch " state " — the caller's model is overwritten"))))))))

(def ^:private fallback-models
  "The three model names the loader's OpenRouter route matched until the provider-resolver change. The
   shipped `:search-phrases/fallback-model` values are the first and the last;
   :google/gemma-3-12b-it was in the route but ships in no loader."
  [:google/gemma-3-27b-it :google/gemma-3-12b-it :dphn/Dolphin-Mistral-24B-Venice-Edition])

(defn- selector-4-fallback-call
  "The loader's fallback call: the model named to the resolved provider."
  [model]
  (loader/create-chat-completion tenant {:model model
                                         :messages [{:role "user" :content "phrases for: a passage"}]}
                                 {:model model}))

(deftest selector-4-fallback-model-follows-the-switch-under-its-own-name
  ;; INVERTED by the provider-resolver change (was selector-4-routes-three-model-names-to-openrouter,
  ;; which pinned these names to OpenRouter whatever the switch said). The route is
  ;; gone: the fallback names its model to the tenant's resolved provider, which
  ;; keeps the name - on Azure it is taken as the deployment, so a tenant with no
  ;; such deployment fails there. OpenRouter is reached by pointing services.llm.*
  ;; at it (`openrouter-is-a-services-llm-value`).
  (testing "the fallback call: the switch decides the destination, and the model name is kept"
    (doseq [[state values] switch-states
            model fallback-models]
      (let [c (sole-call (observe values #(selector-4-fallback-call model)))]
        (if (= :true state)
          (is (at-azure? c) (str "switch " state " " model " -> Azure"))
          (is (at-llm? c) (str "switch " state " " model " -> the OpenAI-compatible branch")))
        (is (= (subs (str model) 1) (:model c)) (str state " " model " - the model name is kept"))
        (is (= [:opts :opts] ((juxt :key-from :endpoint-from) c)) (str state " " model)))))
  (testing "POSITIVE CONTROL: the name alone routes nowhere. Without `{:model ...}` these names
            are overwritten with the provider's own, like any caller's model"
    (doseq [[state values] switch-states
            model fallback-models]
      (let [c (sole-call (observe values #(selector-4-call model)))]
        (is (not= openrouter-endpoint (:endpoint c)) (str state " " model))
        (is (= (if (= :true state) "az-deployment" "generic-model") (:model c)) (str state " " model))))))

(deftest selector-4-fallback-never-reads-the-openrouter-key
  ;; REPLACES selector-4-openrouter-fallback-never-borrows-the-openai-compatible-key.
  ;; That pin held the OpenRouter route to its own key: with
  ;; services.openrouter.api-key unset or blank it refused, rather than send the
  ;; OPENAI_API_KEY secret to openrouter.ai. The provider-resolver change removed the route, so the
  ;; fallback's key is the resolved provider's and services.openrouter.api-key is
  ;; read by nothing. The OpenRouter key-leak issue's property still holds, on the path that replaced it.
  ;; Both states: nil (fresh install) and "   " (a blank `bb config-set`).
  (testing "services.openrouter.api-key unset or blank changes nothing: the fallback still
            reaches the resolved provider, on the provider's key"
    (doseq [[state k] [[:unset nil] [:blank "   "]]
            model fallback-models]
      (let [c (sole-call (observe {switch false "services.openrouter.api-key" k}
                                  #(selector-4-fallback-call model)))]
        (is (at-llm? c) (str state " " model))
        (is (= [:client :opts :opts] ((juxt :via :key-from :endpoint-from) c)) (str state " " model)))))
  (testing "with services.llm.api-key unset or blank: zero calls, and the refusal names the
            path - the OPENAI_API_KEY secret is not borrowed. The absence of the CALL is
            asserted, not just the message: a message can be right while the request still
            goes out"
    (doseq [[state k] [[:unset nil] [:blank "   "]]
            model fallback-models]
      (let [{:keys [calls error]} (observe {switch false "services.llm.api-key" k}
                                           #(selector-4-fallback-call model))]
        (is (empty? calls)
            (str state " " model ": a call reached the wire keyed from " (pr-str (mapv :key-from calls))))
        (is (some->> error ex-message (re-find #"services\.llm\.api-key"))
            (str state " " model ": the refusal names the path: " (some-> error ex-message)))))))

;; ---------------------------------------------------------------------------
;; The stronger facts
;; ---------------------------------------------------------------------------

(deftest on-a-fresh-install-all-four-sites-agree
  (testing "INVERTED by Phase 3 of the provider-resolver change (was on-a-fresh-install-selector-1-disagrees-with-2-3-and-4,
            which pinned the split as the default state). Nothing set: all four go where
            selector 1 says - not Azure."
    (is (false? (fx/with-install {} #(= :azure (provider/selected-provider tenant)))) "selector 1: not Azure")
    (doseq [[label call] [["a selector-1 site" selector-1-call]
                          ["search-phrases" selector-2-call]
                          ["enrichment" selector-3-call]
                          ["the loader" #(selector-4-call "gpt-4o")]]]
      (is (at-llm? (sole-call (observe {} call))) (str label ": the OpenAI-compatible branch")))))

(deftest before-definitions-are-registered-only-the-switch-matters
  (testing "A documented edge, reachable only before `ensure-all-config-definitions!` runs —
            NOT the fresh-install state. CHANGED by Phase 3 of the provider-resolver change (was
            before-definitions-are-registered-the-selectors-disagree-a-second-way): the keyword
            selectors are no longer read, so an unregistered one changes nothing. Since the provider-resolver change
            Phase 4 a fresh install has no definition for either keyword, so for those two
            `without` removes nothing and they describe every fresh install."
    (let [without (fn [p] {:defined (disj (fx/fresh-install-definitions) p)})]
      (testing "selector 1 rethrows: it catches only :tenant-root-missing"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No config definition registered"
                              (fx/with-install {} (without switch) #(= :azure (provider/selected-provider tenant))))))
      (testing "search-phrases no longer reads its keyword: it used to throw naming it"
        (is (at-llm? (sole-call (observe {} (without search-phrases-provider) {} selector-2-call)))))
      (testing "enrichment no longer reads its keyword: it used to swallow the throw and answer Azure"
        (is (at-llm? (sole-call (observe {} (without self-improvement-provider) {} selector-3-call))))))))

;; ---------------------------------------------------------------------------
;; The instrument
;; ---------------------------------------------------------------------------

(deftest the-install-is-anchored-to-real-definitions
  (testing "The defined set is read from data. Non-empty, and containing paths known
            from each source — otherwise every 'undefined' pin above is vacuous."
    (is (<= 100 (count @fx/snapshot-definitions)) "the snapshot's ~121 definitions")
    (is (contains? @fx/snapshot-definitions switch))
    ;; The two keyword selectors anchored this until Phase 4 of the provider-resolver change removed their
    ;; definitions from both sources; services.llm.provider is in both.
    (is (contains? @fx/snapshot-definitions "services.llm.provider"))
    (is (seq @fx/runtime-definitions) "runtime registration was collected")
    (is (contains? @fx/runtime-definitions "services.llm.provider") "setup/config.clj registers it too")
    (is (every? #(str/includes? % ".") (fx/fresh-install-definitions)) "dotted paths, not keywords")))
