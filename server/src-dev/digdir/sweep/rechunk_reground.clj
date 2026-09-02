(ns digdir.sweep.rechunk-reground
  "Lever B re-grounding: for each golden longer than the re-chunk max, split it the
   SAME way the migration did (so sub-chunk ids match the new collection), ask an
   LLM which sub-chunk(s) carry the answer, and remap the question's golden id to
   those new sub-chunk ids. Short goldens keep their id (content unchanged). Writes
   a new question fixture; the original is untouched."
  (:require [clojure.java.io :as io]
            [digdir.sweep.runner :as runner]
            [digdir.sweep.questions :as questions]
            [digdir.rag.core :as rag]
            [digdir.rag.chunking :as ck]
            [digdir.config.accessor :as cfg]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.pprint :as pp]
            [wkok.openai-clojure.api :as api]
            [valuehash.api]))

(defn- chunk-id [content] (->> content valuehash.api/sha-256-str (take 12) (apply str)))

(defn- call-model [tenant messages model]
  (if (cfg/get {:tenant tenant} :services :azure-openai :use-azure-openai-api)
    (api/create-chat-completion
     {:model model :messages messages}
     {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
      :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
      :impl :azure})
    (api/create-chat-completion {:model model :messages messages :stream false})))

(defn- pick-parts
  "LLM: which numbered passage(s) answer the question? Returns a vector of indices."
  [tenant model {:keys [query reference]} parts]
  (let [numbered (str/join "\n\n" (map-indexed (fn [i p] (format "[%d] %s" i p)) parts))
        sys "You identify which numbered passage(s) from a single document contain the answer to a question. Return ONLY JSON."
        usr (str "Question: " query
                 (when (seq reference) (str "\n\nReference answer (ground truth): " reference))
                 "\n\nNumbered passages from one document:\n\n" numbered
                 "\n\nReturn the minimal set of passage numbers whose text contains the answer"
                 " (usually 1, occasionally 2 when the answer spans a split). JSON only:"
                 " {\"parts\": [n, ...]}")
        resp (call-model tenant [{:role "system" :content sys}
                                 {:role "user" :content usr}] model)
        content (-> resp :choices first :message :content str)
        s (str/index-of content "{") e (str/last-index-of content "}")
        m (when (and s e (< s e)) (try (json/read-str (subs content s (inc e)) :key-fn keyword)
                                       (catch Exception _ nil)))
        idxs (->> (:parts m) (keep #(cond (integer? %) % (number? %) (long %)
                                          (string? %) (try (Long/parseLong (str/trim %)) (catch Exception _ nil))))
                  (filter #(and (>= % 0) (< % (count parts)))) distinct vec)]
    (if (seq idxs) idxs [])))

(defn run [{:keys [max-len overlap out-path]}]
  (let [tenant "digdir"
        _model (questions/load-questions!)   ;; force fixture load early to surface drift
        model (or (try (cfg/get {:tenant tenant :default nil} :services :judge :model) (catch Exception _ nil))
                  "gpt-5.5")
        {:keys [collections]} (#'runner/resolve-dataset-config! {:tenant tenant :dataset-config-key "default"})
        {:keys [docs-collection chunks-collection]} collections
        raw (questions/load-raw)
        qs (:questions raw)
        ;; fetch all golden contents once
        all-gids (vec (distinct (mapcat :golden-chunk-ids qs)))
        chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection
                                          (mapv (fn [id] {:chunk_id id}) all-gids)
                                          {:tenant tenant :retrieve-top-k 500})
        content-by-id (into {} (map (juxt :chunk_id :content_markdown)) chunks)
        reground-id (fn [q gid]
                      (let [content (content-by-id gid)]
                        (if (or (nil? content) (<= (count content) max-len))
                          {:old gid :new [gid] :action :keep}
                          (let [parts (ck/split-oversized-content content max-len overlap)
                                idxs (pick-parts tenant model
                                                 {:query (:query q) :reference (:reference-answer q)} parts)
                                idxs (if (seq idxs) idxs [0])   ;; fallback: first part
                                new-ids (mapv #(chunk-id (nth parts %)) idxs)]
                            {:old gid :new new-ids :action :reground :n-parts (count parts) :picked idxs}))))
        regrounded (mapv (fn [q]
                           (let [maps (mapv #(reground-id q %) (:golden-chunk-ids q))
                                 new-goldens (vec (distinct (mapcat :new maps)))]
                             (when (some #(= :reground (:action %)) maps)
                               (println (format "%-44s %s" (:id q)
                                                (str/join "  " (map #(if (= :reground (:action %))
                                                                       (format "[%s->%s p%s/%s]" (subs (:old %) 0 8)
                                                                               (str/join "+" (map (fn [x] (subs x 0 8)) (:new %)))
                                                                               (str/join "+" (:picked %)) (:n-parts %))
                                                                       "keep") maps)))))
                             (assoc q :golden-chunk-ids new-goldens)))
                         qs)]
    (with-open [w (clojure.java.io/writer out-path)]
      (binding [*out* w]
        (pp/pprint (assoc raw :questions regrounded :notes "Lever B re-grounded (long goldens remapped to rechunk1500 sub-chunks)"))))
    (println "\nWrote" out-path "with" (count regrounded) "questions")))
