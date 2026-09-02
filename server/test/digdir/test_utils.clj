(ns digdir.test-utils
  "Shared test utilities for API tests."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(defmacro recording-fn
  "Drop-in replacement for `(fn [& _] body)` that RECORDS the arg count of
   every call. Read the counts with `arities`.

   Use it for any function that is multi-arity in `src/`. `(fn [& _] …)`
   accepts two and three arguments identically and returns the same value
   either way, so no assertion in the suite can see which arity a caller
   chose. That blindness is why #119 shipped behind a green suite: `/v1`
   called the 2-arity `build-rag-skill-params`, dropping the agent's
   `:skill-params`, while MCP called the 3-arity — and the stub of the
   function under test erased the difference.

   Recording rather than pinning the arity is deliberate. A fixed-arity stub
   throws when a caller DROPS an argument but stays silent when a caller ADDS
   one; recording catches both, and lets a test assert the arity a caller is
   expected to use."
  [& body]
  `(let [!calls# (atom [])]
     (with-meta (fn [& args#] (swap! !calls# conj (count args#)) ~@body)
       {::arities !calls#})))

(defn arities
  "Arg counts recorded by `recording-fn`, in call order."
  [stub]
  @(::arities (meta stub)))

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
