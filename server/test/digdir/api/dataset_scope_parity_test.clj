(ns digdir.api.dataset-scope-parity-test
  "Both doors into dataset authorization, driven from ONE table (#464).

   There are two independent implementations of \"which dataset may this API key
   reach\":

   - `digdir.api.context/select-request-dataset-ref!` — every REST route.
   - `digdir.mcp.tools/pick-dataset-scope` — `/api/mcp` tools/call AND
     `/v1/chat/completions`, which share it.

   They diverged: the MCP one accepted an explicit tenant + dataset_config_key
   from caller-supplied arguments without checking the key's grant whenever the
   agent declared no `:allowed-dataset-scopes` — which all three shipped agents
   do. A key granted one dataset could name another and be given it.

   That is the third instance of one shape in this codebase — two call paths
   into one capability, corrected at one of them — so this is deliberately a
   PARITY guard rather than a second patch. Both paths are driven from
   `cases` below and asserted to agree, so neither can be changed alone.

   ⚠️ Asserting only that both REFUSE would be satisfied by a path that refuses
   everything, so `:granted` rows are asserted too. Both directions matter: the
   guard has to fail for an over-permissive path AND for an over-strict one."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.api.context :as api-ctx]
            [digdir.mcp.tools :as mcp-tools]))

(def ^:private cases
  "One table, both paths. `:verdict` is the security-relevant outcome both
   implementations must reach for the same inputs.

   Every row has a NON-EMPTY key grant on purpose: an empty grant is the one
   place the two paths still legitimately differ, and it is asserted separately
   below rather than smuggled in here."
  [{:label    "in-grant, agent unrestricted"
    :key      [{:tenant "acme" :dataset-config-key "a"}]
    :agent    []
    :request  {:tenant "acme" :dataset-config-key "a"}
    :verdict  :granted}

   {:label    "OUTSIDE grant, agent unrestricted — the #464 case"
    :key      [{:tenant "acme" :dataset-config-key "a"}]
    :agent    []
    :request  {:tenant "victim" :dataset-config-key "secret"}
    :verdict  :refused}

   {:label    "OUTSIDE grant but same tenant, agent unrestricted"
    :key      [{:tenant "acme" :dataset-config-key "a"}]
    :agent    []
    :request  {:tenant "acme" :dataset-config-key "b"}
    :verdict  :refused}

   {:label    "in-grant, several granted — per-call selection still works"
    :key      [{:tenant "acme" :dataset-config-key "a"}
               {:tenant "acme" :dataset-config-key "b"}]
    :agent    []
    :request  {:tenant "acme" :dataset-config-key "b"}
    :verdict  :granted}

   {:label    "in both grant and agent declaration"
    :key      [{:tenant "acme" :dataset-config-key "a"}]
    :agent    [{:tenant "acme" :dataset-config-key "a"}]
    :request  {:tenant "acme" :dataset-config-key "a"}
    :verdict  :granted}

   {:label    "declared by the agent but NOT granted to the key"
    :key      [{:tenant "acme" :dataset-config-key "a"}]
    :agent    [{:tenant "acme" :dataset-config-key "a"}
               {:tenant "acme" :dataset-config-key "b"}]
    :request  {:tenant "acme" :dataset-config-key "b"}
    :verdict  :refused}

   {:label    "granted to the key but NOT declared by the agent"
    :key      [{:tenant "acme" :dataset-config-key "a"}
               {:tenant "acme" :dataset-config-key "b"}]
    :agent    [{:tenant "acme" :dataset-config-key "a"}]
    :request  {:tenant "acme" :dataset-config-key "b"}
    :verdict  :refused}

   {:label    "outside both"
    :key      [{:tenant "acme" :dataset-config-key "a"}]
    :agent    [{:tenant "acme" :dataset-config-key "a"}]
    :request  {:tenant "victim" :dataset-config-key "secret"}
    :verdict  :refused}])

(defn- mcp-verdict
  "Drive the MCP / openai-compat path. `{:scope …}` is a grant, `{:error …}` a
   refusal."
  [{:keys [key agent request]}]
  (let [r (mcp-tools/pick-dataset-scope
            {:id "agent-under-test" :allowed-dataset-scopes agent}
            {:api-key/dataset-scopes key}
            {"tenant" (:tenant request)
             "dataset_config_key" (:dataset-config-key request)})]
    (if (:error r)
      {:verdict :refused :detail (get-in r [:error :code])}
      {:verdict :granted :detail (:scope r)})))

(defn- rest-verdict
  "Drive the REST path. Returns the ref on a grant and throws on a refusal."
  [{:keys [key agent request]}]
  (try
    {:verdict :granted
     :detail (api-ctx/select-request-dataset-ref!
               {:api-key/dataset-scopes key}
               {:tenant (:tenant request)
                :dataset-config-key (:dataset-config-key request)}
               {:allowed-dataset-scopes (seq agent)})}
    (catch clojure.lang.ExceptionInfo e
      {:verdict :refused :detail (:status (ex-data e))})))

(deftest both-paths-agree-on-every-case
  (doseq [{:keys [label verdict] :as case-row} cases]
    (testing (str "case: " label)
      (let [mcp (mcp-verdict case-row)
            rest* (rest-verdict case-row)]
        (is (= verdict (:verdict mcp))
            (str "MCP/openai-compat path (pick-dataset-scope) — " label))
        (is (= verdict (:verdict rest*))
            (str "REST path (select-request-dataset-ref!) — " label))
        (is (= (:verdict mcp) (:verdict rest*))
            (str "THE PATHS HAVE DIVERGED on: " label
                 " — MCP " (:verdict mcp) " " (pr-str (:detail mcp))
                 ", REST " (:verdict rest*) " " (pr-str (:detail rest*))))))))

(deftest no-path-grants-a-dataset-outside-the-keys-grant
  (testing "the invariant, stated once and independently of the table's shape"
    (doseq [{:keys [label key request] :as case-row} cases]
      (let [granted (set (map (juxt :tenant :dataset-config-key) key))
            asked ((juxt :tenant :dataset-config-key) request)]
        (when-not (contains? granted asked)
          (is (= :refused (:verdict (mcp-verdict case-row)))
              (str "MCP granted a dataset outside the key's scopes — " label))
          (is (= :refused (:verdict (rest-verdict case-row)))
              (str "REST granted a dataset outside the key's scopes — " label)))))))

(deftest known-divergence-a-key-with-no-scopes
  ;; NOT parity, and deliberately so. A key with NO dataset-scopes is
  ;; UNRESTRICTED on the MCP surface — its convention on every other axis
  ;; (`:agent-refs`, `:skill-graphs`, `:allowed-config-keys` are all
  ;; "empty means no restriction") — while REST answers 401.
  ;;
  ;; Pinned rather than fixed: closing it would silently revoke MCP access from
  ;; every key without scopes, including the documented `TENANT` /
  ;; `DATASET_CONFIG_KEY` single-tenant deployment. That is a policy decision
  ;; with real blast radius, recorded on #464 and not taken here.
  ;;
  ;; This test exists so the divergence is DECLARED. If someone closes it, this
  ;; fails and points at the decision instead of letting it land unremarked.
  (let [scopeless {:key [] :agent [] :request {:tenant "any" :dataset-config-key "thing"}}]
    (testing "MCP treats an empty grant as unrestricted"
      (is (= :granted (:verdict (mcp-verdict scopeless)))))
    (testing "REST treats an empty grant as no access"
      (is (= :refused (:verdict (rest-verdict scopeless)))))))
