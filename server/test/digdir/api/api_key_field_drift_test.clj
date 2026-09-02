(ns digdir.api.api-key-field-drift-test
  "What we tell callers to send must match what we accept.

   `POST /console-api/api-keys` is advertised in three places — the malli
   schema that accepts the request, `openapi.yaml`, and
   `docs/api/endpoints/api-keys.md` — and they had drifted apart in three
   different ways at once (#172):

     - the doc still said `skill-graphs`, renamed to `modes` in #167
     - the doc said `scopes`, which this endpoint has never read
     - both advertisements omitted `policy-id`, which it does accept

   None of that was catchable by testing the code, because the code was right.
   The failure was in the instructions, and it was silent: coercion strips an
   unknown key before the handler sees it, so a caller following our own
   documentation got a 201 and a key missing the grants they asked for.

   These tests compare the three lists directly. Reading the docs off disk is
   the point — a test that restated the field list in Clojure would drift with
   the code and never notice the doc."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]
            [digdir.api.routes.endpoints :as endpoints]))

;; ---------------------------------------------------------------------------
;; The three sources
;; ---------------------------------------------------------------------------

(defn- schema-fields
  "Field names the malli schema accepts, excluding the ones declared only to be
   rejected."
  []
  (->> (rest endpoints/create-api-key-body-parameters)
       (keep (fn [entry] (when (vector? entry) (first entry))))
       (remove endpoints/rejected-create-api-key-fields)
       set))

(defn- docs-file
  "Docs live beside the code; tests run with the server directory as cwd."
  [relative]
  (let [f (io/file "docs/api" relative)]
    (is (.exists f) (str "expected to find " (.getPath f)))
    f))

(defn- openapi-fields
  []
  (-> (yaml/parse-string (slurp (docs-file "openapi.yaml")))
      (get-in [:components :schemas :CreateApiKeyRequest :properties])
      keys
      set))

(defn- markdown-fields
  "Field names in the Required/Optional body-field tables of the Create API Key
   section — the tables a reader copies from. Stops at the next `##`, so the
   later endpoints in the same file are not swept in."
  []
  (let [text (slurp (docs-file "endpoints/api-keys.md"))
        section (-> text
                    (str/split #"(?m)^## Create API Key$")
                    second
                    (str/split #"(?m)^## ")
                    first)
        ;; Only the two field tables, not the "rejected with a 400" table below
        ;; them, which lists names we deliberately do not accept.
        body-tables (-> section
                        (str/split #"(?m)^Two field names are")
                        first)]
    (->> (re-seq #"(?m)^\|\s*`([a-z-]+)`\s*\|" body-tables)
         (map second)
         (map keyword)
         set)))

;; ---------------------------------------------------------------------------

(deftest openapi-advertises-exactly-what-the-schema-accepts
  (testing "the machine-readable spec and the accepting schema agree"
    (let [schema (schema-fields)
          spec (openapi-fields)]
      (is (= schema spec)
          (str "openapi.yaml and the malli schema disagree.\n"
               "  advertised but not accepted: " (sort (set/difference spec schema)) "\n"
               "  accepted but not advertised: " (sort (set/difference schema spec)))))))

(deftest api-keys-md-advertises-exactly-what-the-schema-accepts
  (testing "the human-readable doc and the accepting schema agree"
    ;; This is the assertion that would have caught #172: the code was correct
    ;; and this file was not.
    (let [schema (schema-fields)
          doc (markdown-fields)]
      (is (= schema doc)
          (str "api-keys.md and the malli schema disagree.\n"
               "  documented but not accepted: " (sort (set/difference doc schema)) "\n"
               "  accepted but not documented: " (sort (set/difference schema doc)))))))

(deftest rejected-fields-are-declared-and-not-advertised
  (testing "each rejected name is declared in the schema, so it 400s rather than being stripped"
    ;; An unknown key is silently dropped by coercion; only a declared one can
    ;; fail validation. If someone deletes an entry thinking it is dead weight,
    ;; the field goes back to being silently ignored.
    (let [declared (->> (rest endpoints/create-api-key-body-parameters)
                        (keep (fn [entry] (when (vector? entry) (first entry))))
                        set)]
      (doseq [f endpoints/rejected-create-api-key-fields]
        (is (contains? declared f)
            (str f " must stay declared in the schema or it becomes a silent no-op again")))))

  (testing "and none of them is advertised as accepted"
    (let [advertised (set/union (openapi-fields) (markdown-fields))]
      (doseq [f endpoints/rejected-create-api-key-fields]
        (is (not (contains? advertised f))
            (str f " is rejected; it must not appear as an accepted field"))))))

(deftest the-rejection-messages-name-what-to-do-instead
  (testing "a 400 that only refuses is worse than one that redirects"
    (let [entries (->> (rest endpoints/create-api-key-body-parameters)
                       (filter vector?)
                       (filter (comp endpoints/rejected-create-api-key-fields first)))]
      (is (= (count endpoints/rejected-create-api-key-fields) (count entries)))
      (doseq [[field _opts schema] entries]
        (let [message (get-in schema [1 :error/message])]
          (is (string? message)
              (str field " needs an :error/message — it reaches the caller in details.humanized"))
          (is (not (str/blank? message))))))
    (let [modes-entry (->> (rest endpoints/create-api-key-body-parameters)
                           (filter vector?)
                           (filter #(= :skill-graphs (first %)))
                           first)]
      (is (str/includes? (get-in (nth modes-entry 2) [1 :error/message]) "modes")
          "the skill-graphs rejection must name `modes` as the replacement"))))
