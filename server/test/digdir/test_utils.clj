(ns digdir.test-utils
  "Shared test utilities for API tests."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn mock-ring-request
  "Create a mock Ring request map.

  Args:
    method - :get, :post, :put, :delete
    uri - Request URI path
    opts - Optional map with:
           :body - Request body (will be converted to InputStream)
           :headers - Map of headers
           :params - Query parameters
           :path-params - Path parameters (e.g., {:id \"123\"})
           :api-key/entity-id - Entity ID from API key auth
           :user/id - User ID from JWT auth"
  [method uri & [opts]]
  (let [body-str (when-let [body (:body opts)]
                   (if (string? body) body (json/generate-string body)))]
    (cond-> {:request-method method
             :uri uri
             :headers (or (:headers opts) {})
             :params (or (:params opts) {})
             :remote-addr "127.0.0.1"}
      body-str (assoc :body (io/input-stream (.getBytes body-str "UTF-8")))
      (:path-params opts) (assoc :path-params (:path-params opts))
      (:api-key/entity-id opts) (assoc :api-key/entity-id (:api-key/entity-id opts))
      (:user/id opts) (assoc :user/id (:user/id opts)))))

(defn parse-json-body
  "Parse JSON from a Ring response body."
  [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn response-status
  "Get status code from Ring response."
  [response]
  (:status response))

(defmacro with-reset-atom
  "Execute body with an atom reset to empty map, then restore original value."
  [atom-var & body]
  `(let [original# (deref ~atom-var)]
     (try
       (reset! ~atom-var {})
       ~@body
       (finally
         (reset! ~atom-var original#)))))
