(ns digdir.docs.website
  "Website document source - imports markdown from sitemap URLs.

   This pipeline:
   1. Fetches and parses a sitemap XML
   2. Downloads markdown files from the URLs
   3. Chunks content by headers
   4. Generates search phrases via LLM
   5. Stores documents, chunks, and phrases in TypeSense"
  {:clj-kondo/ignore true}
  (:require [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.pipeline.orchestration :as orch]
            [digdir.docs.pipeline.search-phrases :as search-phrases]
            [digdir.docs.pipeline.telemetry :as telemetry]
            [digdir.docs.pipeline.protocol :as proto]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.java.io :as jio]
            [missionary.core :as m]
            [taoensso.telemere :as t]
            [hyperfiddle.rcf :refer [tests tap %]])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (java.net HttpURLConnection Inet4Address Inet6Address InetAddress URI)
           (java.nio.charset StandardCharsets)
           (java.util Locale)))

;; ============================================================================
;; URL Helper Functions
;; ============================================================================

(defn make-relative-url
  "Converts an absolute URL to a relative URL by removing the base-url"
  [base-url absolute-url]
  (if (and base-url (str/starts-with? absolute-url base-url))
    (subs absolute-url (count base-url))
    absolute-url))

(defn make-absolute-url
  "Converts a relative URL to an absolute URL by prepending the base-url"
  [base-url relative-url]
  (if (and base-url (not (str/starts-with? relative-url "http")))
    (str base-url relative-url)
    relative-url))

(tests
 "URL conversion functions"
 (make-relative-url "http://localhost:1313" "http://localhost:1313/en/about/index.md")
 := "/en/about/index.md"

 (make-absolute-url "http://localhost:1313" "/en/about/index.md")
 := "http://localhost:1313/en/about/index.md"

 (make-absolute-url "http://localhost:1313" "http://example.com/test.md")
 := "http://example.com/test.md"

 (make-relative-url nil "http://localhost:1313/en/about/index.md")
 := "http://localhost:1313/en/about/index.md")

;; ============================================================================
;; Sitemap Parsing
;; ============================================================================

(def default-fetch-policy
  {:connect-timeout-ms 5000
   :read-timeout-ms 15000
   :max-sitemap-bytes (* 5 1024 1024)
   :max-markdown-bytes (* 10 1024 1024)
   :max-redirects 5
   :max-sitemap-depth 5})

(def ^:dynamic *host-resolver*
  "Indirection makes DNS/address policy deterministic in regression tests."
  (fn [host] (seq (InetAddress/getAllByName host))))

(def ^:dynamic *active-fetch-policy* nil)

(defn- unsigned-byte [b]
  (bit-and 0xff (int b)))

(defn private-or-reserved-address?
  "True for addresses a website pipeline must never contact. This includes
   local/private ranges and non-routable documentation, carrier NAT,
   benchmarking, multicast and future-use ranges."
  [^InetAddress address]
  (let [bytes (.getAddress address)]
    (or (.isAnyLocalAddress address)
        (.isLoopbackAddress address)
        (.isLinkLocalAddress address)
        (.isSiteLocalAddress address)
        (.isMulticastAddress address)
        (cond
          (instance? Inet4Address address)
          (let [[a b c] (map unsigned-byte bytes)]
            (or (= a 0)
                (= a 10)
                (= a 127)
                (and (= a 100) (<= 64 b 127))
                (and (= a 169) (= b 254))
                (and (= a 172) (<= 16 b 31))
                (and (= a 192) (= b 0) (= c 0))
                (and (= a 192) (= b 0) (= c 2))
                (and (= a 192) (= b 88) (= c 99))
                (and (= a 192) (= b 168))
                (and (= a 198) (or (= b 18) (= b 19)))
                (and (= a 198) (= b 51) (= c 100))
                (and (= a 203) (= b 0) (= c 113))
                (>= a 224)))

          (instance? Inet6Address address)
          (let [a (unsigned-byte (aget bytes 0))
                b (unsigned-byte (aget bytes 1))
                c (unsigned-byte (aget bytes 2))
                d (unsigned-byte (aget bytes 3))]
            (or (= 0xfc (bit-and a 0xfe))       ; fc00::/7 unique-local
                (and (= a 0x20) (= b 0x01)
                     (= c 0x0d) (= d 0xb8))))  ; documentation

          :else true))))

