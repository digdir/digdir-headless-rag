(ns digdir.boot.phrase-cache
  "Unpack the committed warm phrase cache on first run (yardarm-warmcache).

   WHAT THIS SAVES. `mk-distill-search-phrases-t` makes one LLM call per chunk
   whose phrases are not already on disk. A newcomer's first materialisation of
   the demo corpus therefore pays that bill in full, for chunks whose phrases we
   have already generated and which are identical on every installation.

   WHY THE CACHE IS PORTABLE AT ALL. The key is
   `<sha256-short(chunk text)>-<sha256-short(model)>-<sha256-short(prompt)>-<parser-version>`.
   None of those segments is installation-specific: the model is the literal
   `gpt-4o` shipped as a constant in `digdir.setup.demo-dataset`, the prompt is
   the shipped default, and the chunk text comes from a corpus pinned by
   `manifest.edn`. So a key generated here is the key generated there.

   ⚠️ THE KEY IS NOT PROMISED TO BE STABLE, AND THIS IS BUILT FOR THAT. A change
   to the chunker, the prompt, the model constant or `parser-version` orphans
   every entry — they are simply never read again, exactly as the cache-key
   docstring says. Nothing here assumes otherwise: the archive is data, the
   filename carries the key version it was built under, and regenerating it is
   `bb phrase-cache-archive`. There is no migration to write, because an orphaned
   entry is inert rather than wrong.

   ⚠️ AND IT IS AN OPTIMISATION, SO IT NEVER FAILS THE BOOT. A missing, corrupt
   or unreadable archive logs and continues. The system's behaviour is identical
   with and without it, apart from the size of the first LLM bill — which is also
   what makes the verification honest: a run with the archive absent must still
   log `:search-phrases/cache-miss`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.util.zip GZIPInputStream]))

(def archive-resource
  "Classpath resource, so it rides inside the uberjar and needs no Dockerfile
   change. `server/resources` is already on `:paths` and already COPYed into the
   build stage."
  "demo-corpus/phrase-cache-folder-v2.edn.gz")

(def cache-dir
  "Must match `mk-distill-search-phrases-t`, which builds
   `(str \"cache/\" cache-dir-name \"-search-phrases/\")` — relative, so under
   the container's /app it lands inside the `digdir-cache` volume mounted at
   /app/cache (#495). This fills plumbing that already exists."
  "cache/folder-search-phrases")

(defn- already-warm?
  "Does the cache directory already hold entries? Anything present means a real
   run has written there, or a previous unpack succeeded — either way this must
   not overwrite it. A warmer cache than the archive is the normal steady state."
  [^java.io.File dir]
  (boolean (some-> (.listFiles dir) seq)))

(defn ensure-warm!
  "Unpack the archive into `cache-dir` unless it is already populated.

   Returns a map describing what happened, never a bare nil, so a caller can log
   it and a test can assert on it:
     {:action :unpacked  :written n}
     {:action :skipped   :reason :already-populated :existing n}
     {:action :skipped   :reason :no-archive}
     {:action :skipped   :reason :unreadable :error \"...\"}"
  ([] (ensure-warm! cache-dir))
  ([dir-path]
   (let [dir (io/file dir-path)]
     (cond
       (already-warm? dir)
       {:action :skipped :reason :already-populated
        :existing (count (.listFiles dir))}

       (nil? (io/resource archive-resource))
       {:action :skipped :reason :no-archive}

       :else
       (try
         (let [m (with-open [in (GZIPInputStream. (io/input-stream (io/resource archive-resource)))]
                   (edn/read-string (slurp in)))]
           (.mkdirs dir)
           (doseq [[k phrases] m]
             (spit (io/file dir (str k ".edn")) (pr-str phrases)))
           {:action :unpacked :written (count m)})
         (catch Throwable t
           ;; An optimisation must not be able to stop a boot.
           {:action :skipped :reason :unreadable :error (.getMessage t)}))))))

(defn warm!
  "Boot entry point: unpack and log. Logs at INFO on every path, because the
   verification for this feature is reading the log — a silent success and a
   silent skip are indistinguishable to the person checking whether it worked."
  []
  (let [result (ensure-warm!)]
    (log/info (str "Warm phrase cache: " (pr-str result)))
    result))
