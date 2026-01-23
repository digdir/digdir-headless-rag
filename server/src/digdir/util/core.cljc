(ns digdir.util.core
  (:require [clojure.edn :as edn]
            #?(:clj [duratom.core :refer [duratom]])
            [time-literals.read-write]))
(time-literals.read-write/print-time-literals-clj!)

(def edn-opts {:readers *data-readers*})

(defn edn-read-string
  ([s] (edn-read-string {} s))
  ([opts s] (edn/read-string (merge-with merge edn-opts opts) s)))

#?(:clj 
   (defn edn-read
     ([stream] (edn-read {} stream))
     ([opts stream] (edn/read (merge-with merge edn-opts opts) stream))))

#?(:clj 
   (defn fileatom
     "A duratom wrapper with support for reading and writing #time reader literals."
     ([file-path] (fileatom file-path nil))
     ([file-path init]
      (duratom :local-file
               :file-path file-path
               :init init
               :rw {:read (fn [p] (-> p slurp edn-read-string))
                    :write (fn [f x] (spit f (pr-str x)))}))))

(comment 
  (def !box (fileatom "/tmp/box"))
  @!box := nil

  (def !box (fileatom "/tmp/box2" :init))
  @!box := :init

  (require '[tick.core :as ⏰])
  (reset! !box (⏰/now))

  @!box := #time/instant "2025-04-23T16:45:09.565175Z")
