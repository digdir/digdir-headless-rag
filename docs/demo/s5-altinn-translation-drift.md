# S5 — Altinn NB / EN Translation Drift

**Demo scenario from** [`plans/ideas/altinn-docs-skill-demo-plan.md`](../../plans/ideas/altinn-docs-skill-demo-plan.md)

A single agent over a custom skill graph that takes a topic, retrieves doc pages from `https://docs.altinn.studio` across both Norwegian Bokmål (`/nb/`) and English (`/en/`) URL trees, pairs them by canonical URL stem, fetches each pair's full chunk set, and emits a structured per-pair drift report. The agent composes two built-in skills (entity-extraction, multi-retrieval), one bridge skill borrowed from S2 (entities→queries), and three user-defined skills written for S5 (the page-pairer, the full-content expander, and the tool-forcing JSON drift synthesizer). It exercises customization flavors 1 (persona), 2 (custom skill graph), and 3 (new skills), but its more interesting story is what the build *itself* revealed about the surrounding system — the corpus-config UX, the loader's missing recursion, and a chunk-level/page-level mismatch you can't see from outside.

## What was built

Demo namespace: [`server/src/digdir/demo/altinn_translation_drift.clj`](../../server/src/digdir/demo/altinn_translation_drift.clj).

- **`:demo/translation-page-pairer` skill** — pure-data adapter: walks retrieval chunks, extracts each chunk's `:url` (under the collection-named key), strips the `/nb/` or `/en/` prefix to a canonical path, groups by that path, and retains only paths where both an NB and an EN chunk appeared. Paths present in only one language land in a separate `:unpaired` output for diagnostics. No LLM, no services.
- **`:demo/translation-pair-content-expander` skill** — bridge skill that exists because retrieval returns matched *chunks*, not whole *pages*. For each pair, calls `digdir.rag.retrieval/retrieve-chunks-by-range` once per side to pull every chunk of both docs (sorted by `chunk_index`) directly from Typesense. The retrieval-selected fragments are replaced with the full chunk sequence, and per-side counts (`:nb-retrieved-chunk-count`, `:nb-full-chunk-count`, etc.) are exposed so the trace shows the expansion.
- **`:demo/translation-drift-synthesis` skill** — structured synthesis: takes the topic + the expanded pairs, calls Azure OpenAI with tool-forcing on an `emitDriftReport` JSON schema, post-processes the result for invariant compliance, and emits both a markdown response and a typed `:drift-report`. Each pair has a `:status` of `"aligned" | "minor_drift" | "substantive_drift"` and a `:divergences` array that MUST be non-empty iff status is `substantive_drift`. The post-processor `enforce-status-divergence-invariant` cleans up violations and records what it changed under `:enforced-status-fixes`.
- **`:demo/translation-drift` skill graph** — `entity-extraction → entities->queries → multi-retrieval → translation-page-pairer → translation-pair-content-expander → translation-drift-synthesis`.
- **One agent** `demo/altinn-translation-drift` — `:default-skill-graph "demo/translation-drift"`, locked to `{:tenant "digdir" :dataset-config-key "public-docs"}`, `:guardrails {:answer-style :research :citations-required true}`.

Wiring mirrors S2-A: `register!` is called from [`skills/init.clj`](../../server/src/digdir/skills/init.clj) at boot; `seed-agents!` from [`config/db.clj`](../../server/src/digdir/config/db.clj) `init-config-db!` at dump-import time; the dev-only seed step in [`server/src-dev/dev.cljc`](../../server/src-dev/dev.cljc) re-seeds on every dev boot.

## The test prompt

Free-form topic, one line:

```
Maskinporten authentication
```

The plan called for this scenario to take a topic and find drift; "Maskinporten authentication" is well-attested in the corpus on both `/nb/` and `/en/` sides, and Maskinporten guidance is the kind of evolving API surface where drift between languages would be a realistic finding.

## The pipeline run

