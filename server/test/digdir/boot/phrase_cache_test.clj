(ns digdir.boot.phrase-cache-test
  "The committed warm phrase cache must unpack, must not clobber, and must be
   absent-safe (yardarm-warmcache).

   The third property is what makes the feature's own verification honest: if a
   run with no archive could not produce cache MISSES, a run with the archive
   could not prove its HITS came from the archive rather than from a volume that
   was already warm."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.phrase-cache :as pc]))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/pc-test-" (random-uuid)))

(defn- rm-rf [p]
  (let [f (io/file p)]
    (when (.exists f)
      (doseq [c (.listFiles f)] (io/delete-file c true))
      (io/delete-file f true))))

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
          (is (re-matches #"[0-9a-f]{12}-[0-9a-f]{12}-[0-9a-f]{12}-v\d+\.edn"
                          (.getName sample))
              (str "unexpected key shape: " (.getName sample))))
        (testing "and the content reads back as a vector of phrase strings"
          (let [v (edn/read-string (slurp sample))]
            (is (vector? v))
            (is (seq v))
            (is (every? string? v)))))
      (finally (rm-rf d)))))

(deftest an-already-warm-directory-is-never-clobbered
  ;; A real run writes newer entries than the archive holds. Overwriting them
  ;; would throw away work and silently reverse the feature's purpose.
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

(deftest the-cache-dir-matches-what-the-generator-writes
  ;; `mk-distill-search-phrases-t` builds (str "cache/" name "-search-phrases/").
  ;; If these drift, the archive unpacks somewhere nothing reads.
  (testing "folder corpus, relative path, under the mounted /app/cache"
    (is (= "cache/folder-search-phrases" pc/cache-dir))
    (is (str/starts-with? pc/cache-dir "cache/")
        "an absolute path would land outside the digdir-cache volume")))
