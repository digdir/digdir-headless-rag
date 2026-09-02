# Extend `auto-filter` with language + diataxis detection

> **Superseded by [retrieval-configurable-fields-rules-plan.md](retrieval-configurable-fields-rules-plan.md).**
> This draft hardcodes NB/EN marker words, the Diátaxis universe,
> and the shape-pattern regex set directly in code. The
> configurable version generalizes to a rules engine with four
> rule types (`:facet-match`, `:regex-contains`,
> `:marker-word-classify`, `:shape-pattern`); the digdir-specific
> values become dataset seed config. Retained here for design
> history.

## Why

`detect-query-filters` in `server/src/digdir/rag/auto_filter.clj`
currently detects two query-implicit signals: organization name
(`orgs_long` / `orgs_short`) and year (`title` contains). Both
are useful but miss the **two most decisive** filters for the
public-docs corpus:

- **`language`** — every doc has `language: nb|en` (98.7%
  coverage, indexed). The v3 baseline showed that NB questions
  cleanly pick NB docs and EN questions pick EN docs when
  `language:=` is applied; without it, EN siblings of NB docs
  compete on equal footing and ranking becomes a coin flip.
- **`diataxis`** — every Hugo-frontmatter-tagged doc has
  `diataxis: explanation|how-to-guides|reference|tutorials`
  (68.2% coverage, indexed). Definitional/procedural/refusal
  questions have very different right-doc shapes; matching
  diataxis collapses the candidate set 5–10×.

Together these two filters were the v2 baseline's single
biggest win (54 → 27 tool calls). They're available to
`filter_by` already; nothing detects them from queries.

## What

Extend `detect-query-filters` to additionally return:

1. `{:field "language" :selected-options #{"nb"|"en"} :value-type :string}`
   when language can be classified with reasonable confidence.
2. `{:field "diataxis" :selected-options #{"explanation"|"how-to-guides"|"reference"} :value-type :string}`
   when question-shape suggests a single diataxis category.

