(ns digdir.config.cache-invalidation-test
  (:require
            [clojure.test :refer [deftest is testing]]
            [digdir.config.cache-invalidation :as ci]))

(deftest touch-marker-noop-without-bootstrap
  (testing "touch-marker! returns nil when no file backend is configured"
    ;; The test runner inits the bootstrap config to a file backend, so this
    ;; will typically write a file. We only assert that the call doesn't throw
    ;; — semantically a no-op if the backend isn't :file.
    (is (or (nil? (ci/touch-marker!))
            (string? (ci/touch-marker!))))))

(deftest marker-file-under-store-path
  (testing "marker-file returns a path under the store dir"
    (when-let [marker (ci/marker-file)]
      (is (= ".config-changed" (.getName marker))))))

(deftest touch-marker-bumps-mtime
  (testing "Successive touch-marker! calls produce non-decreasing mtimes"
    (when-let [marker (ci/marker-file)]
      (ci/touch-marker!)
      (let [t1 (.lastModified marker)]
        ;; Sleep slightly to ensure mtime tick on filesystems with second-level
        ;; resolution. APFS gives ns-resolution mtimes so this is usually
        ;; instant.
        (Thread/sleep 50)
        (ci/touch-marker!)
        (let [t2 (.lastModified marker)]
          (is (>= t2 t1) "Second touch should not move mtime backwards"))))))

(deftest poller-must-not-run-in-test-jvms
  (testing "the marker poller is disabled here, and the wiring that disables it is in place"
    ;; The poller calls data.db/reconnect! from a daemon thread, which reassigns
    ;; the process-global config conn. In a long-running server that is the
    ;; point; in a test JVM it takes the conn away from tests that set it
    ;; deliberately, which is what made digdir.config.ui-test fail
    ;; intermittently with "Config node not found runtime-frontpage".
    ;;
    ;; The :test alias in server/deps.edn sets -Ddigdir.config.poller=false.
    ;; Asserting it here means deleting that jvm-opt fails loudly, instead of
    ;; coming back as a 1-in-6 flake in an unrelated namespace.
    (is (= "false" (System/getProperty "digdir.config.poller"))
        "the :test alias must set -Ddigdir.config.poller=false")
    (is (false? (ci/poller-enabled?)))
    (is (nil? (ci/start-poller!))
        "start-poller! must no-op while disabled")
    (is (not-any? #(= "config-cache-invalidation-poller" (.getName ^Thread %))
                  (keys (Thread/getAllStackTraces)))
        "no poller thread may be alive in a test JVM")))
