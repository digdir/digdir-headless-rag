(ns digdir.i18n-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.i18n :as i18n]))

(deftest supported-languages-have-the-same-translation-surface
  (let [translations @i18n/translations
        norwegian-keys (set (keys (:nb translations)))
        english-keys (set (keys (:en translations)))]
    (is (= #{} (set/difference norwegian-keys english-keys)))
    (is (= #{} (set/difference english-keys norwegian-keys)))))

(deftest admin-navigation-is-translated-in-the-selected-language
  (testing "Norwegian navigation labels do not fall back to keyword text"
    (is (= "Chat" (i18n/translate :nb :nav/chat)))
    (is (= "Datasett" (i18n/translate :nb :nav/datasets)))
    (is (= "Konfigurasjon" (i18n/translate :nb :nav/config)))
    (is (= "Import" (i18n/translate :nb :nav/import)))
    (is (= "Evalueringer" (i18n/translate :nb :nav/sweeps))))
  (testing "English labels remain available"
    (is (= "Datasets" (i18n/translate :en :nav/datasets)))
    (is (= "Sweeps" (i18n/translate :en :nav/sweeps)))))

(deftest language-normalization-is-safe
  (is (= :nb (i18n/normalize-language "NB")))
  (is (= :en (i18n/normalize-language "unknown")))
  (is (= :en (i18n/normalize-language nil))))
