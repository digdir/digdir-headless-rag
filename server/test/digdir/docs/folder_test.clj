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
  ;; ⚠️ THIS TEST PREVIOUSLY ASSERTED THE BUG (#556). It read
  ;; "Returns nil for non-existent directory", and that nil was consumed
  ;; downstream as "no documents" — so a materialization against a path that did
  ;; not exist completed with status `completed`, `errorMessage` null and 0
  ;; documents processed. The silent success was not an oversight; it was
  ;; written down as intended behaviour, which is why it survived.
  (testing "Throws for non-existent directory rather than returning nil"
    (is (thrown? clojure.lang.ExceptionInfo
                 (folder/find-markdown-files "/nonexistent/path/12345")))))

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

;; ============================================================================
;; #556 — a missing corpus must FAIL, not complete with 0 documents
;; ============================================================================
;;
;; Both directions are tested on purpose. A check that refuses everything would
;; pass the negative cases below and break the product, so every refusal test
;; here is paired with a positive one that must still succeed.

(deftest missing-corpus-directory-throws
  (testing "an absent directory is refused, not reported as zero documents"
    (let [missing (str (System/getProperty "java.io.tmpdir")
                       "/digdir-556-does-not-exist-" (System/currentTimeMillis))
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (folder/find-markdown-files missing)))]
      (is (= :folder/corpus-directory-missing (:type (ex-data ex)))
          "the missing case must be distinguishable from the empty case"))))

(deftest missing-corpus-message-names-the-absolute-path
  ;; The specific defect: the shipped config says './demo-corpus', which resolves
  ;; against the JVM cwd. A relative path in the error is what let this hide.
  (testing "the refusal names the resolved absolute path, not the relative one"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (folder/find-markdown-files "./digdir-556-relative-nope")))
          {:keys [resolved-path]} (ex-data ex)]
      (is (str/starts-with? resolved-path "/")
          "resolved-path must be absolute")
      (is (str/includes? (.getMessage ex) resolved-path)
          "and the message the operator sees must carry it"))))

(deftest a-file-is-not-a-corpus-directory
  (testing "a path that exists but is not a directory is its own case"
    (fixtures/with-temp-dir
      (fn [dir]
        (let [f (io/file dir "not-a-dir.md")]
          (spit f "# hello")
          (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                (folder/find-markdown-files (.getAbsolutePath f))))]
            (is (= :folder/corpus-path-not-a-directory (:type (ex-data ex))))))))))

(deftest a-real-corpus-still-succeeds
  ;; NOT optional. This is the half that fails if the guard refuses everything.
  (testing "a directory with markdown still returns its files"
    (fixtures/with-temp-dir
      (fn [dir]
        (fixtures/create-markdown-files dir)
        (let [files (folder/find-markdown-files (.getAbsolutePath dir))]
          (is (= 3 (count files)) "the happy path must be untouched")
          (is (every? :lastmod files)))))))

(deftest empty-corpus-is-a-different-failure-from-a-missing-one
  (testing "an existing directory that yields nothing throws its own type"
    (fixtures/with-temp-dir
      (fn [dir]
        (let [path (.getAbsolutePath dir)
              scanned (folder/find-markdown-files path)]
          (is (empty? scanned)
              "an existing empty directory scans cleanly — the refusal is the caller's job")
          (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                (folder/ensure-documents-found! scanned path)))]
            (is (= :folder/corpus-directory-empty (:type (ex-data ex)))
                "must NOT be reported as the missing-directory case: the remedies differ")
            (is (str/includes? (.getMessage ex) path))))))))

(deftest documents-found-passes-through-untouched
  ;; The positive half of the empty check.
  (testing "when documents were found, nothing is thrown and the seq is returned"
    (fixtures/with-temp-dir
      (fn [dir]
        (fixtures/create-markdown-files dir)
        (let [path (.getAbsolutePath dir)
              scanned (folder/find-markdown-files path)]
          (is (= scanned (folder/ensure-documents-found! scanned path))
              "the guard must be transparent on the happy path"))))))

(deftest resolved-path-is-normalised-not-just-absolute
  ;; Measured on a cold stack: the shipped demo config is "./demo-corpus", and
  ;; .getAbsolutePath alone rendered the failure as "/app/./demo-corpus". Correct,
  ;; and harder to read than it needs to be in the one message that exists to be
  ;; read by whoever has to fix the mount.
  (testing "a '.' segment is collapsed in the path the operator is shown"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (folder/find-markdown-files "./digdir-556-nope")))
          resolved (:resolved-path (ex-data ex))]
      (is (not (str/includes? resolved "/./"))
          "the resolved path must be normalised, not merely absolute")
      (is (str/ends-with? resolved "/digdir-556-nope")))))
