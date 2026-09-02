(ns digdir.skills.builtin.agent.streaming-test
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.skills.builtin.agent.streaming :as streaming]))

(defn- collect
  "Run `tokens` through a chunker and return the chunks it emitted."
  [tokens & [opts]]
  (let [emitted (atom [])
        {:keys [on-delta close]}
        (streaming/make-chunker (merge {:on-chunk #(swap! emitted conj %)
                                        :timeout-ms 250
                                        :now-fn (constantly 0)}
                                       opts))]
    (doseq [t tokens] (on-delta t))
    (close)
    @emitted))

(deftest paragraph-boundary-triggers-flush
  (testing "Buffers until \\n\\n, then emits the completed paragraph"
    (is (= ["Hello world.\n\n"]
           (collect ["Hello " "world." "\n\n"])))))

(deftest multiple-paragraphs-emit-separately
  (testing "Each \\n\\n landing in its own delta produces a chunk"
    (is (= ["First paragraph.\n\n" "Second paragraph.\n\n"]
           (collect ["First " "paragraph." "\n\n" "Second " "paragraph." "\n\n"])))))

(deftest close-flushes-remainder
  (testing "Whatever's in the buffer at close time goes out as a final chunk"
    (is (= ["Trailing text with no newline."]
           (collect ["Trailing " "text " "with no newline."])))))

(deftest paragraph-with-trailing-content-leaves-remainder
  (testing "Content after the last \\n\\n stays buffered until close"
    (is (= ["First.\n\n" "Trailing"]
           (collect ["First." "\n\n" "Trailing"])))))

(deftest timeout-flushes-mid-paragraph
  (testing "When `timeout-ms` elapses without a boundary, the buffer is forced out"
    (let [clock (atom 0)
          tick! (fn [delta-ms] (swap! clock + delta-ms))
          emitted (atom [])
          {:keys [on-delta close]}
          (streaming/make-chunker {:on-chunk #(swap! emitted conj %)
                                   :timeout-ms 250
                                   :now-fn #(deref clock)})]
      (on-delta "First fragment ")     ;; t=0 — buffered-at recorded
      (tick! 100)
      (on-delta "second fragment ")    ;; t=100, age=100ms, still buffered
      (tick! 200)
      (on-delta "third fragment.")     ;; t=300, age=300ms ≥ 250ms → flush
      (close)
      (is (= ["First fragment second fragment third fragment."] @emitted)))))

(deftest empty-deltas-are-ignored
  (testing "Empty strings and non-strings do not advance the buffer"
    (let [emitted (atom [])
          {:keys [on-delta close]}
          (streaming/make-chunker {:on-chunk #(swap! emitted conj %)
                                   :now-fn (constantly 0)})]
      (on-delta "")
      (on-delta nil)
      (on-delta "x")
      (close)
      (is (= ["x"] @emitted)))))

(deftest paragraph-flush-then-empty-buffer-resets-clock
  (testing "After a paragraph flush, the next delta restarts the timeout window"
    (let [clock (atom 0)
          tick! (fn [delta-ms] (swap! clock + delta-ms))
          emitted (atom [])
          {:keys [on-delta close]}
          (streaming/make-chunker {:on-chunk #(swap! emitted conj %)
                                   :timeout-ms 250
                                   :now-fn #(deref clock)})]
      (on-delta "para1\n\n")           ;; t=0  — flushes "para1\n\n"
      (tick! 100)
      (on-delta "starts here ")        ;; t=100 — buffered-at=100
      (tick! 100)
      (on-delta "still buffered ")     ;; t=200, age=100 — keep buffering
      (tick! 200)
      (on-delta "now flush")           ;; t=400, age=300 ≥ 250 → flush
      (close)
      (is (= ["para1\n\n"
              "starts here still buffered now flush"]
             @emitted)))))

(deftest requires-on-chunk
  (testing "Missing :on-chunk throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (streaming/make-chunker {})))))
