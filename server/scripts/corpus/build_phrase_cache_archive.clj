#!/usr/bin/env bb
;; Rebuild the committed warm phrase-cache archive (yardarm-warmcache).
;;
;;   bb phrase-cache-archive <cache-dir> [out-file]
;;
;; ⚠️ THE ARCHIVE IS EXPECTED TO BE REGENERATED, NOT MAINTAINED. Its entries are
;; keyed on chunk text, provider, model, prompt and parser-version; a change to
;; the chunker, prompt or parser-version orphans every entry — they are never
;; read again, which is inert rather than wrong. When that happens, re-run a
;; materialisation into an EMPTY cache directory, point this at it, commit the
;; new file, and update `phrase-cache-folder-v2.identity.edn` beside it to the
;; rebuilding installation. The corpus README has the steps. There is no
;; migration to write and nothing here assumes the key is stable.
;;
;; ⚠️ THIS PACKS THE WHOLE DIRECTORY, and ships all of it as the declared
;; generator's output. What stops a directory that was not empty, and on which
;; axis:
;;   - HERE: any key that is not five segments is REFUSED, and nothing is
;;     written. A four-segment key predates the phrase negative-cache issue and cannot name the
;;     model that wrote it - on main every demo install keyed on the CONFIGURED
;;     `gpt-4o` - so a directory of old entries, foreign ones included, would
;;     otherwise pack as the declared generator's.
;;   - AT UNPACK, so in `bb test`: five-segment keys naming more than one
;;     provider/model IDENTITY are refused. By NAME only: a foreign model that
;;     shares the declared provider and model names shares the key, and nothing
;;     here or there can see it. Empty the directory first.
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
          not-current (remove #(= 5 (count (str/split (str/replace (.getName %) #"\.edn$" "") #"-")))
                              files)
          _ (when (seq not-current)
              (println (format "  ✗ refusing: %d of %d entries are not five-segment keys (a four-segment one predates the phrase negative-cache issue and cannot name the model that wrote it, the provider-aware phrase-cache key). Nothing written. Empty the directory and re-materialise. First few:"
                               (count not-current) (count files)))
              (doseq [f (take 10 not-current)] (println "     " (.getName f)))
              (System/exit 1))
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
