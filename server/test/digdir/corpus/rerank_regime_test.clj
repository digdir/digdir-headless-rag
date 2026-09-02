(ns digdir.corpus.rerank-regime-test
  "The demo tenant must run the reranker regime its corpus numbers were measured
   under (#447).

   THE INVARIANT, and it is not the literal 2000:

     A reranker budget must be able to hold a WHOLE CHUNK of the corpus it
     reranks. Below that, the reranker scores a window of a chunk and its
     judgement of that chunk is uninformed — silently, with nothing logged.

   Why this needs a guard. #458 made both call paths into
   `digdir.rag.rerank/rerank-chunks` default to windowing-on at 2000, and #463
   raised the platform seed from 400 to 4000 so a tenant created the normal way
   is no longer in a degraded regime. **Config still beats the skill default**,
   so what a dataset resolves to is a configuration question rather than a code
   one, and the demo's value is pinned to a measurement rather than inherited.

   Measured on the #447 corpus, answer-visible recall@10 (the share of questions
   whose answer text is still present in what the reranker actually receives):

     budget   full articles   split-at-850
       400        33.3%           32.1%
      1000        42.2%           39.3%
      2000        46.2%           39.3%
      4000        47.8%           39.3%
      8000        48.0%           39.3%

   THE DEMO OVERRIDE IS KEPT EVEN THOUGH IT NOW EQUALS THE PLATFORM SEED, and
   that is a decision rather than an oversight. The two are the same number for
   different reasons: the demo's is DERIVED FROM A MEASUREMENT OF ITS OWN CORPUS
   and the published recall figures above are only true at that value, while the
   platform's is a policy choice for every tenant. Collapsing them would make the
   demo silently follow a future platform change it was never measured under, and
   the numbers above would become wrong with nothing failing.

   The duplication objection — two definitions of one number — applies to values
   that are definitionally the same thing. These are not: one answers \"what did
   we measure at\", the other \"what should every tenant get\"."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.setup.demo-dataset :as demo]
            [digdir.setup.workflow :as workflow]))

(def ^:private corpus-chunk-ceiling
  "Largest chunk the demo corpus is built to emit, in characters.

   The split-at-850 arm caps chunks there by construction. The full-article arm
   has no cap at all, so this is a LOWER BOUND on what the reranker must hold —
   an unsplit corpus needs more, never less."
  850)

(defn budget-covers-chunk?
  "Can a reranker budget of `budget` hold a whole `ceiling`-character chunk,
   allowing for the title/metadata prefix prepended to every candidate?

   Prefix allowance is the measured maximum over the 352-article gold set (137
   characters; mean 44, median 39, p95 75), not a percentile — overflow costs
   silent mid-chunk loss while over-allowance costs a few characters."
  [budget ceiling]
  (>= (- budget 137) ceiling))

(deftest budget-covers-chunk-behaves
  (testing "the predicate is not vacuous in either direction"
    (is (budget-covers-chunk? 2000 corpus-chunk-ceiling)
        "the measured regime must pass, or the guard below proves nothing")
    (is (not (budget-covers-chunk? 400 corpus-chunk-ceiling))
        "the seeded platform default must fail, or the guard cannot fire")
    (is (not (budget-covers-chunk? (+ 850 100) corpus-chunk-ceiling))
        "a budget that forgets the prefix does not cover the chunk")))

(deftest platform-default-covers-a-whole-chunk
  ;; Pins #463. The seed was 400 — below the chunk length of essentially every
  ;; corpus we run, and measured against production, below 95.9% of the 713,923
  ;; chunks in the KUDOS collection. This fails if it is reverted.
  ;;
  ;; This test replaced one asserting the OPPOSITE. That one existed while the
  ;; seed was 400 and said so in its failure message: raising the seed is an
  ;; improvement, and the guard's job was to make sure nobody raised it without
  ;; re-checking the demo override. That question has now been asked and
  ;; answered — see the namespace docstring — so the assertion follows the
  ;; premise rather than outliving it.
  (let [seeded (:rerank-rag-max-chunk-length workflow/default-runtime-bootstrap-values)]
    (testing "the seed is actually readable — a nil would pass everything below"
      (is (number? seeded)
          "could not read :rerank-rag-max-chunk-length from the bootstrap values"))

    (testing "a tenant created the normal way can hold a whole chunk"
      (is (budget-covers-chunk? seeded corpus-chunk-ceiling)
          (str "the platform seeds " seeded ", which cannot hold a "
               corpus-chunk-ceiling "-character chunk plus its title prefix. "
               "Every tenant created the normal way would rerank on a window of "
               "each chunk, silently. This is #463 and it was fixed — if this "
               "fails, it has been reverted.")))))

(deftest the-demo-pins-its-own-measured-budget
  ;; ENFORCEMENT. Reads the value the shipped dataset actually carries, so the
  ;; dataset and this guard cannot drift apart.
  (let [budget demo/rerank-max-chunk-length]
    (testing "the shipped dataset's budget covers a whole chunk"
      (is (budget-covers-chunk? budget corpus-chunk-ceiling)
          (str "the demo dataset ships a reranker budget of " budget
               ", which cannot hold a " corpus-chunk-ceiling "-character chunk "
               "plus its title prefix.")))

    (testing "the dataset carries the budget EXPLICITLY rather than inheriting it"
      ;; The whole point of keeping the override now that it equals the platform
      ;; seed. An inherited value would follow a future platform change that this
      ;; corpus was never measured under, and the recall table in the namespace
      ;; docstring would quietly stop being true.
      (is (contains? demo/runtime-values :rerank-rag-max-chunk-length)
          (str "the demo dataset no longer pins its reranker budget and would "
               "inherit whatever the platform seeds. Its published recall figures "
               "were measured at " budget " and are only true at that value."))
      (is (= budget (:rerank-rag-max-chunk-length demo/runtime-values))
          "the demo runtime overrides do not carry the measured budget"))

    (testing "windowing must be on for the budget to mean what was measured"
      ;; Without it the budget is spent by head+tail truncation, which drops the
      ;; MIDDLE of a chunk — measured at 52% answer loss on this corpus. Both
      ;; call paths default it on since #458; nothing here overrides it off.
      (is (nil? (:rerank-windowing demo/runtime-values))
          "the demo must not override windowing off — the measured numbers assume it on"))))
