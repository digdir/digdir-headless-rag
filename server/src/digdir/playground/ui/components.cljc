(ns digdir.playground.ui.components
  "Shared base UI components for Playground."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [clojure.edn :as edn]
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])
            #?(:clj [hiccup2.core :as hiccup])
            #?(:clj [hiccup.util :as hiccup.util])
            [digdir.playground.citations :as citations]
            #?(:clj [digdir.playground.core :as playground])
            [digdir.i18n :refer [t]])
  #?(:clj
     (:import (org.jsoup Jsoup)
              (org.jsoup.nodes Document$OutputSettings)
              (org.jsoup.safety Safelist))))

;; =========Styles=========

(def source-title-ellipsis-style
  {:white-space   "nowrap"
   :overflow      "hidden"
   :text-overflow "ellipsis"})

(def source-heading-ellipsis-style
  {:white-space   "nowrap"
   :overflow      "hidden"
   :text-overflow "ellipsis"})

(def source-modal-title-style
  {:font-weight   "600"
   :font-size     "1.05rem"
   :margin-bottom "0.2rem"
   :color         "#1f2937"})

(def source-modal-heading-style
  {:font-size     "0.78rem"
   :color         "#6b7280"
   :margin-bottom "0.5rem"})

(def modal-backdrop-style
  {:position        "fixed"
   :top             "0"
   :left            "0"
   :width           "100%"
   :height          "100%"
   :background-color "rgba(0, 0, 0, 0.5)"
   :display         "flex"
   :justify-content "center"
   :align-items     "center"
   :z-index         "1000"})

(def modal-content-style
  {:background    "white"
   :padding       "1.5rem"
   :border-radius "8px"
   :max-width     "800px"
   :width         "90%"
   :max-height    "80%"
   :overflow-y    "auto"
   :box-shadow    "0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)"})

(def reference-panel-style
  {:position "relative"
   :width "min(38rem, 42vw)"
   :min-width "22rem"
   :height "100%"
   :flex "0 0 auto"
   :background "white"
   :padding "1.25rem 1.5rem"
   :overflow-y "auto"
   :border-left "1px solid #e2e8f0"
   :box-shadow "-8px 0 24px rgba(15, 23, 42, 0.08)"
   :transition "width 180ms ease, transform 180ms ease"})

(defn badge-style
  [bg color]
  {:display "inline-block"
   :padding "0.15rem 0.4rem"
   :border-radius "999px"
   :background bg
   :color color
   :font-size "0.68rem"
   :font-weight "600"})

(defn search-type-badge-style [search-type]
  {:background (case search-type
                 :phrase "#dbeafe"
                 :metadata "#fef3c7"
                 :content "#dcfce7"
                 "#f3f4f6")
   :color (case search-type
            :phrase "#1e40af"
            :metadata "#92400e"
            :content "#166534"
            "#374151")
   :padding "0.25rem 0.5rem"
   :border-radius "4px"
   :font-size "0.75rem"
   :font-weight "600"})

;; =========Markdown Rendering=========

#?(:clj
   (def ^:private safe-html-tags
     "Tags allowed to pass through unescaped from LLM-generated markdown.
      Excludes resource-loading (a, img, video, iframe, svg…), input controls
      (form, input, button…), and script-bearing tags (script, style, link…)."
     #{"abbr" "address" "article" "aside" "b" "bdi" "bdo" "blockquote"
       "br" "caption" "cite" "code" "col" "colgroup" "data" "dd" "del"
       "details" "dfn" "div" "dl" "dt" "em" "figcaption" "figure" "footer"
       "h1" "h2" "h3" "h4" "h5" "h6" "header" "hr" "i" "ins" "kbd" "li"
       "main" "mark" "nav" "ol" "p" "pre" "q" "rp" "rt" "ruby" "s" "samp"
       "section" "small" "span" "strong" "sub" "summary" "sup" "table"
       "tbody" "td" "tfoot" "th" "thead" "time" "tr" "u" "ul" "var" "wbr"}))

