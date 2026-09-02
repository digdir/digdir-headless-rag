(ns digdir.import-export.format.yaml-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.import-export.format.yaml :as yaml-fmt]))

(def ^:private test-master-key "test-master-key-at-least-32-chars!")

(deftest namespace-stripping-roundtrip
  (testing "matching top-level namespace stripped, others preserved through round-trip"
    (let [records [{:config-def/path "skills.retrieval.top-k"
                    :config-def/root "runtime"
                    :config-def/type "integer"
                    :config-def/default 10
                    :config-def/description "Max chunks per call"
                    :other.thing/foo "bar"}]
          yaml-str (yaml-fmt/write records
                                   {:namespace "config-def"
                                    :collection-key :defs
                                    :key-fn :config-def/path})
          {:keys [namespace collection-key records]} (yaml-fmt/read yaml-str {})
          rec (first records)]
      (is (= "config-def" namespace))
      (is (= :defs collection-key))
      (is (= 1 (count records)))
      (is (= "skills.retrieval.top-k" (:config-def/path rec)))
      (is (= "runtime" (:config-def/root rec)))
      (is (= "integer" (:config-def/type rec)))
      (is (= 10 (:config-def/default rec)))
      (is (= "bar" (:other.thing/foo rec)))))
  (testing "stripped key appears unqualified in serialized YAML"
    (let [yaml-str (yaml-fmt/write
                    [{:config-def/path "x" :config-def/type "string"}]
                    {:namespace "config-def"
                     :collection-key :defs
                     :key-fn :config-def/path})]
      (is (str/includes? yaml-str "_namespace: config-def"))
      (is (str/includes? yaml-str "type: string"))
      (is (not (str/includes? yaml-str "config-def/type"))))))

(deftest insertion-order-preserved
  (testing "key order in serialized YAML matches insertion order across >8 keys"
    (let [keys-in-order [:e/zeta :e/yankee :e/xray :e/whiskey :e/victor
                         :e/uniform :e/tango :e/sierra :e/romeo :e/quebec]
          record (apply array-map (interleave keys-in-order (range)))
          yaml-str (yaml-fmt/write [record]
                                   {:namespace "e"
                                    :collection-key :items
                                    :key-fn (constantly "rec1")})
          positions (mapv #(str/index-of yaml-str (str (name %) ":"))
                          keys-in-order)]
      (is (every? some? positions)
          (str "expected every key present; got positions " positions))
      (is (apply < positions)
          (str "expected ascending positions; got " positions)))))

(deftest secret-roundtrip
  (testing "secret-keys fields are encrypted on write, decrypted on read"
    (let [yaml-str (yaml-fmt/write
                    [{:api-key/name "prod" :api-key/key "sk-plaintext-secret"}]
                    {:namespace "api-key"
                     :collection-key :keys
                     :key-fn :api-key/name
                     :secret-keys #{:api-key/key}
                     :master-key test-master-key})]
      (testing "ciphertext on disk does not contain plaintext"
        (is (str/includes? yaml-str "enc:"))
        (is (not (str/includes? yaml-str "sk-plaintext-secret"))))
      (testing "round-trip with master-key recovers plaintext"
        (let [{:keys [records]} (yaml-fmt/read yaml-str {:master-key test-master-key})
              rec (first records)]
          (is (= "sk-plaintext-secret" (:api-key/key rec)))
          (is (= "prod" (:api-key/name rec))))))))

(deftest missing-master-key-throws
  (testing "reading a file with enc: values without :master-key throws"
    (let [yaml-str (yaml-fmt/write
                    [{:api-key/name "prod" :api-key/key "secret"}]
                    {:namespace "api-key"
                     :collection-key :keys
                     :key-fn :api-key/name
                     :secret-keys #{:api-key/key}
                     :master-key test-master-key})]
      (is (thrown? Exception (yaml-fmt/read yaml-str {}))))))

(deftest multiline-string-roundtrip
  (testing "multi-line text values round-trip exactly"
    (let [text "Line one.\nLine two with **markdown**.\n\nParagraph break."
          yaml-str (yaml-fmt/write
                    [{:doc/id "doc-1" :doc/body text}]
                    {:namespace "doc"
                     :collection-key :docs
                     :key-fn :doc/id})
          {:keys [records]} (yaml-fmt/read yaml-str {})]
      (is (= text (:doc/body (first records)))))))

(deftest drop-and-reinject-natural-key
  (testing "drop-keys + reinject-key round-trip the natural-key field without duplicating it on disk"
    (let [records [{:config-def/path "skills.retrieval.top-k"
                    :config-def/type "integer"
                    :config-def/default 10}]
          yaml-str (yaml-fmt/write records
                                   {:namespace "config-def"
                                    :collection-key :defs
                                    :key-fn :config-def/path
                                    :drop-keys #{:config-def/path}})]
      (testing "the natural-key field is not duplicated inside the record body"
        ;; The path appears once as a YAML map key; it must NOT also appear
        ;; as a `path:` line indented inside the record.
        (is (str/includes? yaml-str "skills.retrieval.top-k:"))
        (is (not (re-find #"\n\s+path:\s" yaml-str))))
      (testing "reading with reinject-key restores the dropped field"
        (let [{:keys [records]} (yaml-fmt/read yaml-str
                                               {:reinject-key :config-def/path})
              rec (first records)]
          (is (= "skills.retrieval.top-k" (:config-def/path rec)))
          (is (= "integer" (:config-def/type rec)))
          (is (= 10 (:config-def/default rec))))))))

(deftest slash-bearing-natural-key-roundtrip
  (testing "natural-key strings containing slashes round-trip without losing prefix"
    ;; clj-yaml parses `runtime/acme/root` as :runtime/acme/root (splitting at
    ;; the first slash), so naive (name k) would lose the `runtime/` prefix.
    (let [records [{:config.node/id "runtime/acme/root" :config.node/label "R"}
                   {:config.node/id "dataset/digdir/public-docs/altinn-docs/materialization"
                    :config.node/label "ADM"}]
          yaml-str (yaml-fmt/write records
                                   {:namespace "config.node"
                                    :collection-key :nodes
                                    :key-fn :config.node/id
                                    :drop-keys #{:config.node/id}})
          {:keys [records]} (yaml-fmt/read yaml-str
                                           {:reinject-key :config.node/id})
          ids (set (map :config.node/id records))]
      (is (contains? ids "runtime/acme/root"))
      (is (contains? ids "dataset/digdir/public-docs/altinn-docs/materialization")))))

(deftest empty-collection-roundtrip
  (testing "writing zero records produces a valid file with empty collection"
    (let [yaml-str (yaml-fmt/write []
                                   {:namespace "config-def"
                                    :collection-key :defs
                                    :key-fn :config-def/path})
          {:keys [namespace records]} (yaml-fmt/read yaml-str {})]
      (is (= "config-def" namespace))
      (is (empty? records)))))
