(ns digdir.skills.init-test
  "Tests for the builtin-agent → skill-graph cross-check (issue #71).

   Two levels. The derivation test checks the expected set is built from
   the agent definitions rather than hardcoded. The resolvability test is
   the actual gate: every graph any builtin agent names must be registered.

   That gate is only assertable because `builtin/docs-agent` and the three
   src-dev-only graphs it needs now live and load together in
   `digdir.agents.dev`. Before that, this assertion failed everywhere
   except a `bb dev` boot, which is why it was deliberately omitted when
   the derivation landed."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.agents.core :as agents-core]
            [digdir.skills.init :as init]))

(deftest expected-skill-graph-ids-derives-from-agent-definitions
  (testing "every builtin agent's declared graphs appear in the expected set"
    (let [expected (init/expected-skill-graph-ids)]
      (doseq [agent (agents-core/builtin-agent-definitions)]
        (testing (str "agent " (:id agent))
          (when-let [default (:default-skill-graph agent)]
            (is (contains? expected
                           (agents-core/normalize-skill-graph-id default))
                (str (:id agent) "'s :default-skill-graph should be expected")))
          (doseq [allowed (:allowed-skill-graphs agent)]
            (is (contains? expected
                           (agents-core/normalize-skill-graph-id allowed))
                (str (:id agent) "'s allowed graph " allowed
                     " should be expected")))))))

  (testing "the set is derived, not the old hardcoded three"
    ;; The previous implementation hardcoded exactly
    ;; #{:builtin/fact-checker :builtin/agent-rag-graph-bundled
    ;;   :builtin/agent-rag-graph-faithful}, which is why a fourth graph
    ;; going missing went unnoticed. Guard the property that made that
    ;; possible: the set must cover graphs beyond those three.
    (let [expected (init/expected-skill-graph-ids)
          hardcoded #{"builtin/fact-checker"
                      "builtin/agent-rag-graph-bundled"
                      "builtin/agent-rag-graph-faithful"}]
      (is (seq (remove hardcoded expected))
          "expected set must include graphs beyond the old hardcoded three")))

  (testing "ids are durable strings, never keywords"
    (is (every? string? (init/expected-skill-graph-ids))
        "registry ids are keywords and agent definitions are strings; the
         expected set must be normalized so the comparison is meaningful")))

(deftest every-agent-skill-graph-resolves
  (testing "no builtin agent names a skill graph that is not registered"
    ;; The gate for #71. It must be able to FAIL: verified by temporarily
    ;; adding a bogus id to a production agent, which turned this red
    ;; (missing ["builtin/BOGUS-GRAPH-DOES-NOT-EXIST"]) before it was removed.
    ;;
    ;; Holds on both classpaths, for different reasons:
    ;;   - production (no src-dev): docs-agent is absent, so its graphs are
    ;;     not expected;
    ;;   - dev/test (src-dev present): docs-agent is present, and
    ;;     digdir.agents.dev registers its three src-dev graphs as it loads.
    (let [{:keys [ok missing expected count]} (init/verify-skill-graphs)]
      (is ok
          (str "skill graphs named by builtin agents but not registered: "
               (pr-str missing)
               " (expected " expected ", registered " count ")")))))

(deftest every-registered-graph-resolves-its-skills
  (testing "no registered skill graph steps through a skill that is not registered"
    ;; The #71 gate one layer down. That one checks agents -> graphs; this one
    ;; checks graphs -> skills. Before #91 a graph could be registered while its
    ;; skills could not resolve, because requiring a skill namespace registers
    ;; it at load time and a second require after a registry reset is a no-op —
    ;; so the failure surfaced at invocation rather than at registration.
    ;;
    ;; It must be able to FAIL: verified by pointing a graph step at a
    ;; nonexistent skill id, which turned this red with that graph -> skill edge
    ;; named, before it was removed.
    (let [{:keys [ok missing checked]} (init/verify-graph-skills)]
      (is ok
          (str "registered graphs naming unregistered skills: "
               (pr-str missing)
               " (" checked " graphs checked)"))
      (is (pos? checked) "must actually have checked some graphs"))))