(defn- normalized-host
  [^URI uri]
  (some-> (.getHost uri)
          (.toLowerCase Locale/ROOT)
          (str/replace #"\.$" "")))

(defn validate-fetch-uri!
  "Validate scheme, authority, host allowlist, and every resolved address.
   Returns the parsed URI or throws before opening a connection."
  [url {:keys [allowed-hosts]}]
  (let [uri (try (URI. (str url))
                 (catch Exception e
                   (throw (ex-info "Invalid website fetch URL" {:url url} e))))
        scheme (some-> (.getScheme uri) str/lower-case)
        host (normalized-host uri)]
    (when-not (#{"http" "https"} scheme)
      (throw (ex-info "Website fetch URL must use HTTP or HTTPS"
                      {:url (str url) :scheme scheme})))
    (when (or (str/blank? host) (.getUserInfo uri))
      (throw (ex-info "Website fetch URL has an invalid authority" {:url (str url)})))
    (when (and (seq allowed-hosts) (not (contains? allowed-hosts host)))
      (throw (ex-info "Website fetch host is not allowed"
                      {:url (str url) :host host :allowed-hosts allowed-hosts})))
    (let [addresses (try (seq (*host-resolver* host))
                         (catch Exception e
                           (throw (ex-info "Website fetch host could not be resolved"
                                           {:url (str url) :host host} e))))]
      (when-not addresses
        (throw (ex-info "Website fetch host resolved to no addresses"
                        {:url (str url) :host host})))
      (when-let [blocked (first (filter private-or-reserved-address? addresses))]
        (throw (ex-info "Website fetch resolved to a private or reserved address"
                        {:url (str url) :host host :address (.getHostAddress ^InetAddress blocked)}))))
    uri))

(defn website-fetch-policy
  "Build the allowlist and resource limits for one configured website source.
   The sitemap and base URL hosts are trusted configuration; sitemap content
   cannot expand the allowlist. Limits can be overridden in direct pipeline
   config with :website-fetch/* keys."
  [config]
  (let [base-url (:base-url config)
        sitemap-url (make-absolute-url base-url (:sitemap/url config))
        configured-hosts (let [v (:website-fetch/allowed-hosts config)]
                           (cond
                             (nil? v) []
                             (string? v) [v]
                             (sequential? v) v
                             :else [v]))
        hosts (->> (concat [base-url sitemap-url] configured-hosts)
                   (keep (fn [url]
                           (when url
                             (try
                               (let [s (str url)]
                                 (if (str/includes? s "://")
                                   (normalized-host (URI. s))
                                   (-> s (.toLowerCase Locale/ROOT)
                                       (str/replace #"\.$" ""))))
                               (catch Exception _ nil)))))
                   (remove str/blank?)
                   set)]
    (merge default-fetch-policy
           {:allowed-hosts hosts}
           ;; Normalize namespaced config keys into the internal policy.
           (into {}
                 (keep (fn [[k v]]
                         (when (= "website-fetch" (namespace k))
                           [(keyword (name k)) v])))
                 config))))

(defn read-bounded-bytes
  [^InputStream in max-bytes]
  (let [buffer (byte-array 8192)
        out (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [n (.read in buffer 0 (int (min (alength buffer)
                                           (inc (- max-bytes total)))))]
        (if (neg? n)
          (.toByteArray out)
          (let [new-total (+ total n)]
            (when (> new-total max-bytes)
              (throw (ex-info "Website response exceeds size limit"
                              {:max-bytes max-bytes})))
            (.write out buffer 0 n)
            (recur new-total)))))))

(def ^:private redirect-statuses #{301 302 303 307 308})

(defn fetch-bytes
  "Fetch an HTTP(S) resource with validation on the initial URL and every
   redirect. Response bodies are read incrementally up to max-bytes."
  [url policy max-bytes]
  (loop [uri (validate-fetch-uri! url policy)
         redirects 0]
    (let [^HttpURLConnection conn (.openConnection (.toURL uri))]
      (let [result
            (try
              (.setInstanceFollowRedirects conn false)
              (.setConnectTimeout conn (int (:connect-timeout-ms policy)))
              (.setReadTimeout conn (int (:read-timeout-ms policy)))
              (.setRequestProperty conn "Accept" "application/xml,text/xml,text/markdown,text/plain;q=0.9,*/*;q=0.1")
              (let [status (.getResponseCode conn)]
                (cond
                  (redirect-statuses status)
                  (do
                    (when (>= redirects (:max-redirects policy))
                      (throw (ex-info "Too many redirects while fetching website content"
                                      {:url (str uri) :max-redirects (:max-redirects policy)})))
                    (let [location (.getHeaderField conn "Location")]
                      (when (str/blank? location)
                        (throw (ex-info "Website redirect is missing Location"
                                        {:url (str uri) :status status})))
                      {:redirect (validate-fetch-uri! (str (.resolve uri location)) policy)}))

                  (<= 200 status 299)
                  (do
                    (when (> (.getContentLengthLong conn) max-bytes)
                      (throw (ex-info "Website response exceeds size limit"
                                      {:url (str uri) :max-bytes max-bytes})))
                    (with-open [in (.getInputStream conn)]
                      {:body (read-bounded-bytes in max-bytes)}))

                  :else
                  (throw (ex-info "Website fetch returned an unsuccessful status"
                                  {:url (str uri) :status status}))))
              (finally
                (.disconnect conn)))]
        (if-let [redirect (:redirect result)]
          (recur redirect (inc redirects))
          (:body result))))))