Both detections must be **permissive on uncertainty**: if the
classifier isn't confident, omit the field. The existing
`auto-filter-fallback` mechanism (retrieval.clj L562: "if
auto-filter contributed constraints and returned 0 hits, drop
the filter") already handles over-filtering.

## Design — language detection

### Heuristic, not LLM

Norwegian Bokmål and English are lexically very distinct on
common-word distribution. No LLM call needed:

```clojure
(def ^:private nb-marker-words
  #{"hvordan" "hva" "hvor" "når" "hvorfor" "og" "eller"
    "ikke" "jeg" "deg" "vi" "dere" "for" "med" "uten"
    "fra" "til" "av" "er" "har" "kan" "skal" "vil" "må"
    "den" "det" "som" "noe" "noen" "ingen" "også"
    "altinn" "tilgang" "tjeneste" "rolle"
    "autentisering" "innlogging" "fullmakt"})

(def ^:private en-marker-words
  #{"how" "what" "where" "when" "why" "and" "or" "not"
    "the" "a" "an" "is" "are" "was" "were" "be" "been"
    "i" "you" "we" "they" "for" "with" "from" "to" "of"
    "can" "could" "should" "would" "may" "might"
    "do" "does" "did" "this" "that" "these" "those"})

(defn- detect-language
  "Return :nb, :en, or nil based on token overlap with marker sets.
   Threshold: classify if winning side has >= 2 markers AND >= 2x
   the loser. Also classify on ÆØÅ char presence regardless of
   marker count (those characters don't appear in EN)."
  [queries]
  (let [text (str/lower-case (str/join " " queries))
        has-nb-chars? (boolean (re-find #"[æøå]" text))
        tokens (set (tokenize text))
        nb-hits (count (set/intersection tokens nb-marker-words))
        en-hits (count (set/intersection tokens en-marker-words))]
    (cond
      has-nb-chars? :nb
      (and (>= nb-hits 2) (>= nb-hits (* 2 (max 1 en-hits)))) :nb
      (and (>= en-hits 2) (>= en-hits (* 2 (max 1 nb-hits)))) :en
      :else nil)))
```

Validation against the v3 7-question set (sanity check):
- Q1 EN "What is Dialogporten…" → `:en` ✓
- Q2 EN "How do I create a new dialog…" → `:en` ✓
- Q3 NB "Hvordan setter jeg opp autentisering…" → `:nb` ✓
- Q4 NB "Når går Altinn over fra…" → `:nb` ✓
- Q5 NB "Hva er forskjellen mellom…" → `:nb` ✓
- Q6 EN "Compare how Altinn Studio v7 and v8…" → `:en` ✓
- Q7 EN "Does Altinn support webhook signatures…" → `:en` ✓

All 7 classify correctly under this heuristic.

### What to emit

```clojure
(when-let [lang (detect-language queries)]
  {:type :multiselect
   :field "language"
   :selected-options #{(name lang)}
   :value-type :string})
```

## Design — diataxis detection

### Heuristic, with explicit "skip if ambiguous"

Diataxis is harder to classify because question shape isn't
always conclusive. Use shape patterns and skip on ambiguity:

```clojure
(def ^:private diataxis-shape-rules
  ;; Each rule: [matches? -> diataxis-value]
  ;; Order matters; first match wins.
  [;; Definitional ("explanation")
   [#"(?i)^\s*(what\s+is|hva\s+er|hva\s+vil\s+det\s+si|define|explain\s+(what|how)\s+)" :explanation]
   [#"(?i)\b(definition|meaning|concept|definisjon|begrepet)\b" :explanation]
   ;; Procedural ("how-to-guides")
   [#"(?i)^\s*(how\s+(do|can|to|should)|hvordan(\s+\w+){0,2}\s+(jeg|man|setter|kan))" :how-to-guides]
   [#"(?i)\b(set\s+up|sett\s+opp|configure|konfigurer|installer|create\s+a|opprett(e)?\s+en|enable|enabling|aktiver)\b" :how-to-guides]
   ;; Reference ("reference")
   [#"(?i)^\s*(what\s+are\s+the|list\s+(of|all|the)|which\s+values|hvilke\s+verdier|hvilke\s+\w+\s+finnes)" :reference]
   [#"(?i)\b(api\s+reference|all\s+(possible|valid)|enum|fields|parameters|attributes|status\s+codes)\b" :reference]])

(defn- detect-diataxis
  [queries]
  (let [text (str/join " " queries)]
    (some (fn [[re v]] (when (re-find re text) v))
          diataxis-shape-rules)))
```

Validation against v3 7-question set:
- Q1 "What is Dialogporten…" → `:explanation` ✓
- Q2 "How do I create…" → `:how-to-guides` ✓
- Q3 "Hvordan setter jeg opp…" → `:how-to-guides` ✓
- Q4 "Når går Altinn over fra…" → nil (temporal, no shape rule)
- Q5 "Hva er forskjellen mellom…" → nil (comparative, no shape rule) — *but* the v3 trail's optimal probe used `language:=nb` *only* on Q5, no diataxis filter; nil here matches what the human did.
- Q6 "Compare how Altinn Studio…" → `:how-to-guides` (matches `(how)`) — *but* the v3 trail used no diataxis filter for Q6. Risk: false-positive on this one.
- Q7 "Does Altinn support…" → nil (yes/no, no shape rule)

5 of 7 classify usefully; 1 is correctly null; 1 (Q6) would
over-filter. The fallback mechanism (drop filter on 0 hits)
catches Q6 — the diataxis-filtered probe likely returns 0 *new*
hits for v7+data-model questions, so it would self-correct.

### Permissive vs strict (bb.edn parallel)

`bb ts-search` exposes both `--diataxis` (permissive: matches
named values + docs without any diataxis set, ~32% of corpus) and
`--diataxis-strict`. **Auto-filter should be permissive by
default** for the same reason: 32% of docs lack the frontmatter
field and excluding them silently misses content.

In Typesense filter syntax: `diataxis:!=[other,values,here]`
(everything except the *unwanted* values, which includes empty).

The current `field-spec->typesense-clause` only emits `field:=[v1,v2]`.
Extend with a new `:not-in` field-type, or — simpler — emit a raw
filter clause for diataxis specifically when it's auto-detected.

### What to emit

```clojure
(when-let [dia (detect-diataxis queries)]
  {:type :diataxis-permissive  ; new sentinel type
   :field "diataxis"
   :selected-options #{(name dia)}
   :value-type :string})
```

And in `field-spec->typesense-clause`, add a case for
`:diataxis-permissive` that emits the `diataxis:!=[…others…]`
form using the canonical universe `["explanation"
"how-to-guides" "reference" "tutorials"]`.

## Where to change code

| File | Change |
|---|---|
| `server/src/digdir/rag/auto_filter.clj` | Add `detect-language` and `detect-diataxis`; extend `detect-query-filters` to merge their results into `:fields`. ~80 lines. |
| `server/src/digdir/rag/filters.cljc` | Add `:diataxis-permissive` case in `field-spec->typesense-clause` that emits `diataxis:!=[<complement>]`. ~15 lines. |
| `server/test/digdir/rag/auto_filter_test.clj` | Add tests for the v3 7-question classifications above. |

## Validation

Re-run the v3 7-question retrieval probe with auto-filter
extended. Predicted impact:

| Q | Today | Predicted | Why |
|---|---:|---:|---|
| 1 | 67% | 67% | Already finds; language filter narrows EN doppelganger. |
| 2 | 0% | **partial** | Without `:doc-title` strategy (companion plan), filter narrows the search set but no strategy queries the right field. Combined with companion plan → high. |
| 3 | 0% | **partial** | `language:=nb` narrows the NB sibling; diataxis split (how-to + reference) is what the optimal trail did manually. Auto-filter would pick one — under-narrows half the time. Combined with `:doc-title` → still partial because Q3 requires multi-diataxis. |
| 4 | 33% | **~50%** | `language:=nb` surfaces the NB migration doc above the EN sibling. |
| 5 | 0% | **partial** | `language:=nb` narrows; combined with `:doc-title` companion → high. |
| 6 | 33% | 0% (regress!) | False-positive diataxis classification → filters out the right docs. Auto-filter-fallback should rescue, but the merged result may still rank wrong. **Needs guarding.** |
| 7 | 20% | 20% | English; no diataxis signal in question. |

**Aggregate**: This plan alone is modest (~30% → ~40%). The
big payoff comes when combined with the `:doc-title` strategy
plan — together they reproduce the v3 optimal-trail probe shape.

## Risks

- **Q6-style false positives.** "Compare how X and Y" matches
  the `how` rule and gets classified as how-to-guides, but the
  question is cross-doc-synthesis. Mitigations: (a) the
  fallback mechanism drops the filter on 0 hits, (b) keep
  `diataxis:!=[others]` permissive form so docs without
  frontmatter still match, (c) tighten the `how` rule to
  require an imperative form (`how do/can/should`) rather than
  the bare word.
- **Misclassified language**. The marker-word approach can
  misfire on very short queries with few function words. Skip
  language detection if `(count (tokenize text)) < 3`.
- **Mixed-language queries**. e.g. "Hvordan setter jeg opp
  Maskinporten Authentication?" has NB grammar and EN terms.
  Token overlap will still classify as NB (more NB function
  words). Acceptable — content match doesn't depend on the
  filter language matching the terms.

## Composition with the `:doc-title` strategy plan

These two plans are designed to compose. Independently:
- `:doc-title` alone surfaces title-matching docs from the wrong
  language. Helps but noisy.
- `auto-filter` alone narrows the search set, but no strategy
  queries the right field — only the existing strategies'
  candidates get filtered.

Together they reproduce the optimal v3 probe:
**`query-by linktitle,frontmatter_title + filter language:=<auto> && diataxis:!=<complement>`**.

Predicted aggregate hit rate **22% → 60-70%** with both shipped.
The remaining gap is the structural problems (Q6 content
absence; Q7 refusal discipline) which retrieval shouldn't claim
to solve.

## Out of scope

- LLM-based query classification. Heuristics first; if they
  underperform we can swap in a small classifier call later.
- Per-tenant tuning of marker word sets. The defaults above are
  derived from the digdir/public-docs corpus; a tenant-config
  hook can be added when a second corpus joins.
- Detecting more diataxis categories at once (multi-value
  permissive filter). The infrastructure supports it; the
  classifier just picks one. Multi-value would help Q3
  specifically.
