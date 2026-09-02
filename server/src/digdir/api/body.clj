(ns digdir.api.body
  "Bounded request-body reading shared by the HTTP API transports."
  (:import (java.io ByteArrayOutputStream InputStream Reader)
           (java.nio.charset StandardCharsets)))

(def default-max-json-body-bytes
  "One MiB is enough for the JSON API envelopes while preventing a request
   from being materialized without bound."
  (* 1024 1024))

(defn body-too-large-ex
  [limit]
  (ex-info "Request body too large"
           {:status 413
            :type ::body-too-large
            :max-body-bytes limit}))

(defn body-too-large?
  [x]
  (= ::body-too-large (:type (ex-data x))))

(defn- content-length
  [request]
  (when-let [raw (or (get-in request [:headers "content-length"])
                     (get-in request [:headers "Content-Length"]))]
    (try
      (Long/parseLong (str raw))
      (catch Exception _ nil))))

(defn- read-input-stream
  [^InputStream in limit]
  (let [buffer (byte-array 8192)
        out (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [remaining (inc (- limit total))
            n (.read in buffer 0 (int (min (alength buffer) remaining)))]
        (if (neg? n)
          (.toString out (.name StandardCharsets/UTF_8))
          (let [new-total (+ total n)]
            (when (> new-total limit)
              (throw (body-too-large-ex limit)))
            (.write out buffer 0 n)
            (recur new-total)))))))

(defn- read-reader
  [^Reader reader limit]
  ;; Ring request bodies are normally InputStreams. Reader support keeps the
  ;; helper compatible with lightweight tests without falling back to slurp.
  (let [buffer (char-array 4096)
        out (StringBuilder.)]
    (loop []
      (let [n (.read reader buffer 0 (alength buffer))]
        (if (neg? n)
          (let [result (.toString out)]
            (when (> (alength (.getBytes result StandardCharsets/UTF_8)) limit)
              (throw (body-too-large-ex limit)))
            result)
          (do
            (.append out buffer 0 n)
            ;; UTF-8 is at least one byte per UTF-16 code unit. This cheap
            ;; check bounds memory before the exact byte count above.
            (when (> (.length out) limit)
              (throw (body-too-large-ex limit)))
            (recur)))))))

(defn read-body-string
  "Read a Ring request body up to `limit` UTF-8 bytes. Throws ExceptionInfo
   carrying HTTP status 413 as soon as the declared or observed size exceeds
   the limit. Does not close the server-owned request stream."
  ([request] (read-body-string request default-max-json-body-bytes))
  ([request limit]
   (when (or (not (integer? limit)) (not (pos? limit)))
     (throw (IllegalArgumentException. "Request body limit must be positive")))
   (when-let [declared (content-length request)]
     (when (> declared limit)
       (throw (body-too-large-ex limit))))
   (let [body (:body request)]
     (cond
       (nil? body) ""
       (string? body)
       (do
         (when (> (alength (.getBytes ^String body StandardCharsets/UTF_8)) limit)
           (throw (body-too-large-ex limit)))
         body)
       (instance? InputStream body) (read-input-stream body limit)
       (instance? Reader body) (read-reader body limit)
       :else (throw (ex-info "Unsupported request body type" {:status 400}))))))
