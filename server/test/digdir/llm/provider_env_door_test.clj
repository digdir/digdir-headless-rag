(ns digdir.llm.provider-env-door-test
  "the process environment is not a credential source for
   production LLM calls any more. Two guards, each with a positive control on
   the SAME walk, so a zero is a measurement and not a blind instrument.

     1. `src` reads OPENAI_API_ENDPOINT / OPENAI_API_KEY (or the
        `:openai-api-key` secret) only at the allowlisted sites - each with its
        count and its reason. The positive control is `src-dev`'s sweep runner,
        which reads the pair by design.
     2. Nothing in `src` installs the resolver's run-override; only src-dev
        does. The positive control is the runner's call.

   Call SHAPES are matched on parsed source (rewrite-clj, the census's own
   technique), so a docstring or comment that names the call is not a read,
   and a read cannot hide by being reformatted."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def ^:private skipped-tags #{:whitespace :newline :comma :comment :uneval})

(defn- code-children [node] (remove #(skipped-tags (n/tag %)) (n/children node)))

(defn- sexpr-of [node] (try (n/sexpr node) (catch Exception _ ::unreadable)))

(defn- calls
  "Every list form under `node` (outside `(comment ...)`) as [head first-arg row]."
  [node]
  (let [tag (n/tag node)]
    (cond
      (skipped-tags tag) []
      (= :list tag)
      (let [[h a] (code-children node)
            head (some-> h sexpr-of)]
        (if (= 'comment head)
          []
          (cons [head (some-> a sexpr-of) (:row (meta node))]
                (mapcat calls (n/children node)))))
      (n/inner? node) (mapcat calls (n/children node))
      :else [])))

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))
       (sort-by str)))

(defn- census
  "{file [[head arg row] ...]} of the calls in `root` that `pred` selects."
  [root pred]
  (into (sorted-map)
        (keep (fn [f]
                (let [hits (filter pred (calls (p/parse-file-all f)))]
                  (when (seq hits) [(str f) (vec hits)]))))
        (source-files root)))

(def ^:private pair #{"OPENAI_API_ENDPOINT" "OPENAI_API_KEY"})

(defn- env-read? [[head arg]]
  (or (and ('#{System/getenv getenv} head) (contains? pair arg))
      (and ('#{secrets/get! secrets/get} head) (= :openai-api-key arg))))

(def ^:private allowed-env-reads
  "The ONLY src sites that may read the pair, with exact counts. Phase 3 of the provider-resolver change
   deleted search-phrases' :lmstudio arm and, with it, the TEMPORARY entry for
   the client fallback moved there verbatim: no call path reads the pair now."
  {"src/digdir/setup/llm.clj"
   {:count 1 :why "setup-time prompt default for the endpoint; never a call path"}})

(deftest src-reads-the-pair-only-where-allowed
  (let [src (census "src" env-read?)
        dev (census "src-dev" env-read?)]
    (testing "POSITIVE CONTROL: the walk finds the sweep runner's reads in src-dev"
      (is (seq (get dev "src-dev/digdir/sweep/runner.clj"))
          (str "the needle cannot see a read it must see: " (pr-str dev))))
    (testing "src: exactly the allowlisted sites, at exactly their counts"
      (is (= (into (sorted-map) (map (fn [[f {:keys [count]}]] [f count])) allowed-env-reads)
             (into (sorted-map) (map (fn [[f hits]] [f (clojure.core/count hits)])) src))
          (str "an environment read of the OpenAI-compatible pair in src is the "
               "per-tenant -> process-global door the provider-resolver change closes: " (pr-str src))))
    (testing "the transport itself reads none of it"
      (is (nil? (get src "src/digdir/llm/client.clj"))))))

(defn- installs-override? [[head]]
  ('#{install-run-override! provider/install-run-override! digdir.llm.provider/install-run-override!} head))

(deftest only-src-dev-installs-the-run-override
  (let [src (census "src" installs-override?)
        dev (census "src-dev" installs-override?)]
    (testing "POSITIVE CONTROL: the sweep runner's call is found"
      (is (seq (get dev "src-dev/digdir/sweep/runner.clj")) (pr-str dev)))
    (testing "no production code installs it"
      (is (empty? src)
          (str "a src caller would route real tenants through a process-global "
               "credential: " (pr-str src))))))
