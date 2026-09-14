#!/usr/bin/env bb
;; Rebuild the committed warm phrase-cache archive (yardarm-warmcache).
;;
;;   bb phrase-cache-archive <cache-dir> [out-file]
;;
;; ⚠️ THE ARCHIVE IS EXPECTED TO BE REGENERATED, NOT MAINTAINED. Its entries are
;; keyed on chunk text, model, prompt and parser-version; a change to any of
;; those orphans every entry — they are never read again, which is inert rather
;; than wrong. When that happens, re-run a materialisation with a real key, point
;; this at the resulting cache directory, and commit the new file. There is no
;; migration to write and nothing here assumes the key is stable.
;;
;; Deterministic on purpose: entries are written in sorted key order into a
;; single EDN map, so rebuilding from the same input produces a byte-identical
;; file and a diff shows what actually changed rather than reordering noise.
(ns build-phrase-cache-archive
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.zip GZIPOutputStream]))

(defn -main [& args]
  (let [[src out] args
        out (or out "server/resources/demo-corpus/phrase-cache-folder-v2.edn.gz")]
    (when-not src
      (println "usage: bb phrase-cache-archive <cache-dir> [out-file]")
      (System/exit 2))
    (let [files (->> (.listFiles (io/file src))
                     (filter #(str/ends-with? (.getName %) ".edn"))
                     (sort-by #(.getName %)))
          m (reduce (fn [acc f]
                      (let [k (str/replace (.getName f) #"\.edn$" "")
                            v (try (edn/read-string (slurp f)) (catch Exception _ nil))]
                        ;; Only vectors of phrases. A partial or corrupt entry is
                        ;; skipped rather than shipped: an unreadable file in the
                        ;; cache directory is a miss, but an unreadable entry in
                        ;; the ARCHIVE would be shipped to everyone.
                        (if (and (vector? v) (every? string? v)) (assoc acc k v) acc)))
                    (sorted-map) files)]
      (io/make-parents out)
      (with-open [o (GZIPOutputStream. (io/output-stream out))]
        (.write o (.getBytes (pr-str m) "UTF-8")))
      (println (format "  %d files read, %d entries written, %d bytes"
                       (count files) (count m) (.length (io/file out))))
      (when (< (count m) (count files))
        (println (format "  ⚠ %d file(s) skipped as unreadable or not a phrase vector"
                         (- (count files) (count m))))))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
