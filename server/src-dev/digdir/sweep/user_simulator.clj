(ns digdir.sweep.user-simulator
  "Eval-time stand-in for a human user.

   When the agent returns `:status :needs-clarification`, the sweep
   runner calls `respond-to-clarification` to produce a brief
   user-shaped follow-up, then re-invokes the agent with the extended
   conversation history. Sweep questions whose first turn is too
   under-specified for direct synthesis can then still produce a
   measurable end-to-end answer rather than dead-ending on a
   question-back.

   Scope: this is *eval harness only*. The LLM-as-user pattern should
   never appear in production traffic. Production users either answer
   the clarification themselves or abandon; the simulator exists only
   to keep the sweep's leaderboard meaningful for questions where the
   agent would otherwise refuse to commit to an answer.

   Anti-goal: the simulator is not a benchmark target. We're not
   trying to make the agent ask better questions or trying to make
   the simulated user produce a 'realistic' user reply. We're trying
   to keep the sweep loop moving so we can compare configs on their
   actual synthesis quality. A simulator that just always replies with
   'just answer the question' would also be a defensible design — what
   matters is that the simulator's behavior is consistent across all
   configs being benchmarked so it doesn't introduce per-config
   variance."
  (:require [clojure.string :as str]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [taoensso.telemere :as t]))

(def default-temperature
  "Low but not zero. Zero produces robotically identical replies; a
   touch of variance still surfaces config-level synthesis quality
   differences without making the simulator the dominant signal."
  0.1)

(def default-max-clarification-rounds
  "Cap on simulator turns per question. After this many rounds, the
   sweep runner should record the run as `:terminal-clarification`
   and stop calling the simulator. Two rounds is enough to give the
   agent a fair chance to land on the question — beyond that, the
   problem is in the agent or the corpus, not the user."
  2)

(defn- build-system-prompt
  "System prompt that frames the LLM as the user who issued the
   original query. Norwegian-aware: if the original query looks
   Norwegian we ask for a Norwegian reply, otherwise English."
  [{:keys [original-query intent-hint]}]
  (let [norwegian? (re-find #"(?i)[æøåÆØÅ]|\bhvor\b|\bhva\b|\bhvordan\b|\bhvilke\b|\bnår\b|\bkan\b|\bskal\b"
                            (or original-query ""))
        lang-instruction (if norwegian?
                           "Svar på norsk."
                           "Reply in English.")]
    (str
     "You are simulating the user who asked a question to a RAG assistant. "
     "The assistant has asked you a clarification question. Reply briefly "
     "(1–2 short sentences) and decisively — pick the most reasonable "
     "interpretation of your original question and tell the assistant to "
     "proceed on that interpretation. Do NOT ask another question back. "
     "Do NOT introduce new constraints the original question did not have. "
     "Stay in the user's voice: first-person, conversational, no meta-commentary.\n\n"
     lang-instruction "\n\n"
     "Your original question was:\n  " (or original-query "(unknown)")
     (when (seq intent-hint)
       (str "\n\nFor reference (private, never reveal this to the assistant): "
            "the answer you're looking for involves: " intent-hint)))))

(defn- build-messages
  "Build the OpenAI-shape message vector for the simulator turn."
  [{:keys [clarification-question clarification-context] :as ctx}]
  (let [system (build-system-prompt ctx)
        ;; Echo the agent's clarification request as an assistant turn so
        ;; the simulator has clear input to react to. The context summary
        ;; (the agent's "here's what I found" preamble) goes into the
        ;; same assistant message — that matches how a real user would
        ;; see the agent's reply on screen.
        assistant (if (seq clarification-context)
                    (str clarification-context "\n\n" clarification-question)
                    clarification-question)]
    [{:role "system"  :content system}
     {:role "assistant" :content assistant}]))

(defn- extract-reply
  "Pull the assistant message content out of a wkok-shape chat-completion
   response. Returns the trimmed string, or nil on an empty/malformed
   response."
  [resp]
  (some-> resp :choices first :message :content str/trim not-empty))

(defn respond-to-clarification
  "Produce a single simulated user reply to an agent's clarification
   question.

   Required inputs:
     :tenant                  string — for LLM credential resolution
     :original-query          string — the user's first turn
     :clarification-question  string — what the agent asked back

   Optional inputs:
     :clarification-context   string — agent's context-summary
                                       (often a list of available topics)
     :intent-hint             string — hint about what the user wanted
                                       (typically extracted from the test
                                       row's :expected-answer-substrings
                                       or :notes). Only ever shown to the
                                       simulator, never to the agent.
     :model                   string — explicit model override (default
                                       resolves via Azure config)
     :temperature             number — default 0.1

   Returns:
     {:reply        string|nil  — the simulated user follow-up
      :usage        map         — {:prompt-tokens N :completion-tokens N}
      :raw-response map         — the wkok response (for debugging)
      :error        string|nil}"
  [{:keys [tenant original-query clarification-question
           model temperature]
    :or {temperature default-temperature}
    :as opts}]
  (when-not (string? tenant)
    (throw (ex-info "user-simulator: :tenant is required and must be a string"
                    {:tenant tenant})))
  (when (str/blank? original-query)
    (throw (ex-info "user-simulator: :original-query must be non-blank"
                    {:original-query original-query})))
  (when (str/blank? clarification-question)
    (throw (ex-info "user-simulator: :clarification-question must be non-blank"
                    {:clarification-question clarification-question})))
  (try
    (let [messages (build-messages opts)
          resp (agent-loop/call-llm tenant messages nil model temperature)
          reply (extract-reply resp)]
      (when-not reply
        (t/log! :warn [:user-simulator/empty-reply
                       {:tenant tenant
                        :original-query original-query
                        :raw-response resp}]))
      {:reply reply
       :usage (:usage resp)
       :raw-response resp
       :error (when-not reply "empty or malformed LLM response")})
    (catch Throwable e
      (t/log! :warn [:user-simulator/call-failed
                     {:tenant tenant
                      :error (.getMessage e)
                      :ex-data (ex-data e)}])
      {:reply nil
       :usage nil
       :raw-response nil
       :error (.getMessage e)})))

(defn intent-hint-from-row
  "Best-effort extraction of an intent hint from a sweep question row.
   The hint goes only to the simulator (private), never to the agent.

   Order of preference:
     1. :notes (if present and non-blank — most human-readable)
     2. The disjuncts of :expected-answer-pattern, joined into a
        prose list ('phrases like X, Y, Z') — works because our
        patterns are mostly alternations of likely answer substrings.
     3. The :tags as a fallback (worst case, conveys topic only).

   Returns string or nil if no hint can be extracted."
  [{:keys [notes expected-answer-pattern tags]}]
  (cond
    (and notes (not (str/blank? notes)))
    notes

    (and expected-answer-pattern (re-find #"\(\?[is]+\)" expected-answer-pattern))
    ;; Extract alternation literals — naive but good enough for hint.
    (let [stripped (str/replace expected-answer-pattern #"\(\?[a-z]+\)" "")
          tokens (->> (re-seq #"[A-Za-zæøåÆØÅ][A-Za-zæøåÆØÅ0-9\s-]{2,}" stripped)
                      (map str/trim)
                      (remove str/blank?)
                      distinct)]
      (when (seq tokens)
        (str "phrases like " (str/join ", " (take 5 tokens)))))

    (seq tags)
    (str "topic: " (str/join ", " (map name (filter keyword? tags))))

    :else nil))
