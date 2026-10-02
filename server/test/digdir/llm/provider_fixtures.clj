(ns digdir.llm.provider-fixtures
  "Two seams for observing where an LLM call goes, shared by the provider-resolver change selector
   pins and the resolved-parameter harness.

   ## The install — config as a deployment would resolve it

   `with-install` stubs the platform accessor with a path->value map and models
   the two different ways a path can be unset, because the selectors disagree
   about both:

     defined, no value  -> the caller's `:default` (nil without one)
     not defined        -> throws, as `accessor/get` does (accessor.clj:317)

   The defined set is NOT typed here. It is the committed snapshot's
   `data.definitions` plus every path `ensure-all-config-definitions!` registers
   at runtime — what a fresh install holds after import. A hand-typed set would
   agree with whatever the test already assumed.

   Every other door into the config DB is a tripwire: `config-db/get-conn`
   throws. A selector that starts resolving through a door this stub does not
   cover fails loudly instead of answering from whatever store the JVM has open.

   ## The wire — what was actually sent

   `with-wire` stubs the two leaves every chat call ends at,
   `wkok.openai-clojure.api/create-chat-completion` and `clj-http.client/post`,
   and records one entry per call. Leaves, not `digdir.llm.client`: enrichment's
   `local-chat-completion` POSTs past the client entirely, and a stub on the
   client would record the branch the caller ASKED for rather than the one taken.

   The client's env-driven body overrides (`OPENAI_TEMPERATURE`,
   `OPENAI_MAX_TOKENS`, ... and the thinking flags) are neutralised, so a record
   does not depend on whose shell ran it. The remaining direct
   `System/getenv` reads cannot be redefined; their NAMES are reported as
   `:env-present` so a reader can see what the run was exposed to."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.llm.client]
            [digdir.secrets :as secrets]
            [digdir.setup.config :as setup-config]
            [wkok.openai-clojure.api :as wkok]))

;; ---------------------------------------------------------------------------
;; Definitions — read from the data, never typed
;; ---------------------------------------------------------------------------

