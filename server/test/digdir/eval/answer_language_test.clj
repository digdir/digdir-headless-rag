(ns digdir.eval.answer-language-test
  "#289: nothing in the eval path checked what language an answer was in, so a
   fluent, correct, well-cited answer in the WRONG language would score on its
   merits against the reference.

   The tests that matter here are the ones that would catch a detector which
   is quietly useless:

     - BOTH DIRECTIONS. A detector that answers :nb for everything is
       indistinguishable from a corpus that is entirely Norwegian, which this
       one very nearly is. English text must come back :en.
     - IT FIRES. A Norwegian question with an English answer must be
       :unexpected, on an answer written wrong on purpose.
     - IT ABSTAINS. Text it cannot call must be :undetected, not a guess, or
       every archived run reads as failing."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.eval.answer-language :as lang]))

;; Real text. The Norwegian is a golden question and a real sweep answer; the
;; English is a golden question and repository prose. Verbatim, because
;; paraphrasing the shape is how a language test ends up testing my own idiom.
(def ^:private nb-question
  "Hvordan kommer jeg i gang med Altinn Broker under overgangen til Altinn 3?")

(def ^:private nb-answer
  (str "Ifølge den endelige tilstandsmaskinen for Altinn Formidling kan en "
       "filoverføring gå gjennom følgende tilstander fra den startes til filen "
       "eventuelt er slettet. Dette gjelder alle overføringer som er initiert "
       "av en avsender, og det er ikke mulig å hoppe over noen av stegene."))

(def ^:private en-question
  "How do I set up options for a dropdown component from a code list in Altinn?")

(def ^:private en-answer
  (str "In Dialogporten, a dialog being seen means that the details of a "
       "particular dialog ID have been requested from the API. It does not "
       "mean that the content has been read by a person, and it is not the "
       "same as the dialog having been opened in the portal."))

;; ---------------------------------------------------------------------------
;; The detector, in both directions
;; ---------------------------------------------------------------------------

(deftest detects-norwegian
  (is (= :nb (lang/detect nb-question)))
  (is (= :nb (lang/detect nb-answer))))

(deftest detects-english
  ;; The direction that a Norwegian-only corpus cannot validate. Without this,
  ;; a detector hardwired to :nb passes every other test in this file.
  (is (= :en (lang/detect en-question)))
  (is (= :en (lang/detect en-answer))))

(deftest abstains-rather-than-guessing
  (testing "too little evidence"
    (is (= :undetected (lang/detect "Ja.")))
    (is (= :undetected (lang/detect "")))
    (is (= :undetected (lang/detect nil))))
  (testing "code is not a language"
    (is (= :undetected (lang/detect "(defn foo [x] (inc x))")))))

(deftest the-marker-sets-do-not-overlap
  ;; A word in both sets contributes to both counts and is pure noise. `for`,
  ;; `over`, `under` and `men` are common in both languages and are excluded
  ;; for that reason; this stops one creeping back in.
  (is (empty? (set/intersection lang/nb-markers lang/en-markers))
      (str "markers in both sets: "
           (sort (set/intersection lang/nb-markers lang/en-markers)))))

;; ---------------------------------------------------------------------------
;; The check fires
;; ---------------------------------------------------------------------------

(deftest a-norwegian-question-answered-in-english-is-unexpected
  ;; THE ACCEPTANCE. An answer written in the wrong language on purpose — the
  ;; equivalent of removing the middleware and watching which tests go red.
  (is (= :unexpected (lang/classify {:question-text nb-question :response en-answer})))
  (is (true? (lang/wrong-language? {:question-text nb-question :response en-answer}))))

(deftest matching-languages-are-expected
  (is (= :expected (lang/classify {:question-text nb-question :response nb-answer})))
  (is (= :expected (lang/classify {:question-text en-question :response en-answer})))
  (testing "an English question answered in English is correct, not a Norwegian failure"
    ;; 39% of the golden set is English (measured 2026-08-24), so treating
    ;; English as wrong by default would fail two questions in five.
    (is (false? (lang/wrong-language? {:question-text en-question :response en-answer})))))

(deftest BOUNDARY-undetected-is-not-failure
  (let [row {:question-text nb-question :response "Ja."}]
    (is (= :undetected (lang/classify row)))
    (is (false? (lang/wrong-language? row))
        "an unproven suspicion must not be reported with the confidence of a fact")))

(deftest BOUNDARY-this-is-not-a-fluency-judge
  ;; Clumsy Norwegian is still Norwegian. The check has no opinion about how
  ;; good the answer is — that is the judge's job, and conflating them would
  ;; turn a validity signal into a quality score.
  (let [clumsy "Det er ikke mulig å gjøre dette, men det kan være at du har en annen løsning som fungerer."]
    (is (= :nb (lang/detect clumsy)))
    (is (= :expected (lang/classify {:question-text nb-question :response clumsy})))))

;; ---------------------------------------------------------------------------
;; Reading the artifact
;; ---------------------------------------------------------------------------

(deftest prefers-the-recorded-columns
  ;; A runs.csv has no question text — only :question-id — so the runner
  ;; records both languages at run time. Without this the guard could never
  ;; run over an artifact, which is the only place it matters.
  (is (= :unexpected (lang/classify {:question-language "nb" :answer-language "en"})))
  (is (= :expected (lang/classify {:question-language "nb" :answer-language "nb"})))
  (is (= :undetected (lang/classify {:question-language "nb" :answer-language "undetected"}))))

(deftest reads-string-keys-too
  ;; Same measured trap as run-validity: a parsed CSV keeps string keys, and a
  ;; keyword-only lookup is green forever in exactly the place it is needed.
  (is (= :unexpected (lang/classify {"question-language" "nb" "answer-language" "en"})))
  (is (= :expected (lang/classify {"question-language" "en" "answer-language" "en"}))))

(deftest the-detector-is-a-seam
  ;; Installing a real library or a model call must not require touching the
  ;; states or the plumbing.
  (with-redefs [lang/detector (atom (constantly :de))]
    (is (= :de (lang/detect "anything at all")))
    (is (= :expected (lang/classify {:question-text "x" :response "y"}))
        "both sides agree under the installed detector")))
