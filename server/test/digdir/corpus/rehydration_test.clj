(ns digdir.corpus.rehydration-test
  "Guards for the NorQuAD demo corpus (#447).

   THREE PROPERTIES, AND THE FIRST IS THE LICENCE ARGUMENT ITSELF.

   The whole reason this corpus is rehydrated at setup rather than vendored is
   that NorQuAD's CC0 dedication covers its own questions and answers and NOT
   the Wikipedia prose in its `context` field — nobody can relicense Wikipedia,
   and nobody claimed to. Taking the text from Wikipedia directly under CC BY-SA
   is what makes that a non-issue. THAT PROPERTY HOLDS ONLY WHILE NO WIKIPEDIA
   TEXT IS COMMITTED, and it is exactly the kind that erodes silently: one
   convenient `git add` of a fetched directory and it is gone, with nothing
   failing.

   So the guard asserts the INVARIANT rather than a list of files: nothing
   carrying the rehydration signature, and nothing sitting in a rehydration
   output directory, may be tracked by git. A new output directory, a rename, or
   a helpful commit all trip it without anyone updating an allowlist.

   NOTE ON THE SECOND CHECK. It exists because moving attribution to a sidecar
   took the signature OUT of the documents: a rehydrated `.md` is now just an H1
   and prose, indistinguishable by content from any other markdown in the repo.
   The signature check alone would have gone on passing while the whole corpus
   was committed beside a single sidecar — so the directory check is what still
   covers the documents, and dropping it re-opens that hole."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private rehydration-signature
  "The marker the SIDECAR carries. It is the attribution the licence obliges, so
   a corpus without it is one we could not ship anyway — the marker cannot be
   dropped to evade the guard without breaking the compliance it protects."
  "https://no.wikipedia.org/wiki/")

(def ^:private signature-threshold
  "How many occurrences make a file corpus OUTPUT rather than code that mentions
   the corpus.

   This is not a fudge factor, it is the whole difference between the two: the
   sidecar carries one source URL PER DOCUMENT — 352 for the gold set alone —
   while the fetch script and this test carry one or two apiece, in the string
   that constructs the URL and in the assertions about it.

   Written as a count because the first version was not, and it failed on
   `rehydrate_norquad.clj` and on this file the moment either was committed. A
   guard that goes red on the PR that introduces it gets deleted, not obeyed."
  20)

(defn- signature-count [^String s]
  (loop [i 0 n 0]
    (let [j (.indexOf s ^String rehydration-signature i)]
      (if (neg? j) n (recur (+ j (count rehydration-signature)) (inc n))))))

(def ^:private output-dir-markers
  "Files the fetch script writes beside the documents. Their presence is what
   identifies a directory as rehydration output now that the documents
   themselves carry no signature."
  #{"MANIFEST.txt" "ATTRIBUTION.tsv"})

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d) nil
      (.exists (io/file d ".git")) d
      :else (recur (.getParentFile d)))))

(defn- tracked-files []
  (let [root (repo-root)
        {:keys [out exit]} (shell/with-sh-dir (str root) (shell/sh "git" "ls-files"))]
    (when-not (zero? exit)
      (throw (ex-info "git ls-files failed" {:exit exit})))
    (->> (str/split-lines out) (remove str/blank?) vec)))

(deftest no-wikipedia-text-is-committed
  (let [root (repo-root)
        files (tracked-files)]

    (testing "the guard can see the repository at all"
      ;; Without this the assertions below pass vacuously over an empty list —
      ;; a guard that cannot fail is worse than none.
      (is (some? root) "could not locate the repository root")
      (is (< 100 (count files))
          (str "expected a populated repo, saw " (count files) " tracked files")))

    (testing "no tracked file carries the rehydration signature at corpus scale"
      (let [offenders (->> files
                           (filter #(let [f (io/file root %)]
                                      (and (.isFile f)
                                           (< (.length f) 5000000)
                                           (<= signature-threshold
                                               (signature-count (slurp f))))))
                           vec)]
        (is (empty? offenders)
            (str "Wikipedia text or its attribution sidecar appears to be committed, "
                 "which breaks the licence argument for this corpus (#447): the text "
                 "is CC BY-SA and is meant to be fetched at setup, never vendored. "
                 "Offending files: " (pr-str offenders)))))

    (testing "the counter separates corpus output from code that mentions it"
      ;; The control for the threshold. Without it the number above is a magic
      ;; constant nobody re-checks after the sidecar format changes.
      (is (= 0 (signature-count "no urls here")))
      (is (= 1 (signature-count (str "(str \"" rehydration-signature "\" title)"))))
      (is (<= signature-threshold
              (signature-count (str/join "\n" (repeat 352 (str "f\tT\t" rehydration-signature "T")))))
          "a real sidecar has one row per document and must trip the threshold"))

    (testing "no tracked file sits in a rehydration output directory"
      ;; Covers the documents, which carry no signature of their own since
      ;; attribution moved to the sidecar.
      (let [tracked (set files)
            offenders (->> files
                           (filter (fn [p]
                                     (when-let [parent (.getParentFile (io/file p))]
                                       (some #(contains? tracked
                                                         (str (io/file parent %)))
                                             output-dir-markers))))
                           vec)]
        (is (empty? offenders)
            (str "files are committed inside a corpus output directory: "
                 (pr-str offenders)))))))

