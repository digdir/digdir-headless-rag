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

;; ---------------------------------------------------------------------------
;; #479 — the admin console's Typesense resolution
;;
;; ⚠️ THIS BUG WAS INVISIBLE TO THE TEST SUITE. The console resolved Typesense
;; with NO tenant; #476 removed the library fallback that had been answering and
;; #486 removed the tenant it answered with, so the call threw — inside the
;; Electric session, which took the WHOLE console down. The suite stayed green
;; throughout, on a stack whose API, retrieval, model list and login had all
;; been verified.
;;
;; The end-to-end proof is a browser loading the console against a container and
;; cannot run here. What CAN be pinned here is the decision the console makes,
;; which is why the resolver takes the tenant as a value in its 1-arity.
;; ---------------------------------------------------------------------------

(deftest an-unresolvable-tenant-is-not-fatal
  ;; THE PROPERTY THAT KEEPS THE CONSOLE ALIVE. Config, import and the pipeline
  ;; views need no Typesense at all, and before this they died with it. One
  ;; unresolvable value must not take down every other panel.
  (testing "no deployment tenant returns nil rather than throwing"
    (is (nil? (main/admin-console-typesense-settings nil))
        "the console throws when TENANT is unset, so the Electric session dies and nothing renders"))

  (testing "a tenant that cannot be resolved returns nil rather than throwing"
    ;; A tenant with no platform config tree: the first-run state, and the state
    ;; of any misconfigured tenant.
    (is (nil? (main/admin-console-typesense-settings "no-such-tenant-exists-here"))
        "an unresolvable tenant throws, which is the failure mode that took the console down")))

(deftest it-does-not-invent-a-tenant
  ;; ⛔ THE THING #476 REMOVED, AND WHY THIS RETURNS AN ABSENCE RATHER THAN A
  ;; DEFAULT. Falling back to another tenant would silently serve that tenant's
  ;; credentials and corpus state under whatever tenant the operator believes
  ;; they are looking at. nil is an absence, not a default.
  (testing "a tenant-less call does not produce settings for some other tenant"
    (is (nil? (main/admin-console-typesense-settings nil))
        "a tenant-less call produced settings, so something is supplying a default again")))
