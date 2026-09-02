# Configurable retrieval fields & auto-filter rules

**Supersedes**: `retrieval-doc-title-strategy-plan.md`,
`retrieval-auto-filter-language-diataxis-plan.md`. Those drafts
proposed the right features but hardcoded digdir/public-docs
specifics (Hugo `linktitle`/`frontmatter_title` field names, NB/EN
marker words, the Diátaxis taxonomy). This plan keeps the feature
shapes but moves all corpus-specific content into runtime config.

## Why configurable

The v3 baseline gap analysis identified two underused signals
on the digdir/public-docs corpus:

1. **Doc-level title fields** (`linktitle`, `frontmatter_title`)
   indexed in v2 — no retrieval strategy queries them.
2. **Faceted classifiers on docs** (`language`, `diataxis`) — no
   auto-filter rule detects them from queries.

Both are valuable *for this corpus*. But:
- `linktitle` is a Hugo concept. A non-Hugo corpus has neither
  the field nor the convention.
- Diátaxis is one of several docs frameworks (others: Information
  Mapping, DITA, etc.). Hardcoding the universe
  `["explanation" "how-to-guides" "reference" "tutorials"]` bakes
  one framework into the retrieval code.
- Norwegian Bokmål + English is one of many possible language
  pairs. The marker-word approach generalizes; the *specific*
  marker sets don't.

Code should ship the **mechanism** (a doc-title strategy, a
rules-driven auto-filter); the **policy** (which fields, which
markers, which patterns) belongs in dataset-scoped config.

## Two configurability surfaces

### Surface 1 — `skills.retrieval.title-fields`

A list of doc-collection field names to search via a new
`:doc-title` strategy. The strategy is generic; the field list is
config.

Schema:

```clojure
(ensure-config-definition!
 "skills.retrieval.title-fields"
 {:root :runtime
  :ownership :inherit
  :value-type :edn
  :description "Skill property: list of doc-collection fields to
                query with the :doc-title strategy. Empty disables
                the strategy. Fields must exist on the docs
                collection schema. Example: [\"linktitle\"
                \"frontmatter_title\" \"title\"]"
  :category :skills
  :service :search
  :sensitivity :internal
  :function :settings})

(ensure-config-definition!
 "skills.retrieval.doc-title-chunk-fanout"
 {:root :runtime
  :value-type :number
  :description "Skill property: per-doc-title-hit chunk fanout K.
                Matched doc contributes chunks 0..K-1 as merge
                candidates. Default 3."
  ...})
```

Default value: `[]` (strategy off until a corpus configures it).
For digdir/public-docs, the seed config writes:

```clojure
{"skills.retrieval.title-fields" ["linktitle" "frontmatter_title"]
 "skills.retrieval.doc-title-chunk-fanout" 3}
```

Code change (one new strategy):

```clojure
(defn search-docs-by-title
  [docs-collection-name title-fields relaxed-queries filter-by opts]
  (when (and (seq title-fields) (seq relaxed-queries))
    ;; multi-search on docs collection
    ;;   query_by  (str/join "," title-fields)
    ;;   include_fields "doc_num,total_chunks"
    ;;   filter_by typesense-filter (direct, no reference wrapper —
    ;;     filter targets this collection)
    ;; For each matched doc: emit K chunk_id stubs by querying chunks
    ;; with filter doc_num:=<doc> && chunk_index:<K, sort_by chunk_index
    ;; Each stub carries rank=doc's _text_match and search-type=:doc-title
    ...))
```

The retrieval skill includes `:doc-title` in `base-strategy-lists`
iff `title-fields` is non-empty.

### Surface 2 — `skills.retrieval.auto-filter-rules`

An EDN vector of rule specs. The auto-filter engine reads it,
dispatches each rule to a code-side handler by `:rule/type`, and
merges the resulting filter fields.

Schema:

```clojure
(ensure-config-definition!
 "skills.retrieval.auto-filter-rules"
 {:root :runtime
  :ownership :inherit
  :value-type :edn
  :description "Skill property: ordered list of auto-filter rule
                specs. Each rule self-describes its type, target
                field, and parameters. Supported types: see
                digdir.rag.auto-filter rule registry. Default []."
  ...})
```

#### Rule types (code-side registry)

