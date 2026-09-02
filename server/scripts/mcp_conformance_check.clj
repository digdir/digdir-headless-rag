(ns mcp-conformance-check
  "Real-client MCP wire-protocol conformance check.

   Boots the production middleware stack on an ephemeral port, creates
   a temporary API key, and exercises the JSON-RPC envelope from a
   real HTTP client. Use this to verify the wire protocol after
   touching anything under digdir.mcp.*.

   Usage (from server/):
     clojure -J-Dlogback.configurationFile=scripts/logback-quiet.xml \\
             -M -e '(do (load-file \"scripts/mcp_conformance_check.clj\")
                        (mcp-conformance-check/-main))'

   The -J flag points logback at `scripts/logback-quiet.xml` so the
   load-time Jetty/mchange/digdir DEBUG noise stays out of the check
   output. Add \"-v\" to the args for full per-step payloads:
     clojure -J-Dlogback.configurationFile=scripts/logback-quiet.xml \\
             -M -e '(do (load-file \"scripts/mcp_conformance_check.clj\")
                        (mcp-conformance-check/-main \"-v\"))'"
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [digdir.agents.db :as agents-db]
            [digdir.api.http :as api-http]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.skills.api :as skills-api])
  (:import (ch.qos.logback.classic Level)
           (org.slf4j LoggerFactory)))

;; ---------------------------------------------------------------------------
;; Output / logging helpers
;; ---------------------------------------------------------------------------

(defn- quiet-loggers!
  "Mute the third-party DEBUG/INFO noise that drowns the actual check
   output. Jetty's lifecycle traces, mchange's config-file probes, our
   own per-request access log, and the transport's ERROR log for the
   intentional step-7 dataset-not-found failure are the biggest
   offenders."
  []
  (let [ctx (LoggerFactory/getILoggerFactory)]
    (doseq [logger ["org.eclipse.jetty"
                    "com.mchange"
                    "com.mchange.v2"
                    "com.mchange.v2.log.MLog"
                    "hato"
                    "ring"
                    "digdir.api"
                    "digdir.api.routes.endpoints"
                    "digdir.api.routes.handlers"
                    "digdir.config"
                    "digdir.data"
                    "digdir.skills"
                    "digdir.mcp"
                    "digdir.mcp.transport"]]
      (.setLevel (.getLogger ctx ^String logger) Level/OFF))))

(def ^:dynamic *verbose?* false)

(defn- ok
  "Print a passing-step line. Extra lines (Map of label -> value) only
   surface when --verbose is set."
  [n label & details]
  (println (format "  ✓ %d. %s" n label))
  (when *verbose?*
    (doseq [[k v] (partition 2 details)]
      (println (format "      %-14s %s" (str k) (pr-str v))))))

(defn- fail!
  "Print a failing-step line with all the context, then throw."
  [n label & details]
  (println (format "  ✗ %d. %s" n label))
  (doseq [[k v] (partition 2 details)]
    (println (format "      %-14s %s" (str k) (pr-str v))))
  (throw (ex-info (str "Step " n " failed: " label) {:step n})))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(def ^:private protocol-version "2026-07-28")

(defn- metadata-headers
  "The request-metadata headers 2026-07-28 requires, mirrored from the body.
   Notifications carry no id and have no defined header requirements."
  [body]
  (let [params (:params body {})]
    (cond-> {"MCP-Protocol-Version" protocol-version
             "Mcp-Method" (str (:method body))}
      (:name params) (assoc "Mcp-Name" (str (:name params))))))

(defn- post-mcp
  ([base-url api-key body] (post-mcp base-url api-key body nil))
  ([base-url api-key body header-overrides]
   (http/post (str base-url "/api/mcp")
              {:headers (merge {"X-API-Key" api-key
                                "Content-Type" "application/json"}
                               (metadata-headers body)
                               header-overrides)
               :body (json/generate-string body)
               :throw-exceptions false
               ;; clj-http's default :coerce is :unexceptional, which parses
               ;; JSON only for 2xx. Every validation check below asserts on a
               ;; 4xx error body, so coercion has to be unconditional.
               :coerce :always
               :as :json})))

