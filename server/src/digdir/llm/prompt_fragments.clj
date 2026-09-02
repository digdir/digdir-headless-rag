(ns digdir.llm.prompt-fragments
  "Reusable prompt fragments for LLM-backed skills.

   Centralised here so we can tune the wording once and have every skill inherit
   the improvement. Each fragment is a plain string suitable for embedding in
   system or user prompts.")

(def same-language-rule
  "Language-preservation rule. Applies to any skill whose output (answer,
   reasoning, queries, clarification question) will be read by the user.

   Wording covers both the direction (respond in the SAME language) and the
   common failure mode (cross-language mixing like 'altinn date'). Skills that
   produce JSON-only outputs do not need this rule."
  (str
   "Always respond in the SAME LANGUAGE as the user's question. "
   "If the question is Norwegian, answer in Norwegian. "
   "If English, answer in English. "
   "Do not mix languages (e.g. never produce phrases like \"altinn date\"). "
   "Preserve source-terminology verbatim even when writing in the other language."))
