(ns digdir.docs.loader-defaults-test
  "Shipped loader defaults must be portable (#490).

   `folder.clj` shipped `~/dev/digdir/docs-digdir-no/_export/markdown/` — one
   developer's home directory — in a live `def` reached from `-main` and from
   the admin UI's import panel.

   Its own siblings establish the convention: `episerver.clj` defaults to a
   relative path and `website.clj` to a localhost URL. Two of three were
   portable, which is why this reads as a leftover rather than a design choice
   and why the guard can assert a convention rather than invent one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.docs.episerver :as episerver]
            [digdir.docs.folder :as folder]
            [digdir.docs.website :as website]))

(defn- machine-specific?
  "Does `s` encode one machine's disk layout rather than a portable location?"
  [s]
  (boolean
   (and (string? s)
        (or (str/starts-with? s "~")
            (str/starts-with? s "/Users/")
            (str/starts-with? s "/home/")
            (str/includes? s "/Users/")
            (str/includes? s "/home/")))))

(deftest machine-specific?-discriminates
  ;; The predicate is the whole guard, so it is tested before it is trusted.
  ;; A version that returned false for everything would make every assertion
  ;; below pass over nothing.
  (testing "it catches the shapes that actually shipped"
    (is (machine-specific? "~/dev/digdir/docs-digdir-no/_export/markdown/")
        "the literal that shipped must be caught, or this guard proves nothing")
    (is (machine-specific? "/Users/someone/docs/"))
    (is (machine-specific? "/home/someone/docs/")))
  (testing "and does not fire on portable locations"
    (is (not (machine-specific? "cache/docs-export/markdown/")))
    (is (not (machine-specific? "cache/episerver-data/epix.xml")))
    (is (not (machine-specific? "http://localhost:1313")))
    (is (not (machine-specific? "/var/lib/digdir/corpus")))))

(deftest shipped-loader-defaults-are-portable
  (testing "the folder loader"
    (is (not (machine-specific? (:folder/path folder/wview)))
        (str "folder.clj ships a machine-specific default path: "
             (pr-str (:folder/path folder/wview))
             ". Set DOCS_FOLDER_PATH for a local export instead of editing "
             "shipped code (#490).")))

  (testing "and its siblings, which are the convention this asserts"
    ;; Positive control on the CONVENTION rather than on the predicate: if these
    ;; two ever became machine-specific, the claim that folder.clj departs from
    ;; a standard would be false and this guard would be asserting a preference.
    (is (not (machine-specific? (:folder/path episerver/wview))))
    (is (not (machine-specific? (:base-url website/wview)))))

  (testing "base-path is derived from the same value, so it cannot drift"
    ;; It was a second copy of the same literal, which is how two lines came to
    ;; carry the same developer's home directory.
    (is (= (folder/normalize-path (:folder/path folder/wview))
           (:base-path folder/wview)))))

(deftest the-default-is-overridable
  ;; The workflow the hardcoded path was serving still has to work, or the fix
  ;; just removes someone's setup.
  (testing "DOCS_FOLDER_PATH is what wview reads when it is set"
    (is (= (or (System/getenv "DOCS_FOLDER_PATH") "cache/docs-export/markdown/")
           folder/default-folder-path))))
