(ns digdir.api.request-doc-drift-test
  "Every endpoint that accepts input must document that input, and the two
   lists must match — for request BODIES and for QUERY PARAMETERS alike.

   THIS IS A DOC-DRIFT TEST, NOT A DETECTOR, and the distinction decides its
   shape. `log-discarded-request-fields-middleware` is body-only on purpose:
   it diffs real TRAFFIC against the schema, and query strings routinely carry
   keys nobody declared — cache-busters, analytics parameters — so the same
   diff there would be mostly noise. This test diffs DOCUMENTATION against the
   schema. Both sides are artifacts we author, neither contains traffic, and
   there is no noise term at all. The detector's argument does not transfer,
   which is why the query side gets a drift test and not a detector (#267).

   Generalised from the body-only version rather than duplicated: a drift test
   that only ever covers the case which prompted it is how six schemas went
   unchecked in the first place (#174).

   Generalised from `api-key-field-drift-test` (#173), which pinned one
   endpoint. The #174 measurement then found the same gap in both directions
   across the surface: six endpoints accepted a body `openapi.yaml` never
   mentioned, one accepted a field it did not document, and one marked a field
   required that the schema treats as optional (#177).

   Why an undocumented body matters even though nothing is broken: coercion
   silently discards fields the schema does not declare, so a caller working
   from a wrong or absent advertisement gets a 2xx and a request that did less
   than they asked. That is #172. Nobody can notice that drift where there is
   no advertisement to drift from.

   THE TRAP, learned by walking into it during #174: `read-json-body`
   normalises camelCase to kebab-case BEFORE coercion, so `filterValue` and
   `filter-value` are the same field. A comparison that does not normalise both
   sides manufactures findings — three of them, in the first pass of that
   measurement. `normalise` below is the whole defence."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]
            [malli.core :as m]
            [digdir.api.routes.endpoints :as endpoints]))

(defn- normalise
  "camelCase -> kebab-case, mirroring digdir.api.util/normalize-request-key.
   Applied to BOTH sides of every comparison in this namespace."
  [k]
  (-> (name k)
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case))

;; ---------------------------------------------------------------------------
;; What the code accepts
;; ---------------------------------------------------------------------------

(defn- schema-fields
  [schema]
  (let [s (m/schema schema)]
    {:keys (into #{} (map (fn [[k _ _]] (normalise k))) (m/children s))
     :required (into #{} (comp (remove (fn [[_ props _]] (:optional props)))
                               (map (fn [[k _ _]] (normalise k))))
                     (m/children s))}))

(defn- walk-routes
  "Every [path method schema] the router will coerce for `kind`."
  [kind routes]
  (let [out (atom [])]
    (letfn [(go [prefix node]
              (cond
                (and (vector? node) (string? (first node)))
                (let [[seg & more] node
                      path (str prefix seg)]
                  (doseq [x more] (go path x)))

                (map? node)
                (doseq [[method data] node]
                  (when (and (keyword? method) (map? data) (get-in data [:parameters kind]))
                    (swap! out conj {:kind kind
                                     :path prefix
                                     :method (name method)
                                     :schema (get-in data [:parameters kind])})))

                (sequential? node) (doseq [x node] (go prefix x))))]
      (doseq [r routes] (go "" r)))
    @out))

(defn- endpoints-taking [kind]
  (concat (walk-routes kind endpoints/api-routes)
          (walk-routes kind endpoints/console-api-routes)))

(def ^:private param-kinds
  "Both input surfaces coercion strips from. Adding a third (`:path`) would be
   one line here plus a reader in `advertised`."
  [:body :query])

(def ^:private endpoints-with-input
  (into [] (mapcat endpoints-taking) param-kinds))

;; ---------------------------------------------------------------------------
;; What openapi.yaml advertises
;; ---------------------------------------------------------------------------

(def ^:private spec
  (delay
    (let [f (io/file "docs/api/openapi.yaml")]
      (assert (.exists f) (str "expected " (.getPath f) " — tests run from the server directory"))
      (yaml/parse-string (slurp f)))))

(defn- spec-path [path]
  (keyword (str/replace path #":([a-zA-Z-]+)" "{$1}")))

(defmulti ^:private advertised
  "What openapi.yaml says an endpoint accepts for `kind`, or nil when the spec
   has no entry for it at all. Uniform shape across kinds: {:keys :required}."
  (fn [kind _path _method] kind))

(defmethod advertised :body
  [_ path method]
  (some-> (get-in @spec [:paths (spec-path path) (keyword method) :requestBody
                         :content (keyword "application/json") :schema])
          (as-> sch
                (let [ref (:$ref sch)
                      sch (if ref
                            (get-in @spec [:components :schemas (keyword (last (str/split ref #"/")))])
                            sch)]
                  {:keys (into #{} (map normalise) (keys (:properties sch)))
                   :required (into #{} (map normalise) (:required sch))}))))

(defmethod advertised :query
  [_ path method]
  ;; Query parameters are a LIST on the operation, each with its own
  ;; `required`, rather than a schema with a `required` array. Matching on
  ;; path+method matters more here than it looks: /api/conversations documents
  ;; page_size, page_index and tags, and /console-api/conversations accepts the
  ;; same three names while being absent from the spec entirely. A name-keyed
  ;; comparison would call that documented.
  (when-let [op (get-in @spec [:paths (spec-path path) (keyword method)])]
    (let [qs (filter #(= "query" (:in %)) (:parameters op))]
      (when (seq qs)
        {:keys (into #{} (map (comp normalise :name)) qs)
         :required (into #{} (comp (filter :required) (map (comp normalise :name))) qs)}))))

;; Fields declared only so a stale request is rejected — see
;; endpoints/rejected-create-api-key-fields. They are not accepted fields and
;; must not be advertised, which api-key-field-drift-test asserts separately.
(defn- accepted [path schema]
  (cond-> (schema-fields schema)
    (= "/console-api/api-keys" path)
    (update :keys set/difference (into #{} (map normalise) endpoints/rejected-create-api-key-fields))))

;; ---------------------------------------------------------------------------

(defn- label [{:keys [kind path method]}]
  (str (str/upper-case method) " " path " (" (name kind) ")"))

(deftest every-accepted-input-is-documented
  (testing "an endpoint that accepts a body or query parameters documents them"
    (let [missing (for [{:keys [kind path method schema] :as ep} endpoints-with-input
                        :when (nil? (advertised kind path method))]
                    (str (label ep) "  accepts " (sort (:keys (accepted path schema)))))]
      (is (empty? missing)
          (str "endpoints accepting undocumented input:\n  "
               (str/join "\n  " missing))))))

(deftest documented-fields-match-accepted-fields
  (testing "field for field, with camelCase normalised on both sides"
    (doseq [{:keys [kind path method schema] :as ep} endpoints-with-input
            :let [adv (advertised kind path method)]
            :when adv]
      (let [acc (accepted path schema)
            documented-not-accepted (set/difference (:keys adv) (:keys acc))
            accepted-not-documented (set/difference (:keys acc) (:keys adv))]
        (is (empty? documented-not-accepted)
            (str (label ep) " documents fields it does not accept — for a body a caller "
                 "sending one gets a 2xx and a silently incomplete request (#172); for a "
                 "query parameter they get a 400 they cannot explain: "
                 (sort documented-not-accepted)))
        (is (empty? accepted-not-documented)
            (str (label ep) " accepts fields it does not document: "
                 (sort accepted-not-documented)))))))

(deftest documented-requiredness-matches-the-schema
  (testing "a field the spec calls required must actually be required"
    (doseq [{:keys [kind path method schema] :as ep} endpoints-with-input
            :let [adv (advertised kind path method)]
            :when adv]
      (let [acc (accepted path schema)
            ;; Only flag fields the spec calls required that the schema does
            ;; not. The reverse is legitimate: several handlers enforce
            ;; either/or rules malli cannot express, and the spec explains
            ;; those in prose.
            over-claimed (set/difference (:required adv) (:required acc))]
        (is (empty? over-claimed)
            (str (label ep) " marks these required, but the schema accepts a request "
                 "without them: " (sort over-claimed)))))))

(deftest the-comparison-is-not-vacuous
  (testing "the walk actually found endpoints of BOTH kinds"
    ;; Counting per kind, not in total: a total would stay plausible if the
    ;; query walk silently found nothing, which is the failure this whole
    ;; generalisation exists to prevent.
    (let [by-kind (group-by :kind endpoints-with-input)]
      (is (<= 13 (count (:body by-kind)))
          "if this drops, the route walk stopped seeing body schemas")
      (is (<= 8 (count (:query by-kind)))
          "if this drops, the query walk stopped seeing query schemas and every
           assertion above passes for the wrong reason")
      (is (some #(= "/console-api/api-keys" (:path %)) (:body by-kind)))
      (is (some #(= :query (:kind %)) endpoints-with-input)))))

;; ---------------------------------------------------------------------------
;; Endpoint EXISTENCE, not just endpoint input (#343)
;;
;; Everything above compares what an endpoint ACCEPTS. It can only reach
;; endpoints that declare `:parameters`, because that is what `walk-routes`
;; collects — so a route taking neither a body nor a query string is invisible
;; to it, and can be absent from `openapi.yaml` entirely without any test
;; complaining. Six public routes were.
;;
;; That is the same shape as the two contracts lifted this week: a fact stated
;; somewhere the guard cannot reach. The MCP header rules lived in a `cond`
;; outside the parameters map (#344); the dispatch table still does (#368);
;; and a parameterless GET's existence lives only in the route tree.
;;
;; Truth comes from the ROUTE TREES the server compiles, not from reading
;; `endpoints.clj` for intent — the distinction #112 paid for.
;; ---------------------------------------------------------------------------

(def ^:private http-verbs #{:get :post :put :delete :patch :head :options})

(defn- walk-all-routes
  "Every [path method] the server serves, regardless of whether it declares
   `:parameters`. Deliberately a separate walk from `walk-routes` above: that
   one answers \"what does this endpoint accept\", this one answers \"does this
   endpoint exist\", and collapsing them would re-create the blind spot."
  [routes]
  (let [out (atom [])]
    (letfn [(go [prefix node]
              (cond
                (and (vector? node) (string? (first node)))
                (let [[seg & more] node
                      path (str prefix seg)]
                  (doseq [x more] (go path x)))

                (map? node)
                (doseq [[method _] node]
                  (when (contains? http-verbs method)
                    (swap! out conj {:path prefix :method (name method)})))

                (sequential? node) (doseq [x node] (go prefix x))))]
      (doseq [r routes] (go "" r)))
    (distinct @out)))

(def ^:private served-routes
  "The public and console trees — TWO of the THREE routers `endpoints.clj`
   mounts.

   ⚠️ `endpoints/debug-routes` is NOT walked, so its 9 served operations
   (`/api/debug/*`) are covered by no existence guard in this file: a tenth
   added tomorrow fails nothing here. That is a scope question — those routes
   sit behind `X-Debug-Api-Key` and no client is generated from them — rather
   than an oversight to patch in passing, so it is filed rather than fixed.
   Do not widen this `concat` without settling whether `/api/debug/*` belongs
   in `openapi.yaml`, because every guard in this file keys off it."
  (delay (concat (walk-all-routes endpoints/api-routes)
                 (walk-all-routes endpoints/console-api-routes))))

(def ^:private console-routes-not-yet-documented
  "Operator Console routes the spec does not describe. A REGISTER, not a
   prefix allowlist: each is named, so a NEW console route still fails this
   test rather than being absorbed by a wildcard.

   EMPTY since #369 documented the last six. The console surface now carries
   the same rule as the public one: every served route is in the spec, with no
   exceptions. Kept rather than deleted because it is the seam that lets a new
   console route be recorded deliberately instead of silently — but adding an
   entry is now a decision to be argued for, not a default.

   The public surface carries no such register and never has: after #350
   removed the listing endpoints, every `/api/*` and `/v1/*` route the server
   serves is in the spec."
  #{})

(defn- documented? [{:keys [path method]}]
  (some? (get-in @spec [:paths (spec-path path) (keyword method)])))

(defn- registered? [{:keys [path method]}]
  (contains? console-routes-not-yet-documented [(str/upper-case method) path]))

(def ^:private expected-served-route-count
  "Pinned so the walker going quiet fails loudly. A route-walker that stops
   matching is indistinguishable from a server with fewer routes, and this
   file's own history is the argument: #174 found six schemas unchecked
   because nothing asserted how many there should be."
  40)

(deftest the-route-walker-still-sees-the-public-and-console-trees
  ;; Named for what it walks, not for the whole surface. It was called
  ;; `...-sees-the-whole-surface` and did not: `debug-routes` is a third
  ;; mounted router this file never walks (see `served-routes`). A test name is
  ;; an active claim — anyone auditing coverage reads it and stops — so an
  ;; overclaiming name costs the unguarded routes AND the next person who
  ;; would have found them.
  (let [n (count @served-routes)]
    (is (= expected-served-route-count n)
        (str "walked " n " served routes, expected " expected-served-route-count
             ". If routes were added or removed, update the count in the same "
             "commit; if the walker simply stopped matching, fix the walker "
             "rather than the number."))))

(deftest every-public-route-is-in-the-spec
  (testing "no exceptions on the public surface — a client is generated from this"
    (let [missing (for [{:keys [path method] :as r} @served-routes
                        :when (not (str/starts-with? path "/console-api"))
                        :when (not (documented? r))]
                    (str (str/upper-case method) " " path))]
      (is (empty? missing)
          (str "served on the PUBLIC surface but absent from openapi.yaml:\n  "
               (str/join "\n  " (sort missing))
               "\n\nA route the spec omits is invisible to every other guard in "
               "this file — its body and query parameters cannot drift-check "
               "against a spec entry that does not exist. There is no register "
               "for the public surface on purpose.")))))

(deftest every-console-route-is-documented-or-registered
  (testing "a new console route fails rather than joining a wildcard"
    (let [unaccounted (for [{:keys [path method] :as r} @served-routes
                            :when (str/starts-with? path "/console-api")
                            :when (not (documented? r))
                            :when (not (registered? r))]
                        (str (str/upper-case method) " " path))]
      (is (empty? unaccounted)
          (str "console routes neither documented nor registered:\n  "
               (str/join "\n  " (sort unaccounted))
               "\n\nEither document it, or add it to "
               "`console-routes-not-yet-documented` and say why in #369."))))
  (testing "the register does not outlive its entries"
    (let [served (set (map (fn [{:keys [path method]}] [(str/upper-case method) path])
                           @served-routes))
          stale (remove served console-routes-not-yet-documented)]
      (is (empty? stale)
          (str "registered routes that are no longer served: " (pr-str (vec stale))
               ". Remove them — a register entry for a deleted route is a claim "
               "about nothing.")))))

(deftest every-spec-path-is-served
  (testing "a documented path the router does not serve is a 404 with no explanation"
    (let [served (set (map (fn [{:keys [path method]}]
                             [(str (spec-path path)) method])
                           @served-routes))
          phantom (for [[spath ops] (:paths @spec)
                        [method _] ops
                        :when (contains? http-verbs method)
                        :let [k [(str spath) (name method)]]
                        :when (not (contains? served k))]
                    (str (str/upper-case (name method)) " " (subs (str spath) 1)))]
      (is (empty? phantom)
          (str "documented but not served:\n  " (str/join "\n  " (sort phantom)))))))