(defn fetch-text
  [url policy max-bytes]
  (String. ^bytes (fetch-bytes url policy max-bytes) StandardCharsets/UTF_8))

(defn fetch-sitemap
  "Fetches sitemap XML from a URL and parses it"
  [url]
  (t/event! :website/fetching-sitemap {:data {:url url}})
  (let [policy (or *active-fetch-policy*
                   (website-fetch-policy {:sitemap/url url}))]
    (with-open [in (ByteArrayInputStream.
                    (fetch-bytes url policy (:max-sitemap-bytes policy)))]
      (xml/parse in))))

(defn extract-urls-from-sitemap
  "Extracts URL entries from parsed sitemap XML structure.
   Returns seq of maps with :loc and :lastmod keys."
  ([sitemap-xml] (extract-urls-from-sitemap sitemap-xml nil))
  ([sitemap-xml base-url]
   (let [urlset (if (= :urlset (:tag sitemap-xml))
                  sitemap-xml
                  (first (filter #(= :urlset (:tag %)) (:content sitemap-xml))))
         urls (filter #(= :url (:tag %)) (:content urlset))]
     (for [url urls]
       (let [children (:content url)
             loc-node (first (filter #(= :loc (:tag %)) children))
             lastmod-node (first (filter #(= :lastmod (:tag %)) children))
             loc (first (:content loc-node))]
         {:loc (if base-url (make-absolute-url base-url loc) loc)
          :lastmod (when lastmod-node (first (:content lastmod-node)))})))))

(defn filter-markdown-urls
  "Filters URL entries to only include .md files"
  [url-entries]
  (filter #(str/ends-with? (:loc %) ".md") url-entries))

(defn extract-sub-sitemap-urls
  "Extracts child <sitemap><loc> values from a parsed <sitemapindex> XML."
  [sitemap-xml]
  (let [sitemap-children (filter #(= :sitemap (:tag %)) (:content sitemap-xml))]
    (for [s sitemap-children
          :let [loc-node (first (filter #(= :loc (:tag %)) (:content s)))
                loc (some-> loc-node :content first)]
          :when loc]
      loc)))

(defn parse-sitemap
  "Fetches and parses sitemap, returning filtered markdown URLs.

   If the root is <sitemapindex>, recursively parses each child sitemap
   and concatenates the results. This lets callers point at a root sitemap
   that fans out to per-language (or per-section) leaves."
  ([url] (parse-sitemap url nil))
  ([url base-url]
   (parse-sitemap url base-url (website-fetch-policy {:sitemap/url url
                                                       :base-url base-url})))
  ([url base-url policy]
   (binding [*active-fetch-policy* policy]
     (letfn [(walk [current-url depth visited]
               (when (> depth (:max-sitemap-depth policy))
                 (throw (ex-info "Sitemap nesting exceeds depth limit"
                                 {:url current-url
                                  :max-depth (:max-sitemap-depth policy)})))
               (if (contains? visited current-url)
                 []
                 (let [parsed (fetch-sitemap current-url)
                       visited' (conj visited current-url)]
                   (if (= :sitemapindex (:tag parsed))
                     (->> (extract-sub-sitemap-urls parsed)
                          (map #(str (.resolve (URI. current-url) (str %))))
                          (mapcat #(walk % (inc depth) visited'))
                          doall)
                     (-> parsed
                         (extract-urls-from-sitemap base-url)
                         filter-markdown-urls)))))]
       (walk url 0 #{})))))

;; ============================================================================
;; Markdown Fetching
;; ============================================================================

(defn mk-cached-fetch-markdown-t
  "Fetches markdown from URL with caching based on lastmod timestamp"
  ([url lastmod]
   (mk-cached-fetch-markdown-t url lastmod
                               (website-fetch-policy {:sitemap/url url})))
  ([url lastmod policy]
  (m/via m/blk
         (let [_ (validate-fetch-uri! url policy)
               cache-key (str (core/sha256-short-hash url) "-" (core/sha256-short-hash (or lastmod "")))
               cache-dir "cache/website-md/"
               cache-path (str cache-dir cache-key ".md")
               file (jio/file cache-path)]

           (when-not (java.io.File/.exists (jio/file cache-dir))
             (jio/make-parents cache-path))

           (if (java.io.File/.exists file)
             (do
               (t/event! :website/markdown-cache-hit {:data {:url url}})
               (slurp file))
             (do
               (t/event! :website/markdown-cache-miss {:data {:url url :lastmod lastmod}})
               (try
                 (let [md (fetch-text url policy (:max-markdown-bytes policy))]
                   (spit file md)
                   (t/event! :website/markdown-cached {:data {:url url}})
                   md)
                 (catch Exception e
                   (t/error! {:id :website/markdown-fetch-error
                              :msg ["Failed to fetch markdown from" url]} e)
                   (throw e)))))))))

;; ============================================================================
;; Frontmatter Parsing
;; ============================================================================

(def ^:private frontmatter-block-re
  #"(?s)\A---\s*\n(.*?)\n---\s*(?:\n|\z)")

(defn parse-yaml-frontmatter
  "Extracts and parses the YAML frontmatter at the top of a Hugo markdown doc.
   Returns a keyword-keyed map, or nil if no frontmatter is present. On parse
   error returns an empty map (lossy YAML — some Hugo values like dates with
   timezone names don't round-trip cleanly, so we tolerate that)."
  [markdown]
  (when (and (string? markdown) (str/starts-with? markdown "---"))
    (when-let [m (re-find frontmatter-block-re markdown)]
      (try
        (yaml/parse-string (second m) :keywords true)
        (catch Exception _ {})))))

(tests
 "Frontmatter parsing"
 (parse-yaml-frontmatter "---\ntitle: Hello\nlinktitle: Hi\n---\nbody")
 := {:title "Hello" :linktitle "Hi"}

 (parse-yaml-frontmatter "no frontmatter here") := nil
 (parse-yaml-frontmatter nil) := nil
 (parse-yaml-frontmatter "") := nil)

(defn derive-language-from-url
  "Derives the language code from a Hugo-style URL: `/en/...` -> \"en\",
   `/nb/...` -> \"nb\". Accepts both absolute and relative URLs."
  [url]
  (when (string? url)
    (let [path (str/replace url #"^https?://[^/]+" "")]
      (cond
        (str/starts-with? path "/en/") "en"
        (str/starts-with? path "/nb/") "nb"))))

(tests
 "Language derivation"
 (derive-language-from-url "http://localhost:1313/nb/about/index.md") := "nb"
 (derive-language-from-url "http://localhost:1313/en/about/index.md") := "en"
 (derive-language-from-url "/nb/about/index.md") := "nb"
 (derive-language-from-url "/en/about/index.md") := "en"
 (derive-language-from-url "/no-language/index.md") := nil
 (derive-language-from-url nil) := nil)

(defn- normalize-diataxis
  "Strips the Hugo `diataxis_` prefix so values become `explanation`,
   `how-to-guides`, `reference`, `tutorials` — cleaner for facets and filters."
  [v]
  (when v
    (-> (str v)
        (str/replace-first #"^diataxis_" ""))))

;; ============================================================================
;; Document Structure
;; ============================================================================

(defn extract-title-from-url
  "Extracts a title from the URL path."
  [url]
  (let [path (-> url
                 (str/replace #"^https?://[^/]+" "")
                 (str/replace #"/index\.md$" "")
                 (str/replace #"\.md$" "")
                 (str/split #"/")
                 last)]
    (-> path
        (str/replace #"-" " ")
        (str/replace #"_" " ")
        str/capitalize)))

(tests
 "Title extraction from URLs"
 (extract-title-from-url "http://localhost:1313/en/about/index.md")
 := "About"

 (extract-title-from-url "/en/about/index.md")
 := "About"

 (extract-title-from-url "/en/authorization/architecture.md")
 := "Architecture")

(defn url-to-doc
  "Converts a sitemap URL entry to document structure."
  [url-entry]
  (let [{:keys [loc lastmod]} url-entry
        doc-id (core/sha256-short-hash loc)]
    {:id doc-id
     :doc_num doc-id
     :url loc
     :lastmod lastmod
     :title (extract-title-from-url loc)
     :language (derive-language-from-url loc)
     :type "website"}))

;; ============================================================================
;; TypeSense Schemas
;; ============================================================================

(defn website-docs-schema [collection-name]
  {:name collection-name
   :fields [{:facet true :index true :name "id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false :sort true :type "string"}
            {:facet false :index true :locale "en" :name "title" :optional false :sort false :type "string"}
            {:facet false :index true :name "linktitle" :optional true :sort false :type "string"}
            {:facet false :index true :name "frontmatter_title" :optional true :sort false :type "string"}
            {:facet true :index true :name "diataxis" :optional true :sort false :type "string"}
            {:facet true :index true :name "language" :optional true :sort false :type "string"}
            {:facet true :index true :name "url" :optional false :sort false :type "string"}
            {:facet true :index true :name "lastmod" :optional true :sort true :type "string"}
            {:facet true :index true :name "type" :optional false :sort false :type "string"}
            {:facet false :index true :name "total_chunks" :optional true :sort true :type "int32"}]})

(defn website-chunks-schema [[docs-collection-name chunks-collection-name :as coll-ids]]
  {:name chunks-collection-name
   :fields [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false
             :reference (str docs-collection-name ".doc_num")
             :async_reference true
             :sort false :type "string"}
            {:facet true :index true :name "chunk_index" :optional false :sort true :type "int32"}
            {:facet false :index true :locale "en" :name "content_markdown" :optional false :sort false :type "string"}
            {:facet false :index true :name "metadata" :optional true :sort false :type "string"}
            {:facet false :index true :name "content_length" :optional true :sort true :type "int32"}
            {:facet true :index true :name "url" :optional false :sort false :type "string"}]})

(defn website-phrases-schema
  [[docs-collection-name chunks-collection-name phrases-collection-name :as coll-ids]]
  {:default_sorting_field "chunk_id"
   :fields
   [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
    {:async_reference false :facet true :index true :name "doc_num" :optional false
     :reference (str docs-collection-name ".doc_num") :sort true :type "string"}
    {:facet false :index true :name "search_phrase" :optional false :sort false :type "string"}
    {:embed {:from ["search_phrase"] :model_config {:model_name "ts/all-MiniLM-L12-v2"}}
     :facet false :hnsw_params {:M 16 :ef_construction 200} :index true :name "phrase_vec"
     :num_dim 384 :optional true :sort false :type "float[]" :vec_dist "cosine"}]
   :name phrases-collection-name})

;; ============================================================================
;; Document Preparation Functions
;; ============================================================================

(defn prepare-website-doc
  "Prepares document for storage, converting absolute URL to relative"
  [config doc]
  (let [base-url (:base-url config)]
    (-> doc
        (update :url #(make-relative-url base-url %))
        (select-keys [:id :doc_num :title :linktitle :frontmatter_title
                      :diataxis :language :url :lastmod :type :total_chunks]))))

(defn prepare-website-chunks
  "Prepares chunks for storage"
  [config chunks]
  (let [base-url (:base-url config)]
    (mapv #(-> %
               (update :url (fn [url] (make-relative-url base-url url)))
               (select-keys [:chunk_id :doc_num :chunk_index :content_markdown :content_length :metadata :url]))
          chunks)))

;; ============================================================================
;; Backward Compatibility Functions
;; ============================================================================

;; These functions maintain backward compatibility with existing tests

(defn extract-ns-from-map
  "Extracts all keys from map m that have namespace ns or start with ns."
  [m ns]
  (storage/extract-ns-from-map m ns))

(defn wview-hash
  "Generates a hash of config values in a specific namespace."
  [config ns]
  (storage/config-hash config ns))

(defn coll-ids
  "Generates collection IDs for documents, chunks, and phrases based on config."
  [config]
  (storage/coll-ids config))

(defn document-inserted?
  "Checks if a document with the given ID exists in the collection."
  [_config coll-name doc]
  (storage/document-inserted? coll-name doc))

(defn create-website-docs-coll
  "Creates the documents collection."
  [name]
  (storage/create-collection! (website-docs-schema name)))

(defn create-website-chunks-coll
  "Creates the chunks collection."
  [config]
  (let [ids (coll-ids config)]
    (storage/create-collection! (website-chunks-schema ids))))

(defn create-website-phrases-coll
  "Creates the phrases collection."
  [config]
  (let [ids (coll-ids config)]
    (storage/create-collection! (website-phrases-schema ids))))

(defn create-stores
  "Creates all three collections."
  [config]
  (storage/create-collections!
   config
   website-docs-schema
   website-chunks-schema
   website-phrases-schema))

(defn mk-filter-url-entries-f
  "Filters URL entries with limit/offset and deduplication."
  [config url-entries]
  (orch/mk-filter-url-entries-f config url-entries))

;; ============================================================================
;; Configuration
;; ============================================================================

(def wview
  {:sitemap/url "/nb/sitemap-markdown.xml"
   :base-url "http://localhost:1313"

   :parallelism/documents 3
   :parallelism/store 1
   :fault-tolerance/max-document-failures 10

   :urls/offset 0
   :urls/limit 30000

   :chunks/hash-changer 1
   :chunks/strategy :header-based
   :chunks/minimum-length 333
   :chunks/maximum-length (* 2 128000)

   :search-phrases/hash-changer 1
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model :google/gemma-3-27b-it
   :search-phrases/prompt search-phrases/default-search-phrases-prompt

   :store/coll-prefix "website_"})

;; ============================================================================
;; Pipeline Implementation
;; ============================================================================

(defn merge-frontmatter-fields
  "Merges Hugo frontmatter fields into a doc map. Pulls `linktitle`,
   `frontmatter_title` (the YAML `title`), and a normalized `diataxis`."
  [doc markdown]
  (let [fm (parse-yaml-frontmatter markdown)]
    (cond-> doc
      (some? (:linktitle fm)) (assoc :linktitle (str (:linktitle fm)))
      (some? (:title fm))     (assoc :frontmatter_title (str (:title fm)))
      (some? (:diataxis fm))  (assoc :diataxis (normalize-diataxis (:diataxis fm))))))

(defn mk-fetch-markdown-t
  "Fetches markdown content for a URL entry"
  [config url-entry]
  (m/sp
   (let [{:keys [loc lastmod]} url-entry
         doc (url-to-doc url-entry)
         markdown (m/? (mk-cached-fetch-markdown-t loc lastmod
                                                    (website-fetch-policy config)))]
     (-> doc
         (assoc :content_markdown markdown)
         (merge-frontmatter-fields markdown)))))

(defn mk-chunk-doc-t
  "Chunks a document"
  [config doc]
  (m/via m/blk (proto/chunk-document config doc :url)))

(defn mk-prepare-document-t
  "Prepares a complete document from a URL entry"
  [config url-entry]
  (m/sp
   (as-> url-entry doc
     (m/? (mk-fetch-markdown-t config doc))
     (m/? (mk-chunk-doc-t config doc))
     (m/? (search-phrases/mk-distill-doc-search-phrases-t config doc "website"))
     (do (core/say "Prepared website doc")
         (t/event! :website/document-prepared {:data {:url (:url doc)}})
         doc))))

(defn mk-store-document-t
  "Stores a prepared document"
  [config doc]
  (m/via m/blk
         (storage/store-complete-document!
          config doc
          prepare-website-doc
          (fn [chunks] (prepare-website-chunks config chunks)))))

(defn mk-prepare-documents-f
  "Prepares documents in parallel"
  [config url-entries-f]
  (orch/mk-prepare-documents-f config mk-prepare-document-t url-entries-f :website))

(defn mk-store-documents-f
  "Stores documents in parallel"
  [config documents-f]
  (orch/mk-store-documents-f config mk-store-document-t documents-f))

(defn mk-materialize-t
  "Creates the complete materialization task"
  [config]
  (m/sp
    (t/event! :website/materializing {:data {:config config :colls (coll-ids config)}})

    (m/? (m/via m/blk (create-stores config)))

    (let [base-url (:base-url config)
          sitemap-url (make-absolute-url base-url (:sitemap/url config))
          _ (t/event! :website/parsing-sitemap {:data {:url sitemap-url}})
          url-entries (m/? (m/via m/blk (parse-sitemap sitemap-url base-url
                                                       (website-fetch-policy config))))
          _ (t/event! :website/found-urls {:data {:count (count url-entries)}})
          url-entries-flow (m/seed url-entries)
          filtered-flow (mk-filter-url-entries-f config url-entries-flow)]

      (m/?
       (m/reduce
        net.cgrand.xforms.rfs/last
        (mk-store-documents-f config (mk-prepare-documents-f config filtered-flow)))))))

;; ============================================================================
;; Entry Points
;; ============================================================================

(defn -main [& args]
  (telemetry/start-job! (core/run-task-async (mk-materialize-t wview))))

(defn stop-job []
  (when (telemetry/stop-job!)
    (t/event! :website/job-cancelled)))

;; Re-export telemetry atoms for backward compatibility
(def !transient-telemetry-aggregate telemetry/!transient-telemetry-aggregate)
(def !signal-window telemetry/!signal-window)
(def !start-job-button-disabled? telemetry/!start-job-button-disabled?)
(def !job-canceller telemetry/!job-canceller)
(def !store-threads telemetry/!store-threads)
