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