(defn- stream-mcp-frames
  "Open an SSE tools/call and return the parsed JSON-RPC frames in order.
   Reads up to `max-frames`, exits as soon as a result/error frame
   lands (after which the stream contains nothing useful)."
  [base-url api-key body max-frames]
  (let [resp (http/post (str base-url "/api/mcp")
                        {:headers (merge {"X-API-Key" api-key
                                          "Content-Type" "application/json"
                                          "Accept" "text/event-stream"}
                                         (metadata-headers body))
                         :body (json/generate-string body)
                         :as :stream :throw-exceptions false
                         :socket-timeout 15000})
        reader (io/reader (:body resp))
        frames (atom [])]
    (loop [n 0]
      (when (< n max-frames)
        (when-let [line (.readLine reader)]
          (cond
            (str/starts-with? line "data: ")
            (let [payload (json/parse-string (subs line 6) true)]
              (swap! frames conj payload)
              (when-not (or (:result payload) (:error payload))
                (recur (inc n))))
            (str/starts-with? line ":") (recur n)
            :else (recur n)))))
    {:content-type (get-in resp [:headers "content-type"])
     :frames @frames}))

(defn- summarize-frame
  [i f]
  (cond
    (:method f) (format "%d. notification  progress=%s"
                        i (get-in f [:params :progress]))
    (:result f) (format "%d. result        isError=%s"
                        i (get-in f [:result :isError]))
    (:error f)  (format "%d. error         code=%s  msg=%s"
                        i (get-in f [:error :code])
                        (get-in f [:error :message]))))

;; ---------------------------------------------------------------------------
;; The seven checks
;; ---------------------------------------------------------------------------

(defn- check-1-unauthenticated [base-url]
  (let [r (http/post (str base-url "/api/mcp")
                     {:body (json/generate-string
                              {:jsonrpc "2.0" :id 0 :method "server/discover"})
                      :headers {"Content-Type" "application/json"
                                "MCP-Protocol-Version" protocol-version
                                "Mcp-Method" "server/discover"}
                      :throw-exceptions false})]
    (if (= 401 (:status r))
      (ok 1 "Unauthenticated request → 401"
          :status (:status r))
      (fail! 1 "Expected 401 for unauthenticated request"
             :status (:status r) :body (:body r)))))

(defn- check-2-server-discover [base-url api-key]
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :id 1 :method "server/discover" :params {}})
        res (get-in r [:body :result])]
    (if (and (= 200 (:status r))
             (= [protocol-version] (:supportedVersions res))
             (some? (get-in res [:capabilities :tools]))
             (number? (:ttlMs res))
             (= "public" (:cacheScope res)))
      (ok 2 (str "server/discover → " (pr-str (:supportedVersions res))
                 " (cacheable, public)")
          :status (:status r)
          :resultType (:resultType res)
          :ttlMs (:ttlMs res)
          :cacheScope (:cacheScope res)
          :capabilities (:capabilities res)
          :serverInfo (get-in res [:_meta (keyword "io.modelcontextprotocol/serverInfo")]))
      (fail! 2 "server/discover did not return required fields"
             :status (:status r) :body (:body r)))))

(defn- check-3-tools-list [base-url api-key]
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :id 2 :method "tools/list"})
        tools (get-in r [:body :result :tools])
        res   (get-in r [:body :result])]
    (if (and (= 200 (:status r)) (pos? (count tools))
             (number? (:ttlMs res))
             ;; PRIVATE is a security requirement here, not a preference: the
             ;; tool list is filtered per API key.
             (= "private" (:cacheScope res)))
      (do (ok 3 (format "tools/list → %d tools (cacheable, private)" (count tools))
              :tools (mapv (fn [t] (cond-> (:name t)
                                     (get-in t [:_meta :default])
                                     (str " [default]")))
                           tools))
          (when-not *verbose?*
            (doseq [t tools]
              (println (format "       - %s%s"
                               (:name t)
                               (if (get-in t [:_meta :default]) " [default]" ""))))))
      (fail! 3 "tools/list must return tools plus private caching hints"
             :status (:status r) :body (:body r)))))

(defn- check-4-ping [base-url api-key]
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :id 3 :method "ping"})]
    (if (= 200 (:status r))
      (ok 4 "ping → {}"
          :status (:status r) :result (-> r :body :result))
      (fail! 4 "ping did not return 200"
             :status (:status r) :body (:body r)))))

