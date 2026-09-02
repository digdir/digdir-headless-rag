(ns digdir.build.prod-boot-test
  "Guards that the production entrypoint initialises the skill system at boot.

   It did not, and nothing noticed. `src-prod/prod.cljc` called
   `e2e-seed/maybe-seed!` and `server/start-server!` and never touched the
   skill system, while the request path reached
   `digdir.skills.api/initialize!` — at the time a DIFFERENT function from
   `digdir.skills.init/initialize!` that registered the builtin graphs only.
   (#91 has since made it delegate to the same initialiser, so that split is
   gone. The boot-time gap this test guards is not.)

   Measured on a booted production uberjar: ONE graph registered instead of
   ten — only `:docs/enrich-one-chunk`, which survives because it
   self-registers on namespace load. Not even `:builtin/fact-checker` was
   present until the first request initialised lazily. The boot cross-check
   added in #71 never fired in production at all.

   Why no existing guard caught it: #30's boundary test and #94's harness
   test both ask what production *can reach*. This asks what production
   *actually does at boot* — reachability was never the problem, the graphs
   were on the classpath and correct. Three arcs (#71, #89, #94) verified
   the classpath and none verified the running registry, because every probe
   called `ensure-initialized!` by hand before looking.

   The assertions were shaped to survive #91 making `skills.api/initialize!`
   delegate, and #91 has now landed. The shape held: it needed one symbol
   added and this rationale rewritten, not a redesigned test. That is what
   checking the INVARIANT — prod boots with a full registry — rather than the
   mechanism buys."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [digdir.skills.init :as skills-init]
            [digdir.skills.templates.core :as templates-core]))

(def ^:private full-registry-initialisers
  "Functions known to leave the registry fully populated.

   `digdir.skills.api/initialize!` IS one of them as of #91 (PR #110). It no
   longer registers a smaller set of its own — it delegates to
   `digdir.skills.init/ensure-initialized!`, the one door — so prod calling
   either namespace's initialiser satisfies this guard.

   This comment previously said the opposite, and said it emphatically. The
   reversal is the point: the guard checks that prod boots with a full
   registry, not which function provides it. If some future change gives an
   initialiser a smaller registry again, remove it from this set in the same
   PR rather than loosening the assertion.

   Both fully-qualified and alias forms are listed because the check reads
   call sites textually, and prod.cljc calls through an alias."
  '#{digdir.skills.init/ensure-initialized!
     digdir.skills.init/initialize!
     skills-init/ensure-initialized!
     skills-init/initialize!
     digdir.skills.api/initialize!
     skills-api/initialize!})

(defn- read-all-forms [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (binding [*read-eval* false]
      (doall (take-while some?
                         (repeatedly #(read {:read-cond :allow :eof nil} r)))))))

(defn- called-symbols
  "Every symbol in function-call position anywhere in `forms`."
  [forms]
  (let [out (volatile! #{})]
    (letfn [(walk [x]
              (when (and (seq? x) (symbol? (first x)))
                (vswap! out conj (first x)))
              (when (coll? x) (run! walk x)))]
      (run! walk forms))
    @out))

(deftest prod-entrypoint-initialises-the-skill-system
  (testing "src-prod/prod.cljc calls a full-registry initialiser at boot"
    (let [f (io/file "src-prod/prod.cljc")
          _ (is (.exists f) "src-prod/prod.cljc is missing")
          called (called-symbols (read-all-forms f))
          hits (filter full-registry-initialisers called)]
      (is (seq hits)
          (str "prod.cljc does not initialise the skill system at boot.\n\n"
               "Without it production registers the builtin graphs only — the "
               "promoted docs/* graphs are on the classpath but absent from "
               "the live registry, and the #71 boot cross-check never fires.\n\n"
               "Expected a call to one of: "
               (pr-str full-registry-initialisers))))))

(deftest the-initialiser-prod-calls-registers-the-promoted-graph
  (testing "the full initialiser really does register the promoted enrichment graph"
    ;; Pins WHY the static check above matters, so it cannot become a
    ;; cargo-cult assertion about a call that no longer does anything.
    (skills-init/ensure-initialized!)
    (let [ids (set (templates-core/list-skill-graph-ids))]
      (is (contains? ids :docs/enrich-one-chunk)
          (str "the initialiser prod.cljc calls did not register "
               ":docs/enrich-one-chunk. Registered: " (pr-str (sort ids))))
      (is (< 5 (count ids))
          (str "registry has " (count ids) " graphs — the builtin-only count is "
               "5, so the demo/promoted registrations are not running")))))
