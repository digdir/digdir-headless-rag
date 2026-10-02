(ns digdir.boot.phrase-cache-test
  "The committed warm phrase cache must unpack, must not clobber, and must be
   absent-safe (yardarm-warmcache).

   The third property is what makes the feature's own verification honest: if a
   run with no archive could not produce cache MISSES, a run with the archive
   could not prove its HITS came from the archive rather than from a volume that
   was already warm."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.phrase-cache :as pc]
            [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.search-phrases :as sp])
  (:import [java.util.zip GZIPInputStream GZIPOutputStream]))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/pc-test-" (random-uuid)))

(defn- rm-rf [p]
  (let [f (io/file p)]
    (when (.exists f)
      (doseq [c (.listFiles f)] (io/delete-file c true))
      (io/delete-file f true))))

(defn- file-names [dir]
  (set (map #(.getName %) (.listFiles (io/file dir)))))

(defn- committed-archive
  "The committed archive's map, read HERE rather than through the unpack, so
   the unpack's output can be compared with what was shipped."
  []
  (with-open [in (GZIPInputStream. (io/input-stream (io/resource pc/archive-resource)))]
    (edn/read-string (slurp in))))

(deftest the-archive-ships-and-is-not-empty
  ;; Non-vacuity for everything below: if the resource were missing, every
  ;; unpack assertion would be satisfied by the no-archive branch.
  (testing "the archive is on the classpath"
    (is (some? (io/resource pc/archive-resource))
        (str "no archive at " pc/archive-resource
             " — the warm cache ships as a classpath resource inside the jar")))
  (testing "and it holds a real number of entries"
    (let [d (tmpdir)]
      (try
        (let [r (pc/ensure-warm! d)]
          (is (= :unpacked (:action r)))
          (is (< 4000 (:written r))
              (str "expected the full folder cache, saw " (:written r))))
        (finally (rm-rf d))))))

(deftest unpacked-entries-are-readable-by-the-cache-that-reads-them
  ;; Shape alone is not enough: the files have to be what
  ;; `read-cached-phrases` will later slurp and `read-string`.
  (let [d (tmpdir)]
    (try
      (pc/ensure-warm! d)
      (let [files (vec (.listFiles (io/file d)))
            sample (first (sort-by #(.getName %) files))]
        (testing "the filename is a cache key, not an arbitrary name"
          (is (re-matches #"[0-9a-f]{12}(-[0-9a-f]{12}){2,3}-v\d+\.edn"
                          (.getName sample))
              (str "unexpected key shape: " (.getName sample)
                   " — the archive's own grammar: four segments before the phrase negative-cache issue, five since")))
        (testing "and the content reads back as a vector of phrase strings"
          (let [v (edn/read-string (slurp sample))]
            (is (vector? v))
            (is (seq v))
            (is (every? string? v)))))
      (finally (rm-rf d)))))

(deftest an-already-warm-directory-is-never-clobbered
  ;; Only the unpack writes this directory, so a populated one is a
  ;; previous unpack, and it is not redone on every boot. What it holds is not
  ;; overwritten, whatever it is.
  (let [d (tmpdir)]
    (try
      (.mkdirs (io/file d))
      (spit (io/file d "mine.edn") (pr-str ["a phrase from a real run"]))
      (let [r (pc/ensure-warm! d)]
        (is (= :skipped (:action r)))
        (is (= :already-populated (:reason r)))
        (is (= 1 (:existing r))))
      (testing "and the existing entry is untouched"
        (is (= ["a phrase from a real run"]
               (edn/read-string (slurp (io/file d "mine.edn"))))))
      (testing "and nothing else was written"
        (is (= 1 (count (.listFiles (io/file d))))))
      (finally (rm-rf d)))))

(deftest a-missing-archive-is-safe-and-is-the-control
  ;; THE CONTROL PATH. With no archive the boot must continue and the cache must
  ;; stay empty, so a later run logs misses. If this returned :unpacked, the
  ;; feature could not be measured.
  (let [d (tmpdir)]
    (try
      (with-redefs [pc/archive-resource "demo-corpus/does-not-exist.edn.gz"]
        (let [r (pc/ensure-warm! d)]
          (is (= :skipped (:action r)))
          (is (= :no-archive (:reason r)))))
      (testing "and no cache directory was populated"
        (is (not (some-> (.listFiles (io/file d)) seq))))
      (finally (rm-rf d)))))

(deftest the-unpack-directory-is-the-one-tier-2-reads-and-no-run-writes
  ;; ⛔ These two directories used to be EQUAL, and this test asserted it. That
  ;; was the provider-aware phrase-cache key: an install whose provider and model are named like the
  ;; declared ones writes its misses at the declared key, so a shared directory
  ;; mixed them into the archive's population. Do not make them equal again to
  ;; turn something green - the separation IS the fix.
  (testing "the unpack fills what tier 2 reads, or the archive lands where nothing looks"
    (is (= (sp/declared-cache-dir "folder") pc/cache-dir)))
  (testing "and never the directory a run writes"
    (is (not= (io/file (sp/local-cache-dir "folder")) (io/file pc/cache-dir))))
  (testing "both relative, under the mounted /app/cache, and the run's did not move"
    (is (= "cache/folder-search-phrases-declared/" pc/cache-dir))
    (is (= "cache/folder-search-phrases/" (sp/local-cache-dir "folder"))
        "moving it would strand every entry an existing volume already holds")
    (is (str/starts-with? pc/cache-dir "cache/")
        "an absolute path would land outside the digdir-cache volume")))

;; ============================================================================
;; the archive is unpacked AS SHIPPED and read under the
;; identity that DECLARES it, so tier 2 can find it without anybody pretending
;; it is local output
;; ============================================================================

(deftest unpacked-entries-are-the-archives-own-keys
  ;; The unpack used to RE-KEY the committed archive, adding a provider
  ;; segment copied from the declaration. Packing that directory then produced
  ;; an archive whose provider "verified" against the declaration it had been
  ;; copied from. Now nothing downstream of generation writes a segment: the
  ;; unpack is a copy.
  (let [d (tmpdir)]
    (try
      (let [r (pc/ensure-warm! d)
            shipped (committed-archive)
            declared (sp/declared-identity)]
        (is (= :unpacked (:action r)) "POSITIVE CONTROL: it unpacked")
        (is (some? declared) "POSITIVE CONTROL: an identity is declared")
        (is (= (set (map #(str % ".edn") (keys shipped))) (file-names d))
            "every file is named by the archive's OWN key, unchanged")
        (is (every? #(= 4 (count (str/split % #"-"))) (keys shipped))
            "NON-VACUITY: the committed keys are four-segment, so a re-key would have changed every name")
        (is (every? #(= (core/sha256-short-hash (:model declared)) (second (str/split % #"-"))) (keys shipped))
            "and each carries the DECLARED model in main's model segment")
        (is (= #{:configured-model} (:verified r))
            (str "the record names what the keys carry: main's CONFIGURED model. There is no provider "
                 "segment, so the provider is only ever DECLARED")))
      (finally (rm-rf d)))))

(deftest unpacked-phrases-are-the-archives-own-phrases
  ;; "Keys and phrases land exactly as shipped": the test above checks the keys;
  ;; this checks every entry's phrases.
  (let [d (tmpdir)]
    (try
      (pc/ensure-warm! d)
      (let [shipped (committed-archive)]
        (is (= shipped (into {} (map (fn [k] [k (edn/read-string (slurp (io/file d (str k ".edn"))))])
                                     (keys shipped))))
            "every entry's phrases read back exactly as shipped"))
      (finally (rm-rf d)))))

(deftest the-committed-archive-is-keyed-exactly-as-tier-2-looks-it-up
  ;; The unpack no longer translates keys, so tier 2 must compute the committed
  ;; archive's OWN grammar. If these drifted, tier 2 would silently miss all
  ;; 7,149 entries and every chunk would cost a call. The expected segments are
  ;; hashed HERE, not by `sp/legacy-cache-key`, so a mistake there is not copied.
  (let [declared (sp/declared-identity)
        expected-tail [(core/sha256-short-hash (:model declared))
                       (core/sha256-short-hash sp/default-search-phrases-prompt)
                       sp/parser-version]
        chunk {:content_markdown "some chunk text"}]
    (is (every? #(= expected-tail (rest (str/split % #"-"))) (keys (committed-archive)))
        "every committed key is content · declared model · the default prompt · the parser version")
    (is (= (str/join "-" (cons (core/sha256-short-hash (:content_markdown chunk)) expected-tail))
           (sp/legacy-cache-key chunk declared sp/default-search-phrases-prompt))
        "and that is the key tier 2 computes for a chunk under the declaration")))

(deftest a-declaration-that-does-not-describe-the-archive-is-refused
  ;; The declared model must hash to the model segment the archive's own keys
  ;; carry. That proves the declaration describes THIS archive - not what
  ;; reached the wire, which the old key could not say.
  (let [d (tmpdir)]
    (try
      (with-redefs [sp/declared-identity (constantly {:provider :azure :model "not-the-generator"})]
        (let [r (pc/ensure-warm! d)]
          (is (= :skipped (:action r)) "it refuses rather than unpacking under a false name")
          (is (= :declaration-mismatch (:reason r)))
          (is (empty? (or (.listFiles (io/file d)) [])) "and writes nothing")))
      (finally (rm-rf d)))))

;; ============================================================================
;; an archive REBUILT under the current key must unpack, and must be
;; refused when its keys name more than one identity
;; ============================================================================

(defn- run-the-real-builder
  "The rebuild exactly as `bb phrase-cache-archive` runs it: the builder script,
   under babashka, over `src-dir`, writing `archive`. Answers `sh`'s result.

   The REAL builder in its REAL runtime, because an earlier defect was an archive the builder
   produced and the unpack refused - an archive made here would have agreed
   with the unpack. And not `load-file` into this JVM: a library on the test
   classpath prints a sorted map as `#sorted/map {…}`, which bb does not, so
   in-process the builder writes an archive nothing can read."
  [src-dir archive]
  (sh/sh "bb" "scripts/corpus/build_phrase_cache_archive.clj" src-dir archive))

(defn- build-with-the-real-builder! [src-dir archive]
  (let [r (run-the-real-builder src-dir archive)]
    (when-not (zero? (:exit r))
      (throw (ex-info "the archive builder failed" r)))
    r))

(defn- write-run-entry!
  "One file exactly as a run writes it: named by `sp/cache-key` under
   `identity`."
  [dir identity text]
  (.mkdirs (io/file dir))
  (spit (io/file dir (str (sp/cache-key {:content_markdown text} identity "the prompt") ".edn"))
        (pr-str [(str "a phrase about " text)])))

(defn- legacy-key
  "A key as `main` wrote it before the phrase negative-cache issue: content · model · prompt · version, no
   provider. On main the model segment hashed the CONFIGURED model - `gpt-4o`
   for every demo install - whatever model actually answered."
  [text model]
  (str/join "-" [(core/sha256-short-hash text)
                 (core/sha256-short-hash model)
                 (core/sha256-short-hash "the prompt")
                 sp/parser-version]))

(defn- rebuild-and-unpack!
  "Fill a fresh directory with `populate!`, build it with the real builder, and
   unpack the result into another fresh one. Answers the unpack's result, plus
   the file names built from and unpacked."
  [populate!]
  (let [src (tmpdir) out (str (tmpdir) ".edn.gz") d (tmpdir)]
    (try
      (populate! src)
      (build-with-the-real-builder! src out)
      (assoc (pc/ensure-warm! d (io/file out))
             ::built-from (file-names src)
             ::unpacked (file-names d))
      (finally (rm-rf src) (io/delete-file out true) (rm-rf d)))))

(defn- unpack-hand-assembled!
  "Write `entries` as an archive WITHOUT the builder - what somebody bypassing
   it, or a builder from before the provider-aware phrase-cache key, produces - and unpack it into a fresh
   directory. A plain map, never a sorted one: see `run-the-real-builder`."
  [entries]
  (let [out (str (tmpdir) ".edn.gz") d (tmpdir)]
    (try
      (with-open [o (GZIPOutputStream. (io/output-stream out))]
        (.write o (.getBytes (pr-str (into {} entries)) "UTF-8")))
      (assoc (pc/ensure-warm! d (io/file out)) ::unpacked (file-names d))
      (finally (io/delete-file out true) (rm-rf d)))))

(deftest a-rebuilt-archive-unpacks-with-both-identity-segments-checked
  ;; The README's rebuild instruction produced an archive this unpack refused
  ;; wholesale, because it accepted only the four-segment key. Following the
  ;; documentation switched the warm cache off, with one INFO line.
  (let [r (rebuild-and-unpack! (fn [src]
                                 (doseq [t ["one" "two" "three"]]
                                   (write-run-entry! src (sp/declared-identity) t))))]
    (is (= :unpacked (:action r)) (pr-str r))
    (is (= 3 (:written r)))
    (is (= #{:provider :model} (:verified r))
        "a five-segment key lets the unpack CHECK the provider, not only declare it")
    (is (= (::built-from r) (::unpacked r))
        "and every key is kept exactly as the run wrote it")))

(deftest a-rebuilt-archive-whose-keys-name-another-identity-is-refused
  ;; The builder packs a WHOLE directory, so whatever else a directory holds
  ;; ships under the declaration. Each case writes NOTHING. BY NAME ONLY: a
  ;; foreign model that shares the declared names shares the key.
  (testing "one entry under another identity refuses the whole archive"
    (let [r (rebuild-and-unpack! (fn [src]
                                   (doseq [t ["one" "two" "three"]]
                                     (write-run-entry! src (sp/declared-identity) t))
                                   (write-run-entry! src {:provider :azure :model "dep-other"} "four")))]
      (is (= :declaration-mismatch (:reason r)) (pr-str r))
      (is (= #{} (::unpacked r)))))

  (testing "the declared model NAME on another provider is refused: the provider is checked now"
    (let [r (rebuild-and-unpack! (fn [src]
                                   (doseq [t ["one" "two"]]
                                     (write-run-entry! src (assoc (sp/declared-identity) :provider :openai-compatible) t))))]
      (is (not= :openai-compatible (:provider (sp/declared-identity))) "NON-VACUITY: it IS another provider")
      (is (= :declaration-mismatch (:reason r)) (pr-str r))
      (is (= #{} (::unpacked r))))))

;; ============================================================================
;; a key from before the phrase negative-cache issue cannot name the model that wrote it, so four-segment
;; keys come from the ONE shipped archive, and nowhere else. Two layers, each
;; tested for WHICH of them refused, so neither can hide behind the other.
;; ============================================================================

(deftest the-systems-own-round-trip-cannot-change-the-record
  ;; the THIRD finding of one class, and the one that named it: a
  ;; transformation this system performs must not turn a DECLARED claim into a
  ;; VERIFIED one. Unpack, then pack the declared directory, then unpack again.
  (testing "the committed archive: its declared directory holds four-segment keys, which the builder refuses"
    (let [d (tmpdir) out (str (tmpdir) ".edn.gz")]
      (try
        (let [r (pc/ensure-warm! d)
              b (run-the-real-builder d out)]
          (is (= #{:configured-model} (:verified r)) "POSITIVE CONTROL: it unpacked, declaring the provider only")
          (is (not (zero? (:exit b))) (str "packing the declared directory is REFUSED: " (pr-str b)))
          (is (not (.exists (io/file out))) "and no archive exists to come back as #{:provider :model}"))
        (finally (rm-rf d) (io/delete-file out true)))))

  (testing "a rebuilt archive: the round trip is a copy, and the record comes back unchanged"
    (let [src (tmpdir) a1 (str (tmpdir) ".edn.gz") d1 (tmpdir) a2 (str (tmpdir) ".edn.gz") d2 (tmpdir)]
      (try
        (doseq [t ["one" "two"]] (write-run-entry! src (sp/declared-identity) t))
        (build-with-the-real-builder! src a1)
        (let [r1 (pc/ensure-warm! d1 (io/file a1))
              _ (build-with-the-real-builder! d1 a2)
              r2 (pc/ensure-warm! d2 (io/file a2))]
          (is (= :unpacked (:action r1) (:action r2)) (pr-str [r1 r2]))
          (is (= (:verified r1) (:verified r2)) "the same record: nothing on the way wrote a segment")
          (is (= (file-names src) (file-names d1) (file-names d2)) "and the same keys the generating run wrote"))
        (finally (doseq [x [src d1 d2]] (rm-rf x)) (doseq [x [a1 a2]] (io/delete-file x true)))))))

(deftest the-builder-packs-only-five-segment-keys
  ;; LAYER 1. The shape measured: a directory holding ONLY old entries,
  ;; some from another model. Nothing is mixed, and every model segment is the
  ;; declared one, because main keyed on the configured `gpt-4o` whatever it
  ;; sent - so without this, it packed and unpacked as the declared generator's.
  (doseq [[label populate!]
          [["only entries from before the phrase negative-cache issue, every model segment the declared one"
            (fn [src]
              (.mkdirs (io/file src))
              (doseq [t ["mine" "foreign"]]
                (spit (io/file src (str (legacy-key t (:model (sp/declared-identity))) ".edn"))
                      (pr-str [(str "written by some model about " t)]))))]
           ["entries from before the phrase negative-cache issue beside current ones"
            (fn [src]
              (write-run-entry! src (sp/declared-identity) "current")
              (spit (io/file src (str (legacy-key "old" (:model (sp/declared-identity))) ".edn"))
                    (pr-str ["an old phrase"])))]]]
    (testing label
      (let [src (tmpdir) out (str (tmpdir) ".edn.gz")]
        (try
          (populate! src)
          (let [r (run-the-real-builder src out)]
            (is (not (zero? (:exit r))) (str "the BUILDER refused: " (pr-str r)))
            (is (str/includes? (:out r) "not five-segment keys") "and said why")
            (is (not (.exists (io/file out))) "and wrote no archive"))
          (finally (rm-rf src) (io/delete-file out true)))))))

(deftest a-four-segment-archive-is-accepted-only-if-it-is-the-shipped-one
  ;; LAYER 2, for an archive that never went through the builder. Its model
  ;; segments all match the declaration, so the model check passes it; only the
  ;; pin on the shipped archive's content refuses it.
  (let [declared-model-seg (core/sha256-short-hash (:model (sp/declared-identity)))
        entries (for [t ["mine" "foreign" "another"]]
                  [(legacy-key t (:model (sp/declared-identity))) [(str "written by some model about " t)]])
        r (unpack-hand-assembled! entries)]
    (is (every? #(= declared-model-seg (second (str/split (first %) #"-"))) entries)
        "NON-VACUITY: every key carries the declared model, so the model check alone would pass it")
    (is (= :four-segment-archive-not-the-shipped-one (:reason r)) (pr-str r))
    (is (= #{} (::unpacked r)) "and nothing reached the declared directory")))

(deftest an-archive-mixing-key-shapes-is-refused-by-the-unpack-too
  ;; LAYER 2 for the mixed case, which the builder also refuses. Asserted by
  ;; REASON, so the pin (which would refuse it as well) cannot hide a disarmed
  ;; mixed-shape check.
  (let [r (unpack-hand-assembled! [[(legacy-key "old" (:model (sp/declared-identity))) ["an old phrase"]]
                                   [(sp/cache-key {:content_markdown "current"} (sp/declared-identity) "the prompt")
                                    ["a current phrase"]]])]
    (is (= :mixed-key-shapes (:reason r)) (pr-str r))
    (is (= #{4 5} (:shapes r)))
    (is (= #{} (::unpacked r)))))

;; ============================================================================
;; the boot's own call, not only the constant it should pass
;; ============================================================================

(deftest the-boot-path-unpacks-into-the-declared-directory
  ;; `the-unpack-directory-is-the-one-tier-2-reads-and-no-run-writes` pins
  ;; `cache-dir`. That proves nothing about the boot unless `warm!` is what hands
  ;; it on: unpacking into the LOCAL directory there stayed green with the
  ;; constant intact - the old defect back, with tier 2 silently empty. So this watches the
  ;; call, and checks it against the DERIVATION rather than the constant.
  (let [seen (atom [])]
    (with-redefs [pc/ensure-warm! (fn [& args]
                                    (swap! seen conj (vec args))
                                    {:action :skipped :reason :probe})]
      (pc/warm!))
    (is (= 1 (count @seen)) "POSITIVE CONTROL: the boot entry point unpacks, once")
    (is (= (str (io/file (sp/declared-cache-dir "folder"))) (str (io/file (ffirst @seen))))
        "into the directory tier 2 reads, not the one runs write")))

(deftest exactly-one-declared-identity-is-accepted
  ;; A list is refused rather than iterated: every extra generator is another
  ;; model whose output we would serve, which is a policy decision.
  (is (thrown? clojure.lang.ExceptionInfo
               (sp/validate-declared-identity [{:provider :azure :model "a"}
                                               {:provider :azure :model "b"}]))
      "a collection of identities is refused")
  (is (thrown? clojure.lang.ExceptionInfo
               (sp/validate-declared-identity {:model "a"}))
      "an identity with no provider is refused")
  (is (= {:provider :azure :model "a"}
         (sp/validate-declared-identity {:provider :azure :model "a" :generated "x"}))
      "POSITIVE CONTROL: one identity is accepted, and only its two fields travel"))
