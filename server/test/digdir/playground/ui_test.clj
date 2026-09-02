(ns digdir.playground.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [digdir.playground.ui :as ui]))

(deftest render-markdown-to-html-supports-inline-br-html
  (testing "Inline <br> HTML is rendered instead of Unknown type message"
    (let [html (ui/render-markdown-to-html "Line 1<br>Line 2")]
      (is (str/includes? html "<br"))
      (is (not (str/includes? html "Unknown type"))))))

(deftest render-markdown-to-html-renders-softbreaks-as-line-breaks
  (testing "Single chat newlines stay visible instead of collapsing into spaces"
    (let [html (ui/render-markdown-to-html "Første linje\nAndre linje")]
      (is (str/includes? html "<br"))
      (is (str/includes? html "Første linje"))
      (is (str/includes? html "Andre linje")))))

(deftest render-markdown-to-html-promotes-inline-numbered-sections-to-list
  (testing "Inline numbered sections render as a real ordered list"
    (let [html (ui/render-markdown-to-html
                "Altinn kan brukes til: 1. **Utvikling av digitale tjenester:** Altinn Studio gjør det mulig å lage applikasjoner. 2. **Distribusjon av applikasjoner:** Tjenesteeiere kan distribuere selv. 3. **Autentisering og autorisering:** Altinn tilbyr API-er.")]
      (is (str/includes? html "<ol"))
      (is (str/includes? html "<li"))
      (is (str/includes? html "<strong>Utvikling av digitale tjenester:</strong>"))
      (is (str/includes? html "<strong>Distribusjon av applikasjoner:</strong>"))
      (is (str/includes? html "<strong>Autentisering og autorisering:</strong>")))))

(deftest render-markdown-to-html-normalizes-display-math-delimiters
  (testing "LaTeX display math delimiters are normalized into formula HTML"
    (let [html (ui/render-markdown-to-html "Først tekst.\n\n\\[\n\\text{Prosentvis økning} = \\frac{6}{326}\n\\]\n\nEtter tekst.")]
      (is (str/includes? html "<figure class=\"formula\">"))
      (is (str/includes? html "\\text{Prosentvis økning} = \\frac{6}{326}"))
      (is (not (str/includes? html "\\[")))
      (is (not (str/includes? html "\\]"))))))

(deftest render-markdown-to-html-does-not-break-tex-commands-inside-display-math
  (testing "Display math containing \\left and \\right stays inside one formula block"
    (let [html (ui/render-markdown-to-html "4. Prosentvis økning:\n\n\\[\n\\text{Prosentvis økning} = \\left( \\frac{\\text{Økning}}{\\text{Antall i 2022}} \\right) \\times 100\n\\]\n\nØkningen er **1,84%**.")]
      (is (str/includes? html "<figure class=\"formula\">"))
      (is (str/includes? html "\\left( \\frac{\\text{Økning}}{\\text{Antall i 2022}} \\right) \\times 100"))
      (is (str/includes? html "<strong>1,84%</strong>")))))

(deftest render-markdown-to-html-normalizes-inline-math-delimiters
  (testing "LaTeX inline math delimiters are normalized into inline formula HTML"
    (let [html (ui/render-markdown-to-html "Resultatet er \\(\\frac{6}{326}\\) i basisform.")]
      (is (str/includes? html "<span class=\"formula\">"))
      (is (str/includes? html "\\frac{6}{326}"))
      (is (not (str/includes? html "\\(")))
      (is (not (str/includes? html "\\)"))))))

