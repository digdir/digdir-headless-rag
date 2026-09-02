(ns digdir.llm.kudos-test
  "Pins the prod/preprod differences the consolidated Kudos client is
   parameterised over: list-endpoint URL (prod carries a `/search` suffix),
   single-document URL, the kview key each profile reads its starting page
   from, and the event names each logs under.

   The event assertions capture real Telemere signals rather than reading the
   profile maps back, because the point of the parameterisation is that preprod
   keeps logging under exactly the keywords it did when it was its own
   namespace. No live provider: `clj-http.client/get` is redefined."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [digdir.llm.kudos :as kudos]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

(defn- one-page-body
  "A list response that declares itself the last page, so `pages` terminates."
  [docs]
  {:body {:meta {:last_page 1 :current_page 1} :data docs}})

(defn- capture-urls
  "Run `f`, returning [urls result] for every URL clj-http was asked for."
  [response f]
  (let [!urls (atom [])]
    (with-redefs [http/get (fn [url _req] (swap! !urls conj url) response)]
      (let [result (f)]
        [@!urls result]))))

(deftest profile-selection-follows-the-kview-flag
  (testing ":kudos/use-preprod? picks preprod; anything else stays on prod"
    (is (= kudos/preprod (kudos/profile {:kudos/use-preprod? true})))
    (is (= kudos/prod (kudos/profile {:kudos/use-preprod? false})))
    (is (= kudos/prod (kudos/profile {})))
    (is (= kudos/prod (kudos/profile nil)))))

(deftest get-page-builds-the-list-url-per-deployment
  (testing "prod appends the /search suffix"
    (let [[urls _] (capture-urls (one-page-body [])
                                 #(m/? (kudos/get-page kudos/prod 3)))]
      (is (= ["https://kudos.dfo.no/api/v0/documents/search?page=3"] urls))))

  (testing "preprod uses the bare documents path"
    (let [[urls _] (capture-urls (one-page-body [])
                                 #(m/? (kudos/get-page kudos/preprod 3)))]
      (is (= ["https://kudos-preprod.dfo.no/api/v0/documents?page=3"] urls)))))

(deftest documents-by-ids-builds-the-document-url-per-deployment
  (testing "single-document path is the same shape on both, under each base URL"
    (let [[prod-urls docs] (capture-urls {:body {:id 42}}
                                         #(m/? (m/reduce conj (kudos/documents-by-ids
                                                               kudos/prod [42]))))]
      (is (= ["https://kudos.dfo.no/api/v0/documents/42"] prod-urls))
      (is (= [{:id 42}] docs)))

    (let [[preprod-urls _] (capture-urls {:body {:id 42}}
                                         #(m/? (m/reduce conj (kudos/documents-by-ids
                                                               kudos/preprod [42]))))]
      (is (= ["https://kudos-preprod.dfo.no/api/v0/documents/42"] preprod-urls)))))

(deftest documents-reads-the-starting-page-key-of-its-profile
  (testing "prod reads :kudos/starting-page"
    (let [[urls _] (capture-urls (one-page-body [{:id 1}])
                                 #(m/? (m/reduce conj (kudos/documents
                                                       kudos/prod
                                                       {:kudos/starting-page 7}))))]
      (is (= ["https://kudos.dfo.no/api/v0/documents/search?page=7"] urls))))

  (testing "preprod reads :kudos-preprod/starting-page"
    (let [[urls _] (capture-urls (one-page-body [{:id 1}])
                                 #(m/? (m/reduce conj (kudos/documents
                                                       kudos/preprod
                                                       {:kudos-preprod/starting-page 7}))))]
      (is (= ["https://kudos-preprod.dfo.no/api/v0/documents?page=7"] urls))))

  (testing "both default to page 1 and yield the page's documents"
    (let [[urls docs] (capture-urls (one-page-body [{:id 1} {:id 2}])
                                    #(m/? (m/reduce conj (kudos/documents kudos/prod {}))))]
      (is (= ["https://kudos.dfo.no/api/v0/documents/search?page=1"] urls))
      (is (= [{:id 1} {:id 2}] docs)))))

(defn- signal-ids
  "Set of Telemere signal ids emitted while draining `flow`. A set, not a
   vector: `backoff` runs its request on another thread, and `with-signals`
   binds dynamically, so which of the shared `:backoff/…` signals land here is
   not something the test should depend on."
  [flow response]
  (let [{:keys [signals]}
        (t/with-signals
          (with-redefs [http/get (fn [_url _req] response)]
            (m/? (m/reduce conj flow))))]
    (into #{} (map :id) signals)))

(defn- deployment-namespaces
  "The `kudos*` namespaces the captured signal ids belong to, ignoring the
   `:backoff/…` signals both deployments share."
  [ids]
  (into #{} (comp (map namespace) (filter #(re-find #"kudos" %))) ids))

(deftest event-names-are-unchanged-per-deployment
  (testing "prod logs the page fetch under :kudos/fetching-page, and nothing preprod"
    (let [ids (signal-ids (kudos/documents kudos/prod {}) (one-page-body [{:id 1}]))]
      (is (contains? ids :kudos/fetching-page))
      (is (= #{"kudos"} (deployment-namespaces ids)))))

  (testing "preprod still logs under :kudos-preprod/fetching-page"
    (let [ids (signal-ids (kudos/documents kudos/preprod {}) (one-page-body [{:id 1}]))]
      (is (contains? ids :kudos-preprod/fetching-page))
      (is (= #{"kudos-preprod"} (deployment-namespaces ids)))))

  (testing "the document-fetch events keep their per-deployment names"
    (let [ids (signal-ids (kudos/documents-by-ids kudos/preprod [42]) {:body {:id 42}})]
      (is (contains? ids :kudos-preprod/fetching-document))
      (is (= #{"kudos-preprod"} (deployment-namespaces ids))))
    (let [ids (signal-ids (kudos/documents-by-ids kudos/prod [42]) {:body {:id 42}})]
      (is (contains? ids :kudos/fetching-document))
      (is (= #{"kudos"} (deployment-namespaces ids)))))

  (testing "the failure-path event names are the ones the preprod namespace used"
    (is (= :digdir.llm.kudos-preprod/failed-to-fetch-kudos-preprod-page-skipping-to-next-page
           (get-in kudos/preprod [:events :page-fetch-failed])))
    (is (= :digdir.llm.kudos-preprod/generic-error
           (get-in kudos/preprod [:events :document-dropped])))
    (is (= :kudos-preprod/failed-to-fetch-document
           (get-in kudos/preprod [:events :document-fetch-failed])))
    (is (= :digdir.llm.kudos/failed-to-fetch-kudos-page-skipping-to-next-page
           (get-in kudos/prod [:events :page-fetch-failed])))
    (is (= :digdir.llm.kudos/generic-error
           (get-in kudos/prod [:events :document-dropped])))
    (is (= :kudos/failed-to-fetch-document
           (get-in kudos/prod [:events :document-fetch-failed])))))

(deftest both-deployments-share-the-retry-schedule
  (testing "1s/3s/7s/60s — prod's longer tail was always reader-discarded"
    (is (= [1000 3000 7000 60000] (:retry-delays-ms kudos/prod)))
    (is (= (:retry-delays-ms kudos/prod) (:retry-delays-ms kudos/preprod)))))