(defn- check-5-notification-accepted [base-url api-key]
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :method "notifications/initialized"})]
    (if (= 202 (:status r))
      (ok 5 "notification → 202 Accepted, no body"
          :status (:status r))
      (fail! 5 "notifications must return 202 Accepted with no body"
             :status (:status r) :body (:body r)))))

(defn- check-6-unknown-method [base-url api-key]
  ;; 404 AND -32601, not 200. The status is the half that matters for
  ;; interoperability: a JSON-RPC error inside a non-2xx response is what
  ;; lets a dual-era client tell a modern server from a legacy one (#139).
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :id 4 :method "some/unknown"})
        err (get-in r [:body :error])]
    (if (and (= 404 (:status r)) (= -32601 (:code err)))
      (ok 6 "Unknown method → 404 + JSON-RPC -32601"
          :status (:status r) :error err)
      (fail! 6 "Unknown method must return HTTP 404 with JSON-RPC code -32601"
             :status (:status r) :body (:body r)))))

(defn- check-7-streaming-tools-call [base-url api-key]
  ;; Pass tenant/dataset_config_key in tool args so pick-dataset-scope
  ;; resolves them on an unscoped seeded agent. The dataset itself
  ;; won't exist in the smoke-test DB, so the call will fail inside
  ;; invoke-rag — but that's enough to see the streaming envelope
  ;; carry a JSON-RPC response frame and prove the SSE path works.
  (let [{:keys [content-type frames]}
        (stream-mcp-frames
          base-url api-key
          {:jsonrpc "2.0" :id 5 :method "tools/call"
           :params {:name "builtin/fact-checker-agent__fact-checker"
                    :arguments {:user-query "Is the sky blue?"
                                :tenant "smoke-test-tenant"
                                :dataset_config_key "smoke-test"}
                    :_meta {:progressToken "smoke"}}}
          12)]
    (if (and content-type
             (str/includes? content-type "text/event-stream")
             (pos? (count frames)))
      (do (ok 7 (format "Streaming tools/call → %d SSE frame%s"
                        (count frames) (if (= 1 (count frames)) "" "s"))
              :content-type content-type
              :frames (mapv summarize-frame
                            (range (count frames))
                            frames))
          (when *verbose?*
            (doseq [[i f] (map-indexed vector frames)]
              (println (format "       %s" (summarize-frame i f))))))
      (fail! 7 "Streaming response did not carry SSE frames"
             :content-type content-type :frames frames))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------


;; ---------------------------------------------------------------------------
;; Server Validation (2026-07-28). These are the checks that did not exist
;; while we were a legacy server, and they are the ones that keep #139 shut.
;; ---------------------------------------------------------------------------

(defn- check-8-version-header-body-mismatch [base-url api-key]
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :id 6 :method "tools/list"
                     :params {:_meta {(keyword "io.modelcontextprotocol/protocolVersion")
                                      "2025-11-25"}}})
        err (get-in r [:body :error])]
    (if (and (= 400 (:status r)) (= -32020 (:code err)))
      (ok 8 "Header/body protocol-version mismatch → 400 + -32020"
          :status (:status r) :error err)
      (fail! 8 "A header that disagrees with the body must be rejected -32020"
             :status (:status r) :body (:body r)))))

(defn- check-9-unsupported-version [base-url api-key]
  (let [r (post-mcp base-url api-key
                    {:jsonrpc "2.0" :id 7 :method "tools/list"}
                    {"MCP-Protocol-Version" "2025-03-26"})
        err (get-in r [:body :error])]
    (if (and (= 400 (:status r))
             (= -32022 (:code err))
             (= [protocol-version] (get-in err [:data :supported])))
      (ok 9 "Unsupported version → 400 + -32022 listing supported versions"
          :status (:status r) :error err)
      (fail! 9 "Unsupported version must return -32022 with a supported list"
             :status (:status r) :body (:body r)))))

