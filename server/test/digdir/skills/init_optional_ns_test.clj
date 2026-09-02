(ns digdir.skills.init-optional-ns-test
  "Tests for `load-optional-dev-namespace!` (#132).

   The point of these is the SECOND case. A missing src-dev namespace is the
   easy, visible one — production hits it on every boot and it was already
   handled. The case that mattered is a namespace that is PRESENT and
   THROWS: the previous handler caught `Exception` broadly and printed the
   same `Could not register…` text for both, so a genuinely broken namespace
   was indistinguishable from normal production output.

   That is why the happy path alone would not have caught this, and why the
   throwing test is the one to keep if anyone ever trims this file."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.init :as init]))

(defn- capturing-out [f]
  (let [w (java.io.StringWriter.)]
    (binding [*out* w] (f))
    (str w)))

(deftest absent-namespace-is-reported-as-expected-not-as-failure
  (testing "a missing src-dev namespace is swallowed and reads as expected"
    (let [out (atom nil)
          result (atom nil)
          text (capturing-out
                 #(reset! result
                          (init/load-optional-dev-namespace!
                            'digdir.demo.not-shipped 'register! "test tooling"
                            (fn [_] (throw (java.io.FileNotFoundException.
                                             "Could not locate digdir/demo/not_shipped")))
                            (fn [_] nil))))]
      (reset! out text)
      (is (= :absent @result))
      (is (re-find #"as expected on a production classpath" @out)
          (str "the absent case must read as expected, not as a failure. Got: " @out))
      (is (not (re-find #"Could not register" @out))
          "the old wording read as a failure; it should not come back"))))

(deftest present-but-throwing-namespace-surfaces-the-exception
  (testing "a namespace that exists but throws must NOT be swallowed"
    ;; THE case the old broad `catch Exception` hid. If this test ever starts
    ;; passing by returning a keyword instead of throwing, the defect is back.
    (is (thrown-with-msg?
          RuntimeException #"boom in a dev namespace"
          (init/load-optional-dev-namespace!
            'digdir.demo.broken 'register! "test tooling"
            (fn [_] (throw (RuntimeException. "boom in a dev namespace")))
            (fn [_] nil)))))

  (testing "a failure inside the registration fn is surfaced too"
    ;; Loading can succeed and registration still blow up; that is equally a
    ;; real defect and equally hidden by a broad catch.
    (is (thrown-with-msg?
          IllegalStateException #"registration exploded"
          (init/load-optional-dev-namespace!
            'digdir.demo.bad-register 'register! "test tooling"
            (fn [_] nil)
            (fn [_] (fn [] (throw (IllegalStateException. "registration exploded")))))))))

(deftest present-namespace-registers
  (testing "the normal dev path calls the registration fn"
    (let [called (atom false)
          result (init/load-optional-dev-namespace!
                   'digdir.demo.present 'register! "test tooling"
                   (fn [_] nil)
                   (fn [_] (fn [] (reset! called true))))]
      (is (= :registered result))
      (is @called "the registration fn should have been invoked")))

  (testing "a loadable namespace with no registration fn is not an error"
    (is (= :no-register-fn
           (init/load-optional-dev-namespace!
             'digdir.demo.no-reg 'register! "test tooling"
             (fn [_] nil)
             (fn [_] nil))))))
