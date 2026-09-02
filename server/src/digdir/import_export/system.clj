(ns digdir.import-export.system
  "Public import/export entry points."
  (:require [digdir.import-export.export :as export]
            [digdir.import-export.import :as import]))

(defn export-system
  [config-conn main-conn opts]
  (export/export-system config-conn main-conn opts))

(defn import-system
  [config-conn main-conn data opts]
  (import/import-system config-conn main-conn data opts))

(defn preview-import-system
  [config-conn main-conn data opts]
  (import/preview-import-system config-conn main-conn data opts))

(defn export-to-file
  [config-conn main-conn file-path opts]
  (export/export-to-file config-conn main-conn file-path opts))

(defn import-from-file
  [config-conn main-conn file-path opts]
  (import/import-from-file config-conn main-conn file-path opts))
