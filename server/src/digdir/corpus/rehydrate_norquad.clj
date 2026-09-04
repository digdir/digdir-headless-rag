(ns digdir.corpus.rehydrate-norquad
  "Rehydrate the NorQuAD demo corpus from Wikipedia, as a `-main` on the jar the
   image ships (#yardarm-fetch-in-image).

   ## What this removes

   This was a babashka script, which made the LAST newcomer step depend on two
   things the container does not have: `bb` and a source tree. Neither ships on
   macOS or Windows by default, so \"fetch the corpus\" was the one instruction a
   newcomer could not follow from the image. Porting it to Python or Node would
   have swapped one missing prerequisite for another; running it on the jar
   removes both at once and behaves identically on every platform, because it is
   the same image.

   Same shape as `digdir.setup.first-admin` (#436) and `digdir.setup.demo-tenant`
   (#493): `/app` holds one file, `app.jar`.

       docker compose --profile fetch run --rm corpus-fetch

   ## ONE implementation, deliberately

   The babashka script was DELETED rather than kept alongside this. Two fetchers
   would be free to drift, and the failure mode is silent and expensive: the
   committed warm cache is keyed by chunk hash, so a fetcher that produced so
   much as a different trailing newline would orphan every one of its entries
   while still looking like it worked. `bb demo-corpus` now shells to this same
   namespace, so there is one implementation behind both entry points.

   ## HTTP client

   `clj-http` rather than `hato` or `http-kit`: it is the only HTTP client
   DECLARED in `server/deps.edn`, and the others are transitive. The document
   bytes this produces were verified identical to the babashka version's — see
   the corpus README.

   NOTHING IT FETCHES IS COMMITTED. Only NorQuAD's Q/A layer comes from the
   HuggingFace API, which is the layer its CC0 dedication covers; the article
   text comes from Wikipedia under CC BY-SA."
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def api "https://no.wikipedia.org/w/api.php")

(def user-agent
  ;; Wikimedia's policy requires a descriptive UA with a contact. A generic one
  ;; gets 429s, which is how the first version of this failed.
  "digdir-rag-demo-corpus/1.0 (https://github.com/itonomi/digdir-headless-rag; corpus rehydration)")

(def polite-delay-ms
  ;; One request per title (see `fetch-extract`), so this is the whole rate limit.
  600)

(defn- api-get
  "GET with backoff on 429/503. Wikimedia rate-limits aggressively and a bare
   retry loop hammers it further, so back off linearly."
  [params]
  (loop [attempt 0]
    (let [resp (try
                 (http/get api {:query-params params
                                :headers {"User-Agent" user-agent}
                                :throw-exceptions false
                                :cookie-policy :none
                                :socket-timeout 60000
                                :connection-timeout 60000})
                 (catch Exception e {:status :error :error (ex-message e)}))]
      (cond
        (= 200 (:status resp)) (json/parse-string (:body resp) true)
        (< attempt 4) (do (Thread/sleep (* 5000 (inc attempt))) (recur (inc attempt)))
        :else (throw (ex-info "Wikipedia API failed after retries"
                              {:params params :status (:status resp)}))))))

(defn resolve-title
  "Does `title` resolve to a Bokmål Wikipedia article? Follows redirects and
   normalisation. This is also the EXACT Wikipedia/news split: NorQuAD's news
   contexts are NAK-derived and their headlines do not resolve."
  [title]
  (let [d (api-get {:action "query" :titles title :format "json"
                    :redirects "1" :prop "info"})
        pages (vals (get-in d [:query :pages]))]
    (and (= 1 (count pages))
         (not (contains? (first pages) :missing)))))

(defn fetch-extract
  "Plain-text extract of `title`, or nil. `:full` is the whole article; `:intro`
   is the lead section only.

   ⚠️ ONE TITLE PER REQUEST, DELIBERATELY. The extracts API returns text for
   only ONE page per request however many titles you pass, and `exlimit=max`
   does not change it. Batching produced a 1.7% match rate where the truth was
   92.2% — the missing pages simply came back empty and looked like drift."
  [title mode]
  (let [d (api-get (cond-> {:action "query" :titles title :format "json"
                            :redirects "1" :prop "extracts" :explaintext "1"}
                     (= mode :intro) (assoc :exintro "1")))]
    (some-> (get-in d [:query :pages]) vals first :extract not-empty)))

(defn- safe-name [title]
  (-> title (str/replace #"[^\p{L}\p{N}\-_ ]" "_") (str/replace #"\s+" "_")
      (subs 0 (min 120 (count (str/replace title #"[^\p{L}\p{N}\-_ ]" "_"))))))

(defn wikitext-headings->markdown
  "Rewrite MediaWiki section headings to markdown.

   ⚠️ LOAD-BEARING, AND SILENT IF OMITTED. `explaintext` extracts return
   headings as wikitext — `== Etymologi ==` — while the chunker's `header-line?`
   (rag/chunking.clj:22) matches markdown only: #\"^(#{1,6})\\s+(.*)\". Measured on
   full extracts: Solsystemet 52 wikitext headings and 0 markdown, Apollon 24
   and 0. So an unrewritten article produces ZERO heading splits and one
   ~35,000-char chunk — and it does not error. It ingests, embeds and scores,
   which is the worst of the three outcomes.

   Wikipedia reserves `=` for the page title, so `==` is the top section level
   and maps to `##`, leaving our own `#` as the document title."
  [s]
  (str/replace s
               #"(?m)^\s*(={2,6})\s*(.+?)\s*\1\s*$"
               (fn [[_ eq title]]
                 (str (apply str (repeat (count eq) "#")) " " title))))

(def ^:private modification-statements
  "What we changed, per fetch mode.

   CC BY-SA obliges a clear statement of modification, and the statement has to
   be TRUE of the file it describes. A full article is not \"excerpted\", and
   EVERY document here has had its headings rewritten from wikitext to Markdown
   (`wikitext-headings->markdown`) — a modification of the text that went
   undeclared while the statement was a hardcoded string."
  {:full  "Headings converted to Markdown."
   :intro "Excerpted: introduction section only. Headings converted to Markdown."})

(defn attribution-line
  "One TSV row of the CC BY-SA attribution owed for `title`.

   This lives in a SIDECAR rather than in the document, and that is a measured
   decision rather than a stylistic one. `docs/folder.clj` has no front-matter
   parser — only `website.clj` does — so a YAML block at the top of a `.md` is
   not metadata to the folder ingest, it is CONTENT. Measured on the 352-article
   gold set: the block chunked as 334 chars of identical licence boilerplate in
   every document, sometimes as its own chunk and sometimes glued onto the
   article body by `concatenate-too-small-chunks`.

   The licence is still satisfied: CC BY-SA obliges attribution AT DISPLAY, not
   inside the indexed text, and the sidecar is what the display layer reads."
  [title modification]
  (let [url (str "https://no.wikipedia.org/wiki/" (str/replace title #" " "_"))]
    (str/join "\t"
              [(str (safe-name title) ".md")
               title
               url
               "CC BY-SA 4.0"
               "https://creativecommons.org/licenses/by-sa/4.0/"
               (str "Wikipedia contributors, \"" title
                    "\", Norwegian Bokm\u00e5l Wikipedia, CC BY-SA 4.0")
               modification])))

(defn write-attribution-sidecar!
  "Rebuild `ATTRIBUTION.tsv` from the documents actually on disk.

   Derived from each document's H1 rather than accumulated as we fetch, so a
   resumed run — the normal case at this request count — produces the same
   sidecar as an uninterrupted one. The modification statement follows the fetch
   MODE, so an excerpt is never described as a full article or the reverse."
  [out-dir mode]
  (let [modification (get modification-statements mode)
        rows (->> (file-seq (io/file out-dir))
                  (filter #(str/ends-with? (.getName %) ".md"))
                  (keep (fn [f]
                          (when-let [t (second (re-find #"(?m)^# (.+)$" (slurp f)))]
                            (attribution-line t modification))))
                  sort)]
    (spit (io/file out-dir "ATTRIBUTION.tsv")
          (str "file\ttitle\tsource_url\tlicense\tlicense_url\tattribution\tmodification\n"
               (str/join "\n" rows) "\n"))
    (count rows)))

(defn document
  "A `.md` document: an H1 and the article text, and nothing else.

   Attribution goes to the sidecar (see `attribution-line`) because everything
   in this string is indexed as corpus text."
  [title intro]
  (str "# " title "\n\n" (wikitext-headings->markdown intro) "\n"))

(def ^:private hf-rows
  "https://datasets-server.huggingface.co/rows")

(defn norquad-contexts
  "Every distinct NorQuAD context, pulled from the HF datasets API.

   NorQuAD is fetched rather than vendored so this script has no corpus input
   file and nothing derived from Wikipedia is committed."
  []
  (->> (for [split ["train" "validation" "test"]
             offset (range 0 5000 100)]
         [split offset])
       (reduce
        (fn [acc [split offset]]
          (let [resp (http/get hf-rows
                               {:query-params {"dataset" "ltg/norquad"
                                               "config" "default"
                                               "split" split
                                               "offset" (str offset)
                                               "length" "100"}
                                :headers {"User-Agent" user-agent}
                                :throw-exceptions false :cookie-policy :none
                                 :socket-timeout 60000 :connection-timeout 60000})]
            (if (= 200 (:status resp))
              (let [rows (:rows (json/parse-string (:body resp) true))]
                (if (empty? rows) acc (into acc (map #(get-in % [:row :context])) rows)))
              acc)))
        [])
       distinct
       vec))

(defn context->title
  "First line of a context is the article title (Wikipedia) or the headline
   (news). Which it is gets decided by `resolve-title`, not by its shape."
  [ctx]
  (str/trim (first (str/split-lines ctx))))

(def ^:private distractor-seeds
  "Fixed alphabetical starting points for distractor sampling.

   `allpages` is alphabetical, so paging from a single start would return 3,000
   articles beginning with 'A' — reproducible but not a representative corpus.
   Spreading fixed starts across the alphabet keeps it reproducible AND varied.
   These are constants, not a random seed, so a rerun yields the same set."
  ["A" "B" "D" "F" "H" "J" "L" "N" "P" "R" "S" "T" "V" "Ø"])

(defn gold-length-floor
  "The 10th percentile of gold document length — the minimum length a distractor
   must reach.

   WHY A FLOOR AT ALL. Measured on the shipped corpus: gold articles have a
   median of 37,767 characters because NorQuAD's annotators chose substantial
   articles, while random Bokmål articles have a median of 999 and half are
   under 1,000. At a 37.8x gap, LENGTH ALONE PARTLY PREDICTS WHICH DOCUMENTS ARE
   GOLD, so an eval over that pool measures \"can you prefer long documents\"
   mixed in with retrieval quality. That is a confound, not realism. It is not
   hypothetical either: adding unfiltered distractors cost the large-chunk arm
   8.0 points of recall@10 and the small-chunk arm 0.2 — the asymmetry BM25
   length normalisation produces when short documents join a pool of long ones.

   WHY p10 AND NOT THE MEDIAN. The median would exclude ~97% of random articles,
   demand an enormous fetch, and make distractors UNIFORMLY long where gold spans
   a wide range — replacing one length signal with its mirror image. p10 removes
   the bottom of the distractor distribution without truncating the top, so
   length stops separating the two populations at the point where gold itself
   bottoms out.

   Computed from the gold on disk rather than hardcoded, so the RULE survives a
   change to the gold set that a frozen number would not."
  [out-dir]
  (let [lens (->> (.listFiles (io/file out-dir "gold"))
                  (filter #(str/ends-with? (.getName %) ".md"))
                  (map #(count (slurp %)))
                  sort vec)]
    (when (seq lens)
      (nth lens (quot (count lens) 10)))))

(defn fetch-random-distractors
  "Distractor intros, sampled reproducibly and disjoint from the gold set.

   Uses Wikipedia's own deterministic `allpages` pagination from the fixed
   starts above rather than the random endpoint, so the same invocation yields
   the same corpus. `exclude` keeps distractors out of the gold set."
  [n exclude out-dir mode min-length]
  (let [dir (io/file out-dir "distractors")
        per-seed (int (Math/ceil (/ (double n) (count distractor-seeds))))]
    (.mkdirs dir)
    (reduce
     (fn [kept seed]
       (if (>= kept n)
         kept
         (loop [from seed taken 0 k kept]
           (if (or (>= taken per-seed) (>= k n))
             k
             (let [d (api-get {:action "query" :list "allpages" :format "json"
                               :aplimit "100" :apnamespace "0"
                               :apfilterredir "nonredirects" :apfrom from
                             ;; Prefilter only, and NON-BINDING: wikitext is
                             ;; never shorter than the plaintext it renders to,
                             ;; so this cannot exclude a page that would pass the
                             ;; real check below. It just stops us fetching
                             ;; obvious stubs.
                             :apminsize (str min-length)})
                   pages (get-in d [:query :allpages])
                   nxt (get-in d [:continue :apcontinue])]
               (if (empty? pages)
                 k
                 (let [[k' taken'] (reduce
                                    (fn [[k2 t2] p]
                                      (let [ti (:title p)
                                            f (io/file dir (str (safe-name ti) ".md"))]
                                        (cond
                                          (or (>= k2 n) (>= t2 per-seed)) [k2 t2]
                                          (contains? exclude ti) [k2 t2]
                                          (.exists f) [(inc k2) (inc t2)]
                                          :else
                                          (let [x (fetch-extract ti mode)
                                                doc (when x (document ti x))]
                                            (Thread/sleep polite-delay-ms)
                                            ;; BINDING filter, on the document as
                                            ;; written — the same quantity the
                                            ;; floor was derived from.
                                            (if (and doc (>= (count doc) min-length))
                                              (do (spit f doc) [(inc k2) (inc t2)])
                                              [k2 t2])))))
                                    [k taken] pages)]
                   (if nxt (recur nxt taken' k') k'))))))))
     0 distractor-seeds)))

(def self-ignore-body
  "Contents of the `.gitignore` written into the output directory.

   `*` ignores everything in the directory including the file itself, so the
   corpus is unaddable no matter WHERE it was fetched to."
  "*\n")

(defn write-self-ignore!
  "Make the output directory ignore its own contents, before anything is written
   into it.

   The repository `.gitignore` can only name the documented default path, but
   `<out-dir>` is caller-supplied — so a corpus fetched anywhere else would sit
   untracked-but-UNIGNORED, one `git add -A` from being committed. That state is
   invisible to a guard that inspects tracked files, which is how it survived:
   nothing is committed, so nothing fails, and the licence argument this corpus
   rests on is one command from being broken.

   Written FIRST, so an interrupted or failed run is covered too."
  [out-dir]
  (.mkdirs (io/file out-dir))
  (spit (io/file out-dir ".gitignore") self-ignore-body))

(def ^:private manifest-resource "demo-corpus/manifest.edn")
(def ^:private manifest-file-path "server/resources/demo-corpus/manifest.edn")

(defn- manifest-source
  "Where to read the provenance manifest from, or nil.

   ⚠️ CLASSPATH FIRST, and that is a fix rather than a preference. The path was
   hardcoded relative to the REPO ROOT, so it resolved only when the process
   happened to start there. In the container the cwd is `/app` and there is no
   source tree at all, so verification silently skipped with a warning — and a
   provenance check that never runs is worse than none, because the run still
   looks verified.

   The manifest lives under `server/resources`, so it is on the classpath as
   `demo-corpus/manifest.edn` in every context that matters: the jar, `bb` from
   the repo root, and `clojure -M` from `server/`. The file fallback is kept for
   a caller that has the tree but not that resource on its classpath."
  []
  (or (io/resource manifest-resource)
      (let [f (io/file manifest-file-path)]
        (when (.exists f) f))))

(defn- digdir-sha256 [^String s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest d (.getBytes s "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn verify-against-manifest!
  "Compare the fetched documents against the checked-in provenance hashes.

   ⚠️ THIS IS A PROVENANCE RECORD, NOT AN INTEGRITY GATE, and the difference is
   the whole design. Wikipedia articles get edited, so a refetch WILL differ
   eventually. Hard-failing would break setup for every newcomer the first time
   anyone edits one of 352 articles — a time bomb with a fuse we do not control.

   So the default is: report loudly and specifically, and CONTINUE. What matters
   is that a run which measures anything knows it is not measuring the corpus the
   published figures came from. `--strict` makes it exit non-zero, for the one
   caller that should refuse to proceed: an eval harness about to publish numbers.

   ONE HASH, of the written document, deliberately. A second hash of the raw
   extract would separate source drift from script drift — but script drift is
   already under version control, and the two are distinguishable from the
   PATTERN anyway: a script change breaks all 352 at once, a Wikipedia edit
   breaks a handful. So the count below is the diagnostic, and a second hash of
   an artifact we never ingest would be one more thing to fall out of sync."
  [out-dir strict?]
  (let [f (manifest-source)]
    (if-not f
      (println (format "  ⚠ manifest not found (classpath %s, file %s) — provenance NOT checked"
                       manifest-resource manifest-file-path))
      (let [{:keys [articles captured]} (edn/read-string (slurp f))
            results (for [{:keys [title file sha256 revid]} articles
                          :let [doc (io/file out-dir "gold" file)]]
                      (cond
                        (not (.exists doc)) [:missing title revid]
                        (= sha256 (digdir-sha256 (slurp doc))) [:match title revid]
                        :else [:moved title revid]))
            by (group-by first results)
            moved (vec (:moved by))
            missing (mapv second (:missing by))]
        (println (format "  provenance: %d of %d articles match the manifest captured %s"
                         (count (:match by)) (count articles) (or captured "(undated)")))
        (when (seq missing)
          (println (format "  ⚠ %d NOT FETCHED: %s" (count missing)
                           (str/join ", " (take 10 missing)))))
        (when (seq moved)
          (println "")
          (println (format "  ⚠⚠ %d ARTICLE(S) HAVE CHANGED SINCE THE MANIFEST WAS RECORDED:" (count moved)))
          ;; Name the revision the pin was taken from, so a human can open the
          ;; diff. It is NOT something the fetcher can pin BY — `prop=extracts`
          ;; ignores `revids` and silently returns today's text. See the manifest
          ;; header; this line exists to send a person to Wikipedia, not to
          ;; suggest the script could fetch it back.
          (doseq [[_ t revid] (take 20 moved)]
            (println (format "       - %-44s was revid %s" t (or revid "?")))
            (when revid
              (println (format "         https://no.wikipedia.org/w/index.php?title=%s&diff=cur&oldid=%s"
                               (str/replace t #" " "_") revid))))
          (when (> (count moved) 20)
            (println (format "       … and %d more" (- (count moved) 20))))
          (println "")
          (println "  This corpus is NOT the one the published retrieval figures were")
          (println "  measured on. That is expected over time — Wikipedia is edited — and")
          (println "  is not an error. It IS a reason not to compare new numbers to old.")
          (if (> (count moved) (quot (count articles) 2))
            (println "  MORE THAN HALF MOVED: suspect a change to this script, not to Wikipedia.")
            (println "  A handful moved: consistent with ordinary Wikipedia edits.")))
        (when (and strict? (or (seq moved) (seq missing)))
          (println "  --strict: refusing to proceed on a corpus that does not match the manifest.")
          (System/exit 3))
        {:matched (count (:match by)) :moved (count moved) :missing (count missing)}))))

(defn -main [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        [out-dir distractor-count] (remove #(str/starts-with? % "--") args)
        ;; FULL ARTICLES ARE THE DEFAULT. Measured on 352 gold articles: full
        ;; articles carry 162 more answers than intros, of which ~135 survive a
        ;; held-out check. `--intros` reproduces the older excerpt corpus.
        ;;
        ;; Distractors take the SAME mode as gold, deliberately: mixing
        ;; full-article gold with intro distractors lets a retriever separate
        ;; them on register rather than on relevance, which would flatter every
        ;; score measured against the pool.
        mode (if (contains? flags "--intros") :intro :full)
        strict? (contains? flags "--strict")
        n-distractors (parse-long (or distractor-count "800"))]
    (when-not out-dir
      (println "usage: bb rehydrate_norquad.clj <out-dir> [distractor-count] [--intros] [--strict]")
      (System/exit 2))
    (println (format "  mode: %s articles" (name mode)))
    (write-self-ignore! out-dir)
    (println "  wrote .gitignore into the output directory (corpus is never committed)")
    (let [gold-dir (io/file out-dir "gold")]
      (.mkdirs gold-dir)
      (println "  pulling NorQuAD contexts from HuggingFace…")
      (let [contexts (norquad-contexts)
            titles (mapv context->title contexts)]
        (println (format "  %d distinct contexts -> %d candidate titles"
                         (count contexts) (count titles)))
        (let [gold (loop [[t & more] titles acc []]
                     (if-not t
                       acc
                       (let [f (io/file gold-dir (str (safe-name t) ".md"))]
                         (cond
                           (.exists f) (recur more (conj acc t))
                           (not (resolve-title t))
                           (do (Thread/sleep polite-delay-ms) (recur more acc))
                           :else
                           (let [intro (fetch-extract t mode)]
                             (Thread/sleep polite-delay-ms)
                             (if intro
                               (do (spit f (document t intro)) (recur more (conj acc t)))
                               (recur more acc)))))))]
          (println (format "  GOLD (resolved on Bokmål Wikipedia): %d" (count gold)))
          (println (format "  EXCLUDED (news half, unresolved):    %d" (- (count titles) (count gold))))
          (println (format "  fetching %d distractors…" n-distractors))
          (let [floor (or (gold-length-floor out-dir) 2888)
                _ (println (format "  distractor length floor (gold p10): %d chars" floor))
                d (fetch-random-distractors n-distractors (set gold) out-dir mode floor)]
            (println (format "  DISTRACTORS: %d" d))
            (println (format "  ATTRIBUTION.tsv: %d rows"
                             (write-attribution-sidecar! out-dir mode)))
            (verify-against-manifest! out-dir strict?)
            (spit (io/file out-dir "MANIFEST.txt")
                  (str "gold=" (count gold) "\ndistractors=" d
                       "\nmode=" (name mode)
                       "\ndistractor-min-length=" (or (gold-length-floor out-dir) 2888)
                       "\nsource=no.wikipedia.org\n"
                       "license=CC BY-SA 4.0\n"))))))))

