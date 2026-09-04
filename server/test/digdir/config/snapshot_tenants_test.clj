(ns digdir.config.snapshot-tenants-test
  "The product must not ship anybody's real tenant.

   The committed snapshot is generated from a running system, so the failure
   mode is not somebody typing a tenant in — it is somebody REGENERATING the
   export and committing whatever their instance happened to hold. That is why
   this asserts an INVARIANT rather than the absence of two known names: a
   future export would reintroduce a tenant nobody thought to add to a list.

   What may appear:
     `__global__` — the cross-tenant baseline, a product artefact
     `demo`       — the shipped demo tenant, if it is ever exported

   Everything else is somebody's deployment.

   Also asserted: no USER records. The snapshot shipped one — a named
   individual, with an email address and `admin-full` permissions — which in a
   repository about to be opened is a worse artefact than a tenant's Typesense
   host, and is not covered by \"do not ship the digdir tenant\" as worded."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private snapshot-path "../config/system-import.normalized.20260821.json")

(def ^:private product-tenants
  "Tenants that are product artefacts rather than somebody's deployment."
  #{"__global__" "demo"})

(defn- snapshot []
  (let [f (io/file snapshot-path)]
    (when (.exists f) (json/parse-string (slurp f) true))))

(defn- record-tenant
  "The tenant a record belongs to, whatever the key is called on that record
   type — nodes and node-values both carry one, under different namespaces."
  [m]
  ;; ⚠️ `name` STRIPS THE NAMESPACE: (name :config.node/tenant) is "tenant",
  ;; not "config.node/tenant". Matching on a "/tenant" suffix therefore finds
  ;; nothing, which is how the first version of this reader came back empty —
  ;; caught by the non-vacuity control below rather than by review, and exactly
  ;; the failure that control exists for.
  (some (fn [[k v]]
          (when (and (string? v) (= "tenant" (name k)))
            v))
        m))

(deftest snapshot-ships-no-real-tenant
  (let [snap (snapshot)
        data (:data snap)
        tenant-bearing (concat (:nodes data) (:node-values data))
        tenants (into #{} (keep record-tenant) tenant-bearing)]

    (testing "the instrument can answer at all"
      (is (some? snap) (str "snapshot not found at " snapshot-path))
      (is (<= 100 (count (:definitions data)))
          "the snapshot is not populated; every assertion below would be vacuous"))

    (testing "and it can SEE tenants — otherwise an empty snapshot passes forever"
      ;; The control #485 needed, for the same reason: an absence proves nothing
      ;; if the needle cannot hit. If `record-tenant` stopped matching — a
      ;; renamed key, a changed record shape — this set would be empty and the
      ;; assertion below would pass on a snapshot full of real tenants.
      (is (seq tenant-bearing)
          "no tenant-bearing records found; the reader is looking at the wrong keys")
      (is (seq tenants)
          (str "no tenant could be read from " (count tenant-bearing)
               " records, so 'no real tenant ships' is unfalsifiable here")))

    (testing "no tenant outside the product set"
      ;; The invariant, not a denylist: a future export reintroducing some other
      ;; tenant fails this without anyone having to have predicted the name.
      (let [foreign (sort (remove product-tenants tenants))]
        (is (empty? foreign)
            (str "the committed snapshot ships tenant(s) that are somebody's "
                 "deployment rather than a product artefact: " (pr-str foreign)))))

    (testing "and no user records"
      (is (empty? (:users data))
          (str "the snapshot ships " (count (:users data)) " user record(s). "
               "A named individual with permissions does not belong in the "
               "product, and this repository is going open source.")))))
