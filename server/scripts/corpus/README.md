# The NorQuAD demo corpus (#447)

A Norwegian question-answering corpus for the shipped demo tenant, assembled at
setup time from two sources with two different licences. **Nothing it produces is
committed**, and that is the design rather than a convenience.

## Why nothing is vendored

NorQuAD is offered under CC0. That dedication genuinely covers the layer NorQuAD
created — its questions and answers. It does **not** cover the prose in its
`context` field, which is Wikipedia text: nobody can relicense Wikipedia, and
NorQuAD never claimed to. Its paper contains exactly one sentence about
licensing in 5,672 words, and its dataset card says nothing about the source
material at all.

So this script takes the two layers from the two places that can license them:

| Layer | Source | Licence |
|---|---|---|
| Questions and answers | NorQuAD, via HuggingFace at run time | CC0-1.0 |
| Article text | Norwegian Bokmål Wikipedia, fetched directly | CC BY-SA 4.0 |

There is no corpus input file in the repository, and no output is tracked.
`digdir.corpus.rehydration-test` asserts that as an invariant rather than as a
file list: nothing carrying the signature at corpus scale, and nothing inside a
directory holding `MANIFEST.txt` or `ATTRIBUTION.tsv`, may be tracked by git.

## Usage

```bash
bb server/scripts/corpus/rehydrate_norquad.clj <out-dir> [distractor-count]
```

Resumable and idempotent — an article already written is not refetched, so an
interrupted run continues where it stopped. At roughly one request per title
this **will** be interrupted at least once.

Output:

```
<out-dir>/gold/*.md            352 articles carrying NorQuAD answers
<out-dir>/distractors/*.md     random articles, if a count was given
<out-dir>/ATTRIBUTION.tsv      the CC BY-SA attribution for every document
<out-dir>/MANIFEST.txt         counts and provenance
```

## Three things that are not obvious, each of which cost a measurement

**One title per request.** Wikipedia's `extracts` API returns text for exactly
one page per call regardless of how many titles you pass, and `exlimit=max` does
not change it. Batching looks like it works — you get a 200 and a well-formed
response — but every title after the first comes back empty. Measured against
the same ground truth, batched fetching matched 1.7% of NorQuAD contexts and
one-per-request matched 92.2%. The 1.7% reads as "rehydration does not work".

**Headings arrive as wikitext, not markdown.** `explaintext` returns
`== Etymologi ==`, and our chunker's `header-line?` matches markdown only. An
unrewritten extract therefore produces **zero** heading splits and one
~35,000-character chunk — and it does not error. It ingests, embeds and scores,
which is the worst available outcome. `wikitext-headings->markdown` rewrites on
the way out and a guard fails the build if any written document still carries a
wikitext heading.

**Attribution goes in a sidecar, not in front-matter.** `docs/folder.clj` has no
front-matter parser — only `website.clj` does — so a YAML block at the top of a
`.md` is not metadata to the folder ingest, it is **content**. Measured on the
352-article gold set: the block chunked as 334 characters of identical licence
boilerplate in every document, and in 116 of 352 cases
`concatenate-too-small-chunks` glued it across the `# Title` boundary into the
article body. Removing it dropped the intro corpus from 588 chunks to 352 —
exactly one per document. CC BY-SA is still satisfied, because attribution is
owed at **display**, and `ATTRIBUTION.tsv` is what the display layer reads.

## Distractors

The gold set is 352 articles; a full article yields roughly 60 chunks, of which
only a handful carry answers. The remaining ~19,000 are same-register distractors
drawn from the same corpus, so a separate distractor fetch may be unnecessary.
Fetch the extra articles only if measured scores come back implausibly high, and
**state the pool composition beside every score** — 352 topics, ~21,000 chunks,
distractors from the gold articles themselves — so nobody reads a number as
covering a broader corpus than it does.

## The warm phrase cache

`server/resources/demo-corpus/phrase-cache-folder-v2.edn.gz` — 7,149 pre-generated
phrase sets, 1.40 MB compressed, unpacked on first run by
`digdir.boot.phrase-cache/warm!` into `cache/folder-search-phrases/`, which is
inside the `digdir-cache` volume mounted at `/app/cache` (#495).

Without it, a newcomer's first materialisation of the demo corpus pays one LLM
call per uncached chunk. With it, the chunks it covers cost nothing.

### Which key version this archive was built under

| segment | value | source |
|---|---|---|
| model | `gpt-4o` → `a2a69af70d1b` | `digdir.setup.demo-dataset/dataset-values` |
| prompt | → `871d369894de` | `search-phrases/default-search-phrases-prompt` |
| parser version | `v2` | `search-phrases/parser-version` |

Verified **inside the runtime image**, not by inspection: the shipped model and
prompt hash to exactly the segments the committed keys carry.

**The key is not promised to be stable.** A change to the chunker, the prompt,
the model constant or `parser-version` orphans every entry — they are simply
never read again, which is inert rather than wrong. Nothing is designed around
the key holding. When it changes, re-run a materialisation and rebuild:

```sh
bb phrase-cache-archive <cache-dir>
```

That script reproduces the committed archive byte-for-byte from the same input,
so a diff shows what changed rather than reordering noise.

### Coverage: what it actually warms

Measured against the shipped demo corpus, chunked with the shipped dataset config
(`:header-based`, minimum 333, no sub-split):

Rebuilt 2026-09-03 from a **complete** materialisation of the freshly fetched
pinned corpus (351 documents, 7,109 chunks, 82,993 phrases):

| | |
|---|---|
| archive entries | **7,149** |
| chunks the pipeline actually requests phrases for | **7,109** |
| of those, covered by the archive | **7,109 / 7,109 = 100%** |
| chunks below the 333-character minimum, dropped before any phrase call | 135 |
| archive entries not matching a current chunk (inert) | 40 |

**A newcomer re-materialising the demo corpus now pays zero LLM calls for
phrases.** The previous archive covered 65.6% because it was built over a
*partial* ingest; that is no longer the case.

⚠️ **The 135 sub-minimum chunks are deliberately not in the denominator.** The
pipeline drops them before it ever asks for phrases, so an archive cannot cover
them and counting them understates coverage — that miscount is what produced an
earlier reading of 98.1%. Coverage is measured against chunks the pipeline
*requests*, which is the only population an archive can serve.

The 40 surplus entries are orphans carried forward from the previous key epoch.
They are never read, which is inert rather than wrong — see the key-version note
above.

### Licence

The phrases are model output over CC BY-SA text, and they are shipped under the
**same CC BY-SA attribution the corpus already carries** — `ATTRIBUTION.tsv`
covers them.

That is deliberately the conservative reading. The tempting argument is that
machine-generated text carries no copyright and the phrases are therefore
unencumbered; that is jurisdictionally shaky and it is not needed. Measured over
the archive: 55,920 phrases, median 35 characters, max 114, and **19.2% occur
verbatim in the source articles** (400 sampled). So roughly four fifths are
generated description and one fifth is short verbatim fragments — non-contiguous,
averaging a line each, from which no article can be reconstructed.

Treating the whole set as an attributed derivative costs nothing we were not
already doing, and does not depend on a claim about authorship of model output
that we would rather not have to defend. Compare the corpus-side judgement in
`manifest.edn`, where answer spans were kept on the same reasoning at 0.49% of
the source; this is ~2.7%, still fragmentary, and the conclusion is the same.
