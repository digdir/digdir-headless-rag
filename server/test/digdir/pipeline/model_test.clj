(ns digdir.pipeline.model-test
  "Guards for the API-boundary half of a source-specific property.

   Optionality for a pipeline property is expressed in TWO places, and both have
   to agree for the property to be usable:

     `digdir.pipeline.model`          allowed vs required at the API boundary
     `digdir.pipeline.materialization` required vs optional at translation time

   `materialization-test` covers the second. Nothing covered the first, which
   means `:folder-base-url` could be dropped from `source-specific-properties`
   and the API would go back to refusing the value outright while every
   materialization test stayed green — the capability broken at one door with
   the other still guarded (#506)."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.pipeline.model :as model]))

(defn- validation-result
  "Either :accepted, or the rejection message."
  [properties]
  (try
    (model/validate-source-config! properties {:require-source-type? true})
    :accepted
    (catch clojure.lang.ExceptionInfo e
      (.getMessage e))))

(deftest folder-datasets-may-supply-a-base-url
  ;; Before #506 this was rejected as "Source-specific properties do not match
  ;; source-type", so the value could not even be offered — which is why the
  ;; translation-side fix alone would not have been enough.
  (testing "a folder dataset is allowed to give one"
    (is (= :accepted
           (validation-result {:source-type :folder
                               :folder-path "/corpus/markdown/"
                               :folder-base-url "https://example.no/docs/"}))))

  (testing "and is equally allowed NOT to"
    ;; The regression that matters: a local corpus with no public address is
    ;; legitimate (#504) and must not be forced to invent one.
    (is (= :accepted
           (validation-result {:source-type :folder
                               :folder-path "/corpus/markdown/"}))))

  (testing "so it is allowed but not required"
    (is (contains? (:folder model/source-specific-properties) :folder-base-url)
        "the property is not accepted at the API boundary at all")
    (is (not (contains? (:folder model/source-required-properties) :folder-base-url))
        "the property is REQUIRED, so every existing folder dataset now fails validation")))

(deftest source-specific-properties-stay-source-specific
  ;; Control on the above: the allowance is scoped, not a hole. If this stops
  ;; rejecting, the previous test proves much less than it appears to.
  (testing "a folder dataset still cannot use the website property"
    (is (= "Source-specific properties do not match source-type"
           (validation-result {:source-type :folder
                               :folder-path "/corpus/markdown/"
                               :website-base-url "https://example.no/"}))))

  (testing "a website dataset still cannot use the folder property"
    (is (= "Source-specific properties do not match source-type"
           (validation-result {:source-type :website
                               :website-sitemap-url "https://example.no/sitemap.xml"
                               :folder-base-url "https://example.no/docs/"}))))

  (testing "and the required source property is still enforced"
    (is (not= :accepted
              (validation-result {:source-type :folder
                                  :folder-base-url "https://example.no/docs/"}))
        "a folder dataset with no :folder-path was accepted")))
