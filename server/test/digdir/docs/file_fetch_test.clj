(ns digdir.docs.file-fetch-test
  "The hop's whole purpose is refusing bytes that do not match the index, so
   the mismatch cases are the load-bearing tests. `clj-http.client/get` is
   stubbed — the var production calls — and every expected digest is computed
   from the same bytes the stub serves, so no test asserts a hand-copied hash."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-http.client :as http]
            [digdir.docs.file-fetch :as ff]
            [digdir.docs.loader :as loader]))

(def ^:private pdf-body (.getBytes "%PDF-1.7\nreal document bytes" "UTF-8"))
(def ^:private pdf-sha (ff/sha256-hex pdf-body))
(def ^:private landing-html
  (str "<html><head><meta name=\"citation_title\" content=\"A report\">"
       "<meta name=\"citation_pdf_url\" content=\"http://kudos.example/dokument/uuid/file\">"
       "</head><body>landing page</body></html>"))

(defn- entry [& {:as overrides}]
  (merge {:url "https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
          :sha256 pdf-sha
          :size (alength pdf-body)}
         overrides))

(defn- responder
  "Serve `by-url`, recording the URLs requested in order."
  [!urls by-url]
  (fn [url & _]
    (swap! !urls conj url)
    (if-let [b (get by-url url)]
      {:body b}
      (throw (ex-info "clj-http: status 404" {:status 404 :url url})))))

(deftest recognises-pdf-magic
  (is (true? (ff/pdf-bytes? pdf-body)))
  (is (false? (ff/pdf-bytes? (.getBytes "<!DOCTYPE html>" "UTF-8"))))
  (is (false? (ff/pdf-bytes? (byte-array 0))))
  (is (false? (ff/pdf-bytes? nil))))

(deftest extracts-citation-pdf-url-in-either-attribute-order
  (is (= "http://kudos.example/dokument/uuid/file" (ff/citation-pdf-url landing-html)))
  (is (= "http://x/y.pdf"
         (ff/citation-pdf-url "<meta content=\"http://x/y.pdf\" name=\"citation_pdf_url\">")))
  (testing "a landing page without the tag yields nil — the 404 subset's shape"
    (is (nil? (ff/citation-pdf-url "<html><head><title>Not found</title></head></html>")))))

(deftest hops-to-citation-pdf-url-when-the-indexed-url-serves-html
  (let [!urls (atom [])]
    (with-redefs [http/get (responder !urls
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes landing-html "UTF-8")
                                       "http://kudos.example/dokument/uuid/file" pdf-body})]
      (is (= (seq pdf-body) (seq (ff/fetch-file! (entry)))))
      (is (= ["https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
              "http://kudos.example/dokument/uuid/file"]
             @!urls)
          "must fetch the indexed url first, then hop"))))

(deftest uses-the-indexed-url-directly-when-it-still-serves-a-pdf
  (let [!urls (atom [])]
    (with-redefs [http/get (responder !urls
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       pdf-body})]
      (is (= (seq pdf-body) (seq (ff/fetch-file! (entry)))))
      (is (= 1 (count @!urls)) "no hop when the direct fetch is already a PDF"))))

