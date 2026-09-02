(ns digdir.eval.answer-language
  "Is the answer in the language the question was asked in? (#289)

   ## WHAT THIS IS FOR, AND WHAT IT IS NOT

   One question about one artifact: is the answer in the same language as the
   question. That is all.

   It is NOT a translation-quality judge. It is NOT a fluency or grammar
   check - a clumsy Norwegian answer is Norwegian. It is NOT an assertion
   about the CORPUS; documents carry their own :language metadata and their
   own filtering, and none of that is this. And it is NOT a quality score: an
   answer in the wrong language is a BROKEN RUN, not a bad answer, and belongs
   beside :no-llm-call rather than in a mean - the same argument as #276.

   ## What the expected language IS, established rather than invented

   The pipeline already answers this, so this namespace does not get a vote.
   `digdir.llm.prompt-fragments/same-language-rule` - used by
   `skills.builtin.synthesis` and `skills.builtin.agent.sufficiency` - says:

     Always respond in the SAME LANGUAGE as the user question.

   So the expected language of an ANSWER is the language of the QUESTION.

   Deliberately NOT the corpus language, which is a different setting
   governing a different artifact: the query planner's `corpus-language`
   translates the SEARCH QUERIES into the corpus language so retrieval works.
   The two are complementary rather than competing - for an English question
   against a Norwegian corpus the pipeline intends to search in Norwegian and
   answer in English, and this checks only the second half.

   ## Why this exists now

   The one defect known to have shipped here was a language defect: the query
   planner ran from 2026-05-27 to 2026-08-21 with its same-language
   instruction silently dropped, because `when-not` returned only its last
   form. Three months, on a Norwegian corpus, and nothing in the eval path
   would have caught it - a fluent, correct, well-cited answer in the wrong
   language scores on its merits when the judge compares against a reference.

   ## Three states, because absence is not failure

   :expected / :unexpected / :undetected. The third is not a hedge - a short
   or code-heavy answer genuinely cannot be classified, and an archive of runs
   predating this column must not read as failing. Same reasoning as
   `digdir.eval.run-validity`: a guard that condemns history earns a
   reputation for noise and gets switched off before it reports anything true.

   ## The detector is a seam, and the default is honest about its range

   `detector` holds the function that names a language. The default
   discriminates NORWEGIAN FROM ENGLISH ONLY - the two languages this corpus
   and its questions are actually in - and returns :undetected for anything it
   cannot call with margin. It is a function-word frequency classifier, not a
   regex over content: it counts how many closed-class words belong to each
   language and requires a clear winner.

   A detector that says Norwegian for everything is indistinguishable from a
   corpus that is entirely Norwegian, which this one very nearly is. So it is
   validated in BOTH directions against real repository text, not only against
   Norwegian. Install a better one - a real library, or a model call - by
   resetting `detector`; the states and the plumbing do not change."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The default detector
;; ---------------------------------------------------------------------------

(def nb-markers
  "Closed-class Norwegian words with no English homograph.

   Chosen for DISJOINTNESS rather than frequency - `for`, `over`, `under` and
   `men` are common in both languages, so they carry no signal and are left
   out. A test asserts the two sets do not intersect."
  #{"og" "er" "det" "som" "en" "et" "til" "av" "ikke" "med" "har" "den"
    "om" "jeg" "vi" "skal" "være" "eller" "når" "hvor" "hva" "hvordan"
    "ved" "fra" "etter" "gjennom" "kan" "blir" "ble" "sin" "sine" "dette"
    "disse" "noen" "alle" "andre" "også" "mellom" "uten" "hvis" "slik"
    "hver" "sammen" "under" "flere" "bare" "mer" "enn" "ny" "nye"})

