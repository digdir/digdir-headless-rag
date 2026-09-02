(ns digdir.rag.typesense-admin
  "Vibed utilities for working with Typesense, including paginated search"
  (:require [typesense.client :as ts]
            [taoensso.telemere :as t]))

(defn ts-search-all-pages
  "Fetches all pages of search results from Typesense by making multiple requests.
  Returns a map with :hits containing all results and :found with the total count.
  
  Parameters:
  - ts-settings: Typesense connection settings map with :uri and :key
  - collection-name: Name of the Typesense collection to search
  - search-params: Search parameters map (same as ts/search but :per_page will be set to 250)
  
  The function will fetch 250 items at a time and combine all results."
  [ts-settings collection-name search-params]
  (let [per-page 250
        search-params-with-pagination (assoc search-params :per_page per-page)]
    (loop [page 1
           all-hits []]
      (let [current-params (assoc search-params-with-pagination :page page)
            _ (t/log! ["Fetching Typesense page" page "for collection" collection-name])
            result (try
                     (ts/search ts-settings collection-name current-params)
                     (catch Exception e
                       (t/error! {:id :vibed-typesense/search-error
                                 :data {:page page
                                        :collection collection-name}}
                                e)
                       (throw e)))
            hits (:hits result)
            total-found (:found result)
            accumulated-hits (into all-hits hits)
            fetched-so-far (count accumulated-hits)]
        
        (t/log! ["Fetched Typesense page" page 
                 "- hits on page:" (count hits)
                 "- total fetched:" fetched-so-far
                 "- total found:" total-found])
        
        ;; Continue if we have more results to fetch
        (if (and (seq hits) ;; We got results on this page
                 (< fetched-so-far total-found)) ;; And there are more to fetch
          (recur (inc page) accumulated-hits)
          ;; Return the complete result
          {:hits accumulated-hits
           :found total-found})))))

