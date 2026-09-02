(ns digdir.docs.file-fetch
  "Fetch a Kudos document's binary from an indexed `files[]` entry.

   `files[].url` no longer resolves to the file. Kudos migrated from numeric
   ids to UUIDs; the indexed URL now serves the HTML landing page with HTTP 200
   (or 404s outright), and the binary lives at that page's `citation_pdf_url`.
   Measured across 300 sampled URLs: zero returned a PDF. See
   `docs/investigations/239-files-url-findings.md`.

   Every fetch is verified against the digest the index already holds, BEFORE
   the bytes reach a parser. That check is free — the Kudos API supplies
   `sha256` and `size` alongside each entry — and it is the point of this
   namespace: a hop that quietly fetched the wrong file would be one more
   silent success, which is the failure mode this repository keeps finding."
  (:require [clj-http.client :as http]
            [taoensso.telemere :as t])
  (:import [java.security MessageDigest]))

(defn pdf-bytes?
  "True when `body` starts with the `%PDF` magic number."
  [^bytes body]
  (boolean (and body (>= (alength body) 4) (= "%PDF" (String. body 0 4 "US-ASCII")))))

(defn sha256-hex
  "Lowercase hex SHA-256 of `body`, in the same form the Kudos API reports."
  [^bytes body]
  (->> (.digest (MessageDigest/getInstance "SHA-256") body)
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(def ^:private citation-patterns
  "Both attribute orders — the meta tag is emitted either way in the wild."
  [#"(?i)citation_pdf_url\"?[^>]*?content=\"([^\"]+)\""
   #"(?i)content=\"([^\"]+)\"[^>]*?citation_pdf_url"])

(defn citation-pdf-url
  "The landing page's declared PDF location, or nil when it declares none.
   nil is meaningful: the ~15% of entries whose URL 404s have landing pages
   carrying no `citation_pdf_url` at all, and no hop can reach those."
  [^String html]
  (when html
    (some (fn [re] (second (re-find re html))) citation-patterns)))

(defn tag-mentioned?
  "True when the page mentions `citation_pdf_url` at all, however it is spelled.

   Deliberately weaker than `citation-pdf-url`: it is the control that separates
   two cases the extractor alone conflates — a landing page that genuinely
   declares no PDF (the irreparable residue) from one that declares it in a
   shape the patterns miss (our parse bug). Validated 2026-08-24 against 65
   real pages: all 40 that serve a PDF carry the tag and all 40 extract; all 25
   that 404 lack the string entirely; zero pages had the tag without extracting."
  [^String html]
  (boolean (and html (.contains html "citation_pdf_url"))))

(defn verify-bytes!
  "Return `body` when it matches the digest and size the index holds for
   `file`; throw otherwise.

   Throws when the entry carries no digest at all, rather than passing
   unverified bytes through — an entry without a digest is an anomaly (all
   11,553 indexed entries have one), and silently skipping the check is the
   behaviour this function exists to prevent."
  [{:keys [url sha256 size]} ^bytes body source]
  (let [actual-size (alength body)
        actual-sha (sha256-hex body)]
    (when-not sha256
      (throw (ex-info "Refusing unverified bytes: file entry has no sha256"
                      {:url url :source source :size size :actual-size actual-size})))
    (when (and size (not= (long size) actual-size))
      (throw (ex-info "Fetched file size does not match the index"
                      {:url url :source source :expected-size (long size)
                       :actual-size actual-size :actual-sha256 actual-sha})))
    (when (not= sha256 actual-sha)
      (throw (ex-info "Fetched file digest does not match the index"
                      {:url url :source source :expected-sha256 sha256
                       :actual-sha256 actual-sha :actual-size actual-size})))
    (t/event! :file-fetch/verified
              {:data {:url url :source source :size actual-size}})
    body))

(def ^:private deterministic-http-statuses
  "Statuses that describe the state of the source rather than the state of the
   attempt. Re-requesting cannot change them, so they must not be retried.

   Deliberately narrow. 5xx, timeouts and connection failures stay retryable
   because they are exactly what the backoff ladder exists for; only these two
   say the resource is not there, as opposed to not there *right now*."
  #{404 410})

(defn- mark-not-worth-retrying
  "Re-throw `e` carrying `:worth-retrying false`.

   `digdir.docs.loader/worth-retrying?` walks the cause chain for the first
   ex-data containing `:worth-retrying` and, finding none, returns true — the
   classifier is opt-OUT, so anything unmarked is retried. Nothing on this path
   marked itself, which is why a permanently missing file was costing a 1-60
   minute sleep before failing anyway (#308). The original is kept as the cause
   so nothing is lost from the log."
  [^Exception e url status]
  (throw (ex-info (str "File is unreachable at the indexed url (HTTP " status ")")
                  {:url url :status status :worth-retrying false}
                  e)))

(defn- get-bytes [url]
  (try
    (:body (http/get url {:as :byte-array
                          :socket-timeout 120000
                          :connection-timeout 30000}))
    (catch clojure.lang.ExceptionInfo e
      (let [status (:status (ex-data e))]
        (if (contains? deterministic-http-statuses status)
          (mark-not-worth-retrying e url status)
          (throw e))))))

(defn fetch-file!
  "Verified bytes for an indexed `files[]` entry.

   Fetches `:url`; if that yields a PDF it is used directly, otherwise the
   response is treated as the landing page and the hop to `citation_pdf_url`
   is taken. Either way the bytes are checked against `:sha256`/`:size` before
   being returned. Throws — loudly, with the URL and both digests in ex-data —
   on a 404, a landing page with no hop, a hop that yields non-PDF bytes, or
   any mismatch."
  [{:keys [url] :as file}]
  (let [body (get-bytes url)]
    (if (pdf-bytes? body)
      (verify-bytes! file body :direct)
      (let [html (String. ^bytes body "UTF-8")
            hop (citation-pdf-url html)]
        (when-not hop
          ;; Two different failures, and they must not share a message: an absent
          ;; tag means the binary is unreachable (upstream), a present-but-
          ;; unextracted tag means this namespace is wrong (ours).
          (let [declared? (tag-mentioned? html)]
            (throw (ex-info (if declared?
                              "Landing page declares citation_pdf_url but it could not be extracted"
                              "Indexed url served a landing page with no citation_pdf_url")
                            (cond-> {:url url
                                     :tag-declared? declared?
                                     :parse-failure? declared?
                                     :body-bytes (alength ^bytes body)}
                              ;; Absent tag: the page rendered fine and declares no
                              ;; PDF, so re-fetching returns the same HTML. State of
                              ;; the source, not a failed attempt (#308).
                              ;;
                              ;; The present-but-unextracted branch is deliberately
                              ;; left retryable: it means this namespace is wrong,
                              ;; which is a fault and should keep behaving like one.
                              (not declared?) (assoc :worth-retrying false))))))
        (t/event! :file-fetch/hopping {:data {:url url :citation-pdf-url hop}})
        (let [hopped (get-bytes hop)]
          (when-not (pdf-bytes? hopped)
            (throw (ex-info "citation_pdf_url did not yield a PDF"
                            {:url url :citation-pdf-url hop
                             :body-bytes (alength ^bytes hopped)})))
          (verify-bytes! file hopped :citation-pdf-url))))))