#?(:clj
   (defn- safe-html-fragment?
     "True when every tag in raw-html is on the safe list and the fragment
      carries no inline event handlers or javascript/data/vbscript URIs."
     [raw-html]
     (let [tag-names (->> (re-seq #"</?\s*([a-zA-Z][a-zA-Z0-9-]*)" raw-html)
                          (map (comp str/lower-case second)))]
       (and (seq tag-names)
            (every? safe-html-tags tag-names)
            (not (re-find #"(?i)\son\w+\s*=" raw-html))
            (not (re-find #"(?i)(?:href|src|action|formaction|xlink:href)\s*=\s*[\"']?\s*(?:javascript|data|vbscript):" raw-html))))))

#?(:clj
   (defn- html-node->hiccup
     "Render HTML nodes from nextjournal/markdown: pass safe tags through as
      raw HTML, normalize <br> variants, and escape anything else."
     [{:keys [content]}]
     (let [raw-html (->> content (map :text) (apply str))
           normalized (-> raw-html str/trim str/lower-case)]
       (cond
         (contains? #{"<br>" "<br/>" "<br />"} normalized) [:br]
         (safe-html-fragment? raw-html) (hiccup.util/raw-string raw-html)
         :else raw-html))))

#?(:clj
   (defn- normalize-inline-numbered-sections [content]
     (some-> content
             (str/replace #"([:.!?\]])\s+(\d+\.\s+(?:\*\*|[\p{Lu}\p{Lt}\d]))"
                          "$1\n\n$2"))))

#?(:clj
   (defn- normalize-latex-math-delimiters [content]
     (let [s (some-> content str)]
       (when s
         (let [display-lines (loop [remaining-lines (str/split-lines s)
                                    in-display? false
                                    body-lines []
                                    out-lines []]
                               (if-let [line (first remaining-lines)]
                                 (let [trimmed (str/trim line)]
                                   (cond
                                     (and (not in-display?) (= "\\[" trimmed))
                                     (recur (rest remaining-lines) true [] out-lines)
                                     (and in-display? (= "\\]" trimmed))
                                     (recur (rest remaining-lines) false [] (conj out-lines "$$" (str/join "\n" body-lines) "$$"))
                                     in-display? (recur (rest remaining-lines) true (conj body-lines line) out-lines)
                                     :else (recur (rest remaining-lines) false body-lines (conj out-lines line))))
                                 (cond-> out-lines in-display? (into ["\\[" (str/join "\n" body-lines)]))))]
           (-> (str/join "\n" display-lines)
               (str/replace #"(?s)\\\((.+?)\\\)"
                            (fn [[match body]]
                              (if (or (re-find #"(?i)\\(?:left|right)\s*$" body) (str/includes? body "\n"))
                                match
                                (str "$" body "$"))))))))))

#?(:clj
   (def ^:private markdown-html-safelist
     ;; Build this explicitly instead of extending Safelist/relaxed: rendered
     ;; answers are untrusted document/LLM output, and a relaxed browser-facing
     ;; allowlist can quietly grow when jsoup changes. Images are intentionally
     ;; absent: remote images leak operator network information and data: SVGs
     ;; have historically been an execution surface.
     (doto (Safelist/none)
       (.addTags (into-array String
                            ["a" "abbr" "address" "article" "aside" "b" "bdi" "bdo"
                             "blockquote" "br" "caption" "cite" "code" "col" "colgroup"
                             "data" "dd" "del" "details" "dfn" "div" "dl" "dt" "em"
                             "figcaption" "figure" "footer" "h1" "h2" "h3" "h4" "h5"
                             "h6" "header" "hr" "i" "ins" "kbd" "li" "main" "mark"
                             "nav" "ol" "p" "pre" "q" "rp" "rt" "ruby" "s" "samp"
                             "section" "small" "span" "strong" "sub" "summary" "sup"
                             "table" "tbody" "td" "tfoot" "th" "thead" "time" "tr"
                             "u" "ul" "var" "wbr"]))
       ;; Markdown and formula rendering need classes; citation click handling
       ;; needs its numeric data attribute. Inline style is deliberately not
       ;; allowed—the citation presentation lives in index.css instead.
       (.addAttributes ":all" (into-array String ["class"]))
       (.addAttributes "a" (into-array String ["href" "title"]))
       (.addAttributes "abbr" (into-array String ["title"]))
       (.addAttributes "blockquote" (into-array String ["cite"]))
       (.addAttributes "col" (into-array String ["span"]))
       (.addAttributes "colgroup" (into-array String ["span"]))
       (.addAttributes "data" (into-array String ["value"]))
       (.addAttributes "del" (into-array String ["cite" "datetime"]))
       (.addAttributes "details" (into-array String ["open"]))
       (.addAttributes "ins" (into-array String ["cite" "datetime"]))
       (.addAttributes "li" (into-array String ["value"]))
       (.addAttributes "ol" (into-array String ["start" "reversed" "type"]))
       (.addAttributes "q" (into-array String ["cite"]))
       (.addAttributes "span" (into-array String ["data-index"]))
       (.addAttributes "td" (into-array String ["colspan" "rowspan" "headers"]))
       (.addAttributes "th" (into-array String ["colspan" "rowspan" "headers" "scope"]))
       (.addAttributes "time" (into-array String ["datetime"]))
       (.addProtocols "a" "href" (into-array String ["http" "https" "mailto"]))
       (.addProtocols "blockquote" "cite" (into-array String ["http" "https"]))
       (.addProtocols "del" "cite" (into-array String ["http" "https"]))
       (.addProtocols "ins" "cite" (into-array String ["http" "https"]))
       (.addProtocols "q" "cite" (into-array String ["http" "https"]))
       (.preserveRelativeLinks true))))

#?(:clj
   (defn- sanitize-rendered-html [html]
     ;; A fixed HTTPS base lets jsoup validate relative hrefs as web links;
     ;; preserveRelativeLinks ensures that artificial origin is never emitted.
     (Jsoup/clean html "https://markdown.invalid/" markdown-html-safelist
                  (doto (Document$OutputSettings.)
                    (.prettyPrint false)))))

#?(:clj
   (defn render-markdown-to-html [content]
     (when (and content (not (str/blank? content)))
       (try
         (let [normalized-content (-> content
                                      normalize-inline-numbered-sections
                                      normalize-latex-math-delimiters)
               parsed (md/parse normalized-content)
               renderers (assoc md.transform/default-hiccup-renderers
                                :softbreak (fn [_ctx _node] [:br])
                                :html-inline (fn [_ctx node] (html-node->hiccup node))
                                :html-block (fn [_ctx node] (html-node->hiccup node)))
               hiccup-content (md.transform/->hiccup renderers parsed)]
           (sanitize-rendered-html (str (hiccup/html hiccup-content))))
         (catch Exception _
           (sanitize-rendered-html
            (-> content (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\n" "<br>"))))))))

#?(:clj
   (defn render-inline-markdown-to-html [content]
     (when-let [html (render-markdown-to-html content)]
       (-> html
           (str/replace #"(?is)^<p>" "")
           (str/replace #"(?is)</p>$" "")))))

(defn set-markdown-html! [node html]
  #?(:cljs (when (and node html)
             (.add (.-classList node) "markdown-content")
             (set! (.-innerHTML node) html)
             (when-let [katex (some-> js/window (aget "katex"))]
               (doseq [formula-el (array-seq (.querySelectorAll node ".formula"))]
                 (let [formula-text (.-textContent formula-el)
                       display-mode? (= "FIGURE" (.-tagName formula-el))]
                   (js-invoke katex "render" formula-text formula-el #js {:displayMode display-mode? :throwOnError false})))))
     :clj nil))

(defn markdown-preview [content]
  (let [s (some-> content str/trim)]
    (when-not (str/blank? s)
      (or (first (remove str/blank? (str/split s #"\n\s*\n"))) s))))

#?(:clj
   (defn metadata->markdown
     "Normalize chunk metadata into markdown for UI rendering."
     [metadata]
     (letfn [(header-entry? [k]
               (boolean (re-matches #"(?i)^header\s+\d+$" (str k))))
             (header-level [k]
               (or (some->> (re-find #"(?i)^header\s+(\d+)$" (str k)) second edn/read-string)
                   1))

             (map->markdown [m]
               (let [entries (seq m)
                     header-lines (->> entries
                                       (filter (comp header-entry? key))
                                       (sort-by (comp header-level key))
                                       (keep (fn [[k v]]
                                               (let [value (str/trim (str (or v "")))]
                                                 (when-not (str/blank? value)
                                                   (str (apply str (repeat (header-level k) "#"))
                                                        " "
                                                        value)))))
                                       (vec))
                     other-lines (->> entries
                                      (remove (comp header-entry? key))
                                      (keep (fn [[k v]]
                                              (let [value (str/trim (str (or v "")))]
                                                (when-not (str/blank? value)
                                                  (str "- **" k "**: " value)))))
                                      (vec))]
                 (str/join "\n\n" (concat header-lines other-lines))))]
       (cond
         (nil? metadata) nil
         (map? metadata) (map->markdown metadata)
         (string? metadata)
         (let [s (str/trim metadata)]
           (if (str/blank? s)
             nil
             (try
               (let [parsed (edn/read-string s)]
                 (if (map? parsed)
                   (map->markdown parsed)
                   s))
               (catch Exception _
                 s))))
         :else (str metadata)))))

#?(:clj
   (defn render-markdown-with-citations [content]
     (when-let [html (render-markdown-to-html content)]
       (str/replace html #"\[(\d+)\]"
                    "<span class=\"citation\" data-index=\"$1\">[$1]</span>"))))

;; =========Source Display Helpers=========

(defn source-display-data
  ([chunk] (source-display-data chunk nil))
  ([chunk docs-collection-name]
   (citations/source-display-data chunk docs-collection-name)))

(defn citation-source-display-data
  ([citation used-chunks] (citation-source-display-data citation used-chunks nil))
  ([citation used-chunks docs-collection-name] (citation-source-display-data citation used-chunks docs-collection-name nil))
  ([citation used-chunks docs-collection-name fetched-chunk]
   (citations/citation-source-display-data citation used-chunks docs-collection-name fetched-chunk)))

;; =========Components=========

(e/defn HeadingLineMarkdown [heading-line style]
  (e/client
   (when heading-line
     (let [heading-html (e/server (render-inline-markdown-to-html (e/client heading-line)))]
       (dom/div
        (dom/props {:style style})
        (if heading-html
          (set-markdown-html! dom/node heading-html)
          (dom/text heading-line)))))))

(e/defn SourceTitleMarkdown [title style]
  (e/client
   (let [title      (or title "Untitled source")
         title-html (e/server (render-inline-markdown-to-html (e/client title)))]
     (dom/span
      (dom/props {:style style})
      (if title-html
        (set-markdown-html! dom/node title-html)
        (dom/text title))))))

(e/defn SourceTitleAndHeading [source title-style heading-style]
  (e/client
   (SourceTitleMarkdown (:title source) title-style)
   (when-let [heading-line (:heading-line source)]
     (HeadingLineMarkdown heading-line heading-style))))

(e/defn ChunkModalHeader [source chunk on-close]
  (e/client
   (let [chunk-id (or (:chunk_id chunk) (:chunk-id chunk))]
     (dom/div
      (dom/props {:style {:display         "flex"
                          :justify-content "space-between"
                          :align-items     "flex-start"
                          :margin-bottom   "1rem"}})
      (dom/div
       (SourceTitleAndHeading source source-modal-title-style source-modal-heading-style)
       (when chunk-id
         (dom/div
          (dom/props {:style {:font-size     "0.75rem"
                              :color         "#6b7280"
                              :margin-bottom "0.4rem"
                              :font-family   "ui-monospace, SFMono-Regular, Menlo, monospace"}})
          (dom/text (str (t :playground/chunk-id) ": " chunk-id))))
       (dom/div
        (dom/props {:style {:display   "flex"
                            :gap       "0.5rem"
                            :flex-wrap "wrap"}})
        (let [search-types (or (:search-types chunk) #{})
              rank         (or (:rank chunk) 0)
              percentage   (Math/round (* rank 100))]
          (e/for [search-type (e/diff-by identity (vec search-types))]
            (dom/span
             (dom/props {:style (search-type-badge-style search-type)})
             (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)")))))))
      (dom/button
       (dom/props {:style {:background  "none"
                           :border      "none"
                           :font-size   "1.5rem"
                           :cursor      "pointer"
                           :color       "#6b7280"
                           :padding     "0"
                           :line-height "1"}
                   :aria-label (t :playground/close-reference)
                   :title (t :playground/close-reference)})
       (dom/text "×")
       ;; Closing removes this component from the Electric graph. A Token tied
       ;; to the disappearing branch can be invalidated before acknowledgement,
       ;; so keep this purely client-side state transition as a direct handler.
       (dom/On "click" (fn [_] (on-close)) nil))))))

(e/defn SourceCard [idx citation used-chunks docs-collection-name chunks-collection on-select-chunk]
  (e/client
   (let [chunk-id (:chunk-id citation)
         fetched-chunk (when (and chunk-id docs-collection-name chunks-collection)
                         (e/server
                          (playground/fetch-chunk-by-id chunks-collection docs-collection-name (e/client chunk-id))))
         chunk (first (filter #(= chunk-id (:chunk_id %)) used-chunks))
         rank (or (:rank chunk) 0)
         percentage (Math/round (* rank 100))
         source (citation-source-display-data citation used-chunks docs-collection-name fetched-chunk)
         content (or (:content_markdown chunk) "")
         excerpt (markdown-preview content)
         excerpt-html (e/server (render-markdown-to-html (e/client excerpt)))]
     (dom/div
      (dom/props {:style {:padding "0.5rem 0.75rem" :border "1px solid #e5e7eb" :border-radius "6px" :background "white" :cursor "pointer"}})
      (dom/On "click" #(when chunk-id (on-select-chunk chunk-id)) nil)
      (dom/div
       (dom/props {:style {:display "flex" :justify-content "space-between" :align-items "center" :margin-bottom "0.25rem"}})
       (dom/div
        (dom/props {:style {:display "flex" :gap "0.5rem" :align-items "center"}})
        (dom/span (dom/props {:style {:color "#2563eb" :font-weight "700" :font-size "0.8rem"}}) (dom/text (str "[" (:index citation) "]")))
        (SourceTitleMarkdown (:title source) (merge {:font-weight "500" :font-size "0.8rem" :color "#1f2937" :display "inline-block" :max-width "48ch"} source-title-ellipsis-style)))
       (dom/span (dom/props {:style {:font-size "0.7rem" :color "#059669" :font-weight "600" :background "#ecfdf5" :padding "0.1rem 0.4rem" :border-radius "3px"}}) (dom/text (str percentage "%"))))
      (when-let [heading-line (:heading-line source)]
        (HeadingLineMarkdown heading-line (merge {:font-size "0.66rem" :color "#6b7280" :margin-bottom "0.2rem"} source-heading-ellipsis-style)))
      (dom/div
       (dom/props {:style {:font-size "0.7rem" :color "#6b7280" :line-height "1.4" :max-height "40px" :overflow "hidden"}})
       (when excerpt-html (set-markdown-html! dom/node excerpt-html)))))))

(e/defn SourceCardsPanel [diagnostics on-select-chunk]
  (e/client
   (let [citations (:citations diagnostics)
         used-chunks (:used-chunks diagnostics)
         docs-collection-name (:docs-collection-name diagnostics)
         chunks-collection (:chunks-collection-name diagnostics)]
     (when (seq citations)
       (let [!show-all (atom false)
             show-all (e/watch !show-all)
             visible-citations (if show-all citations (take 3 citations))
             remaining (- (count citations) 3)]
         (dom/div
          (dom/props {:style {:margin-top "0.75rem" :padding "0.5rem" :background "#f9fafb" :border-radius "6px" :border "1px solid #e5e7eb"}})
          (dom/div
           (dom/props {:style {:font-weight "600" :font-size "0.75rem" :color "#374151" :margin-bottom "0.5rem" :padding-bottom "0.25rem" :border-bottom "1px solid #e5e7eb"}})
           (dom/text (t :playground/sources-count (count citations))))
          (dom/div
           (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.375rem"}})
           (e/for [[idx citation] (e/diff-by first (map-indexed vector visible-citations))]
             (SourceCard idx citation used-chunks docs-collection-name chunks-collection on-select-chunk)))
          (when (and (not show-all) (pos? remaining))
            (dom/button
             (dom/props {:style {:background "none" :border "none" :color "#3b82f6" :cursor "pointer" :font-size "0.7rem" :padding "0.25rem 0" :margin-top "0.25rem"}})
             (dom/text (str "+ " (t :playground/more-count remaining)))
             (let [[t err] (e/Token (dom/On "click" identity nil))]
               (when t (reset! !show-all true) (t)))))))))))

(e/defn ReferenceNavigation
  [position total on-previous on-next]
  (e/client
   (when (> (or total 0) 1)
     (dom/nav
      (dom/props {:style {:display "flex"
                          :align-items "center"
                          :justify-content "space-between"
                          :gap "0.75rem"
                          :padding "0 0 0.85rem"
                          :margin-bottom "0.85rem"
                          :border-bottom "1px solid #e2e8f0"}
                  :aria-label (t :playground/citation-navigation)})
      (dom/button
       (dom/props {:style {:padding "0.35rem 0.65rem"
                           :background "white"
                           :border "1px solid #cbd5e1"
                           :border-radius "6px"
                           :cursor (if (pos? position) "pointer" "not-allowed")}
                   :disabled (not (pos? position))
                   :aria-label (t :playground/previous-citation)})
       (dom/text (str "← " (t :playground/previous)))
       (when (pos? position)
         (let [[tok err] (e/Token (dom/On "click" identity nil))]
           (when tok (on-previous) (tok)))))
      (dom/span
       (dom/props {:style {:font-size "0.875rem"
                           :font-weight "600"
                           :color "#475569"}
                   :aria-live "polite"})
       (dom/text (t :playground/citation-position (inc position) total)))
      (dom/button
       (dom/props {:style {:padding "0.35rem 0.65rem"
                           :background "white"
                           :border "1px solid #cbd5e1"
                           :border-radius "6px"
                           :cursor (if (< position (dec total)) "pointer" "not-allowed")}
                   :disabled (not (< position (dec total)))
                   :aria-label (t :playground/next-citation)})
       (dom/text (str (t :playground/next) " →"))
       (when (< position (dec total))
         (let [[tok err] (e/Token (dom/On "click" identity nil))]
           (when tok (on-next) (tok)))))))))

(e/defn ChunkDetailPanel
  "Non-blocking side panel for reading a source while keeping the chat visible."
  [source chunk on-close position total on-previous on-next]
  (e/client
   (dom/aside
    (dom/props {:style reference-panel-style
                :role "complementary"
                :aria-label (t :playground/reference-panel)})
    (ReferenceNavigation position total on-previous on-next)
    (ChunkModalHeader source chunk on-close)
    (when-let [metadata (e/server (metadata->markdown (e/client (:metadata chunk))))]
      (let [metadata-html (e/server (render-markdown-to-html metadata))]
        (dom/div
         (dom/props {:style {:font-size "0.85rem"
                             :color "#6b7280"
                             :margin-bottom "0.75rem"
                             :padding "0.5rem"
                             :background "#f3f4f6"
                             :border-radius "4px"}})
         (when metadata-html
           (set-markdown-html! dom/node metadata-html)))))
    (let [content (or (:content_markdown chunk) (t :playground/no-source-content))
          html (e/server (render-markdown-to-html content))]
      (dom/div
       (dom/props {:style {:line-height "1.6"
                           :font-size "0.9rem"}})
       (when html
         (set-markdown-html! dom/node html)))))))

(e/defn DiagnosticsChunkPanel
  "Fetch and display full chunk content in the reference side panel.

   ⚠️ `tenant` IS LOAD-BEARING, NOT OPTIONAL METADATA. `fetch-chunk-by-id`
   resolves Typesense through `make-ts-settings`, which since #476 has NO
   default tenant and throws \"Typesense settings requested with no tenant\".
   This call site passed the 3-arity, so opts were nil, so every fetch here
   failed — the same defect #516 fixed in the admin console (#479), at a call
   site that sweep missed. Second door, same room.

   The failure was invisible because the fetch swallows its exception and
   returns nil, and a nil chunk renders as BOTH \"Untitled source\" (no title,
   no headings) and \"No source content available\" (no content). One nil, two
   strings, and nothing on screen saying which of the two it was — which is how
   this survived to reach a human tester."
  [chunk-id docs-collection chunks-collection on-close position total on-previous on-next tenant]
  (e/client
   (when chunk-id
     (let [chunk (e/server
                  (playground/fetch-chunk-by-id
                   chunks-collection docs-collection (e/client chunk-id)
                   (when-let [t (e/client tenant)] {:tenant t})))
           ;; Degrade to the citation's own identity rather than to a blank
           ;; document, and SAY WHICH BRANCH WAS TAKEN. A panel that silently
           ;; renders an empty document is indistinguishable from a document
           ;; that is genuinely empty, which is the property that let a broken
           ;; fetch look like a data problem.
           source (if chunk
                    (source-display-data chunk docs-collection)
                    {:title (str "Source " chunk-id " could not be loaded")
                     :headings []
                     :heading-line (if (str/blank? (str tenant))
                                     "No tenant in scope — Typesense cannot be resolved (#476)."
                                     "The chunk was not found in the configured collections.")
                     :chunk-id chunk-id})]
       (ChunkDetailPanel source chunk on-close position total on-previous on-next)))))

;; Compatibility alias for older call sites while the side-panel terminology
;; propagates through downstream namespaces.
(e/defn DiagnosticsChunkModal [chunk-id docs-collection chunks-collection on-close]
  (DiagnosticsChunkPanel chunk-id docs-collection chunks-collection on-close 0 1 nil nil nil))