(defn- check-10-missing-method-header [base-url api-key]
  (let [r (http/post (str base-url "/api/mcp")
                     {:headers {"X-API-Key" api-key
                                "Content-Type" "application/json"
                                "MCP-Protocol-Version" protocol-version}
                      :body (json/generate-string
                              {:jsonrpc "2.0" :id 8 :method "tools/list"})
                      :throw-exceptions false :coerce :always :as :json})
        err (get-in r [:body :error])]
    (if (and (= 400 (:status r)) (= -32020 (:code err)))
      (ok 10 "Missing Mcp-Method header → 400 + -32020"
          :status (:status r) :error err)
      (fail! 10 "A missing required standard header must be rejected -32020"
             :status (:status r) :body (:body r)))))

(defn- check-11-get-and-delete-are-405 [base-url api-key]
  (let [g (http/get (str base-url "/api/mcp")
                    {:headers {"X-API-Key" api-key}
                     :throw-exceptions false :coerce :always :as :json})
        d (http/delete (str base-url "/api/mcp")
                       {:headers {"X-API-Key" api-key}
                        :throw-exceptions false :coerce :always :as :json})]
    (if (and (= 405 (:status g)) (= 405 (:status d)))
      (ok 11 "GET and DELETE on the MCP endpoint → 405"
          :get (:status g) :delete (:status d))
      (fail! 11 "GET/DELETE must be 405 — they belonged to the pre-2026 shape"
             :get (:status g) :delete (:status d)))))


(defn- check-12-legacy-initialize-is-told-what-we-speak [base-url api-key]
  ;; A legacy-only client (e.g. MCP Inspector 2.3.0) opens with `initialize`.
  ;; We cannot serve it, but the spec asks us to NAME our supported versions in
  ;; the error, because a legacy client has no fall-forward and this may be the
  ;; only diagnostic its user sees.
  (let [r (http/post (str base-url "/api/mcp")
                     {:headers {"X-API-Key" api-key
                                "Content-Type" "application/json"}
                      :body (json/generate-string
                              {:jsonrpc "2.0" :id 9 :method "initialize"
                               :params {:protocolVersion "2025-03-26"}})
                      :throw-exceptions false :coerce :always :as :json})
        err (get-in r [:body :error])]
    (if (and (= 400 (:status r))
             (= -32022 (:code err))
             (= [protocol-version] (get-in err [:data :supported]))
             (re-find (re-pattern protocol-version) (str (:message err))))
      (ok 12 "Legacy initialize → 400 + -32022 naming our supported versions"
          :status (:status r) :error err)
      (fail! 12 "A legacy initialize must be refused with our version named"
             :status (:status r) :body (:body r)))))

(defn -main [& args]
  (let [verbose? (boolean (some #{"-v" "--verbose"} args))]
    (quiet-loggers!)
    (binding [*verbose?* verbose?]
      (skills-api/initialize!)
      (agents-db/seed-builtin-agents! (config-db/get-conn))
      (let [api-key (:api-key (api-keys/create-api-key!
                                (config-db/get-conn) "mcp-conf" "smoke"
                                {:scopes #{:query} :user-email "s@t"}))
            server (api-http/start-server!
                     (fn [_] nil)
                     {:port 0 :host "127.0.0.1"
                      :resources-path "public" :manifest-path "manifest.edn"})
            port (-> server (.getConnectors) first .getLocalPort)
            base-url (str "http://127.0.0.1:" port)]
        (try
          (println "MCP conformance check —" base-url)
          (check-1-unauthenticated base-url)
          (check-2-server-discover base-url api-key)
          (check-3-tools-list base-url api-key)
          (check-4-ping base-url api-key)
          (check-5-notification-accepted base-url api-key)
          (check-6-unknown-method base-url api-key)
          (check-7-streaming-tools-call base-url api-key)
          (check-8-version-header-body-mismatch base-url api-key)
          (check-9-unsupported-version base-url api-key)
          (check-10-missing-method-header base-url api-key)
          (check-11-get-and-delete-are-405 base-url api-key)
          (check-12-legacy-initialize-is-told-what-we-speak base-url api-key)
          (println "\n✓ All twelve checks passed.")
          (when-not verbose?
            (println "  Re-run with -v to see full per-step payloads."))
          (catch Throwable e
            (println "\n✗ Conformance check failed:" (ex-message e))
            (.exit (Runtime/getRuntime) 1))
          (finally
            (.stop server)))
        (.exit (Runtime/getRuntime) 0)))))
