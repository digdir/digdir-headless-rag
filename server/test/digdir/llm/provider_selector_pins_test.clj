(ns digdir.llm.provider-selector-pins-test
  "Where each of the four provider selectors sends a call TODAY,
   INCLUDING THE DEFECTS. These are the before-half of every later comparison:
   they pass on the base branch because they assert its current behaviour, and
   each phase that changes a selector must flip the pins it names — no others.

     1  services.azure-openai.use-azure-openai-api  provider/selected-provider
     2  services.search-phrases.provider            search-phrases/resolve-provider
     3  services.self-improvement.provider          propose-questions/self-improvement-provider
     4  (none — hardcoded)                          loader/create-chat-completion

   ## The defect, stated as a pin

   `use-azure-openai-api-false-still-sends-search-phrases-to-azure` is RED BY
   DESIGN in intent: it passes today because it asserts the wrong behaviour.
   Phase 3 must invert it, and that inversion is the mission's done-condition.

   ## Unset is the interesting case, not false

   On a fresh install nothing sets any selector, and they already disagree:
   selector 1 unset means NOT Azure, selectors 2-4 unset mean Azure.
   `on-a-fresh-install-selector-1-disagrees-with-2-3-and-4` pins that.

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
(def ^:private lmstudio-endpoint "http://lmstudio.pin.invalid:1234")
(def ^:private openrouter-endpoint "https://openrouter.ai/api/v1")

(def ^:private credentials
  "Every provider fully credentialed, so ROUTING is the only variable."
  {"services.azure-openai.api-key" "az-key"
   "services.azure-openai.api-endpoint" azure-endpoint
   "services.azure-openai.deployment-name" "az-deployment"
   "services.azure-openai.model-name" "generic-model"
   "services.lmstudio.api-key" "lm-key"
   "services.lmstudio.api-endpoint" lmstudio-endpoint
   "services.lmstudio.model" "lm-model"
   "services.openrouter.api-key" "or-key"})

(def ^:private switch "services.azure-openai.use-azure-openai-api")
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
  (testing "unset and false: the generic OpenAI-compatible branch, with model-name —
            and NO opts map, so key and endpoint are process-global (the client's
            env fallback). Phase 2 flips this: the resolver reads services.llm.*"
    (doseq [state [:unset :false]]
      (let [c (sole-call (observe (switch-states state) selector-1-call))]
        (is (= :openai (:branch c)) (str "switch " state))
        (is (not= azure-endpoint (:endpoint c)) (str "switch " state))
        (is (= "generic-model" (:model c)) (str "switch " state))
        (is (= :client (:via c)) (str "switch " state))
        (is (= :secret (:key-from c)) (str "switch " state " — key from the OPENAI_API_KEY secret, not config"))
        (is (#{:env :default} (:endpoint-from c)) (str "switch " state " — endpoint not from config")))))
  (testing "true: Azure, with the deployment name, key and endpoint from config"
    (let [c (sole-call (observe (switch-states :true) selector-1-call))]
      (is (at-azure? c))
      (is (= "az-deployment" (:model c)))
      (is (= :opts (:key-from c)))
      (is (= :opts (:endpoint-from c)))
      (is (false? (:key-rederived? c)) "both opts supplied: nothing re-derived")
      (is (false? (:endpoint-rederived? c)))))
  (testing "unset with no OPENAI_API_KEY: the fresh-install first query fails naming the secret (the Azure-switch default mismatch's symptom)"
    (let [{:keys [calls error]} (observe {} {} {:openai-api-key nil} selector-1-call)]
      (is (empty? calls) "nothing reaches the wire")
      (is (some->> error ex-message (re-find #"Missing secret :openai-api-key"))))))

(deftest azure-with-no-configured-key-borrows-the-process-global-one
  (testing "The Azure-side env door (the provider-resolver change scope, per round-1 decisions). The tenant has
            no Azure key in config; the call still goes out on the Azure branch with
            `:api-key nil`, and wkok then reads AZURE_OPENAI_API_KEY from the process.
            Per-tenant silently becomes process-global. Phase 2's done-condition flips
            this: the resolver never returns a nil key — it throws naming the path."
    (let [{:keys [calls error]} (observe {switch true "services.azure-openai.api-key" nil} selector-1-call)
          c (first calls)]
      (is (nil? error))
      (is (= 1 (count calls)))
      (is (= :azure (:branch c)))
      (is (= :wkok (:via c)))
      (is (contains? #{:env nil} (:key-from c))
          "NOT :opts — the key is whatever the environment holds (nil in a shell without it)")
      (is (= :opts (:endpoint-from c)) "the endpoint was configured, so only the key crossed the door")
      (is (true? (:key-rederived? c)) "and the record says the key's source is re-derived, not observed")
      (is (false? (:endpoint-rederived? c))))))

;; ---------------------------------------------------------------------------
;; Selector 2 — search-phrases
;; ---------------------------------------------------------------------------

(deftest use-azure-openai-api-false-still-sends-search-phrases-to-azure
  (testing "THE DEFECT. The switch says not Azure; search-phrases goes to Azure anyway.
            Passes today. Phase 3 must invert it — that inversion is the mission's done-condition."
    (let [values {switch false}]
      (is (false? (fx/with-install values #(= :azure (provider/selected-provider tenant))))
          "precondition: selector 1 answers NOT Azure for this install")
      (let [c (sole-call (observe values selector-2-call))]
        (is (at-azure? c) "search-phrases went to the Azure endpoint on the Azure branch")
        (is (= "az-deployment" (:model c)) "with the Azure deployment name, overwriting the caller's model")))))

(deftest selector-2-search-phrases-ignores-the-switch
  (doseq [[state values] switch-states]
    (let [c (sole-call (observe values selector-2-call))]
      (is (at-azure? c) (str "switch " state " -> Azure"))
      (is (= "az-deployment" (:model c)) (str "switch " state)))))

(deftest selector-2-follows-its-own-keyword
  (testing "POSITIVE CONTROL: the capture can see selector 2 go somewhere other than Azure"
    (let [c (sole-call (observe {search-phrases-provider :lmstudio} selector-2-call))]
      (is (= :openai (:branch c)))
      (is (= lmstudio-endpoint (:endpoint c)))
      (is (= "lm-model" (:model c)))))
  (testing "explicit :azure-openai is indistinguishable at the wire from unset"
    (is (at-azure? (sole-call (observe {search-phrases-provider :azure-openai} selector-2-call))))))

(deftest selector-2-openrouter-arm-reaches-openrouter
  ;; INVERTED by the OpenRouter model-registration issue. This pin used to assert the defect: `services.openrouter.model`
  ;; (search_phrases.clj:74) was registered by neither the snapshot nor runtime
  ;; setup, so the :openrouter arm threw `No config definition registered` on every
  ;; install before any call was made. The OpenRouter model-registration issue registers it in both places.
  ;;
  ;; ⚠️ COVERAGE COUPLING. `fx/fresh-install-definitions` is the UNION of snapshot
  ;; and runtime registration, so this pin goes red only when the path is missing
  ;; from BOTH halves - it cannot say which half regressed. The per-half checks
  ;; are `search-phrases-provider-paths-test` (runtime: every-documented-…;
  ;; snapshot: the-openrouter-model-definition-ships-…) and the description
  ;; parity in `llm-namespace-test`, whose `some?` guards stop nil = nil passing
  ;; when both halves are missing. Delete or loosen either side knowingly.
  (testing "the :openrouter arm resolves its model and reaches OpenRouter"
    (is (contains? (fx/fresh-install-definitions) "services.openrouter.model")
        "the path is defined on a fresh install")
    (is (contains? (fx/fresh-install-definitions) "services.openrouter.api-key")
        "positive control: the same lookup finds its sibling")
    (let [c (sole-call (observe {search-phrases-provider :openrouter
                                 "services.openrouter.model" "or-model"}
                                selector-2-call))]
      (is (= :openai (:branch c)))
      (is (= openrouter-endpoint (:endpoint c)))
      (is (= "or-model" (:model c))))))

(deftest selector-2-openrouter-arm-with-its-model-unset-fails-naming-the-path
  ;; Registering the OpenRouter model created a state that did not exist before it: DEFINED BUT UNSET, which
  ;; is what every fresh install has. OpenRouter has no "whatever is loaded"
  ;; default the way LM Studio does, so a nil model can only fail at the vendor,
  ;; one layer from the cause. It fails here instead, naming the path.
  (let [{:keys [calls error]} (observe {search-phrases-provider :openrouter} selector-2-call)]
    (is (empty? calls) "nothing reaches the wire")
    (is (some->> error ex-message (re-find #"services\.openrouter\.model"))
        (str "the refusal names the path: " (some-> error ex-message)))))

(deftest selector-2-openrouter-arm-never-borrows-the-openai-compatible-key
  ;; `digdir.llm.client` fills a nil `:api-key` from the OPENAI_API_KEY secret on
  ;; the direct branch. For this arm that would send the process-global
  ;; OpenAI-compatible key to openrouter.ai, a third party. Unreachable before
  ;; The OpenRouter model registration (the model lookup threw first); reachable once the model is defined.
  (testing "POSITIVE CONTROL: with its own key, the capture sees exactly one call, keyed from opts"
    (let [c (sole-call (observe {search-phrases-provider :openrouter
                                 "services.openrouter.model" "or-model"}
                                selector-2-call))]
      (is (= openrouter-endpoint (:endpoint c)))
      (is (= :opts (:key-from c)))))
  ;; nil is the fresh-install state; "   " is what `bb config-set` of a blank
  ;; value stores (the env bridge skips blanks, so only an explicit write gets
  ;; here). A guard weakened to `nil?` would pass the first and send the chunk
  ;; to openrouter.ai under a blank bearer on the second.
  (doseq [[state k] [[:unset nil] [:blank "   "]]]
    (testing (str "with its key " (name state) ": zero calls, and the refusal names the path")
      (let [{:keys [calls error]} (observe {search-phrases-provider :openrouter
                                            "services.openrouter.model" "or-model"
                                            "services.openrouter.api-key" k}
                                           selector-2-call)]
        (is (empty? calls)
            (str state ": a call reached the wire keyed from " (pr-str (mapv :key-from calls))))
        (is (some->> error ex-message (re-find #"services\.openrouter\.api-key"))
            (str state ": the refusal names the path: " (some-> error ex-message)))))))

;; ---------------------------------------------------------------------------
;; Selector 3 — self-improvement (enrichment question generation)
;; ---------------------------------------------------------------------------

(deftest selector-3-self-improvement-ignores-the-switch
  (doseq [[state values] switch-states]
    (let [observed (observe values selector-3-call)
          c (sole-call observed)]
      (is (at-azure? c) (str "switch " state " -> Azure"))
      (is (= "az-deployment" (:model c)) (str "switch " state))
      (is (= :azure-openai (-> observed :result :outputs :provenance :provider))
          (str "switch " state " — and the stamped provenance agrees with the wire")))))

(deftest selector-3-follows-its-own-keyword
  (testing "POSITIVE CONTROL: :lmstudio posts directly, past digdir.llm.client"
    (let [observed (observe {self-improvement-provider :lmstudio} selector-3-call)
          c (sole-call observed)]
      (is (= :openai (:branch c)))
      (is (= lmstudio-endpoint (:endpoint c)))
      (is (= "lm-model" (:model c)))
      (is (= :direct-post (:via c)) "bypasses digdir.llm.client — the client-side stub would not have seen it")
      (is (= :lmstudio (-> observed :result :outputs :provenance :provider))))))

;; ---------------------------------------------------------------------------
;; Selector 4 — the document loader (no config at all)
;; ---------------------------------------------------------------------------

(deftest selector-4-loader-ignores-the-switch
  (doseq [[state values] switch-states]
    (let [c (sole-call (observe values #(selector-4-call "gpt-4o")))]
      (is (at-azure? c) (str "switch " state " -> Azure"))
      (is (= "az-deployment" (:model c)) (str "switch " state " — the caller's model is overwritten")))))

(deftest selector-4-routes-three-model-names-to-openrouter
  (testing "POSITIVE CONTROL, and a second provider that is live in shipped loader
            defaults (the `:search-phrases/fallback-model` keywords): OpenRouter,
            whatever the switch says"
    (doseq [[state values] switch-states
            model [:google/gemma-3-27b-it :google/gemma-3-12b-it :dphn/Dolphin-Mistral-24B-Venice-Edition]]
      (let [c (sole-call (observe values #(selector-4-call model)))]
        (is (= :openai (:branch c)) (str state " " model))
        (is (= openrouter-endpoint (:endpoint c)) (str state " " model))
        (is (= (subs (str model) 1) (:model c)) (str state " " model " — the model name is kept"))
        (is (= [:client :opts :opts] ((juxt :via :key-from :endpoint-from) c)) (str state " " model))))))

(deftest selector-4-openrouter-fallback-never-borrows-the-openai-compatible-key
  ;; Every shipped loader's `:search-phrases/fallback-model` is one of these
  ;; three keywords, so this arm runs whenever the Azure primary throws. With
  ;; `services.openrouter.api-key` unset (the fresh-install state: defined, no
  ;; value) the arm passed `:api-key nil`, and `digdir.llm.client` filled it from
  ;; the OPENAI_API_KEY secret - sending the process-global OpenAI-compatible key
  ;; to openrouter.ai. The absence of the CALL is asserted, not just the message:
  ;; a message can be right while the request still goes out.
  ;; Both states: nil (fresh install) and "   " (a blank `bb config-set`). A guard
  ;; weakened to `nil?` passes the first and sends the chunk out on the second.
  (doseq [[state k] [[:unset nil] [:blank "   "]]
          model [:google/gemma-3-27b-it :google/gemma-3-12b-it :dphn/Dolphin-Mistral-24B-Venice-Edition]]
    (let [{:keys [calls error]} (observe {"services.openrouter.api-key" k} #(selector-4-call model))]
      (is (empty? calls)
          (str state " " model ": a call reached the wire keyed from " (pr-str (mapv :key-from calls))))
      (is (some->> error ex-message (re-find #"services\.openrouter\.api-key"))
          (str state " " model ": the refusal names the path: " (some-> error ex-message))))))

;; ---------------------------------------------------------------------------
;; The stronger facts
;; ---------------------------------------------------------------------------

(deftest on-a-fresh-install-selector-1-disagrees-with-2-3-and-4
  (testing "Nothing set. The split is the default state, not something an operator opts into."
    (let [s1 (sole-call (observe {} selector-1-call))
          s2 (sole-call (observe {} selector-2-call))
          s3 (sole-call (observe {} selector-3-call))
          s4 (sole-call (observe {} #(selector-4-call "gpt-4o")))]
      (is (false? (fx/with-install {} #(= :azure (provider/selected-provider tenant)))) "selector 1: not Azure")
      (is (not (at-azure? s1)) "a selector-1 site does not go to Azure")
      (is (at-azure? s2) "selector 2: Azure")
      (is (at-azure? s3) "selector 3: Azure")
      (is (at-azure? s4) "selector 4: Azure"))))

(deftest before-definitions-are-registered-the-selectors-disagree-a-second-way
  (testing "A documented edge, reachable only before `ensure-all-config-definitions!` runs —
            NOT the fresh-install state. An unregistered path throws in `accessor/get`, and
            the selectors then disagree differently from the issue's disagreement."
    (let [without (fn [p] {:defined (disj (fx/fresh-install-definitions) p)})]
      (testing "selector 1 rethrows: it catches only :tenant-root-missing"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No config definition registered"
                              (fx/with-install {} (without switch) #(= :azure (provider/selected-provider tenant))))))
      (testing "selector 2 throws: no catch around its read"
        (let [{:keys [calls error]} (observe {} (without search-phrases-provider) {} selector-2-call)]
          (is (empty? calls))
          (is (some->> error ex-message (re-find #"services\.search-phrases\.provider")))))
      (testing "selector 3 swallows every exception and answers Azure"
        (is (at-azure? (sole-call (observe {} (without self-improvement-provider) {} selector-3-call))))))))

;; ---------------------------------------------------------------------------
;; The instrument
;; ---------------------------------------------------------------------------

(deftest the-install-is-anchored-to-real-definitions
  (testing "The defined set is read from data. Non-empty, and containing paths known
            from each source — otherwise every 'undefined' pin above is vacuous."
    (is (<= 100 (count @fx/snapshot-definitions)) "the snapshot's ~121 definitions")
    (is (contains? @fx/snapshot-definitions switch))
    (is (contains? @fx/snapshot-definitions search-phrases-provider))
    (is (contains? @fx/snapshot-definitions self-improvement-provider))
    (is (seq @fx/runtime-definitions) "runtime registration was collected")
    (is (contains? @fx/runtime-definitions search-phrases-provider) "setup/config.clj registers it too")
    (is (every? #(str/includes? % ".") (fx/fresh-install-definitions)) "dotted paths, not keywords")))
