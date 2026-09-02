(ns digdir.ui.routing-test
  "Console route resolution, and the legacy-segment alias from #171.

   The tab that renders `SkillsUI` carried three names for one thing: `:id
   :skills`, `:segment \"skill-graphs\"`, and a visible label \"Skill Graphs\".
   #171 made them agree on `skills`.

   NOT `modes`. That is the wire vocabulary for the skill-graph *grant*
   (#122/#164), and this panel is broader — SkillsUI holds Skill Graphs,
   Skills and Tools — so `modes` would name it more narrowly than it is.

   WHY THE LEGACY TEST IS THE LOAD-BEARING ONE. `resolve-tab-from-segment`
   falls back to the group default when a segment does not match, so before
   the alias an old `/config/skill-graphs` bookmark resolved to index 0 —
   Config. Not an error, not a 404: SILENTLY THE WRONG TAB. A rename without
   the alias would have looked clean and quietly moved every existing
   bookmark to a different screen."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.ui.routing :as routing]))

(defn- tab-index [id]
  (->> (get-in routing/route-config [:config :tabs])
       (keep-indexed (fn [i t] (when (= id (:id t)) i)))
       first))

(deftest every-config-tab-id-matches-its-segment
  (testing "the convention this file exists to restore"
    ;; The defect in #171 was one tab disagreeing with itself. Asserting the
    ;; convention across ALL tabs means the next divergence fails here rather
    ;; than being noticed by an operator reading a URL.
    (let [mismatched (for [{:keys [id segment]} (get-in routing/route-config [:config :tabs])
                           :when (not= (name id) segment)]
                       [id segment])]
      (is (empty? mismatched)
          (str "these config tabs carry two different names for one tab; "
               ":id and :segment should agree:\n"
               (pr-str (vec mismatched)))))))

(deftest skills-tab-resolves-from-its-current-segment
  (testing "the new URL reaches the skills tab"
    (is (= (tab-index :skills)
           (routing/resolve-tab-from-segment :config "skills")))))

(deftest skills-tab-still-resolves-from-the-legacy-segment
  (testing "an existing /config/skill-graphs bookmark still lands on the same tab"
    ;; Without the alias this returns 0 (Config) — a wrong tab, not an error.
    (is (= (tab-index :skills)
           (routing/resolve-tab-from-segment :config "skill-graphs"))
        "the pre-#171 URL must not silently land on the group default")))

(deftest the-legacy-alias-is-not-what-the-url-becomes
  (testing "resolving forward still produces the new segment"
    ;; Guards the other direction: an alias that leaked into link generation
    ;; would keep minting the abandoned word.
    (is (= "skills" (routing/resolve-segment-from-tab :config (tab-index :skills))))))

(deftest an-unknown-segment-still-falls-back-to-the-group-default
  (testing "the alias did not break the fallback it sits next to"
    (is (= 0 (routing/resolve-tab-from-segment :config "no-such-tab")))
    (is (= 0 (routing/resolve-tab-from-segment :config nil)))))

(deftest legacy-aliases-do-not-collide-with-live-segments
  (testing "no tab's legacy alias is another tab's real segment"
    ;; If it were, the alias would hijack a working URL — and the hijack would
    ;; be invisible, because both resolve to a real tab.
    (let [tabs (mapcat :tabs (vals (select-keys routing/route-config [:main :config :import])))
          live (set (keep :segment tabs))
          aliases (mapcat #(seq (:legacy-segments %)) tabs)
          collisions (filter live aliases)]
      (is (empty? collisions)
          (str "these legacy aliases shadow a live segment: " (pr-str (vec collisions)))))))
