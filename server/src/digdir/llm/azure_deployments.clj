(ns digdir.llm.azure-deployments
  "List available Azure OpenAI deployments for a tenant via the data-plane
   deployments API. Uses the same inference endpoint and api-key already
   configured for chat completions — no Azure AD service principal or
   management-plane credentials needed.

   Results are cached per-tenant for 60 seconds to avoid hitting the API on
   every UI render. Failures degrade silently (return nil) so callers can
   fall back to a hardcoded list."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as openai]
            [taoensso.telemere :as t]))

(def ^:private cache-ttl-ms 60000)

(defonce ^:private !cache (atom {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- fresh? [entry]
  (and entry (< (- (now-ms) (:at entry)) cache-ttl-ms)))

(defn- normalize-endpoint
  "Strip trailing slashes from the configured endpoint so the deployments
   path appends cleanly."
  [endpoint]
  (some-> endpoint (str/replace #"/+$" "")))

(defn- fetch-deployments
  [tenant]
  (let [endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
        api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)]
    (when (and (not (str/blank? endpoint))
               (not (str/blank? api-key)))
      (try
        ;; `2022-12-01` is the last api-version on which the data-plane
        ;; deployments-LIST endpoint still answers. It is not the inference
        ;; api-version (wkok's Azure impl sends `2024-06-01` for
        ;; chat/completions) and the two move independently: measured against
        ;; the configured resource, `2022-12-01` and `2023-03-15-preview`
        ;; return 200 with all 39 deployments, while `2024-08-01-preview`,
        ;; `2024-10-21` and `2025-04-01-preview` all answer
        ;; 404 {"code":"404","message":"Resource not found"}. The previous
        ;; `2024-08-01-preview` therefore made this fn ALWAYS return nil, and
        ;; the failure is silent by design — `list-deployment-names` degrades to
        ;; nil and the playground picker falls back to its hardcoded list. The
        ;; symptom is a model picker that looks fine while showing deployments
        ;; that do not exist, and it is how a stale `deployment-name` survived
        ;; long enough to 404 every agent run. Prefer the stable version over
        ;; the preview one.
        (let [url (str (normalize-endpoint endpoint)
                       "/openai/deployments?api-version=2022-12-01")
              resp (http/get url {:headers {"api-key" api-key}
                                  :throw-exceptions false
                                  :socket-timeout 5000
                                  :conn-timeout 5000})]
          (if (= 200 (:status resp))
            (->> (get-in (json/read-str (:body resp) :key-fn keyword) [:data])
                 (keep :id)
                 (filter string?)
                 sort
                 vec)
            (do
              (t/log! :warn [:azure-deployments/non-200
                             {:tenant tenant
                              :status (:status resp)}])
              nil)))
        (catch Exception e
          (t/log! :warn [:azure-deployments/fetch-failed
                         {:tenant tenant
                          :error (.getMessage e)}])
          nil)))))

(defn list-deployment-names
  "Return Azure OpenAI deployment names for `tenant` as a sorted vector,
   or nil when:
   - the tenant isn't using Azure (`use-azure-openai-api` false / unset),
   - the inference endpoint or api-key isn't configured,
   - the API call fails or returns non-200.

   Cached for 60s per tenant to avoid hitting the API on every UI render.
   Callers should treat nil as 'fall back to a hardcoded list'."
  [tenant]
  (when (and (not (str/blank? tenant))
             (try (openai/use-azure-openai tenant) (catch Exception _ false)))
    (let [cached (get @!cache tenant)]
      (if (fresh? cached)
        (:names cached)
        (when-let [names (fetch-deployments tenant)]
          (swap! !cache assoc tenant {:at (now-ms) :names names})
          names)))))

(defn invalidate-cache!
  "Force the next call to fetch fresh data. Useful for testing and after an
   operator updates the tenant's Azure config."
  ([] (reset! !cache {}))
  ([tenant] (swap! !cache dissoc tenant)))
