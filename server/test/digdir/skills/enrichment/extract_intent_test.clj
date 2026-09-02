(ns digdir.skills.enrichment.extract-intent-test
  "Unit coverage for `:builtin/extract-user-intent`.

   Tests are split between:
   - Pure parsing (no LLM call, deterministic)
   - Regex fallback for chunk-id extraction
   - End-to-end skill body with a stubbed LLM via the `:llm-call-fn`
     parameter (no Azure config needed)

   The LLM-stub injection point matches the pattern used by the LLM-
   selection branch in analyze-corpus."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.extract-intent :as ei]))

(use-fixtures :once
  (fn [t]
    (ei/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/extract-user-intent is in the skills registry"
    (is (some? (skills/get-skill :builtin/extract-user-intent)))))

;; =============================================================================
;; render-prompt
;; =============================================================================

(deftest prompt-mentions-corpus-language-and-shape
  (testing "Prompt includes the corpus language and the three required JSON keys"
    (let [p (ei/render-prompt "When was Altinn 3 launched?" "Norwegian (bokmål)")]
      (is (str/includes? p "Norwegian (bokmål)"))
      (is (str/includes? p "\"topic\""))
      (is (str/includes? p "\"explicit_chunk_ids\""))
      (is (str/includes? p "\"goal\""))
      (is (str/includes? p "When was Altinn 3 launched?")))))

(deftest prompt-defaults-corpus-language-when-omitted
  (testing "Default corpus language is Norwegian"
    (let [p (ei/render-prompt "anything" nil)]
      (is (str/includes? p "Norwegian (bokmål)")))))

;; =============================================================================
;; parse-intent-response
;; =============================================================================

(defn- llm-response [content]
  {:choices [{:message {:content content}}]})

(deftest parse-clean-json
  (testing "Plain JSON with all three fields"
    (let [out (ei/parse-intent-response
               (llm-response "{\"topic\":\"Altinn 3 lanseringsdato\",\"explicit_chunk_ids\":[\"8e22ae4b88b1\"],\"goal\":\"Find when Altinn 3 launched.\"}"))]
      (is (= "Altinn 3 lanseringsdato" (:topic out)))
      (is (= ["8e22ae4b88b1"] (:explicit-chunk-ids out)))
      (is (= "Find when Altinn 3 launched." (:goal out))))))

(deftest parse-strips-code-fence
  (testing "LLM wraps JSON in ```json … ``` — still parsed"
    (let [out (ei/parse-intent-response
               (llm-response "```json\n{\"topic\":\"x\",\"explicit_chunk_ids\":[],\"goal\":\"y\"}\n```"))]
      (is (= "x" (:topic out)))
      (is (= [] (:explicit-chunk-ids out)))
      (is (= "y" (:goal out))))))

(deftest parse-tolerates-missing-fields
  (testing "Missing or wrong-type fields degrade to nil / empty"
    (let [out (ei/parse-intent-response
               (llm-response "{\"topic\":\"only topic\"}"))]
      (is (= "only topic" (:topic out)))
      (is (= [] (:explicit-chunk-ids out)))
      (is (nil? (:goal out))))))

(deftest parse-rejects-non-hex-chunk-ids
  (testing "Junk strings in explicit_chunk_ids are filtered out"
    (let [out (ei/parse-intent-response
               (llm-response "{\"topic\":\"x\",\"explicit_chunk_ids\":[\"valid8e22ae4b88b1\",\"NOT_A_CHUNK\",\"abc\",\"abcd1234ef\"],\"goal\":\"y\"}"))]
      (is (some #{"abcd1234ef"} (:explicit-chunk-ids out))
          "Accepts 10-char hex token")
      (is (not (some #{"NOT_A_CHUNK"} (:explicit-chunk-ids out)))
          "Rejects non-hex token")
      (is (not (some #{"abc"} (:explicit-chunk-ids out)))
          "Rejects too-short hex token"))))

(deftest parse-handles-bad-json
  (testing "Malformed JSON → all fields nil/empty (not a thrown exception)"
    (let [out (ei/parse-intent-response (llm-response "this is not JSON at all"))]
      (is (nil? (:topic out)))
      (is (= [] (:explicit-chunk-ids out)))
      (is (nil? (:goal out))))))

;; =============================================================================
;; regex-extract-chunk-ids
;; =============================================================================

(deftest regex-extracts-named-chunk-id
  (testing "Pulls a hex-like chunk_id out of free text"
    (is (= ["8e22ae4b88b1"]
           (ei/regex-extract-chunk-ids
            "For chunk 8e22ae4b88b1 (which contains the Altinn 3 launch date), do X")))))

(deftest regex-ignores-non-hex-tokens
  (testing "Words that aren't hex don't match"
    (is (= []
           (ei/regex-extract-chunk-ids
            "When was Altinn 3 launched? Please answer in Norwegian.")))))

(deftest regex-handles-multiple-chunk-ids
  (testing "Two distinct chunk_ids in one query both surface"
    (is (= ["8e22ae4b88b1" "4d86c6513742"]
           (ei/regex-extract-chunk-ids
            "Compare 8e22ae4b88b1 with 4d86c6513742")))))

;; =============================================================================
;; Skill body (end-to-end with stubbed LLM)
;; =============================================================================

(deftest skill-uses-llm-fields-when-available
  (testing "LLM returned a topic + chunk-ids → those are used verbatim"
    (let [fake-llm (fn [_prompt]
                     (llm-response "{\"topic\":\"Altinn 3 lansering\",\"explicit_chunk_ids\":[\"8e22ae4b88b1\"],\"goal\":\"Enrich Altinn 3 launch chunk.\"}"))
          res (ei/execute-extract-user-intent
               {:inputs {:user-query "When did Altinn 3 launch?"}
                :parameters {:llm-call-fn fake-llm}
                :skill-params {:tenant "digdir"}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res))
      (is (= "Altinn 3 lansering" (:topic outputs)))
      (is (= ["8e22ae4b88b1"] (:explicit-chunk-ids outputs)))
      (is (= "Enrich Altinn 3 launch chunk." (:goal outputs))))))

(deftest skill-falls-back-to-regex-when-llm-misses-chunk-id
  (testing "LLM returned no explicit_chunk_ids, but the raw query named one — regex catches it"
    (let [fake-llm (fn [_]
                     (llm-response "{\"topic\":\"Altinn 3 lansering\",\"explicit_chunk_ids\":[],\"goal\":\"x\"}"))
          res (ei/execute-extract-user-intent
               {:inputs {:user-query "For chunk 8e22ae4b88b1, please enrich"}
                :parameters {:llm-call-fn fake-llm}
                :skill-params {:tenant "digdir"}})
          outputs (skills/get-result-outputs res)]
      (is (= ["8e22ae4b88b1"] (:explicit-chunk-ids outputs))
          "Regex picked up what the LLM dropped"))))

(deftest skill-degrades-on-llm-error
  (testing "LLM throws → topic falls back to raw user-query, regex still finds chunk-ids"
    (let [fake-llm (fn [_] (throw (ex-info "boom" {})))
          res (ei/execute-extract-user-intent
               {:inputs {:user-query "For chunk 8e22ae4b88b1, please enrich"}
                :parameters {:llm-call-fn fake-llm}
                :skill-params {:tenant "digdir"}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res)
          "Skill itself succeeds — the graph keeps going")
      (is (= "For chunk 8e22ae4b88b1, please enrich" (:topic outputs))
          "Fallback topic is the raw user-query")
      (is (= ["8e22ae4b88b1"] (:explicit-chunk-ids outputs))
          "Regex fallback caught the chunk-id"))))

(deftest skill-no-chunk-ids-when-none-named
  (testing "A user query with no chunk-id reference returns an empty vec"
    (let [fake-llm (fn [_]
                     (llm-response "{\"topic\":\"x\",\"explicit_chunk_ids\":[],\"goal\":\"y\"}"))
          res (ei/execute-extract-user-intent
               {:inputs {:user-query "When was Altinn 3 launched?"}
                :parameters {:llm-call-fn fake-llm}
                :skill-params {:tenant "digdir"}})
          outputs (skills/get-result-outputs res)]
      (is (= [] (:explicit-chunk-ids outputs))))))
