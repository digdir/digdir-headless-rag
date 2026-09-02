(ns digdir.config.cache-invalidation
  "Cross-process invalidation for the in-memory datahike snapshot.

   The running bb dev JVM holds a cached `digdir.data.db/!conn`. External
   writers (`bb config-set`, `bb dump-import`, manual REPL transactions in a
   sibling JVM) persist to disk but don't propagate into this JVM's view —
   datahike's file backend doesn't notify other processes when index roots
   change. Without a fresh signal, every read in this JVM keeps returning
   the pre-write snapshot until the JVM restarts.

   The hybrid:
   - **File marker** (durable, cross-process). Writers touch
     `<datahike-dir>/.config-changed`. A daemon thread here polls its
     mtime every 5s; on change, calls `data.db/reconnect!`.
   - **HTTP refresh endpoint** lives in api/routes; calls into here for
     the immediate-refresh push.

   Both paths funnel into `data.db/reconnect!`, which is the single,
   serialized point of mutation for the conn atom."
  (:require [clojure.java.io :as io]
            [digdir.config.core :as config]
            [digdir.data.db :as ddb]
            [taoensso.timbre :as timbre]))

(def ^:private marker-filename ".config-changed")
(def ^:private poll-interval-ms 5000)

(defn- bootstrap-store-path
  "Resolve the configured datahike file-backend path from bootstrap config.
   Returns nil if not file-backed or no bootstrap loaded — the poller no-ops
   in that case (e.g. in-memory test runs)."
  []
  (when-let [bootstrap @config/!bootstrap-config]
    (let [env (:db-env bootstrap)
          cfg (get bootstrap env)
          backend (get-in cfg [:store :backend])]
      (when (= :file backend)
        (get-in cfg [:store :path])))))

(defn marker-file
  "Path of the cross-process invalidation marker, relative to the configured
   datahike file-backend store. Returns nil when there's no file backend."
  []
  (when-let [store-path (bootstrap-store-path)]
    (io/file store-path marker-filename)))

(defn touch-marker!
  "Bump the marker file's mtime to signal external config writes. Idempotent;
   creates the file if missing. Safe to call from external JVMs (`bb config-set`,
   `bb dump-import`) — it just touches a file under the store dir.

   Returns the file path string, or nil if no file backend is configured."
  []
  (when-let [file (marker-file)]
    (try
      (io/make-parents file)
      (.createNewFile file)
      (.setLastModified file (System/currentTimeMillis))
      (.getPath file)
      (catch Exception e
        (timbre/warn e "touch-marker! failed for" (.getPath file))
        nil))))

(defonce ^:private !poller (atom nil))

(def ^:private poller-property "digdir.config.poller")

(defn poller-enabled?
  "Whether the marker poller may run in this JVM. True unless
   -Ddigdir.config.poller=false.

   The poller exists to keep a *long-running* server fresh against external
   writers. A batch JVM - the test suite, a one-shot bb task - gains nothing
   from it and is actively harmed by it: `reconnect!` reassigns the
   process-global config conn from a daemon thread, so it can take that conn
   away from code that had just pointed it somewhere on purpose. The test
   suite sets this to false (see the :test alias in server/deps.edn)."
  []
  (not= "false" (System/getProperty poller-property)))

(defn- mtime
  [^java.io.File f]
  (try
    (when (.exists f) (.lastModified f))
    (catch Exception _ 0)))

(defn- poll-loop!
  [^java.io.File file]
  (loop [last-seen (mtime file)]
    (Thread/sleep (long poll-interval-ms))
    (let [now-seen (mtime file)]
      (when (and (some? now-seen)
                 (> now-seen (or last-seen 0)))
        (timbre/info "config-cache-invalidation: marker changed, reconnecting datahike conn")
        (try
          (ddb/reconnect!)
          (catch Exception e
            (timbre/warn e "config-cache-invalidation: reconnect! failed"))))
      (recur (or now-seen last-seen)))))

(defn start-poller!
  "Spawn the marker-poller daemon thread. Idempotent — second-and-later calls
   are no-ops. Called from `data.db/init-db!` after migrations complete.

   No-ops when `poller-enabled?` is false, which is how test JVMs keep a
   background thread from reassigning the process-global config conn."
  []
  (when (and (poller-enabled?) (nil? @!poller) (some? (marker-file)))
    (let [file (marker-file)
          t (Thread. ^Runnable #(poll-loop! file)
                     "config-cache-invalidation-poller")]
      (.setDaemon t true)
      (.start t)
      (reset! !poller t)
      t)))

(defn stop-poller!
  "Interrupt and drop the marker-poller daemon thread, mostly for tests."
  []
  (when-let [t @!poller]
    (.interrupt ^Thread t)
    (reset! !poller nil)))
