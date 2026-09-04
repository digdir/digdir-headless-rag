(ns digdir.pipeline.dataset-property-declaration-test
  "#513 — the API must refuse a dataset that materialization cannot execute.

   The defect was a DISAGREEMENT, not a missing check: `model.clj` said
   `:website-base-url` was optional, `materialization.clj` derived its required
   set from `(keys source-loader-key-map)` and so treated it as mandatory. The
   API accepted the dataset; execution then threw. Both layers now derive from
   `model/dataset-properties`.

   ⚠️ THE TEST THAT MATTERS IS THE REFUSAL. Asserting that a valid dataset still
   works would have passed on the broken code — it did pass, every day, while
   #513 was open. But a validator that refuses everything also passes a
   refusal-only test, so the accept case is here too, as the vacuity guard
   rather than as the point."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.pipeline.materialization :as materialization]
            [digdir.pipeline.model :as model]))

(def ^:private website-without-base-url
  {:source-type :website
   :website-sitemap-url "https://example.org/sitemap.xml"})

(def ^:private website-complete
  (assoc website-without-base-url :website-base-url "https://example.org"))

(deftest api-refuses-a-website-dataset-materialization-cannot-execute
  (testing "the exact shape #513 reports: accepted by the API, refused at execution"
    (is (thrown? clojure.lang.ExceptionInfo
                 (model/validate-source-config! website-without-base-url
                                                {:require-source-type? true}))
        "a website dataset with no base-url must be refused at the API boundary")))

(deftest and-still-accepts-a-valid-one
  (testing "the vacuity guard — a validator that refuses everything is not a fix"
    (is (nil? (model/validate-source-config! website-complete
                                             {:require-source-type? true}))
        "a complete website dataset must still be accepted")))

(deftest the-two-layers-cannot-disagree
  (testing "every property the API requires, materialization also requires"
    ;; This is the assertion the old code could not make, because the two
    ;; statements of requiredness lived in different shapes in different
    ;; namespaces. It is the regression guard for the drift itself rather than
    ;; for one instance of it.
    (doseq [source-type [:kudos :website :folder :episerver]]
      (let [api-required (get model/source-required-properties source-type #{})
            exec-required (set (keys (materialization/required-execution-properties
                                      {:source-type source-type})))]
        (is (empty? (set/difference api-required exec-required))
            (str "properties required by the API but not by execution, for "
                 source-type))))))

(deftest folder-base-url-stays-optional
  (testing "#506/#504 are preserved: a folder dataset may omit its base URL"
    (is (nil? (model/validate-source-config! {:source-type :folder
                                              :folder-path "/corpus"}
                                             {:require-source-type? true}))
        "folder without a base URL must still be accepted")
    (is (nil? (model/validate-source-config! {:source-type :folder
                                              :folder-path "/corpus"
                                              :folder-base-url "https://example.org"}
                                             {:require-source-type? true}))
        "and supplying one must also be accepted")))

(deftest declaration-is-the-only-statement-of-requiredness
  (testing "requiredness is derivable for every declared property"
    ;; Guards the property this design exists to create: if someone reintroduces
    ;; a hand-written required list, this still passes — but if they add a
    ;; property WITHOUT declaring requiredness, it fails here rather than at a
    ;; customer's materialization.
    (doseq [{:keys [property required? source-types]} model/dataset-properties]
      (is (boolean? required?)
          (str property " must declare :required? explicitly"))
      (is (or (= :all source-types) (set? source-types))
          (str property " must declare :source-types as a set or :all")))))