Single run against `digdir/public-docs` (now ~2100 documents — 1075 NB + 1030 EN — after the corpus re-crawl described in [Iteration 1](#iteration-1-the-corpus-couldnt-supply-en)). Final timings from the graph-runner trace:

```
extract       :builtin/entity-extraction          ~700 ms  → 1 entity ("Maskinporten authentication", :concept)
to-queries    :demo/entities->queries                <5 ms → 1 query string
retrieve      :builtin/multi-retrieval           ~1100 ms  → 40 chunks (mixed NB/EN)
pair          :demo/translation-page-pairer        ~15 ms  → 2 paired paths, 23 unpaired
expand        :demo/translation-pair-content-expander  ~564 ms → 1→9 NB chunks, 2→10 EN chunks (per pair)
synthesize    :demo/translation-drift-synthesis  ~1353 ms  → 2 aligned, 0 minor_drift, 0 substantive_drift
                                                ~3.8 sec total
```

Final structured output:

```clojure
{:pairs
 [{:canonical_path "/altinn-studio/v8/guides/development/fiks-arkiv/index.md"
   :status "aligned"
   :divergences []
   :summary "Guide for configuring Fiks Arkiv integration in an app, including Maskinporten client setup and required keys/scopes."}
  {:canonical_path "/authorization/guides/system-vendor/system-user/systemregistration/index.md"
   :status "aligned"
   :divergences []
   :summary "Guide for registering an end-user system in the system registry via API, including identifiers, rights, visibility, and client IDs."}]
 :notes
 ["The EN pages are mostly direct translations of the NB pages with terminology and wording changes only; no substantive factual differences were present in the provided text."
  "Both pairs include Maskinporten-related setup or authentication context, but the facts stated match across languages."]}
```

Both pairs aligned, both with non-empty summaries, no `:enforced-status-fixes` — the model honored the status×divergences invariant cleanly. The `:notes` field captures the model's own meta-observation. 23 paths surfaced as `:unpaired` (one language only); these are recorded in trace metadata but not analysed by this MVP — they're candidates for a future "untranslated pages" report.

## The five iterations

S5 took longer to land than S2-A because the scenario surfaced problems outside the demo namespace before the pipeline could even run. Each iteration produced a finding worth keeping.

### Iteration 1: the corpus couldn't supply EN

Before any drift detection can happen, the corpus has to contain both languages. The first attempt to inventory the existing Typesense data — chunks retrieved by previous demos all carried `/nb/...` URLs, and zero traces ever showed an `/en/...` URL — suggested an NB-only corpus. The user confirmed: the crawler had been instructed to NB only.

Three discoveries from looking at the crawler config:

1. **Only one knob exists.** The website source schema exposes exactly `pipeline.source.website.sitemap-url` and `pipeline.source.website.base-url`. There is no path filter, no language flag, no multi-sitemap support. The "NB only" instruction had been implemented by setting `:website-sitemap-url` to `/nb/sitemap-markdown.xml` directly — bypassing the language-index `/sitemap.xml` that would have fanned out to both languages.
2. **The loader didn't fan out anyway.** `digdir.docs.website/extract-urls-from-sitemap` matched only `:urlset` roots. A `:sitemapindex` root (the natural fan-out point) silently produced zero URLs. So even if the operator had pointed at `/sitemap.xml`, the loader would not have walked it.
3. **There was no `:sitemap-markdown` parent index** on `docs.altinn.studio` to use as a drop-in replacement — the `.md` URL trees are leaf-level only. (The user fixed this on the Hugo side mid-iteration, exposing a new `localhost:1313/sitemap-markdown.xml` parent sitemapindex.)

**Fix in code:** taught `parse-sitemap` to detect `:sitemapindex` and recurse, with a small `extract-sub-sitemap-urls` helper. 3 new unit tests in [`server/test/digdir/docs/website_test.clj`](../../server/test/digdir/docs/website_test.clj) cover the recursion path and the urlset-unchanged regression case.

**Fix in config:** on `dataset/digdir/public-docs/altinn-docs/materialization`, set `pipeline.source.website.base-url` to `http://localhost:1313` and `pipeline.source.website.sitemap-url` to `/sitemap-markdown.xml`. `bb config-set <path> <edn-value> digdir dataset altinn-docs-materialization`.

### Iteration 2: nothing happened, because of the JVM

After the loader change and config flip, the user triggered a re-crawl. It completed in under a second and reported `:website/found-urls {:count 0}`. The fetch trace showed exactly one URL fetched (the sitemapindex root), with no recursion into children.

**Cause:** the running JVM still had the pre-edit `parse-sitemap` definition. The materialization re-ran against the *old* loader, hit the same `:urlset`-only logic, and returned empty.

**Fix:** server restart. After restart, the next crawl walked both sub-sitemaps as expected, returning ~2100 documents.

**The finding worth keeping:** *the empty result was indistinguishable from a genuinely empty sitemap.* No log line said "your sitemapindex was ignored," no diagnostic surfaced that the in-memory loader was stale. If this were a UI-driven re-crawl rather than a CLI-driven one, the operator would have no signal that they needed to restart. This is friction worth fixing later — either a `:website/sitemap-root-ignored` warning when a `:sitemapindex` is encountered without recursion support, or a code-hash check at materialization start.

### Iteration 3: the pairer found zero pairs

With both languages in the corpus, the first end-to-end run still produced zero pairs. The trace showed:

```
== STEP pair ==
[outputs]
  - pairs :: []
  - unpaired :: []
[metadata] {:input-chunk-count 40 :unique-paths 14 :paired-paths 0 :unpaired-paths 0}
```

`:unique-paths 14` proved chunks WERE annotated with canonical paths. `:paired-paths 0` AND `:unpaired-paths 0` were the giveaway — together they said the grouping bucket was empty, not that the buckets contained "all-unpaired."

**Cause:** the predicate driving the `group-by` step was

```clojure
(group-by (fn [[_ chs]]
            (let [by-lang (group-by ::lang chs)]
              (and (seq (get by-lang :nb))
                   (seq (get by-lang :en)))))
          grouped)
```

`(and (seq nb) (seq en))` returns either the *seq itself* (truthy but not the boolean `true`) or `nil` (falsy but not the boolean `false`). `group-by` uses the predicate's return value verbatim as the key — so the resulting map was keyed by `<some-seq>` and `nil`, not `true` and `false`. The downstream destructure `{paired-paths true unpaired-paths false}` matched neither bucket and silently bound both to `nil`.

**Fix:** wrap the predicate in `boolean`. Three lines.

**Generalizable lesson:** when `group-by`'s predicate is intended as a boolean partition, return an actual boolean. The `(and (seq …) (seq …))` shape is the most common offender.

### Iteration 4: the model said "no content provided"

With the pairer fixed, the synthesis step ran cleanly — and emitted four `"aligned"` pairs with no divergences, plus a `:notes` field that said:

> "No page content was provided for either language in any pair, so no substantive drift could be assessed from the supplied material."

The model was being honest: the prompt's pair-content blocks were empty. The `format-context-docs`-equivalent helper read `(:page_content c)` from each chunk. But raw retrieval chunks have their text under `:content_markdown`, not `:page_content` — `:page_content` is the field set by the reranker on context-docs. S2-A's helper had been copy-pasted; the S5 graph skipped rerank and went straight from retrieval to pairing, so the field name was wrong.

**Fix:** check `:content_markdown` first, fall back to `:page_content`, then `:content`, then `""`. Eight lines.

**Finding:** *structured outputs surface upstream bugs better than free-form responses.* The model could have just hallucinated a confident-looking comparison. Instead, the schema required a `:notes` field for cross-cutting observations, and the model used it to flag exactly what was wrong upstream. The prompt's "Rules" section asked for honesty about what wasn't comparable — and got it.

### Iteration 5: false drift from chunk-section mismatch

Iteration 4 plus content-field fix produced a run with three pairs marked `"substantive_drift"` and one `"aligned"`. The divergences quoted real sentences — but the divergences for any given pair compared an NB sentence from one *section* of the page against an EN sentence from a *different section* of the same page. The pages WERE faithful translations; the chunks just covered non-overlapping subsets.

The model again surfaced the diagnosis in its own `:notes`:

> "Several pairs show scope shifts rather than translation drift, especially where the EN page appears to be from a different or more specific article than the NB page."
> "The recurring substantive difference is that NB often describes the general concept or limitation, while EN introduces implementation details, token exchange steps, or a specific product example."

Root cause: chunk-level retrieval is fundamentally mismatched with page-level pairing. Multi-retrieval returns matched chunks per query; the NB-matched and EN-matched chunks for the same canonical page rarely cover the same sections of that page. The pairer was comparing "subset of NB sections" against "subset of EN sections" — which naturally looks like substantive divergence, because they're not even talking about the same things.

**Fix:** added a new step between pair and synthesize. `:demo/translation-pair-content-expander` hits Typesense once per side per pair via `digdir.rag.retrieval/retrieve-chunks-by-range`, fetching every chunk of each paired doc sorted by `chunk_index`. The synthesizer now compares FULL pages on both sides, not retrieval-selected fragments. For the Maskinporten run, the expander grew the Fiks-Arkiv pair from `(1 NB chunk, 2 EN chunks)` to `(9 NB chunks, 10 EN chunks)` — the actual whole pages.

The next run produced the output at the top of this document: 2 aligned pairs, no false drift, `:notes` confirming faithful translation. The post-processor's `:enforced-status-fixes` was empty — the model didn't need to be corrected.

## The generalizable findings

Two findings worth keeping from S5, both new and both about how demo scenarios reveal infrastructure shape:

**1. The shape of the *upstream pipeline* changes how downstream skills should be written.**

The synthesis stage's prompt was correct, its schema was correct, its post-processor was correct. The bug was that it consumed chunks (matched fragments) when it conceptually wanted pages (the unit being compared). Adding the expander between pair and synthesize realigned the data shape with the comparison intent — and the LLM immediately did the right thing.

Said differently: when a synthesis step disagrees with a reasonable observer about whether two things are aligned, ask whether you're feeding it the unit it's actually supposed to compare. Retrieval is granular; alignment is page-level. Bridge that explicitly.

**2. Demo scenarios surface system friction that backend work alone hides.**

Building S5 required changing four things outside the demo namespace:

- The website loader gained `:sitemapindex` recursion (3 new unit tests, [`server/src/digdir/docs/website.clj`](../../server/src/digdir/docs/website.clj)).
- The materialization-node config schema had to be discovered the hard way (no UI affordance for "what's the tenant-config-key for the altinn-docs materialization node?"; a direct Datahike query was the fastest path).
- A hot-reload limit was confirmed — pipeline-code changes require a server restart, not a re-trigger, and the symptom is identical to "the data isn't there." Worth a future warning hook.
- The Hugo build needed a new `/sitemap-markdown.xml` parent index (user-side fix).

None of these were on the S5 plan. None show up in S2-A. They're surfaced by the *concrete* requirement that this scenario have something to look at — the same way running an integration test surfaces deployment problems that unit tests miss.

The demo plan calls this out as a goal in its own framing: "verify that such changes are intuitive and user friendly." The honest answer for S5 is: the *capabilities* were available; the *paths* to use them were not discoverable without reading source.

## What this proves about the skill system

S5 exercises all three customization flavors and adds a slightly different angle on each:

| Flavor | Mechanism | Where it shows up in S5 |
|---|---|---|
| 1. Persona | Custom `:instructions` on the agent record | `demo/altinn-translation-drift` carries a translation-reviewer persona that frames substantive vs. stylistic drift |
| 2. Custom skill graph | Register a new graph via `templates/register-skill-graph!` | `:demo/translation-drift` composes 2 built-in + 3 user-defined steps and reuses one bridge from S2 |
| 3. New skill | Register a new skill via `skills/register-skill!` | Three new skills: pairer (pure data), expander (typesense-dependent bridge), drift-synthesizer (structured LLM call) |

Two observations specific to S5:

- **Adapter skills can have services**, not just be pure-data. `:demo/entities->queries` from S2-A is pure-data (no `:required-services`). `:demo/translation-pair-content-expander` declares `:required-services #{:typesense}` and uses the rag/retrieval helpers directly. The skill system accommodates both without distinction — the registry just sees a skill with metadata and an `:execute` fn.
- **The graph runner absorbed an architectural pivot mid-build.** Iteration 5 added a step between two existing steps without touching either of them. The pairer kept emitting `:pairs`; the synthesizer kept reading `:pairs` from its declared input ref; the new expander slotted in by changing one `:inputs {:pairs [:pair :pairs]}` to `:inputs {:pairs [:expand :pairs]}` in the synthesizer's step definition. Cheap insertion is the test of whether a pipeline abstraction is paying for itself; this one passed.

## Costs and limitations

- **Retrieval is language-biased.** Even with both languages in the corpus, a single Maskinporten query surfaced 23 unpaired paths vs. 2 paired. Either retrieval's semantic relevance favours one language per chunk's content, or the embedding model handles cross-lingual matching unevenly. The pairer is conservative by design (no inferred pairings from URL stem alone — both sides must appear in retrieval), so unpaired paths are an honest output. A future fan-out version could explicitly run two retrieval calls (NB-filtered and EN-filtered) and merge — once `:foreach` lands.
- **Per-side prompt budget.** Full-page content can be large (Fiks-Arkiv's full page = 18 chunks combined). The synthesizer truncates each side at `:side-char-limit 6000` chars (configurable). This is enough for a 4–6K-word page; longer pages risk losing tail content. A future version could chunk-pair within a page using `chunk_index` correspondence.
- **No real fan-out.** Same as S2-A: per-pair summarization + per-pair fact-checking (the plan's original shape for S5) needs `:foreach`. The bundled-skill MVP handles all pairs in one LLM call, which works fine at 2–5 pairs but won't scale past ~10 without prompt-budget pressure.
- **Two retrieval-side calls per pair**, plus the LLM call. With 5 paired-paths-max, that's 10 Typesense queries during expand. Cheap, but a foreach-based decomposition would also let these be batched or skipped when full content is already available.

## What this scenario didn't need

S5's two synthesis-side iterations (4 and 5) stayed entirely inside the demo namespace. The three infrastructure-side changes (sitemapindex recursion, config-set on the materialization node, server restart) were one-time fixes that benefit every future website-source scenario, not S5-specific scaffolding. The skill system handed back exactly the composition surface promised; the friction was upstream of it.
