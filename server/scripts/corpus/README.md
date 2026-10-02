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
phrase sets, 1.40 MB compressed — and beside it
`phrase-cache-folder-v2.identity.edn`, which **declares who generated them**.

At boot, `digdir.boot.phrase-cache/warm!` unpacks the archive into
`cache/folder-search-phrases-declared/`, inside the `digdir-cache` volume mounted
at `/app/cache`. That directory is the archive's alone: a run never writes
there. A run's own entries go to `cache/folder-search-phrases/`.

Without the archive, a newcomer's first materialisation of the demo corpus pays
one LLM call per uncached chunk. With it, the chunks it covers cost nothing,
**whatever provider and model the newcomer runs**, provided the phrase prompt and
parser version are the shipped ones. A changed prompt misses, as it should. So
does changed chunk text: the corpus is fetched live, so the archive covers less
as the articles are edited (see *Coverage* below).

### How the archive is reached: a declared generator

The cache key names the provider that answers and the model actually sent:

`<chunk text>-<provider>-<model SENT>-<prompt>-<parser-version>`

Both are deployment-specific, so no installation's own key for a chunk is the
archive's. Rather than weaken the key, the archive declares whose output it is,
and a lookup has two tiers:

1. **local:** `cache/folder-search-phrases/`, under this installation's own
   provider and model;
2. **declared:** `cache/folder-search-phrases-declared/`, under the declared
   provider and model, combined with *this* installation's prompt and parser
   version, and in the key grammar the archive was built in. The unpack copies
   the archive AS SHIPPED, so the committed archive is looked up under `main`'s
   four-segment key (content, declared model, prompt, version).

Three rules, each one a test:

- **Precedence:** a local positive, then the declared positive, then a local
  negative. A mis-loaded local model is what writes negatives, so if a local
  negative came first, the archive would stop helping exactly when the local
  model breaks.
- **Read-only:** nothing is written into the declared directory, and a declared hit
  is never copied into the local one. Either would put one generator's output
  under another's name. **The separate directory is what makes this hold.** An
  identity is two names, so an `azure` installation whose deployment is called
  `gpt-4o` computes exactly the declared key. No key shape can tell those two
  apart. Separate storage does not tell them apart either; what it does is keep
  that installation's output out of the archive. A mis-loaded model can
  still write junk under its own correct-looking name, in its own local cache
- **Positive-only:** a declared "this chunk has no phrases" is ignored.

### What the unpack checks, and what it only declares

The committed archive predates the current key. Its keys have four segments and
no provider:

| segment | the archive was built under | now |
|---|---|---|
| provider | *(absent)*, and it **stays absent**: the unpack copies the archive as shipped | the provider that answers, from `provider/selected-provider` |
| model | `gpt-4o` → `a2a69af70d1b`, the CONFIGURED value | the model actually sent, from `provider/model-for` |
| prompt | → `871d369894de` | unchanged, `search-phrases/default-search-phrases-prompt` |
| parser version | `v2` | unchanged, `search-phrases/parser-version` |

So for this archive the unpack **checks the model** against the keys and only
**declares the provider**. The model check is all-or-nothing: if one key does not
match, the whole archive is refused. The boot log says what was checked:
`:verified #{:configured-model}`, because `main`'s model segment hashed the
CONFIGURED value, which the request overwrote. So the check shows that the
declaration matches what the generating run was configured with, not what
reached the wire. That gap is why the key changed.

An archive rebuilt under the current key has five segments, and the unpack
checks **both**: `:verified #{:provider :model}`.

**What either check establishes, and on which axis.** It shows that the
declaration and each entry's own key agree, on the segments the key has. It
cannot show which model actually wrote an entry. A key holds names, so two
generators that share a provider and model name share a key.

**The invariant that makes that record mean anything: a key segment is
evidence only of the run that generated the entry, so nothing downstream of
generation writes one.** The unpack and the builder copy keys; they never
compose them. `:verified` therefore names exactly the identity segments the keys
carry, with no special case per path. The unpack used to ADD a provider segment
to four-segment keys, copied from the declaration. Packing that directory then
"verified" the provider against the declaration it had been copied from.

⛔ **Four-segment keys are accepted from ONE archive only: the committed one,
pinned by the SHA-256 of its content** in `digdir.boot.phrase-cache`. The
old key cannot tell its generators apart, because on `main` every demo install
keyed on the *configured* `gpt-4o`, whatever it sent. So accepting the key SHAPE
would unpack any directory of entries from before the phrase negative-cache issue under the declared name, foreign
ones included, and it did, until this pin. A rebuild cannot obtain the pin:
- the builder refuses any key that is not five segments;
- an archive assembled without the builder matches only if its content is
  byte-identical to the committed archive;