(deftest rendered-markdown-is-sanitized-before-reaching-inner-html
  (testing "active and resource-loading content is removed from Markdown output"
    (doseq [markdown ["[click](javascript:alert(1))"
                      "[click](JaVaScRiPt:alert(1))"
                      "[click](jav&#x61;script:alert(1))"
                      "[click](data:text/html,boom)"
                      "[click](vbscript:msgbox(1))"]]
      (let [html (ui/render-markdown-to-html markdown)]
        (is (not (re-find #"(?i)javascript:|data:text|<script|<img" html))
            (str "unsafe rendered HTML for " (pr-str markdown) ": " html))
        (is (not (re-find #"(?i)<a[^>]+href=" html))
            (str "unsafe href survived for " (pr-str markdown) ": " html))))
    (let [html (ui/render-markdown-to-html
                "![tracking pixel](https://attacker.example/pixel.png)")]
      (is (not (str/includes? html "<img")))))

  (testing "raw HTML cannot retain browser-executable attributes"
    (let [html (ui/render-markdown-to-html
                (str "<span class=\"kept\" style=\"position:fixed\">safe text</span>"
                     "<span onclick=\"alert(1)\">blocked attribute</span>"))]
      (is (str/includes? html "safe text"))
      (is (str/includes? html "class=\"kept\""))
      (is (not (re-find #"(?i)<span[^>]+(?:style|onclick)=" html)))))

  (testing "ordinary absolute and relative links remain usable"
    (let [html (ui/render-markdown-to-html
                "[external](https://example.com/docs) [local](/datasets/public-docs)")]
      (is (str/includes? html "href=\"https://example.com/docs\""))
      (is (str/includes? html "href=\"/datasets/public-docs\""))))

  (testing "citation markup keeps only the attributes needed by click delegation"
    (let [html (ui/render-markdown-with-citations "Answer [12]")]
      (is (str/includes? html "class=\"citation\""))
      (is (str/includes? html "data-index=\"12\""))
      (is (not (str/includes? html "style="))))))

(deftest metadata->markdown-map-headers
  (testing "Metadata maps with Header N keys become markdown headings"
    (let [md (ui/metadata->markdown {"Header 2" "**Bærekraft**"
                                     "Header 1" "Styring og kontroll i virksomheten"
                                     "Header 4" "**HR**"})]
      (is (str/includes? md "# Styring og kontroll i virksomheten"))
      (is (str/includes? md "## **Bærekraft**"))
      (is (str/includes? md "#### **HR**")))))

(deftest metadata->markdown-edn-string
  (testing "EDN metadata strings are parsed and normalized"
    (let [md (ui/metadata->markdown "{\"Header 1\" \"Title\"}")]
      (is (str/includes? md "# Title")))))

(deftest markdown-preview-uses-first-block
  (testing "Preview uses first markdown block to avoid broken inline formatting"
    (is (= "**Bold title** line"
           (ui/markdown-preview "**Bold title** line\n\nSecond paragraph")))))

(deftest effective-chat-config-keeps-resolved-skill-graph-local
  (testing "Derived skill graph selection is applied without mutating the stored config shape"
    (is (= {:model "gpt-4o"
            :temperature 0.1
            :max-tokens 4096
            :rerank-top-k nil
            :context-top-k nil
            :rerank-threshold nil
            :skill-graph "builtin/retrieve-only"}
            (ui/effective-chat-config {:skill-graph "invalid"
                                      :model "gpt-4o"}
                                     "builtin/retrieve-only")))))

(deftest displayed-agent-id-prefers-explicit-selection
  (testing "The selector shows the explicit conversation agent instead of a derived fallback"
    (is (= "agent/from-conversation"
           (ui/displayed-agent-id
            "agent/from-conversation"
            "agent/from-skill-graph"
            [{:id "agent/from-conversation"}
             {:id "agent/from-skill-graph"}])))))

(deftest displayed-agent-id-falls-back-to-effective-agent
  (testing "The selector falls back to the effective agent when no explicit selection is available"
    (is (= "agent/from-skill-graph"
           (ui/displayed-agent-id
            nil
            "agent/from-skill-graph"
            [{:id "agent/from-skill-graph"}])))))

(deftest playground-preferences-keep-only-safe-display-state
  (testing "Only valid, non-sensitive browser preferences survive normalization"
    (is (= {:selected-agent-id "agent/docs"
            :selected-tenant "digdir"
            :selected-dataset-config-key "public-docs"
            :show-sidebar false}
           (ui/normalize-playground-preferences
            {:selected-agent-id "agent/docs"
             :selected-tenant "digdir"
             :selected-dataset-config-key "public-docs"
             :view-mode :focused
             :show-sidebar false
             :query "must not persist"
             :conversation-id "must-not-persist"}))))
  (testing "Blank IDs and unsupported display values cannot poison startup"
    (is (= {}
           (ui/normalize-playground-preferences
            {:selected-agent-id " "
             :selected-tenant nil
             :selected-dataset-config-key ""
             :view-mode :unknown
             :show-sidebar "false"})))))

(deftest default-playground-chat-state-hydrates-preferences-without-chat-content
  (let [state (ui/default-playground-chat-state
               {:selected-agent-id "agent/docs"
                :selected-tenant "digdir"
                :selected-dataset-config-key "public-docs"
                :view-mode :classic
                :show-sidebar false
                :query "ignored"})]
    (is (= "agent/docs" (:selected-agent-id state)))
    (is (= "digdir" (:selected-tenant state)))
    (is (= "public-docs" (:selected-dataset-config-key state)))
    (is (nil? (:view-mode state)))
    (is (false? (:show-sidebar state)))
    (is (= "" (:query state)))
    (is (nil? (:conversation-id state)))))

(deftest preferred-option-restores-valid-values-and-recovers-from-stale-values
  (is (= "second" (ui/preferred-option "second" ["first" "second"])))
  (is (= "first" (ui/preferred-option "revoked" ["first" "second"])))
  (is (= "first" (ui/preferred-option nil ["first" "second"])))
  (is (nil? (ui/preferred-option "stale" []))))

(deftest selected-reference-is-total-during-reactive-close-transitions
  (testing "an index paired with an already-cleared vector does not throw"
    (is (nil? (ui/selected-reference {:reference-index 0
                                      :reference-chunk-ids []}))))
  (testing "indices are bounded against the same vector used for lookup"
    (is (= {:chunk-id "chunk-2" :index 1 :total 2}
           (ui/selected-reference {:reference-index 99
                                   :reference-chunk-ids ["chunk-1" "chunk-2"]})))
    (is (= {:chunk-id "chunk-1" :index 0 :total 2}
           (ui/selected-reference {:reference-index -4
                                   :reference-chunk-ids ["chunk-1" "chunk-2"]}))))
  (testing "a closed pane stays closed while its ids remain cached"
    (is (nil? (ui/selected-reference {:reference-index nil
                                      :reference-chunk-ids ["chunk-1"]})))))

(deftest conversation-sidebar-dependencies-ignore-transient-chat-state
  (let [base {:conversation-id "conversation-1"
              :sidebar-page-size 40
              :conversation-search "Altinn"
              :conversation-list-revision 3}
        transient-update (assoc base
                                :execution-id "execution-2"
                                :scroll-to-message-id "message-9"
                                :reference-index 1
                                :editing-text "draft"
                                :pending-send {:query "hello"})]
    (testing "message and execution updates cannot invalidate the sidebar reactor"
      (is (= (ui/conversation-sidebar-state base)
             (ui/conversation-sidebar-state transient-update))))
    (testing "actual sidebar inputs still invalidate it"
      (is (not= (ui/conversation-sidebar-state base)
                (ui/conversation-sidebar-state
                 (assoc base :conversation-search "authorization"))))
      (is (not= (ui/conversation-sidebar-state base)
                (ui/conversation-sidebar-state
                 (update base :conversation-list-revision inc)))))))

(deftest eligible-agents-follow-the-selected-organization-and-dataset
  (let [agents [{:id "agent/digdir"
                 :allowed-dataset-scopes [{:tenant "digdir"
                                           :dataset-config-key "docs"}]}
                {:id "agent/nav"
                 :allowed-dataset-scopes [{:tenant "nav"
                                           :dataset-config-key "rules"}]}
                {:id "agent/unrestricted"
                 :allowed-dataset-scopes []}]]
    (is (= ["agent/digdir" "agent/unrestricted"]
           (mapv :id (ui/eligible-agents-for-scope agents "digdir" "docs"))))
    (is (= ["agent/nav" "agent/unrestricted"]
           (mapv :id (ui/eligible-agents-for-scope agents "nav" "rules"))))))

(deftest tenant-options-are-not-limited-by-the-previous-agent
  (let [agents [{:id "agent/digdir"
                 :allowed-dataset-scopes [{:tenant "digdir"
                                           :dataset-config-key "docs"}]}
                {:id "agent/nav"
                 :allowed-dataset-scopes [{:tenant "nav"
                                           :dataset-config-key "rules"}]}]
        tenant-state (ui/derive-chat-tenant-state
                      (assoc (ui/default-playground-chat-state)
                             :selected-agent-id "agent/digdir"
                             :selected-tenant "nav")
                      "user-1"
                      ["digdir" "nav"]
                      agents
                      {:options []})]
    (is (= ["digdir" "nav"] (:visible-tenants tenant-state)))
    (is (= "nav" (:effective-selected-tenant tenant-state)))
    (is (= #{{:tenant "digdir" :dataset-config-key "docs"}
             {:tenant "nav" :dataset-config-key "rules"}}
           (set (:available-agent-dataset-scopes tenant-state))))))

(deftest chat-scope-derivation-is-ready-on-first-visit
  (let [agent {:id "agent/docs"
               :name "Docs agent"
               :default-skill-graph "builtin/agent-rag-graph-bundled"
               :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
               :allowed-dataset-scopes [{:tenant "digdir"
                                         :dataset-config-key "public-docs"}]}
        tenant-state (ui/derive-chat-tenant-state
                      (ui/default-playground-chat-state)
                      "user-1"
                      ["digdir"]
                      [agent]
                      {:options [{:value "builtin/agent-rag-graph-bundled"
                                  :label "Agentic RAG"}]})
        scope-state (ui/derive-chat-scope-state
                     tenant-state
                     {:dataset-config-key "public-docs"}
                     [{:value "public-docs" :label "Public docs"}])]
    (is (= "agent/docs" (:effective-agent-id tenant-state)))
    (is (= "digdir" (:effective-selected-tenant tenant-state)))
    (is (= "public-docs" (:effective-selected-dataset-config-key scope-state)))
    (is (= 1 (:resolution-level scope-state)))
    (is (true? (:scope-complete? scope-state)))))

(deftest chat-scope-derivation-discards-remembered-options-after-access-changes
  (let [agent {:id "agent/current"
               :allowed-dataset-scopes [{:tenant "current-tenant"
                                         :dataset-config-key "current-dataset"}]}
        tenant-state (ui/derive-chat-tenant-state
                      (assoc (ui/default-playground-chat-state)
                             :selected-agent-id "agent/revoked"
                             :selected-tenant "revoked-tenant"
                             :selected-dataset-config-key "revoked-dataset")
                      "user-1"
                      ["current-tenant"]
                      [agent]
                      {:options []})
        scope-state (ui/derive-chat-scope-state
                     tenant-state
                     {:dataset-config-key "current-dataset"}
                     [{:value "current-dataset" :label "Current dataset"}])]
    (is (= "agent/current" (:effective-agent-id tenant-state)))
    (is (= "current-tenant" (:effective-selected-tenant tenant-state)))
    (is (= "current-dataset" (:effective-selected-dataset-config-key scope-state)))
    (is (true? (:scope-complete? scope-state)))))

(deftest resolve-selected-dataset-config-prefers-v2-runtime-config
  (testing "Selected dataset config uses V2 runtime values when agent scope is available"
    (with-redefs [db/get-conn (fn [] (atom nil))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-dataset-by-ref (fn [_ dataset-ref master-key]
                                                 (is (= {:tenant "ka"
                                                         :dataset-config-key "dev"}
                                                        dataset-ref))
                                                 (is (= "master-key" master-key))
                                                 {:id "assistant"
                                                  :dataset-config-key "dev"
                                                  :docs-collection "docs"})
                  cfg/get-runtime-skill-config-v2-with-trace (fn [opts]
                                                               (is (= {:tenant "ka"
                                                                       :tenant-config-key "default"
                                                                       :agent-id "agent/custom-agent"}
                                                                      opts))
                                                               {:config {:rerank-top-k 41
                                                                         :query-planner-prompt "v2 prompt"}})]
      (is (= {:id "assistant"
              :dataset-config-key "dev"
              :docs-collection "docs"
              :rerank-top-k 41
              :query-planner-prompt "v2 prompt"}
             (ui/resolve-selected-dataset-config "ka" "dev" "agent/custom-agent"))))))

(deftest resolve-selected-dataset-config-without-agent-returns-dataset-only
  (testing "Selected dataset config does not read legacy runtime skill config without agent scope"
    (with-redefs [db/get-conn (fn [] (atom nil))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-dataset-by-ref (fn [_ dataset-ref master-key]
                                                 (is (= {:tenant "ka"
                                                         :dataset-config-key "dev"}
                                                        dataset-ref))
                                                 (is (= "master-key" master-key))
                                                 {:id "assistant"
                                                  :dataset-config-key "dev"
                                                  :docs-collection "docs"})
                  cfg/get-runtime-skill-config-v2-with-trace (fn [& _]
                                                               (throw (ex-info "V2 runtime config should not be read" {})))]
      (is (= {:id "assistant"
              :dataset-config-key "dev"
              :docs-collection "docs"}
             (ui/resolve-selected-dataset-config "ka" "dev" nil))))))

(deftest normalize-debug-playground-mode-falls-back-to-full
  (testing "Unknown values do not activate a partial playground by accident"
    (is (= "full" (ui/normalize-debug-playground-mode nil)))
    (is (= "full" (ui/normalize-debug-playground-mode "")))
    (is (= "full" (ui/normalize-debug-playground-mode "unknown")))))

(deftest normalize-debug-playground-mode-accepts-known-modes
  (testing "Known playground isolation modes are normalized consistently"
    (is (= "bare" (ui/normalize-debug-playground-mode "bare")))
    (is (= "scope" (ui/normalize-debug-playground-mode " scope ")))
    (is (= "inspect" (ui/normalize-debug-playground-mode "inspect")))
    (is (= "data" (ui/normalize-debug-playground-mode "DATA")))
    (is (= "no-effects" (ui/normalize-debug-playground-mode "NO-EFFECTS")))
    (is (= "full" (ui/normalize-debug-playground-mode "full")))))

(deftest conversation-scope-label-is-agent-first
  (testing "Conversation labels prefer the agent identity and keep dataset scope as context"
    (is (= "Altinn Docs Agent • altinn-docs / dev"
           (ui/conversation-scope-label
            {:conversation/agent-id "agent/altinn-docs"
             :conversation/tenant "altinn-docs"
             :conversation/dataset-config-key "dev"}
            {"agent/altinn-docs" "Altinn Docs Agent"})))))

(deftest dataset-scope-options-collapse-materializations-to-one-dataset
  (testing "Playground dataset choices collapse multiple materialization-backed scopes into one dataset option"
    (with-redefs [db/get-conn (fn [] (atom nil))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/list-config-nodes (fn [& _]
                                                (throw (ex-info "Should not enumerate config nodes when explicit dataset scopes exist" {})))
                  config-db/get-dataset-by-ref (fn [_ {:keys [dataset-config-key]} _]
                                                 (case dataset-config-key
                                                   "default-altinn-docs" {:dataset-id "public-docs"
                                                                          :dataset-config-key "public-docs"}
                                                   "default-digdir-docs" {:dataset-id "public-docs"
                                                                          :dataset-config-key "public-docs"}
                                                   nil))
                  config-db/get-dataset-record (fn [_ dataset-id]
                                                 (is (= "public-docs" dataset-id))
                                                 {:dataset/id dataset-id
                                                  :dataset/name "Public Docs"})
                  config-db/get-config-node-by-tenant-config-key (fn [& _] nil)]
      (is (= [{:value "public-docs"
               :label "Public Docs"}]
             (ui/dataset-scope-options "digdir"
                                       [{:tenant "digdir" :dataset-config-key "default-altinn-docs"}
                                        {:tenant "digdir" :dataset-config-key "default-digdir-docs"}]))))))

(deftest dataset-scope-options-fallback-to-canonical-dataset-ids
  (testing "Without explicit agent scopes, Playground uses tenant-visible datasets rather than config keys"
    (with-redefs [db/get-conn (fn [] (atom nil))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/list-datasets (fn [_ tenant _]
                                            (is (= "digdir" tenant))
                                            ["altinn-docs" "digdir-docs"])
                  config-db/get-dataset-pipeline (fn [_ pipeline-id]
                                                   {:dataset.pipeline/id pipeline-id
                                                    :dataset.pipeline/dataset {:dataset/id "public-docs"}})
                  config-db/get-dataset-by-ref (fn [_ {:keys [dataset-config-key]} _]
                                                 (is (= "public-docs" dataset-config-key))
                                                 {:dataset-id "public-docs"
                                                  :dataset-config-key "public-docs"
                                                  :dataset-node-id "dataset/digdir/public-docs/default"})
                  config-db/get-dataset-record (fn [_ dataset-id]
                                                 (is (= "public-docs" dataset-id))
                                                 {:dataset/id dataset-id
                                                  :dataset/name "Public Docs"})
                  config-db/get-config-node (fn [_ node-id]
                                              (is (= "dataset/digdir/public-docs/default" node-id))
                                              {:config.node/id node-id
                                               :config.node/label "Public Docs"})
                  config-db/get-config-node-by-tenant-config-key (fn [& _] nil)]
      (is (= [{:value "public-docs"
               :label "Public Docs"}]
             (ui/dataset-scope-options "digdir" []))))))

(deftest agent-status-messages-from-trace
  (testing "Agent status falls back to trace reasoning and skips blank entries"
    (is (= ["Starter søk etter årsverk Digdir 2022"
            "Leser mest relevante treff"]
           (ui/agent-status-messages
            {:agent-trace [{:iteration 2 :reasoning "Leser mest relevante treff"}
                           {:iteration 1 :reasoning " Starter søk etter årsverk Digdir 2022 "}
                           {:iteration 3 :reasoning "   "}
                           {:iteration 4}]})))))

(deftest agent-status-messages-prefers-explicit-status
  (testing "Explicit agent status messages have precedence over trace reasoning"
    (is (= ["status fra workspace"]
           (ui/agent-status-messages
            {:agent-status [" status fra workspace "]
             :agent-trace [{:iteration 1 :reasoning "trace-reasoning"}]})))))
