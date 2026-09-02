(ns digdir.data.background-worker
  "Background worker for non-blocking Datahike transactions.
   
   Allows queuing transactions to be processed asynchronously,
   preventing DB latency (especially JDBC) from blocking the 
   primary application or agentic loops."
  (:require [clojure.core.async :as a]
            [datahike.api :as d]
            [digdir.data.db :as db]
            [taoensso.telemere :as t]))

(defonce ^:private !tx-chan (a/chan 1000))
(defonce ^:private !worker-stop (atom nil))

(defn- process-batch!
  "Process a batch of transaction requests."
  [conn batch]
  (let [tx-data (mapcat :tx-data batch)
        callbacks (keep :callback batch)
        error-callbacks (keep :error-callback batch)]
    (try
      (when (seq tx-data)
        (let [report (d/transact conn {:tx-data (vec tx-data)})]
          (doseq [cb callbacks]
            (try (cb report) (catch Exception e (t/log! :error [::callback-failed {:error (.getMessage e)}]))))))
      (catch Exception e
        (doseq [error-cb error-callbacks]
          (try
            (error-cb e)
            (catch Exception callback-e
              (t/log! :error [::error-callback-failed {:error (.getMessage callback-e)}]))))
        (t/log! :error [::batch-transact-failed {:error (.getMessage e) :batch-size (count batch)}])))))

(defn- run-worker-loop!
  "Run the batching loop on a named DAEMON thread.

   Deliberately not a `future`. `future` runs on Clojure's agent send-off pool,
   whose threads are NON-daemon, and this loop polls every 20ms so it never goes
   idle long enough to be reaped. One call to `start!` therefore kept the whole
   JVM alive forever: `bb setup` printed \"Setup complete!\" and hung, and so did
   every other one-shot entry point that reached `skills.init/initialize!`
   (issue #100). A daemon thread cannot outlive the process it supports, and
   naming it makes the next thread dump self-explanatory."
  [stop-ch]
  (doto (Thread.
         ^Runnable
         (fn []
           (loop []
             (let [timeout (a/timeout (long 20))
                   [v port] (a/alts!! [!tx-chan stop-ch timeout])]
               (cond
                 (= port stop-ch) (t/log! :info [::worker-stopped])

                 ;; If we got a value, try to accumulate more for a small batch
                 (some? v)
                 (let [batch (loop [acc [v]]
                               (if (>= (count acc) 50)
                                 acc
                                 (let [[next-v port] (a/alts!! [!tx-chan (a/timeout (long 10))])]
                                   (if (and (= port !tx-chan) (some? next-v))
                                     (recur (conj acc next-v))
                                     acc))))]
                   (process-batch! (db/get-conn) batch)
                   (recur))

                 ;; Just a timeout, no work to do
                 :else (recur)))))
         "digdir-background-worker")
    (.setDaemon true)
    (.start)))

(defn start!
  "Start the background worker. 
   Consumes from !tx-chan and batches transactions for efficiency."
  []
  (locking !worker-stop
    (when-not @!worker-stop
      (let [stop-ch (a/chan)
            _ (reset! !worker-stop stop-ch)]
        (run-worker-loop! stop-ch)
        (t/log! :info [::worker-started])))))

(defn- ensure-started!
  []
  (when-not @!worker-stop
    (start!)))

(defn stop! []
  (locking !worker-stop
    (when-let [stop-ch @!worker-stop]
      (a/>!! stop-ch :stop)
      (reset! !worker-stop nil))))

(defn queue-transact!
  "Queue transaction data for background processing.
   
   Optional callback functions will be called with the transaction
   report upon success or the exception upon failure."
  ([tx-data] (queue-transact! tx-data nil nil))
  ([tx-data callback] (queue-transact! tx-data callback nil))
  ([tx-data callback error-callback]
   (ensure-started!)
   (if-not (a/put! !tx-chan {:tx-data tx-data
                             :callback callback
                             :error-callback error-callback})
     (do
       (when error-callback
         (try
           (error-callback (ex-info "Background worker queue unavailable"
                                    {:tx-count (count tx-data)}))
           (catch Exception e
             (t/log! :error [::error-callback-failed {:error (.getMessage e)}]))))
       (t/log! :warn [::queue-full {:tx-count (count tx-data)}])
       false)
     true)))
