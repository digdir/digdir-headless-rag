(ns digdir.llm.marker
  "Wrapper for the Marker PDF-to-markdown service API

  https://github.com/VikParuchuri/marker"
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [taoensso.telemere :as t]))

(def marker-api-url (delay (cfg/get :services :marker :api-url)))
(def marker-api-key (delay (cfg/get :services :marker :api-key)))
(def marker-timeout-ms (* 6 60 60 1000)) ; 6 hours

(def page-separator "\n\n------------------------------------------------\n\n")

(def retry-delays-ms
  "Retry delays for 503 errors: 1min, 10min, 30min, 60min, 120min, 24hr"
  [(* 1 60 1000)
   (* 10 60 1000)
   (* 30 60 1000)
   (* 60 60 1000)
   (* 120 60 1000)
   (* 24 60 60 1000)])

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

(defn- make-marker-request
  "Make a single HTTP request to the Marker API. Returns the response body on success."
  [pdf-file filename]
  (let [start-time (System/currentTimeMillis)
        response (http/post @marker-api-url
                            {:multipart [{:name "file"
                                          :content pdf-file
                                          :filename (.getName pdf-file)}]
                             :query-params {"cache" "true"}
                             :headers {"X-API-Key" @marker-api-key}
                             :socket-timeout marker-timeout-ms
                             :connection-timeout marker-timeout-ms
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
  [pdf-file filename]
  (loop [delays retry-delays-ms
         attempt 1]
    (let [result (try
                   {:success (make-marker-request pdf-file filename)}
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

  Retries on 502/503/429 errors with delays: 1min, 10min, 30min, 60min, 120min, 24hr"
  [filename]
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
                    :api-url @marker-api-url
                    :timeout-minutes (/ marker-timeout-ms 1000 60)}})

    (try
      (let [{:keys [pages cached file-hash elapsed-ms]}
            (make-marker-request pdf-file filename)]

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
                   :data {:marker-api-url @marker-api-url
                          :filename filename
                          :error (.getMessage e)
                          :error-class (.getName (.getClass e))}}
                  e)
        (throw e)))))

(defn paginate
  "Split markdown content back into pages."
  [md]
  (str/split md (re-pattern page-separator)))