(defn- snapshot-file
  "The one committed `config/system-import.normalized.<date>.json`. Resolved
   relative to `server/`, where `bb test` runs."
  []
  (let [files (->> (.listFiles (io/file "../config"))
                   (filter #(re-matches #"system-import\.normalized\.\d+\.json" (.getName ^java.io.File %))))]
    (when-not (= 1 (count files))
      (throw (ex-info "expected exactly one committed config snapshot"
                      {:found (mapv #(.getName ^java.io.File %) files)})))
    (first files)))

(def snapshot-definitions
  "Paths registered by the committed snapshot."
  (delay
    (->> (get-in (json/parse-string (slurp (snapshot-file))) ["data" "definitions"])
         (map #(get % "config-def/path"))
         set)))

(def runtime-definitions
  "Paths `ensure-all-config-definitions!` registers after import, collected by
   intercepting the one registration function every `ensure-*` calls."
  (delay
    (let [acc (atom #{})]
      (with-redefs [setup-config/ensure-config-definition! (fn [path _opts] (swap! acc conj path) nil)
                    config-db/get-conn (fn [] (throw (ex-info "tripwire: definition collection touched the DB" {})))]
        (binding [*out* (java.io.StringWriter.)]
          (setup-config/ensure-all-config-definitions!)))
      @acc)))

(defn fresh-install-definitions
  "What a fresh install has defined: snapshot, then runtime registration."
  []
  (into @snapshot-definitions @runtime-definitions))

;; ---------------------------------------------------------------------------
;; The install
;; ---------------------------------------------------------------------------

(defn- keys->path
  "`accessor/get`'s variadic path: keywords, or a single vector of them."
  [parts]
  (let [parts (if (and (= 1 (count parts)) (sequential? (first parts))) (first parts) parts)]
    (str/join "." (map name parts))))

(defn- path->string
  "`get-platform-value`'s path: a dotted string, or a vector of names."
  [path]
  (if (string? path) path (str/join "." (map name path))))

(defn with-install
  "Run `f` against a stubbed install.

   `values` maps dotted path -> value. Options:
     :defined       the defined paths (default: `fresh-install-definitions`)
     :tenant-tree?  false models a tenant with no platform tree and no global
                    fallback: every read throws `:tenant-root-missing`."
  ([values f] (with-install values {} f))
  ([values {:keys [defined tenant-tree?] :or {tenant-tree? true}} f]
   (let [defined (or defined (fresh-install-definitions))
         no-tree (fn [p] (ex-info "No platform tree for tenant" {:kind :tenant-root-missing :path p}))
         lookup (fn [p default]
                  (if (contains? values p) (clojure.core/get values p) default))]
     (with-redefs [accessor/get
                   (fn [opts & parts]
                     (let [p (keys->path parts)]
                       (cond
                         (not (contains? defined p))
                         (throw (ex-info (str "No config definition registered for path " (pr-str p))
                                         {:path p}))
                         (not tenant-tree?) (throw (no-tree p))
                         :else (lookup p (:default opts)))))

                   accessor/get-platform-value-with-trace
                   (fn [path opts]
                     (let [p (path->string path)]
                       (when-not tenant-tree? (throw (no-tree p)))
                       {:value (lookup p (:default opts))
                        :trace {:winning-node (if (contains? values p) :stub-install :default)}
                        :node nil}))

                   config-db/get-conn
                   (fn [] (throw (ex-info "UNSTUBBED CONFIG DOOR: a read reached the config DB past with-install" {})))]
       (f)))))

;; ---------------------------------------------------------------------------
;; The wire
;; ---------------------------------------------------------------------------

(def ^:private chat-suffix "/chat/completions")

(defn- max-tokens-of [body]
  (or (:max_completion_tokens body) (:max_tokens body) (:max-tokens body)))

(defn- wire-record [branch endpoint body key?]
  {:branch branch
   :endpoint endpoint
   :model (some-> (:model body) (#(if (keyword? %) (subs (str %) 1) (str %))))
   :temperature (:temperature body)
   :max-tokens (max-tokens-of body)
   :key? key?})

(defn- bearer-present? [headers]
  (boolean (some-> (get headers "Authorization") (str/replace #"^Bearer\s*" "") str/trim not-empty)))

(def ^:private env-names-read-directly
  "`System/getenv` reads on the chat path that cannot be redefined."
  ["OPENAI_API_ENDPOINT" "OPENAI_REASONING_EFFORT" "OPENAI_ENABLE_THINKING"
   "OPENAI_SOCKET_TIMEOUT_MS" "OPENAI_DISABLE_THINKING"
   "AZURE_OPENAI_API_KEY" "AZURE_OPENAI_API_ENDPOINT" "OPENAI_API_KEY" "OPENAI_ORGANIZATION"])

;; Where the key and endpoint came from, on every branch. Field names follow
;; Phase 0's in-src record so the two instruments
;; compare field by field.
;;
;;   :opts     the caller passed it — per tenant, from config
;;   :secret   the client's fallback, `secrets/get! :openai-api-key` (env backend)
;;   :env      process-global, read from the environment by the transport
;;   :default  a library default (wkok's OpenAI spec base-url; the client's
;;             `default-openai-endpoint`)
;;   nil       nothing: the request goes out without it
;;
;; `:env` is possible on BOTH branches until Phase 2. wkok falls back to the
;; environment whenever an opt is nil, on the Azure impl as much as the OpenAI
;; one (`azure.clj`: `(or api-key (System/getenv "AZURE_OPENAI_API_KEY"))`), so
;; a tenant with no configured Azure key silently borrows the process-global
;; one. That rule lives in the jar, below this stub — so on the wkok path these
;; fields RE-DERIVE it, and `:key-rederived?` / `:endpoint-rederived?` say so.
;; On the client's direct path they are exact: its own opts are captured on the
;; way in. After Phase 2, `:env`, `:secret` and nil must not occur at all.

(def ^:private wkok-env-vars
  {:azure  {:key "AZURE_OPENAI_API_KEY" :endpoint "AZURE_OPENAI_API_ENDPOINT"}
   :openai {:key "OPENAI_API_KEY"       :endpoint "OPENAI_API_ENDPOINT"}})

(def ^:private wkok-openai-default
  "wkok's OpenAI-impl base URL, used when both the opt and OPENAI_API_ENDPOINT are
   absent. READ from wkok's own bootstrapped martian (`:api-root` of
   `wkok.openai-clojure.openai/m`, parsed from its bundled openapi.yaml), not
   typed here — a typed copy would agree with itself if wkok ever changed."
  (delay (:api-root @@(requiring-resolve 'wkok.openai-clojure.openai/m))))

(defn- wkok-provenance
  "wkok's `(or opt (System/getenv …))` rule, re-derived for `branch`."
  [branch opts]
  (let [{:keys [key endpoint]} (wkok-env-vars branch)]
    {:via :wkok
     :key-from (cond (:api-key opts) :opts (System/getenv key) :env :else nil)
     :endpoint-from (cond (:api-endpoint opts) :opts
                          (System/getenv endpoint) :env
                          (= :azure branch) nil
                          :else :default)
     :key-rederived? (not (:api-key opts))
     :endpoint-rederived? (not (:api-endpoint opts))}))

(def ^:private ^:dynamic *client-direct-opts*
  "The opts `digdir.llm.client` received for the direct call in flight;
   `::bypassed` when a caller POSTs without going through the client."
  ::bypassed)

(defn- direct-provenance
  "Exact, from the opts the client received. The client resolves a missing key
   through `secrets/get! :openai-api-key` and a missing endpoint through
   `OPENAI_API_ENDPOINT`, then its own default."
  []
  (let [o *client-direct-opts*]
    (merge {:key-rederived? false :endpoint-rederived? false}
           (if (= ::bypassed o)
             {:via :direct-post :key-from :opts :endpoint-from :opts}
             {:via :client
              :key-from (if (:api-key o) :opts :secret)
              :endpoint-from (cond (:api-endpoint o) :opts
                                   (System/getenv "OPENAI_API_ENDPOINT") :env
                                   :else :default)}))))

(defn- sse-channel
  "A closed channel carrying the minimum SSE an OpenAI-compatible server sends
   for `content`: one delta, then a finish. The streaming path
   (`digdir.llm.openai/streaming-chat-completion`) drains it synchronously."
  [content]
  (let [events [{:choices [{:delta {:content content} :index 0}]}
                {:choices [{:delta {} :finish_reason "stop" :index 0}]}]
        ch (async/chan (count events))]
    (doseq [e events] (async/>!! ch e))
    (async/close! ch)
    ch))

(defn with-wire
  "Run `f` with both chat leaves stubbed, returning
   `{:result r :error e :calls [record ...] :env-present [names]}`.

   Each record: `:branch` (:azure | :openai), `:path` (:blocking | :streaming),
   `:endpoint :model :temperature :max-tokens :key?`, and the provenance fields
   `:via :key-from :endpoint-from :key-rederived? :endpoint-rederived?`.

   `f` throwing is captured as `:error` rather than propagated, so a test can
   state what reached the wire BEFORE a failure — zero calls is an observation.

   Options:
     :respond         request body -> assistant content (default \"ok\"), or a
                      whole message map (e.g. one carrying :tool_calls)
     :openai-api-key  what the `OPENAI_API_KEY` secret resolves to on the direct
                      branch (default a dummy; nil models it being absent)"
  ([f] (with-wire {} f))
  ([{:keys [respond openai-api-key] :or {respond (constantly "ok") openai-api-key "pin-openai-env-key"}} f]
   (let [calls (atom [])
         reply (fn [body] (let [r (respond body)]
                            {:choices [{:index 0 :message (if (map? r) r {:role "assistant" :content r})}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}))
         wkok-leaf (fn [params opts]
                     (let [branch (if (= :azure (:impl opts)) :azure :openai)
                           prov (wkok-provenance branch opts)]
                       (swap! calls conj (merge (wire-record branch
                                                             (or (:api-endpoint opts)
                                                                 (System/getenv (get-in wkok-env-vars [branch :endpoint]))
                                                                 (when (= :openai branch) @wkok-openai-default))
                                                             params
                                                             (boolean (or (:api-key opts)
                                                                          (System/getenv (get-in wkok-env-vars [branch :key])))))
                                                prov
                                                {:path (if (:stream params) :streaming :blocking)})))
                     (if (:stream params)
                       ;; The two wkok impls answer a stream in DIFFERENT shapes:
                       ;; Azure {:body <channel>}, OpenAI the bare channel.
                       (let [r (respond params)
                             ch (sse-channel (if (map? r) (:content r) r))]
                         (if (= :azure (:impl opts)) {:body ch} ch))
                       (reply params)))
         client-direct @#'digdir.llm.client/openai-compat-completion]
     (with-redefs [wkok/create-chat-completion
                   (fn ([params] (wkok-leaf params nil))
                     ([params opts] (wkok-leaf params opts)))

                   digdir.llm.client/openai-compat-completion
                   (fn [params opts]
                     (binding [*client-direct-opts* opts]
                       (client-direct params opts)))

                   http/post
                   (fn [url opts]
                     (when-not (str/ends-with? url chat-suffix)
                       (throw (ex-info "unexpected HTTP POST under with-wire" {:url url})))
                     (let [body (json/parse-string (:body opts) true)]
                       (swap! calls conj (merge (wire-record :openai (subs url 0 (- (count url) (count chat-suffix)))
                                                             body (bearer-present? (:headers opts)))
                                                (direct-provenance)
                                                {:path :blocking}))
                       {:status 200 :body (reply body)}))

                   digdir.llm.client/env-inference-overrides (constantly {})
                   digdir.llm.client/env-flag? (constantly false)]
       (binding [secrets/*resolution-order* [:env]
                 secrets/*env-lookup* (fn [n] (when (= "OPENAI_API_KEY" n) openai-api-key))]
         (let [[result error] (try [(f) nil] (catch Exception e [nil e]))]
           {:result result
            :error error
            :calls @calls
            :env-present (filterv #(some? (System/getenv %)) env-names-read-directly)}))))))