| `:rule/type` | What it does | Required params |
|---|---|---|
| `:facet-match` | Match query text against cached facet values; emit `field:=[matched]`. Replaces current `detect-org-filters`. | `:fields` (vec), `:prefer` (string), optional `:max-options` |
| `:regex-contains` | Detect tokens matching a regex (e.g. years); emit `field:contains:[matches]`. Replaces current year detection. | `:field`, `:pattern` |
| `:marker-word-classify` | Score query against per-value marker-word sets; emit `field:=[winner]` if confident. | `:field`, `:values` (map of value → `{:marker-words, optional :requires-chars}`), `:min-tokens`, `:threshold-ratio` |
| `:shape-pattern` | First-match-wins regex-to-value mapping; emit `field:=[value]` or `field:!=[complement]` for permissive mode. | `:field`, `:patterns` (vec of `{:value :regex}`), `:mode` `:strict|:permissive`, `:universe` (vec, required for permissive) |

The handler signature is uniform:

```clojure
(defmulti detect-by-rule (fn [_rule _queries _opts] (:rule/type rule)))

(defmethod detect-by-rule :marker-word-classify
  [{:keys [field values min-tokens threshold-ratio] :as rule} queries _opts]
  ...)

;; etc.
```

The existing `detect-query-filters` becomes:

```clojure
(defn detect-query-filters [queries docs-collection opts]
  (let [rules (or (:auto-filter-rules opts) (config-fetch "skills.retrieval.auto-filter-rules"))
        opts-with-collection (assoc opts :docs-collection docs-collection)]
    (->> rules
         (keep #(detect-by-rule % queries opts-with-collection))
         (mapcat :fields)
         vec
         (#(when (seq %) {:fields %})))))
```

#### Seed config for digdir/public-docs

```clojure
[{:rule/type :facet-match
  :fields ["orgs_long" "orgs_short"]
  :prefer "orgs_long"}

 {:rule/type :regex-contains
  :field "title"
  :pattern "\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"}

 {:rule/type :marker-word-classify
  :field "language"
  :min-tokens 3
  :threshold-ratio 2.0
  :values
  {"nb" {:requires-chars "[æøå]"
         :marker-words ["hvordan" "hva" "hvor" "når" "hvorfor"
                        "og" "eller" "ikke" "jeg" "deg" "vi"
                        "er" "har" "kan" "skal" "vil" "må"
                        "altinn" "tilgang" "tjeneste" "rolle"
                        "autentisering" "innlogging" "fullmakt"]}
   "en" {:marker-words ["how" "what" "where" "when" "why" "and"
                        "or" "not" "the" "is" "are" "was"
                        "can" "could" "should" "would" "do"
                        "does" "did" "this" "that" "these"]}}}

 {:rule/type :shape-pattern
  :field "diataxis"
  :mode :permissive
  :universe ["explanation" "how-to-guides" "reference" "tutorials"]
  :patterns
  [{:value "explanation"
    :regex "(?i)^\\s*(what\\s+is|hva\\s+er|hva\\s+vil\\s+det\\s+si|define|explain)"}
   {:value "how-to-guides"
    :regex "(?i)^\\s*(how\\s+(do|can|to|should)|hvordan(\\s+\\w+){0,2}\\s+(jeg|man|setter|kan))"}
   {:value "reference"
    :regex "(?i)^\\s*(what\\s+are\\s+the|list\\s+(of|all|the)|which\\s+values|hvilke\\s+verdier|hvilke\\s+\\w+\\s+finnes)"}]}]
```

Same rules deliver the same predicted ~50–70% top-30 hit rate
on the v3 baseline as the prior drafts.

## How the two surfaces compose

This is the same composition story as the prior drafts — only the
implementation factors out:

1. `skills.retrieval.auto-filter-rules` runs over the query →
   `{:fields [{:field "language" :selected-options #{"nb"}} ...]}`.
2. The filter is passed to *every* strategy, including the new
   `:doc-title` strategy. Each strategy's underlying Typesense
   query gets narrowed.
3. `:doc-title` queries by `title-fields` (from config) within the
   narrowed scope. The optimal v3 probe shape — *query-by
   linktitle,frontmatter_title + filter language && diataxis* — is
   reconstructed from two config-driven pieces.

Neither the rule types nor the strategy mention "linktitle",
"diataxis", "nb", or "explanation" anywhere in code.

## Migration of existing hardcoded detections

`auto_filter.clj` currently has:
- `detect-org-filters` (hardcoded `orgs_long`/`orgs_short`)
- `detect-year-title-filter` (hardcoded year regex, hardcoded `title` field)

