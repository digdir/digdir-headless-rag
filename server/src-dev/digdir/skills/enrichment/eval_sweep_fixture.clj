(ns digdir.skills.enrichment.eval-sweep-fixture
  "Installs the sweep question fixture as `eval-sweep`'s regression-question
   source.

   Slice 2b of #82 (#94) inverted a dependency. `digdir.skills.enrichment.eval-sweep`
   used to call `digdir.sweep.questions/load-questions!` directly, which put a
   research harness namespace — and a file under `test/fixtures` — on the
   critical path of a production skill. It now exposes an empty seam instead,
   and this namespace fills it.

   That leaves exactly one file naming the fixture, and it is this one, in
   src-dev where the fixture actually exists. A production build simply has no
   source installed and callers pass `:regression-questions` as data.

   Requiring this namespace has the side effect; that matches how the
   enrichment skills register themselves at ns-load."
  (:require [digdir.skills.enrichment.eval-sweep :as eval-sweep]
            [digdir.sweep.questions :as questions]))

(defn install!
  "Point eval-sweep's regression-question lookup at the sweep fixture.

   The seam's contract is (fn [ids] -> seq of question maps); eval-sweep does
   the id filtering and raises on ids that resolve to nothing, so returning
   the whole set is correct and keeps the fixture's own loading rules in one
   place."
  []
  (reset! eval-sweep/regression-question-source
          (fn [_ids] (questions/load-questions!))))

(install!)
