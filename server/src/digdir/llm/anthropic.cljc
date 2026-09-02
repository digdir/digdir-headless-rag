(ns digdir.llm.anthropic
  #?(:clj (:require [clj-http.client :as http]
                    [digdir.secrets :as secrets]
                    [taoensso.telemere :as t])))

#?(:clj
   (defn clean-http-response
     "Remove non-serializable fields from HTTP response for safe storage/logging"
     [resp]
     (dissoc resp :http-client :orig-content-encoding :trace-redirects :streaming? :repeatable? :protocol-version)))

#?(:clj
   (defn create-chat-completion
     "Create a chat completion using Anthropic's API.
      Options map can contain:
      - :model (string) - Model to use
      - :temperature (double) - Temperature setting
      - :max_tokens (int) - Maximum tokens in response
      - :messages (vector) - Messages in Anthropic format"
     [options]
     (let [{:keys [model temperature max_tokens messages tools tool_choice]
            :or {model "claude-sonnet-4-20250514"
                 temperature 0.0
                 max_tokens 4096}} options
           start-time (System/currentTimeMillis)
           request-size (count (str options))
           resp (http/post "https://api.anthropic.com/v1/messages"
                           {:headers {;; Was `(or (System/getenv "ANTHROPIC_API_KEY") "Not set")` — a
                                      ;; missing key was sent to Anthropic as the literal
                                      ;; string "Not set", so a configuration error arrived
                                      ;; as an authentication failure at the provider (#22).
                                      "x-api-key" (secrets/get! :anthropic-api-key)
                                      "anthropic-version" "2023-06-01"
                                      "anthropic-beta" "prompt-caching-2024-07-31"
                                      "content-type" "application/json"
                                      "Accept-Charset" "utf-8"}
                            :throw-exceptions false
                            :form-params (cond-> {:model model
                                                  :temperature temperature
                                                  :max_tokens max_tokens
                                                  :messages messages}
                                           tools (assoc :tools tools)
                                           tool_choice (assoc :tool_choice tool_choice))
                            :content-type :json
                            :as :json})]
       (case (:status resp)
         200 (let [response-body (:body resp)
                   content (:content response-body)
                   duration (- (System/currentTimeMillis) start-time)
                   response-size (count (str content))
                   usage (:usage response-body)]
               (t/event! :anthropic/call-summary {:data {:request-size request-size
                                                         :response-size response-size
                                                         :duration-ms duration
                                                         :tokens-used (select-keys usage [:input_tokens :output_tokens])}})
               response-body)
         429 (let [retry-after (get-in resp [:headers "retry-after"])
                   sleep (if retry-after (parse-long retry-after) 60)]
               (t/event! :anthropic/ratelimit {:data {:retry-after sleep}})
               (Thread/sleep (* 1000 sleep))
               (recur options))
         (do
           (t/event! :anthropic/api-error {:data {:status (:status resp)
                                                  :body (:body resp)
                                                  :headers (select-keys (:headers resp) ["content-type" "x-ratelimit-remaining"])
                                                  :request-options (select-keys options [:model :temperature :max_tokens])}})
           (throw (ex-info "Anthropic API call failed" {:response (clean-http-response resp)
                                                        :status (:status resp)
                                                        :error-body (:body resp)})))))))