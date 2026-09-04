(ns digdir.rag.typesense-tenant-test
  "Guards for #476: the Typesense resolver must never answer for a tenant it was
   not asked about.

   THE DEFECT THIS RETIRES. `make-ts-settings` used to fall back, when given no
   tenant, to the first of a hardcoded `[\"digdir\" \"public-sector-knowledge\"]`.
   The ingest path reached it through a load-time `def`, so a pipeline running
   for tenant `demo` wrote to Typesense using digdir's host and digdir's admin
   key. On a clean install it failed instead — `no protocol: /collections`,
   because the URI resolved empty — which is the only reason anyone noticed.

   The failure mode that matters is NOT the crash. It is the success: on a real
   deployment the fallback fires, the ingest works, and nothing anywhere records
   that one tenant's credentials were used for another tenant's data. So the
   guard below that carries the weight is `a-tenant-never-borrows-another-tenants-settings`
   — the others check that failures are legible, which is worth having, but a
   legible failure was never the thing at risk.

   Every case stubs `cfg/get-platform-value` so the tests describe a config
   database rather than needing one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.accessor :as cfg]
            [digdir.rag.typesense :as sut]))

(defn- config-db
  "A stub platform resolver over `tenant -> {key value}`.

   Anything not named is genuinely absent, which is the point: the stub cannot
   accidentally supply a value the test did not describe."
  [m]
  (fn [path opts]
    (get-in m [(:tenant opts) (last path)])))

(def ^:private only-digdir
  {"digdir" {:api-host "ts.example:8108" :api-tls false :api-key-admin "digdir-key"}})

;; ---------------------------------------------------------------------------
;; The guard that carries the weight
;; ---------------------------------------------------------------------------

(deftest a-tenant-never-borrows-another-tenants-settings
  (with-redefs [cfg/get-platform-value (config-db only-digdir)]
    (testing "POSITIVE CONTROL — the configured tenant does resolve, so a throw below
              means 'this tenant has nothing', not 'the stub supplies nothing'"
      (is (= {:uri "http://ts.example:8108" :key "digdir-key"}
             (sut/make-ts-settings {:tenant "digdir"}))))
    (testing "an unconfigured tenant THROWS rather than silently receiving digdir's"
      ;; This is #476. Before the fix a tenant-less resolution returned digdir's
      ;; settings and the ingest succeeded under the wrong credentials.
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (sut/make-ts-settings {:tenant "demo"})))]
        (is (= "demo" (:tenant (ex-data e)))
            "the error must name the tenant that was actually asked for")
        (is (not (str/includes? (str (ex-data e)) "digdir-key"))
            "and must not have reached another tenant's credentials at all")))
    (testing "no tenant at all is refused, not defaulted"
      (doseq [opts [{} {:tenant nil} {:tenant "  "}]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (sut/make-ts-settings opts)))]
          (is (= :no-tenant (:reason (ex-data e)))
              (str "opts " (pr-str opts) " must be refused as tenant-less")))))))

;; ---------------------------------------------------------------------------
;; Failures name what they lacked
;; ---------------------------------------------------------------------------

(deftest an-incomplete-tenant-names-the-missing-keys
  (testing "host present, admin key absent"
    (with-redefs [cfg/get-platform-value (config-db {"t" {:api-host "h:1"}})]
      (is (= [:api-key-admin] (:missing (ex-data (try (sut/make-ts-settings {:tenant "t"})
                                                      (catch clojure.lang.ExceptionInfo e e))))))))
  (testing "admin key present, host absent"
    (with-redefs [cfg/get-platform-value (config-db {"t" {:api-key-admin "k"}})]
      (is (= [:api-host] (:missing (ex-data (try (sut/make-ts-settings {:tenant "t"})
                                                 (catch clojure.lang.ExceptionInfo e e))))))))
  (testing "both absent — both named, not just the first"
    (with-redefs [cfg/get-platform-value (config-db {"t" {}})]
      (is (= [:api-host :api-key-admin]
             (:missing (ex-data (try (sut/make-ts-settings {:tenant "t"})
                                     (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest tls-decides-the-scheme-and-is-not-required
  (with-redefs [cfg/get-platform-value
                (config-db {"t" {:api-host "h:1" :api-key-admin "k" :api-tls true}})]
    (is (= "https://h:1" (:uri (sut/make-ts-settings {:tenant "t"})))))
  (testing "absent :api-tls is a default (plain http), not a missing precondition"
    (with-redefs [cfg/get-platform-value (config-db {"t" {:api-host "h:1" :api-key-admin "k"}})]
      (is (= "http://h:1" (:uri (sut/make-ts-settings {:tenant "t"})))))))

;; ---------------------------------------------------------------------------
;; The required set is derived, not restated
;; ---------------------------------------------------------------------------

(deftest required-platform-keys-is-the-single-source-of-truth
  (testing "it is populated — a vacuous vector would make every check below pass"
    (is (seq sut/required-platform-keys))
    (is (every? keyword? sut/required-platform-keys)))
  (testing "a fully-configured tenant satisfies exactly what the vector demands"
    (with-redefs [cfg/get-platform-value
                  (config-db {"t" (zipmap sut/required-platform-keys (repeat "v"))})]
      (is (map? (sut/make-ts-settings {:tenant "t"})))))
  (testing "adding a key makes it required WITHOUT touching the resolver — which is
            what lets a caller assert 'this tenant is completely configured'
            against this vector instead of restating the list"
    (with-redefs [sut/required-platform-keys (conj sut/required-platform-keys :api-invented)
                  cfg/get-platform-value
                  (config-db {"t" {:api-host "h:1" :api-key-admin "k"}})]
      (is (= [:api-invented]
             (:missing (ex-data (try (sut/make-ts-settings {:tenant "t"})
                                     (catch clojure.lang.ExceptionInfo e e))))))))) 