- the pin lives in the unpack's source, not in the identity resource that a
  rebuild edits.

The unpack also refuses an archive that mixes four- and five-segment keys, and a
five-segment archive whose keys name more than one provider/model identity.

### Rebuilding it

**The key is not promised to be stable.** A change to the chunker, the phrase
prompt or `parser-version` orphans every archived entry. Orphaned entries are
never read again, which is inert rather than wrong. A change to an
installation's *own* model does not orphan them, because the archive is looked up
under the declared identity. When a rebuild is needed:

1. **Materialise the demo corpus into an EMPTY `cache/folder-search-phrases/`, with
   NO `cache/folder-search-phrases-declared/` present.** The builder packs the
   whole directory, so anything already in it ships as the declared generator's
   output. Entries from before the phrase negative-cache issue make the builder refuse. Another model's
   entries under a different name make `bb test` refuse (step 4). Another
   model's entries **under the same provider and model names are not detected
   anywhere**, because their keys are identical. Emptying the directory
   is the only protection against that case. And while
   the old archive is unpacked, any chunk it answers is never written locally,
   so the rebuild would be missing those chunks. A dev boot does not unpack the
   archive; a production boot does, so remove the declared directory after the
   boot. **Packing the declared directory is not a rebuild.** For the committed
   archive it holds four-segment keys, which the builder refuses. For a rebuilt
   archive it repacks the same record it already had.
2. Build:

   ```sh
   bb phrase-cache-archive <cache-dir>
   ```

   Given the same input, the builder writes a byte-identical archive, so a diff
   shows what changed rather than reordering noise.
3. **Update `phrase-cache-folder-v2.identity.edn`.** `:provider` and `:model`
   become the rebuilding installation's (`provider/selected-provider`,
   `provider/model-for`), and `:generated` becomes the rebuild date. **The
   `:vetting` record does not carry over.** It describes the old entries, and
   the new ones need their own.
4. **`bb test`.** `digdir.boot.phrase-cache-test/the-archive-ships-and-is-not-empty`
   unpacks the committed archive under the committed declaration. It fails if
   they disagree, or if the archive's keys name more than one identity. It
   cannot fail for a foreign model that shares the declared names. The
   builder has already refused any key that is not five segments. A committed
   archive with four-segment keys is refused unless it is the pinned one.

⚠️ **An existing volume keeps its first unpack.** The unpack runs only when the
declared directory is empty, so a new image carrying a rebuilt archive does not
replace entries already unpacked. To pick up the new archive on such a volume,
remove `cache/folder-search-phrases-declared/` from it.

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

That 100% was measured on 2026-09-03 under the four-segment key, before the
declared tier existed. Coverage is by chunk text, so it should carry over:
with the shipped prompt and parser version, a lookup under the declared identity
computes exactly the key each entry is unpacked under. **That is by
construction, not by measurement.** What has been checked since is narrower:
that an `:openai-compatible` installation is served from the unpacked archive
without a model call. Nobody has run a full materialisation through the declared
tier, so "a newcomer pays zero LLM calls for phrases" is what the design
predicts, not what was observed.

⚠️ **The prediction holds only for chunk text that has not changed since
2026-09-03, and it gets worse with time.** The key arithmetic covers the KEYS,
not the TEXT. The corpus is fetched live and cannot be pinned to a revision:
`prop=extracts` ignores `revids` and returns today's text (see
`rehydrate_norquad.clj`). So when an article has been edited since the pin, it
re-chunks, its changed chunks hash differently and miss, and each one costs a
call. The rehydrator reports such articles as changed since the manifest and
carries on. The 100% above decays as Wikipedia is edited, and the number of
paid calls grows. The previous archive covered 65.6% because it was
built over a *partial* ingest; that is no longer the case.

⚠️ **The 135 sub-minimum chunks are deliberately not in the denominator.** The
pipeline drops them before it ever asks for phrases, so an archive cannot cover
them and counting them understates coverage — that miscount is what produced an
earlier reading of 98.1%. Coverage is measured against chunks the pipeline
*requests*, which is the only population an archive can serve.

The 40 surplus entries are orphans carried forward from the previous key epoch.
They are never read, which is inert rather than wrong — see *Rebuilding it*
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
