(ns digdir.api.api-key-info-shape-test
  "What `ApiKeyInfo` advertises must match what `public-api-key` emits.

   The schema declared seven fields. The producer emits fourteen, plus a
   conditional fifteenth — and one of the seven, `id`, is a field no handler
   emits at all: the producer emits `api-key-id` (#201).

   Both halves are caller-facing and both are silent. A client generated from
   the spec got a type with seven fields, received fourteen, and dropped seven
   of them — including `modes`, the grant field #167 renamed precisely so that
   callers could discover it. The response is a 200, so nothing ever pointed
   at it.

   This asserts the KEY SET rather than individual values, because a declared
   field that is never emitted is invisible to a value assertion — nothing
   reads it. That is how `config-key` survived in #191.

   It exercises the producer rather than restating its field list here: a list
   restated in Clojure agrees with the code forever and never notices the
   spec, and the spec is what was wrong."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]
            [digdir.api.routes.handlers :as handlers]))

(def ^:private key-info
  "What `list-api-keys` actually hands `public-api-key`, with every optional
   collection populated so the producer emits its full unconditional key set.

   This is the POST-normalisation shape. The read path is
   list-api-keys -> pull-api-key-entity -> normalize-key-entity, and that last
   step has already flattened the reference collections before the handler sees
   them: agent-refs arrives as agent-id STRINGS, not as stored
   :api-key.agent-ref/* maps, and dataset-scopes arrives with plain keys.

   An earlier version of this fixture used the pre-normalisation shape. It made
   the producer emit namespaced maps for agent-refs — a shape the real path
   never produces — and I wrote that into the schema as a finding. A stub must
   return a value the system can actually produce at the point it stands in
   for; see \"use the parser, not a regex\"'s sibling rule in CONTRIBUTING.md."
  {:api-key/id "k-1"
   :api-key/name "CI key"
   :api-key/scopes [:query]
   :api-key/skill-graphs ["builtin/agent-rag-graph-bundled"]
   :api-key/agent-refs ["builtin/agent-rag-agent"]
   :api-key/dataset-scopes [{:tenant "digdir" :dataset-config-key "public-docs"}]
   :api-key/allowed-config-keys []
   :api-key/clients ["client-1"]
   :api-key/usage-count 7
   :api-key/created 1700000000
   :api-key/created-by "u-1"
   :api-key/last-used 1700000900
   :api-key/expires-at 1800000000
   :api-key/revoked false})

(defn- emitted [ki] (set (map name (keys (#'handlers/public-api-key ki)))))

(defn- declared []
  (let [f (io/file "docs/api/openapi.yaml")]
    (is (.exists f) (str "expected to find " (.getPath f)))
    (->> (get-in (yaml/parse-string (slurp f))
                 [:components :schemas :ApiKeyInfo :properties])
         keys
         (map name)
         set)))

;; ---------------------------------------------------------------------------

(deftest api-key-info-declares-nothing-the-producer-omits
  (testing "a declared field the producer never emits is the #184 class"
    ;; `id` was the instance: plausible, and never sent.
    (let [phantom (set/difference (declared) (emitted key-info) #{"policy-id"})]
      (is (empty? phantom)
          (str "ApiKeyInfo advertises fields public-api-key does not emit: "
               (sort phantom))))))

(deftest api-key-info-declares-everything-the-producer-emits
  (testing "an emitted field the spec omits is dropped by a generated client"
    (let [missing (set/difference (emitted key-info) (declared))]
      (is (empty? missing)
          (str "public-api-key emits fields ApiKeyInfo does not declare: "
               (sort missing)
               "\nA client generated from the spec silently drops these.")))))

(deftest policy-id-is-conditional-and-declared
  (testing "policy-id appears only when the key is bound to a policy"
    ;; Declared, but absent from the unconditional set — so it is excluded
    ;; from the phantom check above rather than treated as drift.
    (is (not (contains? (emitted key-info) "policy-id"))
        "policy-id must not appear when the key has no policy")
    (is (contains? (emitted (assoc key-info :api-key/policy {:access-policy/id "p-1"}))
                   "policy-id")
        "policy-id must appear when the key has one")
    (is (contains? (declared) "policy-id")
        "policy-id is emitted on that path, so it must be advertised")))

(deftest the-grant-field-is-advertised-under-its-public-name
  (testing "modes, not skill-graphs — the rename exists so callers can find it"
    (is (contains? (emitted key-info) "modes"))
    (is (contains? (declared) "modes"))
    (is (not (contains? (declared) "skill-graphs"))
        "skill-graphs is the stored attribute, not the wire name (#167)")))

(deftest collection-elements-are-the-shape-the-producer-actually-emits
  (testing "agent-refs is agent-id strings, not stored :api-key.agent-ref/* maps"
    ;; A key-set assertion cannot see this: the key is present either way, only
    ;; its ELEMENT shape differs. An earlier fixture here supplied the
    ;; pre-normalisation value and made the producer emit namespaced maps, which
    ;; went into the schema as a finding that was not real. This pins the
    ;; element type so that cannot recur silently.
    (let [out (#'handlers/public-api-key key-info)]
      (is (every? string? (:agent-refs out))
          (str "agent-refs elements should be agent-id strings, got: "
               (pr-str (:agent-refs out))))
      (is (= [{:tenant "digdir" :dataset-config-key "public-docs"}]
             (:dataset-scopes out))
          "dataset-scopes elements are projected to plain keys")
      (is (every? string? (:modes out))
          "modes elements are skill-graph ids as strings"))))