(deftest rehydrated-documents-carry-no-front-matter
  ;; MEASURED, not stylistic. `docs/folder.clj` contains zero front-matter
  ;; references — only `website.clj` parses it — so a YAML block at the top of a
  ;; `.md` is not metadata to the folder ingest, it is CONTENT. On the 352-doc
  ;; gold set it chunked as 334 chars of identical licence boilerplate per
  ;; document, sometimes as its own chunk and sometimes glued onto the article
  ;; body by `concatenate-too-small-chunks`.
  (let [doc (str "# Solsystemet\n\n"
                 "Solsystemet er det sol-planetsystemet som ...\n")]

    (testing "the document is an H1 and prose, nothing else"
      (is (not (str/starts-with? doc "---"))
          "a YAML block here would be indexed as corpus text, not read as metadata")
      (is (not (str/includes? doc "license:"))
          "licence boilerplate must not appear in every chunk of the corpus")
      (is (re-find #"(?m)^# \S" doc)
          "needs exactly the H1 the header-based chunker keys on"))

    (testing "the attribution the licence obliges is in the sidecar instead"
      ;; CC BY-SA obliges attribution at DISPLAY, not inside the indexed text.
      ;; This pins the sidecar FORMAT the display layer reads. It is a format
      ;; pin, not a behavioural guard on the fetch script — the script is a
      ;; babashka file and is not on this classpath.
      (let [header "file\ttitle\tsource_url\tlicense\tlicense_url\tattribution\tmodification"
            row (str/join "\t" ["Solsystemet.md" "Solsystemet"
                                "https://no.wikipedia.org/wiki/Solsystemet"
                                "CC BY-SA 4.0"
                                "https://creativecommons.org/licenses/by-sa/4.0/"
                                "Wikipedia contributors, \"Solsystemet\", ..."
                                "Headings converted to Markdown."])]
        (is (= 7 (count (str/split header #"\t")))
            "the sidecar header names every field the licence needs")
        (is (= (count (str/split header #"\t")) (count (str/split row #"\t")))
            "a row must line up with the header or the display layer reads the wrong column")
        (is (str/includes? row "https://no.wikipedia.org/wiki/")
            "a link to the source is required")
        (is (str/includes? row "CC BY-SA 4.0") "the licence must be named")
        (is (str/includes? row "https://creativecommons.org/licenses/by-sa/4.0/")
            "a link to the licence text is required")))

    (testing "the modification statement must match what was actually done"
      ;; The statement was a hardcoded "Excerpted: introduction section only."
      ;; while the corpus moved to FULL articles, which would have declared a
      ;; modification that did not happen. It now follows the fetch mode. Both
      ;; modes also declare the heading rewrite, which every document receives
      ;; and which went undeclared entirely while the string was fixed.
      (let [full  "Headings converted to Markdown."
            intro "Excerpted: introduction section only. Headings converted to Markdown."]
        (is (not (str/includes? full "Excerpted"))
            "a full article must never be described as excerpted")
        (is (str/includes? intro "Excerpted")
            "an intro corpus must declare the excerpting")
        (is (every? #(str/includes? % "Headings converted to Markdown") [full intro])
            "every document has its headings rewritten, so every mode declares it")))))

(def ^:private wikitext-heading-re
  ;; The marker the chunker CANNOT see. `header-line?` (rag/chunking.clj:22) is
  ;; #"^(#{1,6})\s+(.*)" — markdown only — so a wikitext heading is not a
  ;; heading to us. It is invisible rather than invalid, which is why this needs
  ;; a guard rather than a parse error.
  #"(?m)^\s*={2,6}\s*.+?\s*={2,6}\s*$")

(deftest no-written-document-carries-a-wikitext-heading
  ;; Wikipedia's `explaintext` returns headings as `== Etymologi ==`. Measured on
  ;; full-article extracts: Solsystemet 52 wikitext headings and 0 markdown,
  ;; Apollon 24 and 0. Feeding one through unrewritten yields ZERO heading splits
  ;; and one ~35,000-char chunk — and it does not error. It ingests, embeds and
  ;; scores, so nothing downstream reports a problem.
  (testing "the guard's needle can actually hit"
    ;; Without this the assertions below pass over a regex that matches nothing.
    (is (re-find wikitext-heading-re "== Etymologi ==")
        "the detector must match a real wikitext heading")
    (is (re-find wikitext-heading-re "=== Underdel ===")
        "including nested levels"))

  (testing "it does not fire on ordinary prose containing '='"
    (is (nil? (re-find wikitext-heading-re "vanlig tekst = ikke overskrift"))
        "an equals sign in prose is not a heading"))

  (testing "a rehydrated document must carry markdown headings, never wikitext"
    (let [good "# X\n\n## Etymologi\n\nprose\n"
          bad  "# X\n\n== Etymologi ==\n\nprose\n"]
      (is (nil? (re-find wikitext-heading-re good))
          "the rewritten form is what the chunker can split on")
      (is (re-find wikitext-heading-re bad)
          "the unrewritten form is exactly what this guard exists to reject"))))
