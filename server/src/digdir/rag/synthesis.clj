(ns digdir.rag.synthesis
  "LLM-based synthesis and generation logic for RAG."
  (:require [digdir.llm.openai :as llm]
            [digdir.config.accessor :as cfg]
            [digdir.llm.client :as openai]
            [digdir.data.db :as db]))

(defn system-prompt-with-date
  "Generate a system prompt that includes the current date in Norwegian time"
  []
  (let [norway-tz (java.time.ZoneId/of "Europe/Oslo")
        now (java.time.ZonedDateTime/now norway-tz)
        formatter (java.time.format.DateTimeFormatter/ofPattern "d. MMMM yyyy" (java.util.Locale. "no" "NO"))
        norwegian-date (.format now formatter)]
    (str "You are a helpful assistant. The date this conversation was created at is " norwegian-date " (Norwegian time).")))

(defn rag-generate [_!dh-conn convo-id extract-search-queries full-prompt params]
  (let [start-time (System/currentTimeMillis)
        tenant (or (:tenant params) (:tenant (:dataset-ref params)))
        selected-model (or (:selected-model params)
                            (if (llm/use-azure-openai tenant)
                              (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                              (cfg/get {:tenant tenant} :services :azure-openai :model-name)))
        chat-response
        (if (llm/use-azure-openai tenant)
          (openai/create-chat-completion
           {:model selected-model
            :messages [{:role "system" :content (system-prompt-with-date)}
                       {:role "user" :content full-prompt}]
            :temperature 0.1}
           {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
            :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
            :impl :azure})
          (openai/create-chat-completion
           {:model selected-model
            :messages [{:role "system" :content (system-prompt-with-date)}
                       {:role "user" :content full-prompt}]
            :temperature 0.1
            :stream false}))
        end-time (System/currentTimeMillis)
        duration (- end-time start-time)
        _ (println (str "RAG query duration: " duration " ms"))
        assistant-reply (:content (:message (first (:choices chat-response))))
        english-answer (or assistant-reply "")
        translated-answer english-answer
        rag-success true]
    {:conversation-id convo-id
     :dataset-ref (:dataset-ref params)
     :agent-id (:agent-id params)
     :original_user_query (:original_user_query params)
     :english_user_query (:translated_user_query params)
     :user_query_language_name (:user_query_language_name params)
     :english_answer english-answer
     :translated_answer translated-answer
     :rag_success rag-success
     :search_queries (or (:searchQueries extract-search-queries) [])
     :relevant_urls []
     :prompts {:queryRelax (or (:promptRagQueryRelax params) "")
               :generate (or (:promptRagGenerate params) "")
               :fullPrompt full-prompt}}))

(defn simplify-convo-topic [params]
  (let [tenant (or (:tenant params) (:tenant (:dataset-ref params)))
        selected-model (or (:selected-model params)
                           (if (llm/use-azure-openai tenant)
                             (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                             (cfg/get {:tenant tenant} :services :azure-openai :model-name)))
        summary-response
        (if (llm/use-azure-openai tenant)
          (openai/create-chat-completion
           {:model selected-model
            :messages [{:role "system"
                        :content "Provide a 3 to 5 word summary of the user's query, use the same language as the user."}
                       {:role "user"
                        :content (str "<USER_QUERY>" (:original_user_query params) "</USER_QUERY>")}]
            :temperature 0.1
            :max_tokens 30}
           {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
            :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
            :impl :azure})
          (openai/create-chat-completion
           {:model selected-model
            :messages [{:role "system"
                        :content "Provide a 3 to 5 word summary of the user's query, use the same language as the user."}
                       {:role "user"
                        :content (str "<USER_QUERY>" (:original_user_query params) "</USER_QUERY>")}]
            :temperature 0.1
            :max_tokens 30}))
        summary (-> summary-response :choices first :message :content)]
    (db/rename-convo-topic (db/get-conn) (:conversation-id params) summary)))
