(ns digdir.llm.marker
  "Wrapper for the Marker PDF-to-markdown service API

  https://github.com/VikParuchuri/marker"
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [taoensso.telemere :as t]))

(def default-timeout-ms
  "Socket/connection timeout for a Marker request. 6 hours: rendering a large
   PDF is genuinely slow. Override per tenant with `services.marker.timeout-ms`."
  (* 6 60 60 1000))

(def page-separator "\n\n------------------------------------------------\n\n")

(def default-retry-delays-ms
  "Retry delays for 502/503/429: 1min, 10min, 30min, 60min, 120min, 24hr.
   Override per tenant with `services.marker.retry-delays-ms` — e.g. `[1000]`
   to keep a dev feedback loop short, or `[]` for no retries at all."
  [(* 1 60 1000)
   (* 10 60 1000)
   (* 30 60 1000)
   (* 60 60 1000)
   (* 120 60 1000)
   (* 24 60 60 1000)])

(defn- configured
  "Read an optional `services.marker.<k>` value, or nil.

   Config reads throw when no definition is registered or the config DB is
   unavailable (no `CONFIG_MASTER_KEY`). Marker has always run on hardcoded
   values, so a failed read must fall back to them rather than break ingestion
   — same shape as `digdir.sweep.judge/judge-model`."
  [tenant k]
  (try (cfg/get {:tenant tenant :default nil} :services :marker k)
       (catch Exception _ nil)))

(defn- invalid-config!
  "Warn that a configured value is unusable, and return the default."
  [k value default]
  (t/log! {:level :warn
           :id :marker/invalid-config
           :data {:config-path (str "services.marker." (name k))
                  :value value
                  :using-default default}})
  default)

(defn timeout-ms
  "Marker request timeout for `tenant`: `services.marker.timeout-ms` when set to
   a positive number, else `default-timeout-ms`."
  [tenant]
  (if-some [v (configured tenant :timeout-ms)]
    (if (and (number? v) (pos? v))
      (long v)
      (invalid-config! :timeout-ms v default-timeout-ms))
    default-timeout-ms))

(defn retry-delays-ms
  "Retry ladder for `tenant`: `services.marker.retry-delays-ms` when set to a
   sequence of non-negative numbers (`[]` meaning no retries), else
   `default-retry-delays-ms`."
  [tenant]
  (if-some [v (configured tenant :retry-delays-ms)]
    (if (and (sequential? v) (every? #(and (number? %) (not (neg? %))) v))
      (mapv long v)
      (invalid-config! :retry-delays-ms v default-retry-delays-ms))
    default-retry-delays-ms))

(defn- retryable-error? [e]
  (let [status (-> e ex-data :status)]
    (or (= status 503)
        (= status 502)
        (= status 429))))

(defn- format-delay [ms]
  (cond
    (>= ms (* 60 60 1000)) (str (/ ms 60 60 1000) " hours")
    (>= ms (* 60 1000)) (str (/ ms 60 1000) " minutes")
    :else (str (/ ms 1000) " seconds")))

(defn- marker-api-url [tenant]
  (cfg/get {:tenant tenant} :services :marker :api-url))

(defn- marker-api-key [tenant]
  (cfg/get {:tenant tenant} :services :marker :api-key))

(defn- make-marker-request
  "Make a single HTTP request to the Marker API. Returns the response body on success."
  [tenant pdf-file filename]
  (let [start-time (System/currentTimeMillis)
        timeout (timeout-ms tenant)
        response (http/post (marker-api-url tenant)
                            {:multipart [{:name "file"
                                          :content pdf-file
                                          :filename (.getName pdf-file)}]
                             :query-params {"cache" "true"}
                             :headers {"X-API-Key" (marker-api-key tenant)}
                             :socket-timeout timeout
                             :connection-timeout timeout
                             :as :json})
        elapsed-ms (- (System/currentTimeMillis) start-time)
        response-body (:body response)]

    (t/log! {:level :info
             :id :marker/response-received
             :data {:filename filename
                    :elapsed-seconds (/ elapsed-ms 1000.0)
                    :status (:status response)
                    :success (:success response-body)
                    :cached (:cached response-body)}})

    (when-not (:success response-body)
      (throw (Exception. (str "Marker API conversion failed: " (pr-str response-body)))))

    {:pages (vec (:pages response-body))
     :cached (:cached response-body)
     :file-hash (:file_hash response-body)
     :elapsed-ms elapsed-ms}))

(defn- make-marker-request-with-retries
  "Make Marker API request with retries on 502/503/429 errors."
  [tenant pdf-file filename]
  (loop [delays (retry-delays-ms tenant)
         attempt 1]
    (let [result (try
                   {:success (make-marker-request tenant pdf-file filename)}
                   (catch Exception e
                     (if (and (retryable-error? e) (seq delays))
                       {:retry true :error e :delay (first delays)}
                       {:error e})))]
      (cond
        (:success result)
        (:success result)

        (:retry result)
        (let [delay-ms (:delay result)]
          (t/log! {:level :warn
                   :id :marker/retrying
                   :data {:filename filename
                          :attempt attempt
                          :delay-ms delay-ms
                          :delay-human (format-delay delay-ms)
                          :status (-> result :error ex-data :status)
                          :remaining-retries (dec (count delays))}})
          (Thread/sleep delay-ms)
          (recur (rest delays) (inc attempt)))

        :else
        (throw (:error result))))))

(defn ->md
  "Convert PDF file to markdown using Marker service API.

  Returns the full markdown content as a single string with pages
  separated by page-separator.

  Retries on 502/503/429 errors. The retry ladder and the request timeout come
  from `services.marker.retry-delays-ms` / `services.marker.timeout-ms`,
  defaulting to 1min, 10min, 30min, 60min, 120min, 24hr and 6 hours."
  [tenant filename]
  (t/log! {:level :info
           :id :marker/conversion-started
           :data {:filename filename}})

  (let [pdf-file (io/file filename)
        file-size (.length pdf-file)]
    (when-not (.exists pdf-file)
      (throw (Exception. (str "PDF file not found: " filename))))

    (t/log! {:level :info
             :id :marker/sending-request
             :data {:filename filename
                    :file-size file-size
                    :api-url (marker-api-url tenant)
                    :timeout-minutes (/ (timeout-ms tenant) 1000 60)}})

    (try
      (let [{:keys [pages cached file-hash elapsed-ms]}
            (make-marker-request-with-retries tenant pdf-file filename)]

        (t/log! {:level :info
                 :id :marker/conversion-completed
                 :data {:filename filename
                        :page-count (count pages)
                        :cached cached
                        :file-hash file-hash
                        :total-elapsed-seconds (/ elapsed-ms 1000.0)}})

        (str/join page-separator pages))

      (catch Exception e
        (t/error! {:id :marker/api-error
                   :data {:marker-api-url (marker-api-url tenant)
                          :filename filename
                          :error (.getMessage e)
                          :error-class (.getName (.getClass e))}}
                  e)
        (throw e)))))

(defn paginate
  "Split markdown content back into pages."
  [md]
  (str/split md (re-pattern page-separator)))
