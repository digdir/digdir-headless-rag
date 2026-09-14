(ns digdir.setup.seed-guard-test
  "The #493 seed guard: refuse to seed while a server is running, because the
   write is silently lost.

   These tests start a REAL HTTP server rather than stubbing the probe, so a
   probe that never connects fails here instead of passing vacuously — and they
   pin the distinction the guard turns on: a port that merely ACCEPTS is not a
   running server."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [digdir.setup.common :as common])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress ServerSocket]))

(defn- with-http
  "Run an HTTP server that answers /up with `status`, and call f with its port."
  [status f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/up"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [body (.getBytes "ok")]
                          (.sendResponseHeaders exchange status (alength body))
                          (doto (.getResponseBody exchange)
                            (.write body)
                            (.close))))))
    (.start server)
    (try (f (.getPort (.getAddress server)))
         (finally (.stop server 0)))))

(deftest finds-a-server-that-answers-up
  (with-http 200
    (fn [port]
      (is (= (str "127.0.0.1:" port)
             (common/running-server-address [["127.0.0.1" port]]))
          "a live server must be found — otherwise the guard is blind"))))

(deftest a-port-that-only-accepts-is-not-a-server
  ;; The false-refusal case: any unrelated process holding the port would
  ;; otherwise block a legitimate seed. A bare TCP connect cannot tell them
  ;; apart; /up can.
  (let [sock (ServerSocket. 0)]
    (try
      (is (nil? (common/running-server-address [["127.0.0.1" (.getLocalPort sock)]]))
          "a socket that accepts but never answers /up must NOT read as a server")
      (finally (.close sock)))))

(deftest a-server-that-is-unhealthy-is-not-treated-as-present
  (with-http 500
    (fn [port]
      (is (nil? (common/running-server-address [["127.0.0.1" port]]))
          "only a 2xx counts as present"))))

(deftest nothing-listening-reads-as-nil
  (let [port (let [s (ServerSocket. 0)
                   p (.getLocalPort s)]
               (.close s) p)]
    (is (nil? (common/running-server-address [["127.0.0.1" port]]))
        "no listener must read as nil, or the guard blocks legitimate seeding")))

(deftest checks-every-address-not-just-the-first
  ;; MEASURED: `compose exec` sees the server on loopback and a `compose run`
  ;; container sees it only on the service name. Probing one alone passes
  ;; cleanly in the case it cannot see.
  (with-http 200
    (fn [port]
      (is (= (str "127.0.0.1:" port)
             (common/running-server-address [["a-host-that-does-not-resolve.invalid" 8080]
                                             ["127.0.0.1" port]]))
          "an unreachable first address must not stop the probe checking the rest"))))

(deftest default-addresses-cover-all-three-doors
  ;; Assumes HTTP_PORT is unset, as it is in CI; the point is that the HOST door
  ;; exists at all, because a container-only address set passes cleanly for
  ;; every developer running `bb dev` on a worktree.
  (when-not (System/getenv "HTTP_PORT")
    (let [addrs (set (common/probe-addresses))]
      (is (contains? addrs ["127.0.0.1" 8080]) "container: exec into the server's own container")
      (is (contains? addrs ["digdir-rag" 8080]) "container: sibling on the compose network")
      (is (contains? addrs ["127.0.0.1" 8081]) "host: bb dev, whose port the container addresses never see"))))

(deftest refuses-and-exits-non-zero-when-a-server-is-running
  (with-http 200
    (fn [port]
      (let [exits (atom [])]
        (with-redefs [common/exit! (fn [status] (swap! exits conj status))]
          (with-out-str
            (common/refuse-if-server-running! "digdir.setup.demo-tenant"
                                              [["127.0.0.1" port]])))
        (is (= [1] @exits) "a running server must produce exactly one non-zero exit")))))

(deftest proceeds-when-no-server-is-running
  (let [port (let [s (ServerSocket. 0) p (.getLocalPort s)] (.close s) p)
        exits (atom [])]
    (with-redefs [common/exit! (fn [status] (swap! exits conj status))]
      (with-out-str
        (common/refuse-if-server-running! "digdir.setup.demo-tenant" [["127.0.0.1" port]])))
    (is (= [] @exits) "with nothing listening the guard must not exit at all")))

(deftest refusal-names-the-command-and-the-remedy
  (with-http 200
    (fn [port]
      (let [out (with-redefs [common/exit! (fn [_] nil)]
                  (with-out-str
                    (common/refuse-if-server-running! "digdir.setup.demo-dataset"
                                                      [["127.0.0.1" port]])))]
        (is (str/includes? out "digdir.setup.demo-dataset")
            "the refusal must name the command the operator should re-run")
        (is (str/includes? out "stop")
            "and must name the remedy, since a restart does not work")))))
