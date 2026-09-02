(ns digdir.sweep.invoke
  "Closed-loop wrapper around `digdir.skills.invoke/invoke-rag` that
   uses `digdir.sweep.user-simulator` to answer any
   `:needs-clarification` results from the agent, bounded by a
   max-rounds cap. The sweep runner calls this in place of invoke-rag
   directly so the leaderboard captures synthesis quality rather than
   ratio-of-questions-the-agent-asked-back.

   Returns the final invoke-rag result map with three extra keys:

     :clarification-rounds        N    — rounds the simulator was used
     :clarification-history       vec  — per-round {:question :reply [:error]}
     :terminal-clarification?     bool — true iff we exhausted the cap
                                          without resolving to :complete"
  (:require [clojure.string :as str]
            [digdir.skills.invoke :as invoke]
            [digdir.sweep.user-simulator :as sim]
            [taoensso.telemere :as t]))

(defn invoke-with-clarification-loop
  "Drive an invoke-rag call that may need clarification(s).

   Accepts the same arg map as `invoke-rag`, plus:

     :intent-hint                  string — passed to the simulator
                                            only; never to the agent
     :max-clarification-rounds     int    — default 2

   On each clarification turn we:
     1. Call the simulator with the original query + the agent's question
     2. Append [original-or-prior-user-turn, agent-clarification] to the
        conversation history
     3. Re-invoke with the simulator's reply as :user-query

   The original :user-query is preserved as the simulator's
   `:original-query` across all rounds so the simulator's intent stays
   anchored on the user's actual ask, not on its own most-recent reply."
  [{:keys [user-query conversation-history execution-scope intent-hint
           max-clarification-rounds]
    :or {max-clarification-rounds sim/default-max-clarification-rounds}
    :as args}]
  (when-not (string? user-query)
    (throw (ex-info "invoke-with-clarification-loop: :user-query is required and must be a string"
                    {:user-query user-query})))
  (when-not (map? execution-scope)
    (throw (ex-info "invoke-with-clarification-loop: :execution-scope is required"
                    {:execution-scope execution-scope})))
  (let [tenant (:tenant execution-scope)]
    (when-not (string? tenant)
      (throw (ex-info "invoke-with-clarification-loop: :execution-scope must carry a string :tenant"
                      {:execution-scope execution-scope})))
    (loop [current-query     user-query
           history           (or conversation-history [])
           rounds-used       0
           clar-history      []]
      (let [args*  (-> args
                       (dissoc :intent-hint :max-clarification-rounds)
                       (assoc :user-query current-query
                              :conversation-history history))
            result (invoke/invoke-rag args*)
            status (:status result)
            clar   (:clarification result)]
        (cond
          ;; Terminal happy / error path — no clarification or :status :error.
          (not= status :needs-clarification)
          (assoc result
                 :clarification-rounds rounds-used
                 :clarification-history clar-history
                 :terminal-clarification? false)

          ;; Hit the cap — return the clarification as-is, marked terminal.
          (>= rounds-used max-clarification-rounds)
          (do
            (t/log! :info [:sweep.invoke/clarification-cap-hit
                           {:tenant tenant
                            :user-query user-query
                            :rounds-used rounds-used}])
            (assoc result
                   :clarification-rounds rounds-used
                   :clarification-history clar-history
                   :terminal-clarification? true))

          ;; Simulate a user reply and continue.
          :else
          (let [sim-result (sim/respond-to-clarification
                             {:tenant tenant
                              :original-query user-query
                              :clarification-question (:question clar)
                              :clarification-context (:context-summary clar)
                              :intent-hint intent-hint
                              :model (:model args)})
                reply (:reply sim-result)
                next-history (conj history
                                   {:role "user"      :content current-query}
                                   {:role "assistant" :content (:question clar)})
                next-clar-history (conj clar-history
                                        (cond-> {:question (:question clar)
                                                 :reply reply
                                                 :usage (:usage sim-result)}
                                          (:error sim-result)
                                          (assoc :error (:error sim-result))))]
            (if (str/blank? reply)
              ;; Simulator gave us nothing usable — abort and mark terminal.
              (do
                (t/log! :warn [:sweep.invoke/simulator-blank-reply
                               {:tenant tenant
                                :rounds-used rounds-used
                                :sim-error (:error sim-result)}])
                (assoc result
                       :clarification-rounds rounds-used
                       :clarification-history next-clar-history
                       :terminal-clarification? true))
              (recur reply
                     next-history
                     (inc rounds-used)
                     next-clar-history))))))))