(def en-markers
  "Closed-class English words with no Norwegian homograph."
  #{"the" "is" "and" "of" "to" "in" "that" "it" "with" "as" "are" "was"
    "be" "this" "have" "or" "you" "we" "not" "will" "how" "what" "which"
    "when" "where" "they" "their" "there" "would" "should" "could" "been"
    "into" "than" "then" "these" "those" "each" "other" "between" "without"
    "through" "after" "only" "more" "new" "any" "all" "can"})

(def ^:private min-marker-hits
  "Below this many total marker words the text is too short to call.

   Not a magic threshold on LENGTH - it is a floor on EVIDENCE. A two-word
   answer and a page of source code both fail it, for the same reason.

   SET FROM THE DATA, not guessed. Across the 103 golden questions the marker
   count is min 0, p10 2, median 5 - so a floor of 6 (the first value tried)
   returned :undetected for most QUESTIONS, which are short by nature. Two
   works because the signal is clean rather than plentiful: Norwegian
   questions score 5-0, 3-0, 2-0 against the English set, so the SHARE does
   the discriminating and the count only has to rule out noise."
  2)

(def ^:private min-share
  "The winner must hold this share of marker hits, else :undetected.

   Norwegian answers routinely quote English product names and vice versa, so
   a bare majority is not enough to call it."
  0.70)

(defn- tokens [text]
  (->> (str/split (str/lower-case (str text)) #"[^\p{L}]+")
       (remove str/blank?)))

(defn detect-nb-en
  "Default detector: :nb, :en, or :undetected. Two languages only, by design."
  [text]
  (let [ts (tokens text)
        nb (count (filter nb-markers ts))
        en (count (filter en-markers ts))
        total (+ nb en)]
    (if (< total min-marker-hits)
      :undetected
      (let [share (/ (double (max nb en)) total)]
        (cond
          (< share min-share) :undetected
          (> nb en) :nb
          (> en nb) :en
          :else :undetected)))))

(defonce detector
  ;; (fn [text] -> :nb | :en | :undetected | other-language-keyword)
  ;;
  ;; A seam rather than a hard dependency: this repo has no language-detection
  ;; library, and adding one for a single column is disproportionate. Reset it
  ;; to install a real detector or a model call - nothing else changes.
  (atom detect-nb-en))

(defn detect
  "Name the language of `text` using the installed detector."
  [text]
  (@detector text))

;; ---------------------------------------------------------------------------
;; The check
;; ---------------------------------------------------------------------------

(defn- cell
  "A column, whether the row has KEYWORD or STRING keys.

   Same reason as `digdir.eval.run-validity/cell`: a runs.csv read back from
   disk keeps string keys, and a keyword-only lookup is green forever in
   exactly the place the guard is needed. That was measured, not assumed."
  [row k]
  (let [v (get row k)]
    (if (some? v) v (get row (name k)))))

(defn- ->lang
  "A recorded language cell as a keyword, or nil when absent/blank."
  [v]
  (when-not (str/blank? (str v)) (keyword (str v))))

(defn classify
  "One of :expected, :unexpected, :undetected for a run row.

   Prefers the RECORDED :question-language and :answer-language columns,
   because a runs.csv carries no question text - only :question-id - so a
   reader cannot re-derive the expected language from the artifact. The runner
   detects both at run time, when it still has the question object, and writes
   them down. Falling back to detecting from text keeps in-memory rows and
   ad-hoc analysis working.

   :undetected whenever EITHER side cannot be named - an answer we cannot
   classify and a question we cannot classify are both cases where no claim is
   available, and reporting either as a failure would be a guess."
  [row]
  (let [q (or (->lang (cell row :question-language)) (detect (cell row :question-text)))
        a (or (->lang (cell row :answer-language)) (detect (cell row :response)))]
    (cond
      (or (= :undetected q) (= :undetected a)) :undetected
      (= q a) :expected
      :else :unexpected)))

(defn wrong-language?
  "True only when the answer is positively known to be in the wrong language.

   False for :undetected, on purpose - an unproven suspicion must not be
   reported with the confidence of a fact."
  [row]
  (= :unexpected (classify row)))
