(ns digdir.llm.kudos
  "Kudos document API client, for both the production and preprod deployments.

   The two deployments differ only in base URL, the list-endpoint path (prod
   appends `/search`, preprod does not) and the event names they log under —
   the response shape, pagination and backoff are identical. So the client is
   one implementation parameterised by a *profile*: `prod` or `preprod`.

   Pick the profile for a kview with `profile`, then pass it as the first
   argument to `get-page` / `pages` / `documents` / `documents-by-ids`.

   Event names are carried on the profile rather than derived, so that the
   preprod path keeps logging under the exact keywords it always has —
   including the `:digdir.llm.kudos-preprod/…` qualified ones inherited from
   the namespace this client absorbed. Anything filtering logs on those names
   keeps working."
  (:require [clj-http.client :as http]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

(def ^:private default-retry-delays-ms
  "1s / 3s / 7s / 60s. Both deployments have always used this schedule: prod's
   longer tail (1h, 3h) is reader-discarded by a `#_#_` and never applied."
  [1000 3000 7000 60000])

(def prod
  "Production Kudos API (kudos.dfo.no). Note the `/search` list suffix."
  {:base-url "https://kudos.dfo.no"
   :list-path "/api/v0/documents/search"
   :document-path "/api/v0/documents"
   :retry-delays-ms default-retry-delays-ms
   :starting-page-key :kudos/starting-page
   :events {:fetching-page :kudos/fetching-page
            :page-fetch-failed ::failed-to-fetch-kudos-page-skipping-to-next-page
            :document-dropped ::generic-error
            :fetching-document :kudos/fetching-document
            :document-fetch-failed :kudos/failed-to-fetch-document}})

(def preprod
  "Preprod Kudos API (kudos-preprod.dfo.no). Same response shape as prod; the
   list endpoint takes no `/search` suffix."
  {:base-url "https://kudos-preprod.dfo.no"
   :list-path "/api/v0/documents"
   :document-path "/api/v0/documents"
   :retry-delays-ms default-retry-delays-ms
   :starting-page-key :kudos-preprod/starting-page
   :events {:fetching-page :kudos-preprod/fetching-page
            :page-fetch-failed :digdir.llm.kudos-preprod/failed-to-fetch-kudos-preprod-page-skipping-to-next-page
            :document-dropped :digdir.llm.kudos-preprod/generic-error
            :fetching-document :kudos-preprod/fetching-document
            :document-fetch-failed :kudos-preprod/failed-to-fetch-document}})

(defn profile
  "The API profile a kview targets: `preprod` when `:kudos/use-preprod?` is
   set, `prod` otherwise."
  [kview]
  (if (:kudos/use-preprod? kview) preprod prod))

(defn backoff [request delays]
  (if-some [[delay & delays] (seq delays)]
    (m/sp
     (try (let [r (m/? request)]
            (t/event! :backoff/generic-success)
            r)
          (catch Exception error
            (t/event! :backoff/generic-retry-i-think-the-other-one-is-not-working)
            (t/error! {:id :backoff/generic-retry
                       :data {:delay delay}
                       :msg ["Waiting" delay "ms"]}
                      error)
            (if true #_(-> error ex-data :worth-retrying)
                (do (m/? (m/sleep delay))
                    (m/? (backoff request delays)))
                (throw error)))))
    request))

(defn http-get [url req] (m/via m/blk (http/get url req)))

(defn get-page
  "Fetch page `n` of the document list from the deployment `profile` names."
  [{:keys [base-url list-path retry-delays-ms]} n]
  (backoff (m/sp (:body (m/? (http-get (str base-url list-path "?page=" n) {:as :json}))))
           retry-delays-ms))

(defn pages [{:keys [events] :as profile} starting-page]
  (m/ap
   (loop [idx starting-page failed-to-fetch-last-page? false]
     (t/event! (:fetching-page events) {:data {:index idx}})
     (let [res (try (m/? (get-page profile idx))
                    (catch Exception e
                      (t/event! (:page-fetch-failed events))
                      e))]
       (if (instance? Exception res)
         (if failed-to-fetch-last-page?
           (ex-info "Failed to fetch page, giving up" {:page idx :error res})
           (m/amb (ex-info "Failed to fetch page" {:page idx :error res})
                  (recur (inc idx) true)))
         (let [last-page-idx (get-in res [:meta :last_page])
               current-page-idx (get-in res [:meta :current_page])
               page (:data res)]
           (if (<= last-page-idx current-page-idx)
             page
             (m/amb page (recur (inc idx) false)))))))))

(defn documents [{:keys [starting-page-key events] :as profile} kview]
  (m/eduction
   (mapcat #(cond-> % (ex-data %) vector))
   (remove #(when (ex-data %)
              (t/error! (:document-dropped events) %)
              true))
   (pages profile (get kview starting-page-key 1))))

(defn documents-by-ids [{:keys [base-url document-path retry-delays-ms events]} ids]
  (m/ap
   (let [id (m/?> (m/seed ids))]
     (t/event! (:fetching-document events) {:data {:id id}})
     (try
       (m/? (backoff
             (m/sp (:body (m/? (http-get (str base-url document-path "/" id)
                                         {:as :json}))))
             retry-delays-ms))
       (catch Exception e
         (t/error! {:id (:document-fetch-failed events)
                    :data {:id id}
                    :msg ["Failed to fetch document" id]}
                   e)
         (ex-info "Failed to fetch document" {:id id :error e}))))))

(comment
  ;; Fetch a single page / document from either deployment
  (m/? (get-page prod 1))
  (m/? (get-page preprod 1))
  (m/? (m/reduce conj (documents-by-ids preprod [379135])))

  ;; Try starting the documents flow and cancelling after a bit :^)
  (do (def canceller ((m/reduce (fn [_acc inp] (prn (type inp))) nil (documents prod {}))
                      (partial prn :success)
                      (partial prn :failure)))
      (Thread/sleep 3000)
      (canceller)))
