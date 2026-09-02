(ns digdir.llm.model-params
  "Per-model-family request-parameter normalization for OpenAI-compatible
   chat completions.

   The GPT-5 family diverges from the GPT-4 request shape in two ways, and both
   are hard 400s rather than ignored parameters:

   - `max_tokens` is REJECTED; the cap must be sent as `max_completion_tokens`.
   - `temperature` is REJECTED unless it is the default 1 — the reasoning-class
     models don't expose sampling temperature at all.

   Call sites shouldn't have to know which family they're aimed at: the model is
   a runtime config value (an Azure *deployment name*, on the Azure path), so the
   same call site hits either family depending on tenant config. The mapping
   therefore lives here and is applied at the single chokepoint every runtime
   call funnels through — `digdir.llm.client/create-chat-completion`.

   Non-GPT-5 models are passed through untouched.")

(def ^:private gpt-5-model-re
  "Matches GPT-5-family model / Azure-deployment names: `gpt-5`, `gpt5`,
   `gpt-5.5`, `gpt-5.4-mini`, and prefixed deployment names like
   `azure-gpt-5.5`. Deliberately does NOT match `gpt-4o`, nor a hypothetical
   `gpt-50` (the lookahead rules out a longer version number)."
  #"(?i)\bgpt-?5(?![0-9])")

(defn gpt-5-family?
  "True when `model` names a GPT-5-family model or Azure deployment.

   The name is the only signal available: on the Azure path `:model` carries the
   deployment name, and deployment names are free-form. A deployment whose name
   doesn't carry the model family can't be detected here — it keeps getting
   GPT-4-shaped params, which is exactly today's behaviour."
  [model]
  (boolean (and model (re-find gpt-5-model-re (str model)))))

(defn normalize-request
  "Normalize chat-completion request `params` for the model family they target.

   For GPT-5-family models:
     - `:max_tokens` is renamed to `:max_completion_tokens`. A caller-supplied
       `:max_completion_tokens` wins; the rejected `:max_tokens` is dropped.
     - `:temperature` is dropped unless it is exactly 1, since any other value
       is a 400. Dropping (rather than clamping) leaves the model on its own
       default and matches what `digdir.sweep.judge/call-model` already does by
       hand for the judge.

   Every other model — including local OpenAI-compatible ones — is returned
   unchanged."
  [params]
  (if-not (gpt-5-family? (:model params))
    params
    (let [temperature (:temperature params)
          drop-temperature? (and (contains? params :temperature)
                                 (not (and (number? temperature)
                                           (== 1 temperature))))]
      (cond-> params
        (contains? params :max_tokens)
        (-> (update :max_completion_tokens #(or % (:max_tokens params)))
            (dissoc :max_tokens))

        drop-temperature?
        (dissoc :temperature)))))