(deftest refuses-bytes-that-do-not-match-the-index
  (let [other (.getBytes "%PDF-1.7\nA DIFFERENT DOCUMENT ENTIRELY" "UTF-8")
        serve (fn [b] (responder (atom []) {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf" b}))]
    (testing "digest mismatch — the silent-wrong-file case this exists to catch"
      (with-redefs [http/get (serve other)]
        (let [e (try (ff/fetch-file! (entry :size (alength other))) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e))
          (is (re-find #"digest does not match" (ex-message e)))
          (is (= pdf-sha (:expected-sha256 (ex-data e))))
          (is (= (ff/sha256-hex other) (:actual-sha256 (ex-data e)))))))

    (testing "size mismatch fails before the digest is even considered"
      (with-redefs [http/get (serve pdf-body)]
        (let [e (try (ff/fetch-file! (entry :size 999999)) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e))
          (is (re-find #"size does not match" (ex-message e)))
          (is (= 999999 (:expected-size (ex-data e)))))))

    (testing "an entry with no digest is refused rather than passed through unverified"
      (with-redefs [http/get (serve pdf-body)]
        (let [e (try (ff/fetch-file! (dissoc (entry) :sha256)) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e))
          (is (re-find #"no sha256" (ex-message e))))))))

(deftest fails-loudly-when-no-hop-is-available
  (testing "the ~15% whose landing page declares no citation_pdf_url"
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes "<html><title>Not found</title></html>" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (re-find #"no citation_pdf_url" (ex-message e))))))

  (testing "a tag the extractor cannot parse is reported as OUR bug, not as unreachable"
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes "<meta name='citation_pdf_url' content='http://x/y.pdf'>" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (re-find #"could not be extracted" (ex-message e))
            "single-quoted attributes are a parse failure, not a missing tag")
        (is (true? (:parse-failure? (ex-data e))))
        (is (true? (:tag-declared? (ex-data e)))))))

  (testing "an absent tag is reported as unreachable, distinctly"
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes "<html><title>Not found</title></html>" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (false? (:tag-declared? (ex-data e))))
        (is (false? (:parse-failure? (ex-data e)))))))

  (testing "a hop that yields HTML again is refused"
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes landing-html "UTF-8")
                                       "http://kudos.example/dokument/uuid/file"
                                       (.getBytes "<html>still not a pdf</html>" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (re-find #"did not yield a PDF" (ex-message e)))))))

;; ============================================================================
;; #308 — deterministic unreachability must not be retried
;; ============================================================================
;;
;; `digdir.docs.loader/worth-retrying?` is opt-OUT: it walks the cause chain for
;; the first ex-data carrying `:worth-retrying` and, finding none, returns true.
;; So an unmarked error is retried, and on this path that meant sleeping 1-60
;; minutes before failing at something that could never succeed.
;;
;; These assert against `worth-retrying?` itself rather than against the presence
;; of a key, because the key only matters if the real classifier reads it.

(deftest deterministic-unreachability-is-not-retried
  (testing "an absent citation_pdf_url tag is classified as not worth retrying"
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes "<html><title>Not found</title></html>" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (false? (:worth-retrying (ex-data e))))
        (is (false? (loader/worth-retrying? e))
            "the real classifier, not just the key"))))

  (testing "a 404 on the indexed url is classified as not worth retrying"
    ;; The stub responder throws the same shape clj-http does for a non-2xx.
    (with-redefs [http/get (responder (atom []) {})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= 404 (:status (ex-data e))))
        (is (false? (loader/worth-retrying? e)))
        (is (some? (ex-cause e)) "the original clj-http error is preserved as the cause"))))

  (testing "a parse failure is OURS and stays retryable"
    ;; Present-but-unextracted means this namespace is wrong. That is a fault
    ;; and should keep behaving like one; only unreachability is exempted.
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes "<meta name='citation_pdf_url' content='http://x/y.pdf'>" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (true? (:parse-failure? (ex-data e))))
        (is (nil? (:worth-retrying (ex-data e))))
        (is (true? (loader/worth-retrying? e))))))

  (testing "a digest mismatch stays retryable — it is a fault, not a known state"
    (with-redefs [http/get (responder (atom [])
                                      {"https://kudos-preprod.dfo.no/documents/151/files/92.pdf"
                                       (.getBytes "%PDF-1.7\ndifferent bytes entirely" "UTF-8")})]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (re-find #"digest does not match|size does not match" (ex-message e)))
        (is (true? (loader/worth-retrying? e))))))

  (testing "a 500 stays retryable — that is what the backoff ladder is for"
    (with-redefs [http/get (fn [& _] (throw (ex-info "clj-http: status 500" {:status 500})))]
      (let [e (try (ff/fetch-file! (entry)) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= 500 (:status (ex-data e))))
        (is (true? (loader/worth-retrying? e)))))))
