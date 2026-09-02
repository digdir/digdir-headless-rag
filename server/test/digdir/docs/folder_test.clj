(ns digdir.docs.folder-test
  "Tests for digdir.docs.folder namespace."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is ]]
            [clojure.java.io :as io]
            [digdir.docs.folder :as folder]
            [digdir.docs.test-fixtures :as fixtures]))

;; ============================================================================
;; Path Helper Functions Tests
;; ============================================================================

(deftest normalize-path-with-tilde
  (testing "Expands ~ to home directory"
    (let [home (System/getProperty "user.home")
          result (folder/normalize-path "~/docs/file.md")]
      (is (= (str home "/docs/file.md") result)))))

(deftest normalize-path-without-tilde
  (testing "Leaves absolute paths unchanged"
    (is (= "/home/user/docs/file.md"
           (folder/normalize-path "/home/user/docs/file.md")))))

(deftest normalize-path-relative
  (testing "Leaves relative paths unchanged"
    (is (= "docs/file.md"
           (folder/normalize-path "docs/file.md")))))

(deftest make-relative-path-strips-base
  (testing "Strips base path from absolute path"
    ;; Note: trailing slash in base means result won't have leading slash
    (is (= "en/about.md"
           (folder/make-relative-path "/home/user/docs/" "/home/user/docs/en/about.md")))))

(deftest make-relative-path-with-nil-base
  (testing "Returns path unchanged when base is nil"
    (is (= "/home/user/docs/en/about.md"
           (folder/make-relative-path nil "/home/user/docs/en/about.md")))))

(deftest make-relative-path-non-matching
  (testing "Returns path unchanged when it doesn't start with base"
    (is (= "/other/path/file.md"
           (folder/make-relative-path "/home/user/docs/" "/other/path/file.md")))))

(deftest make-absolute-path-prepends-base
  (testing "Prepends base path to relative path"
    (is (= "/home/user/docs/en/about.md"
           (folder/make-absolute-path "/home/user/docs/" "en/about.md")))))

(deftest make-absolute-path-already-absolute
  (testing "Leaves absolute paths unchanged"
    (is (= "/en/about.md"
           (folder/make-absolute-path "/home/user/docs/" "/en/about.md")))))

(deftest make-absolute-path-nil-base
  (testing "Returns path unchanged when base is nil"
    (is (= "en/about.md"
           (folder/make-absolute-path nil "en/about.md")))))

(deftest make-url-basic
  (testing "Creates URL from base and relative path"
    (is (= "https://docs.digdir.no/docs/en/about"
           (folder/make-url "https://docs.digdir.no/docs/" "/en/about.md")))))

(deftest make-url-strips-md-extension
  (testing "Strips .md extension from path"
    (is (= "https://example.com/path/to/file"
           (folder/make-url "https://example.com/" "path/to/file.md")))))

(deftest make-url-handles-leading-slash
  (testing "Removes leading slash to avoid double slashes"
    (is (= "https://example.com/docs/page"
           (folder/make-url "https://example.com/docs/" "/page.md")))))

(deftest make-url-nil-inputs
  (testing "Returns nil when inputs are nil"
    (is (nil? (folder/make-url nil "/path.md")))
    (is (nil? (folder/make-url "https://example.com/" nil)))))

;; ============================================================================
;; Title Extraction Tests
;; ============================================================================

(deftest extract-title-from-path-basic
  (testing "Extracts filename without extension"
    (is (= "About"
           (folder/extract-title-from-path "/docs/about.md")))))

(deftest extract-title-from-path-index
  (testing "Extracts 'Index' from index.md"
    (is (= "Index"
           (folder/extract-title-from-path "/home/user/docs/en/about/index.md")))))

(deftest extract-title-from-path-replaces-dashes
  (testing "Replaces dashes with spaces"
    (is (= "My document"
           (folder/extract-title-from-path "/docs/my-document.md")))))

(deftest extract-title-from-path-replaces-underscores
  (testing "Replaces underscores with spaces"
    (is (= "My document"
           (folder/extract-title-from-path "/docs/my_document.md")))))

(deftest extract-title-from-path-capitalizes
  (testing "Capitalizes the title"
    (is (= "Architecture"
           (folder/extract-title-from-path "/home/user/docs/en/authorization/architecture.md")))))

;; ============================================================================
;; find-markdown-files Tests (with temp directory)
;; ============================================================================

(deftest find-markdown-files-basic
  (testing "Finds markdown files in directory"
    (fixtures/with-temp-dir
      (fn [dir]
        (fixtures/create-markdown-files dir)
        (let [files (folder/find-markdown-files (.getAbsolutePath dir))]
          (is (= 3 (count files)))
          (is (every? #(clojure.string/ends-with? (:path %) ".md") files))
          (is (every? :lastmod files)))))))

(deftest find-markdown-files-recursive
  (testing "Finds files in subdirectories"
    (fixtures/with-temp-dir
      (fn [dir]
        (fixtures/create-markdown-files dir)
        (let [files (folder/find-markdown-files (.getAbsolutePath dir))
              paths (set (map :path files))]
          ;; Should find doc3.md in subdir
          (is (some #(clojure.string/includes? % "subdir") paths)))))))

(deftest find-markdown-files-excludes-non-md
  (testing "Excludes non-markdown files"
    (fixtures/with-temp-dir
      (fn [dir]
        (fixtures/create-markdown-files dir)
        (let [files (folder/find-markdown-files (.getAbsolutePath dir))
              paths (map :path files)]
          (is (not-any? #(clojure.string/ends-with? % ".txt") paths)))))))

(deftest find-markdown-files-nonexistent-dir
  (testing "Returns nil for non-existent directory"
    (is (nil? (folder/find-markdown-files "/nonexistent/path/12345")))))

(deftest find-markdown-files-empty-dir
  (testing "Returns empty seq for directory with no markdown files"
    (fixtures/with-temp-dir
      (fn [dir]
        ;; Create only a non-markdown file
        (spit (io/file dir "readme.txt") "Not markdown")
        (let [files (folder/find-markdown-files (.getAbsolutePath dir))]
          (is (empty? files)))))))

;; ============================================================================
;; Integration: Path utilities work together
;; ============================================================================

(deftest path-utilities-roundtrip
  (testing "Path utilities can round-trip"
    (let [base "/home/user/docs/"
          absolute "/home/user/docs/en/guide/intro.md"
          relative (folder/make-relative-path base absolute)
          back-to-absolute (folder/make-absolute-path base relative)]
      ;; Note: trailing slash in base means result won't have leading slash
      (is (= "en/guide/intro.md" relative))
      (is (= "/home/user/docs/en/guide/intro.md" back-to-absolute)))))

(deftest path-to-url-workflow
  (testing "Can convert file path to URL"
    (let [base-path "/home/user/docs/"
          base-url "https://docs.example.com/"
          file-path "/home/user/docs/en/guide/intro.md"
          relative (folder/make-relative-path base-path file-path)
          url (folder/make-url base-url relative)]
      (is (= "https://docs.example.com/en/guide/intro" url)))))
