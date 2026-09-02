(ns digdir.ui.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.ui.main :as main]))

(deftest normalize-debug-ui-mode-falls-back-to-full
  (testing "Unknown or blank values do not activate isolation mode accidentally"
    (is (= "full" (main/normalize-debug-ui-mode nil)))
    (is (= "full" (main/normalize-debug-ui-mode "")))
    (is (= "full" (main/normalize-debug-ui-mode "unknown")))))

(deftest normalize-debug-ui-mode-accepts-known-modes
  (testing "Known isolation modes are normalized consistently"
    (is (= "bare" (main/normalize-debug-ui-mode "bare")))
    (is (= "shell" (main/normalize-debug-ui-mode " shell ")))
    (is (= "playground" (main/normalize-debug-ui-mode "PLAYGROUND")))
    (is (= "config" (main/normalize-debug-ui-mode "config")))
    (is (= "import" (main/normalize-debug-ui-mode "import")))
    (is (= "full" (main/normalize-debug-ui-mode "full")))))