Migrate both into the rules-engine as `:facet-match` and
`:regex-contains` instances. Delete the two `detect-*` functions
and the inline knowledge of `orgs_long` / `orgs_short` /
`year-pattern` from code. They become **seed config** for the
digdir/public-docs dataset, no different from the new language
and diataxis rules.

This is the load-bearing test of the abstraction: if the prior
functionality survives migration into config with no code-side
references to its specific field names, the framework is clean.

## Where to change code

| File | Change |
|---|---|
| `server/src/digdir/rag/retrieval.clj` | Add `search-docs-by-title` (generic, takes `title-fields`). |
| `server/src/digdir/rag/core.cljc` | Re-export. |
| `server/src/digdir/rag/auto_filter.clj` | Replace `detect-org-filters` + `detect-year-title-filter` with rule-dispatch engine + `detect-by-rule` defmulti handlers for the 4 rule types. Net change: ~+100 / −80 lines. |
| `server/src/digdir/rag/filters.cljc` | Add `:not-in-set` (or equivalent) field-type for the `:permissive` diataxis-style filter. |
| `server/src/digdir/skills/builtin/retrieval.clj` | Read `title-fields` and `auto-filter-rules` from skill parameters; conditionally include `:doc-title` strategy. |
| `server/src/digdir/skills/builtin/multi_retrieval.clj` | Same. |
| `server/src/digdir/rag/merge.cljc` | Add `:doc-title 0.8` to default strategy weights. |
| `server/src/digdir/setup/config.clj` | Add config definitions for `title-fields`, `doc-title-chunk-fanout`, `auto-filter-rules`. |
| **Dataset seed config** for `digdir/public-docs` | Write `title-fields`, `doc-title-chunk-fanout: 3`, and the 4-rule `auto-filter-rules` value listed above. |

The dataset seed is **not source code** — it lives in the runtime
config DB and is applied per-dataset via the existing
`config-set` task. New corpora write their own seed.

## Where the corpus-specific content actually lives

| Corpus-specific thing | Lives in |
|---|---|
| Field names (`linktitle`, `frontmatter_title`) | `skills.retrieval.title-fields` config |
| Field universe (Diátaxis values) | `:universe` in a `:shape-pattern` rule in `auto-filter-rules` |
| Language markers (NB/EN words, ÆØÅ regex) | `:values` in a `:marker-word-classify` rule in `auto-filter-rules` |
| Shape patterns (per-language regex for question shape) | `:patterns` in a `:shape-pattern` rule |
| Facet field names (`orgs_long` etc.) | `:fields` in a `:facet-match` rule |
| Year-regex field target | `:field` in a `:regex-contains` rule |
| Chunk-fanout K | `skills.retrieval.doc-title-chunk-fanout` config |

Code knows: how to multi-search, how to score, how to merge, how
to dispatch rules. Code doesn't know: any field name, any
language, any taxonomy.

## Validation

Same predicted impact on the v3 baseline as the prior drafts —
the configurability is structural, not functional. Run the v3
7-question retrieval probe after:
1. Schema migration (rule engine in place, seed config written),
2. Doc-title strategy enabled with digdir field list,
3. Auto-filter rules populated.

Expected hit rate: **22% → 60–70%** on top-30. The remaining gap
is the structural ones (Q6 content absence, Q7 refusal discipline).

## Risks

- **Rule-set growth**: as corpora accumulate, an N-corpus
  registry of rule values becomes a sprawling config surface.
  Mitigations: (a) inherit at the config level (a dataset
  inherits from a "Hugo-docs" template); (b) keep rule TYPES
  small — 4 covers a lot.
- **Over-engineering for a single-corpus problem**: if no second
  corpus arrives, the configurability is dead weight. But the
  migration of org/year detection into the same framework is a
  *net* simplification (removes two hardcoded fields from code).
  So the floor is "no worse than today, and ready for the
  second corpus."
- **Permissive vs strict filter modes**: needs careful handling
  for the `diataxis` 32%-empty case. The existing `bb ts-search
  --diataxis` flag has the precedent — copy its `!=[complement]`
  shape into the filter engine.

## Out of scope

- Per-rule confidence thresholds beyond what each rule type
  already supports.
- LLM-based question classifiers as a rule type. Heuristics
  first; the rule-type registry can accept a `:llm-classify`
  type later without disturbing other rules.
- A UI for editing rules. Initial editing is via the existing
  `bb config-set` task. UI is downstream.
- Schema validation / migration. The existing config-definition
  mechanism provides EDN value-type with no inner-shape check;
  bad rules should fail loudly at first request, not at config
  write. Tighten via Malli if it becomes a problem.
