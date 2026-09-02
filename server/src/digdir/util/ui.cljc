(ns digdir.util.ui
  "Shared utility functions for the UI layer."
  {:clj-kondo/ignore true}
  (:require [clojure.pprint :refer [pprint]]
            [clojure.edn :as edn]
            [lentes.core :as l]
            #?(:clj [tick.core :as tick])
            #?(:clj [taoensso.telemere :as t])
            [missionary.core :as m]))

;; =========Misc utils=========

(defn pprint-str [x]
  (with-out-str (pprint x)))

(defn => [& fns] (apply comp (reverse fns)))

#?(:clj
   (defmacro thread
     "Runs body in a new thread. Exceptions will propagate to the uncaught
  exception handler. Prefer this to future, if you don't need to deref
  it."
     [& body]
     `(doto (Thread. (fn [] ~@body))
        (.start))))

(defn tee [x] (prn x) x)

#?(:cljs
   (defn try-read-string [s]
     (println s)
     (try
       (edn/read-string s)
       (catch js/Object _ ::error))))

(defn cnt
  ([n el] (inc n))
  ([n] n)
  ([] 0))

(defn count-in!
  ([!box inp] (swap! !box inc) !box)
  ([!box] @!box))

(defn prn-acc [rf]
  (fn
    ([acc el] (tee (rf acc el)))
    ([acc] acc)))

#?(:cljs
   (defn target-value [ev]
     (-> ev .-target .-value)))

(defmacro time-and-result
  [& body]
  `(let [start# (tick/inst)
         ret# (do ~@body)]
     [(tick/between start# (tick/inst)) ret#]))

#?(:clj (defn m-fail-with-success [task failure]
          ;; https://clojurians.slack.com/archives/C7Q9GSHFV/p1741191267099549
          (fn [success _failure]
            (task success (fn [error]
                            (t/error! :m-fail-with-success error)
                            (success (failure error)))))))

#?(:clj
   (defn m-fail-with-success-value
     ;; https://clojurians.slack.com/archives/C7Q9GSHFV/p1741191267099549
     ([task failure-value] (m-fail-with-success task (constantly failure-value)))
     ([task] (m-fail-with-success task (constantly nil)))))

(defn cursor [!atom key]
  (l/derive (l/key key) !atom))
