(ns digdir.llm.azure-deployments-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [digdir.config.accessor :as cfg]
            [digdir.llm.azure-deployments :as az]
            [digdir.llm.openai :as openai]))

(defn- clear-cache [f]
  (az/invalidate-cache!)
  (f)
  (az/invalidate-cache!))

(use-fixtures :each clear-cache)

(deftest list-deployment-names-returns-nil-for-blank-tenant
  (testing "Blank/empty tenant short-circuits to nil"
    (is (nil? (az/list-deployment-names nil)))
    (is (nil? (az/list-deployment-names "")))
    (is (nil? (az/list-deployment-names "   ")))))

(deftest list-deployment-names-returns-nil-for-non-azure-tenant
  (testing "Tenant that doesn't use Azure short-circuits to nil — no HTTP call attempted"
    (let [http-called? (atom false)]
      (with-redefs [openai/use-azure-openai (fn [_] false)
                    http/get (fn [& _] (reset! http-called? true) (throw (ex-info "should not be called" {})))]
        (is (nil? (az/list-deployment-names "non-azure-tenant")))
        (is (false? @http-called?))))))

(deftest list-deployment-names-parses-azure-response
  (testing "200 response with :data list yields a sorted vector of :id strings"
    (with-redefs [openai/use-azure-openai (fn [_] true)
                  cfg/get (fn [_ & path]
                            (case (last path)
                              :api-endpoint "https://example.openai.azure.com/"
                              :api-key "secret-key"
                              nil))
                  http/get (fn [_url _opts]
                             {:status 200
                              :body (json/write-str
                                      {:object "list"
                                       :data [{:id "gpt-4o" :model "gpt-4o-2024-11-20"}
                                              {:id "gpt-4" :model "gpt-4"}
                                              {:id "gpt-4o-mini" :model "gpt-4o-mini"}]})})]
      (is (= ["gpt-4" "gpt-4o" "gpt-4o-mini"]
             (az/list-deployment-names "azure-tenant"))))))

(deftest list-deployment-names-degrades-on-non-200
  (testing "Non-200 response degrades to nil — caller falls back to hardcoded list"
    (with-redefs [openai/use-azure-openai (fn [_] true)
                  cfg/get (fn [_ & path]
                            (case (last path)
                              :api-endpoint "https://example.openai.azure.com"
                              :api-key "secret-key"
                              nil))
                  http/get (fn [_url _opts] {:status 401 :body "unauthorized"})]
      (is (nil? (az/list-deployment-names "azure-tenant"))))))

(deftest list-deployment-names-degrades-on-thrown-exception
  (testing "Exception during HTTP call degrades to nil"
    (with-redefs [openai/use-azure-openai (fn [_] true)
                  cfg/get (fn [_ & path]
                            (case (last path)
                              :api-endpoint "https://example.openai.azure.com"
                              :api-key "secret-key"
                              nil))
                  http/get (fn [_url _opts] (throw (java.io.IOException. "connection refused")))]
      (is (nil? (az/list-deployment-names "azure-tenant"))))))

(deftest list-deployment-names-caches-within-ttl
  (testing "Subsequent calls within TTL return cached value without re-hitting HTTP"
    (let [call-count (atom 0)]
      (with-redefs [openai/use-azure-openai (fn [_] true)
                    cfg/get (fn [_ & path]
                              (case (last path)
                                :api-endpoint "https://example.openai.azure.com"
                                :api-key "secret-key"
                                nil))
                    http/get (fn [_url _opts]
                               (swap! call-count inc)
                               {:status 200
                                :body (json/write-str {:data [{:id "gpt-4o"}]})})]
        (is (= ["gpt-4o"] (az/list-deployment-names "azure-tenant")))
        (is (= ["gpt-4o"] (az/list-deployment-names "azure-tenant")))
        (is (= 1 @call-count))))))

(deftest list-deployment-names-requests-an-api-version-that-answers
  ;; The request URL was invisible to every test above: they all stub `http/get`
  ;; as `(fn [_url _opts] ...)`, so an api-version the resource 404s for passes
  ;; the whole suite while this fn returns nil in production. That is exactly
  ;; what `2024-08-01-preview` did — the playground model picker silently fell
  ;; back to its hardcoded list, showing deployments that do not exist and
  ;; hiding the ones that do. Measured against the configured resource:
  ;; 2022-12-01 and 2023-03-15-preview return 200 with all 39 deployments;
  ;; 2024-08-01-preview, 2024-10-21 and 2025-04-01-preview all answer 404.
  (testing "the deployments list is requested on an api-version that answers"
    (let [!url (atom nil)]
      (with-redefs [openai/use-azure-openai (fn [_] true)
                    cfg/get (fn [_ & path]
                              (case (last path)
                                :api-endpoint "https://example.openai.azure.com/"
                                :api-key "secret-key"
                                nil))
                    http/get (fn [url _opts]
                               (reset! !url url)
                               {:status 200
                                :body (json/write-str
                                        {:object "list"
                                         :data [{:id "gpt-5.6-sol" :model "gpt-5.6-sol"}]})})]
        (is (= ["gpt-5.6-sol"] (az/list-deployment-names "azure-tenant")))
        (is (= (str "https://example.openai.azure.com"
                    "/openai/deployments?api-version=2022-12-01")
               @!url)
            "trailing slash normalized away, and the working api-version sent")
        (is (nil? (re-find #"2024-08-01-preview|2024-10-21|2025-04-01-preview" @!url))
            "none of the api-versions measured to 404 on this endpoint")))))
