(ns digdir.sweep.judge
  "LLM-as-judge for sweep answer quality (P1 of
   plans/proposed/llm-as-judge-answer-quality-plan.md).

   Scores an agent answer against an authored reference answer using a
   configurable, INDEPENDENT judge model (default `gpt-5.5-chat`, via
   `services.judge.model`). Prod ns: depends only on the LLM client + config
   accessor — no src-dev deps — so the dashboard can call it directly.

   The judge grades the candidate RELATIVE TO THE REFERENCE, allowing correct
   answers phrased differently or grounded in non-golden chunks. Never throws:
   API/parse failures come back as an :error / :unparseable verdict."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [digdir.config.accessor :as cfg]
            [digdir.llm.client :as api]))

(def default-judge-model "gpt-5.5")

(defn judge-model
  "The configured judge model (`services.judge.model`), defaulting to
   `gpt-5.5-chat` when unset."
  [tenant]
  (or (try (cfg/get {:tenant tenant :default nil} :services :judge :model)
           (catch Exception _ nil))
      default-judge-model))

(def ^:private system-prompt
  "You are a strict evaluator of question-answering quality for Altinn developer
documentation. You are given a USER QUESTION, a REFERENCE ANSWER (the ground
truth), and a CANDIDATE ANSWER produced by an AI agent. Do two things.

1) QUALITY — judge whether the candidate answer is correct, complete, and
faithful RELATIVE TO THE REFERENCE. Allow answers that are phrased differently,
longer, or that cite different sources, as long as they establish the same key
facts as the reference and do not contradict it. Penalize missing key facts,
incorrect or contradictory facts, and hallucinated specifics not supported by
the reference.

2) DIFFICULTY — rate the INTRINSIC difficulty of the QUESTION for a
retrieval-augmented agent answering from a large Altinn developer-documentation
corpus, on a 1-5 integer scale. Consider these factors: (a) vocabulary gap —
does the query use the corpus's own terms, or lay/indirect phrasing far from how
the docs are written; (b) synthesis — is the answer one explicit fact in one
chunk, or must facts be combined across multiple chunks/documents; (c) scope —
single-part vs a compound, multi-part question; (d) distractors — is the topic
easily confused with adjacent ones the corpus also covers.

Anchors (USE THE FULL 1-5 RANGE — calibrate to the spread of these questions; do
NOT cluster everything at 2-3):
  1 = trivial: a single explicit fact in the exact vocabulary a user would
      search with; one obvious chunk.
  2 = easy: one fact or a short list, mostly direct vocabulary, a single chunk.
  3 = moderate: the right document plus some synthesis, OR a moderate vocabulary
      gap, OR a two-part question whose parts share one source.
  4 = hard: REQUIRES combining facts across multiple chunks/documents, OR clearly
      lay/indirect phrasing far from corpus vocabulary, OR a compound question
      whose parts need different sources, OR high distractor density (easily
      confused with an adjacent topic). Most multi-source or lay-phrased
      questions belong here, NOT at 3.
  5 = very hard: several level-4 factors at once (e.g. multi-document synthesis
      AND lay phrasing AND strong adjacent-topic confusion) — the kind where even
      a strong agent will likely drop a required condition.

Judge difficulty from the QUESTION and REFERENCE ONLY — do NOT let the candidate
answer's correctness influence the rating.

Respond with ONLY a JSON object (no prose, no code fence) of the form:
{\"verdict\": \"correct\" | \"partial\" | \"incorrect\", \"score\": <number 0.0-1.0>, \"rationale\": \"<one sentence>\", \"difficulty\": <integer 1-5>, \"difficulty_rationale\": \"<one sentence>\"}
where: correct = establishes all key facts with no contradictions (score >= 0.8);
partial = establishes some key facts but misses or muddles others (0.3-0.7);
incorrect = wrong, contradicts the reference, or fails to answer (< 0.3).")

(defn- build-messages [{:keys [query reference response]}]
  [{:role "system" :content system-prompt}
   {:role "user"
    :content (str "USER QUESTION:\n" query
                  "\n\nREFERENCE ANSWER:\n" reference
                  "\n\nCANDIDATE ANSWER:\n" response
                  "\n\nReturn only the JSON verdict.")}])

(defn- call-model
  "Call the judge model. We deliberately OMIT `temperature` — gpt-5.x
   reasoning-class models reject any non-default temperature (400), and they are
   stable enough without it; non-reasoning models fall back to their default."
  [tenant messages model]
  (if (cfg/get {:tenant tenant} :services :azure-openai :use-azure-openai-api)
    (api/create-chat-completion
     {:model model :messages messages}
     {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
      :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
      :impl :azure})
    (api/create-chat-completion
     {:model model :messages messages :stream false})))

(defn- parse-verdict [content]
  (let [content (str content)
        s (str/index-of content "{")
        e (str/last-index-of content "}")
        json-str (when (and s e (< s e)) (subs content s (inc e)))
        m (when json-str (try (json/read-str json-str :key-fn keyword) (catch Exception _ nil)))]
    (if (and m (:verdict m))
      {:verdict (str/lower-case (str (:verdict m)))
       :score (let [sc (:score m)]
                (cond (number? sc) (double sc)
                      (string? sc) (try (Double/parseDouble sc) (catch Exception _ nil))
                      :else nil))
       :rationale (str (:rationale m))
       :difficulty (let [d (:difficulty m)]
                     (cond (integer? d) d
                           (number? d) (long (Math/round (double d)))
                           (string? d) (try (long (Math/round (Double/parseDouble d))) (catch Exception _ nil))
                           :else nil))
       :difficulty-rationale (some-> (:difficulty_rationale m) str)}
      {:verdict "unparseable" :score nil :difficulty nil
       :rationale (subs content 0 (min 240 (count content)))})))

(defn judge-answer
  "Judge one agent answer against a reference. Returns
   {:verdict :score :rationale :judge-model}. Never throws."
  [tenant {:keys [reference response] :as item}]
  (let [model (judge-model tenant)]
    (cond
      (str/blank? (str reference))
      {:verdict "no-reference" :score nil :rationale "no reference answer authored" :judge-model model}
      (str/blank? (str response))
      {:verdict "no-response" :score nil :rationale "no agent response in latest sweep" :judge-model model}
      :else
      (try
        (let [resp (call-model tenant (build-messages item) model)
              content (get-in resp [:choices 0 :message :content])]
          (assoc (parse-verdict content) :judge-model model))
        (catch Exception e
          {:verdict "error" :score nil :rationale (str (.getMessage e)) :judge-model model})))))
