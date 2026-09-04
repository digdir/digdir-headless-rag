(ns digdir.setup.config-cli
  "Read and write one config value from inside the runtime image (#505).

   ## Why this is a `-main` and not a `bb` task

   The same reason `digdir.setup.bootstrap` is (#496), measured the same way:
   `/app` contains exactly one file, `app.jar`. There is no `server/src`, no
   `bb.edn`, no `deps.edn`. Every `bb` task that does real work shells out to
   `clojure {:dir \"server\"} -M …`, which needs the source tree — so `bb`
   cannot reach the config DB from in here, and the DB is on a Docker volume
   so the host's `bb` cannot reach it either.

   What the image has is a JVM and this jar, which is enough:

       docker compose exec server \\
         java -cp /app/app.jar clojure.main -m digdir.setup.config-cli \\
              get skills.retrieval.top-k demo runtime default

       docker compose exec server \\
         java -cp /app/app.jar clojure.main -m digdir.setup.config-cli \\
              set skills.retrieval.top-k 40 demo runtime default

   ## It calls the same functions the `bb` tasks call — deliberately

   `set` resolves with `config-db/get-config-node-by-tenant-config-key` and
   writes with `config-db/set-node-value!`; `get` delegates to
   `tools-config/get-value`. Those are the exact functions behind
   `bb config-set` and `bb config-get`.

   Nothing here reimplements \"write a config value\" or \"resolve a config
   value\". Two entry points that each own an implementation drift into
   disagreeing about what the operation means, and that is not hypothetical
   in this repository: #497 was two functions computing one collection name,
   #500 was two reads of one switch with opposite defaults. The read path had
   to be MOVED to `src` to make that possible — see `digdir.tools.config`.

   ## It touches the same change-marker, and that is not optional

   `config-db/set-node-value!` writes to disk. A running server holds a cached
   datahike snapshot and datahike's file backend does not notify other
   processes, so without `ci/touch-marker!` the value lands in the DB and the
   server keeps serving the old one — the write appears to do nothing. The
   failure is silent and reads as a broken write rather than a missing signal,
   which is why the marker is called here rather than left to the caller."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [digdir.config.cache-invalidation :as ci]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.tools.config :as tools-config]))

(def ^:private usage
  (str/join
   \newline
   ["Usage:"
    "  get <path> <tenant> <config-root> <config-key>"
    "      [--dataset-id <id>] [--agent-id <id>] [--pipeline-id <id>]"
    "  set <path> <edn-value> <tenant> <config-root> <config-key>"
    ""
    "  config-root is one of: platform | runtime | dataset"
    ""
    "Examples:"
    "  get skills.retrieval.top-k demo runtime default"
    "  set skills.retrieval.top-k 40 demo runtime default"
    "  set pipeline.storage.chunks-collection '\"demo_chunks\"' demo dataset default"]))

(defn- die!
  "Fail with a message on stderr and a non-zero exit.

   A CLI that prints nil and exits 0 for a value that is not there cannot be
   used in a script and misleads an operator reading it by eye, which is the
   situation this whole namespace exists to improve."
  [code & lines]
  (binding [*out* *err*]
    (doseq [l lines] (println l))
    (flush))
  (System/exit code))

(defn- flag-val
  "Value of `flag` in the trailing argument list, or nil.

   Mirrors the same scan `bb config-get` does over its trailing args."
  [args flag]
  (loop [xs args]
    (cond
      (empty? xs) nil
      (= flag (first xs)) (second xs)
      :else (recur (rest xs)))))

(defn- read-edn
  "Read an EDN value with the same reader `bb config-set` uses.

   The reader map is not decoration: `bb.edn`'s `read-edn` carries
   `{'sorted/map identity}`, so a plain `clojure.edn/read-string` here would
   reject a value the `bb` task accepts and the two entry points would
   disagree about what is a legal value."
  [s]
  (edn/read-string {:readers {'sorted/map identity}} s))

(defn- run-get!
  [[path tenant config-root config-key & flags]]
  (when-not (and path tenant config-root config-key)
    (die! 2 "get: needs <path> <tenant> <config-root> <config-key>" "" usage))
  (let [dataset-id  (flag-val flags "--dataset-id")
        agent-id    (flag-val flags "--agent-id")
        pipeline-id (flag-val flags "--pipeline-id")
        opts (cond-> {:path path
                      :root config-root
                      :tenant tenant
                      :tenant-config-key config-key}
               dataset-id  (assoc :dataset-id dataset-id)
               agent-id    (assoc :agent-id agent-id)
               pipeline-id (assoc :pipeline-id pipeline-id))
        ;; get-value prints the full resolution summary (same output as
        ;; `bb config-get`) and returns it, so the exit status can reflect
        ;; whether anything was actually found.
        summary (tools-config/get-value opts)]
    (if (:resolved? summary)
      (do (flush) (System/exit 0))
      (die! 1
            ""
            (str "  ✖ '" path "' does not resolve for tenant '" tenant
                 "' at root '" config-root "' / config-key '" config-key "'.")
            ""
            "    The traversal above shows where the lookup stopped. A path that"
            "    is merely unset resolves to nil the same way a misspelt one does,"
            "    so check the spelling against the printed :traversal before"
            "    concluding the value is missing."
            ""))))

(defn- run-set!
  [[path value-str tenant config-root config-key]]
  (when-not (and path value-str tenant config-root config-key)
    (die! 2 "set: needs <path> <edn-value> <tenant> <config-root> <config-key>" "" usage))
  (let [value (try
                (read-edn value-str)
                (catch Exception e
                  (die! 2 (str "set: value is not readable EDN: " value-str)
                        (str "     " (.getMessage e))
                        ""
                        "     A string value needs quotes the shell will not eat, e.g."
                        "       set some.path '\"a-string\"' demo dataset default")))
        root (keyword config-root)
        conn (config-db/get-conn)
        master-key (config-core/get-master-key)
        node (or (config-db/get-config-node-by-tenant-config-key @conn tenant root config-key)
                 (die! 1
                       ""
                       (str "  ✖ No config node for tenant '" tenant "' at root '"
                            config-root "' with config-key '" config-key "'.")
                       ""
                       "    Nothing was written. Check the tenant and config-key exist"
                       "    before assuming the path is wrong."
                       ""))
        result (config-db/set-node-value! conn
                                          {:root root
                                           :tenant tenant
                                           :node-id (:config.node/id node)
                                           :path path
                                           :value value
                                           :master-key master-key})
        ;; Without this a running server keeps serving its cached snapshot and
        ;; the write looks like a no-op. See the namespace docstring.
        marker-path (ci/touch-marker!)]
    (prn {:result result
          :tenant tenant
          :root root
          :tenant-config-key config-key
          :path path
          :marker marker-path})
    (when-not marker-path
      (binding [*out* *err*]
        (println)
        (println "  ⚠ No change-marker was written: this config DB has no file backend.")
        (println "    The value IS in the database, but a running server will keep")
        (println "    serving its cached snapshot until it restarts.")))
    (flush)
    (System/exit 0)))

(defn -main
  "Dispatch `get` / `set`. Exits explicitly, because the config DB starts
   non-daemon threads that keep the JVM alive after this returns."
  [& args]
  (let [[op & rest-args] args]
    (try
      (case op
        "get" (run-get! rest-args)
        "set" (run-set! rest-args)
        (die! 2
              (if op (str "Unknown operation: " op) "No operation given.")
              ""
              usage))
      (catch clojure.lang.ExceptionInfo e
        (die! 1
              ""
              (str "  ✖ " (.getMessage e))
              (str "    " (pr-str (ex-data e)))
              ""))
      (catch Exception e
        (die! 1
              ""
              (str "  ✖ " (.getClass e) ": " (.getMessage e))
              "")))))
