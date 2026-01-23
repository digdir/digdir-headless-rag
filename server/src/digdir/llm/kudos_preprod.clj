(ns digdir.llm.kudos-preprod
  "Kudos preprod API client.

   Uses https://kudos-preprod.dfo.no/ instead of production.

   Key differences from production (kudos.dfo.no):
   - List endpoint: /api/v0/documents (no /search suffix)
   - Single doc endpoint: /api/v0/documents/{id} (same)
   - Response structure: identical"
  (:require [clj-http.client :as http]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

(def base-url "https://kudos-preprod.dfo.no")

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
  "Fetch a page of documents from the preprod API.
   Note: preprod uses /api/v0/documents (no /search suffix)"
  [n]
  (backoff (m/sp (:body (m/? (http-get (str base-url "/api/v0/documents?page=" n) {:as :json}))))
           [1000 3000 7000 60000]))

(defn pages [starting-page]
  (m/ap
   (loop [idx starting-page failed-to-fetch-last-page? false]
     (t/event! :kudos-preprod/fetching-page {:data {:index idx}})
     (let [res (try (m/? (get-page idx))
                    (catch Exception e
                      (t/event! ::failed-to-fetch-kudos-preprod-page-skipping-to-next-page)
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

(defn documents [kview]
  (m/eduction
   (mapcat #(cond-> % (ex-data %) vector))
   (remove #(when (ex-data %)
              (t/error! ::generic-error %)
              true))
   (pages (:kudos-preprod/starting-page kview 1))))

(defn documents-by-ids [ids]
  (m/ap
   (let [id (m/?> (m/seed ids))]
     (t/event! :kudos-preprod/fetching-document {:data {:id id}})
     (try
       (m/? (backoff
             (m/sp (:body (m/? (http-get (str base-url "/api/v0/documents/" id)
                                         {:as :json}))))
             [1000 3000 7000 60000]))
       (catch Exception e
         (t/error! {:id :kudos-preprod/failed-to-fetch-document
                    :data {:id id}
                    :msg ["Failed to fetch document" id]}
                   e)
         (ex-info "Failed to fetch document" {:id id :error e}))))))

(comment
  ;; Test fetching a single page
  (m/? (get-page 1))

  ;; Test fetching a single document
  (m/? (m/reduce conj (documents-by-ids [379135])))

  ;; Test the documents flow
  (do (def canceller ((m/reduce (fn [acc inp] (prn (type inp))) nil (documents {:kudos-preprod/starting-page 1}))
                      (partial prn :success)
                      (partial prn :failure)))
      (Thread/sleep 3000)
      (canceller)))
