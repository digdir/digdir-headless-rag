# Arc A — Stuck-Domains Retrieval Diagnostic

Sweeps 1–3 left 5 domains at 0–11% recall@10 across every config tested. Before designing more sweeps, run a pure-retrieval probe (bypassing the agent loop) to find out *where* in the pipeline the goldens are being lost.

## Method

For 5 confirmed-stuck-domain questions, query the retrieval skill standalone and record:
- Per-strategy rank of the golden in `:phrase-hits`, `:metadata-hits`, `:content-hits`
- Rank in the merged ranking (`:merged-hits`)
- Behaviour as `retrieve-top-k` scales from 10 → 100

All probes use the Round-2 winning weights `{:content 1.0 :phrase 2.0 :metadata 0.2}`.

## Headline findings

### F1 — Metadata and content strategies contribute nothing for these goldens

Across all 5 questions, the golden chunk-id appeared **0 times** in `:metadata-hits` and **0 times** in `:content-hits` at any retrieve-top-k. **The phrase strategy is the only path by which a stuck-domain golden ever surfaces.** The metadata and content strategies are returning hits — just not the right ones.

### F2 — The merge function actively *degrades* phrase rank as the candidate pool grows

Most striking single result, from `altinn-broker-rest-usage` (golden `0f3807200a65`):

| retrieve-top-k | phrase rank | merged rank |
|--:|--:|--:|
| 10 | not found | not found |
| 30 | **15** | **17** |
| 60 | **15** | **52** |
| 100 | **15** | **91** |

Phrase rank is *stable* at 15. As more content/metadata candidates flow in, the merge function pushes the same phrase-rank-15 chunk further down — from rank 17 to 91. The phrase signal is being drowned by content/metadata noise even though those strategies don't contain the golden.

Same pattern milder on `altinn-studio-datamodeling`:

| top-k | phrase rank | merged rank |
|--:|--:|--:|
| 30 | 12 | 29 |
| 60 | 12 | 29 |
| 100 | 12 | 29 |

(merged stabilises here but at a worthless rank-29 position.)

### F3 — Three distinct failure modes, three different remediation paths

| question | failure mode | phrase rank | merged rank | remediation |
|---|---|--:|--:|---|
| `altinn-studio-create-user` | **agent-read failure** | 3 | 4 | golden is in the workspace; agent picks other chunks |
| `altinn-broker-rest-usage` | **merge-dilution** | 15 | 17 / 52 / 91 | tune merge weights or cap strategy contributions |
| `altinn-studio-datamodeling` | **merge-dilution + phrase-buried** | 12 | 29 | tune merge + bump retrieve-top-k |
| `altinn-systemuser-api-model` | **phrase-buried** | 49 (only at top-k 100) | 69 | requires `retrieve-top-k ≥ 100` AND merge tuning AND rerank lift |
| `altinn-authorization-regler` | **fundamentally unfindable** | not found at any top-k | n/a | enrichment indexing OR query re-author |

### F4 — Studio-create-user is the cleanest "agent loop is the bottleneck" case

The golden lands at merged rank 4 and stays there across every retrieve-top-k. It should be in every workspace by default. Yet Round-1/2/3 sweeps showed `altinn-studio-create-user` at 0% recall across **every** cell. That means the *retrieval is fine* — the agent is picking other chunks to read+rerank, ignoring rank 4.

### F6 — Workspace-tail occlusion: present but past rank 10

Cross-referencing Round-3's `runs.csv` for `altinn-studio-create-user` (a 0-recall-everywhere question with golden at phrase rank 3, merged rank 4):

| Round-3 cell × repeat | n-retrieved | golden in retrieved? | rank | recall@10 |
|---|--:|---|--:|--:|
| baseline rep 0 | 6 | no | — | 0.0 |
| baseline rep 1 | 3 | no | — | 0.0 |
| **baseline rep 2** | **14** | **yes** | **12** | **0.0** |
| minimal rep 0 | 4 | no | — | 0.0 |
| minimal rep 1 | 9 | no | — | 0.0 |
| minimal rep 2 | 7 | no | — | 0.0 |
| strict rep 0 | 8 | no | — | 0.0 |
| strict rep 1 | 8 | no | — | 0.0 |
| strict rep 2 | 8 | no | — | 0.0 |

Baseline-rep-2 retrieved 14 chunks total and the golden was at position 12 — beyond `recall-at-k` ≤ 10, and **not in the cited chunks either** (the synthesis call cited `5b7220458fe7;829b010fc712`, two unrelated chunks). So the workspace *had* the golden once, but post-rerank trimming dropped it. This is **F6: present-but-tail**, distinct from never-retrieved.

This adds another argument for raising the rerank-output cap (`:context-top-k`) above 10, or scoring `recall@workspace-size` rather than `recall@10` so we can see when the workspace contains the right answer but the rerank-output trimming hides it.

### F5 — Enrichment strategies are unavailable for this dataset

Confirmed via dataset-config inspection: `public-docs` is indexed with **only the base 3 strategies** (phrase, metadata, content). The `:hypothetical-questions`, `:verified-phrases`, `:fact-assertions` collections that the retrieval skill's enrichment branch could query don't exist. Enabling them would require new ingestion work, not a config knob change.

## What this invalidates / changes about Arcs B and C

**Arc C (eval scale-up) should NOT be the next step.** Adding more questions to a corpus where the merge function is actively demoting correct results would just produce more rows that don't move. Fix the leak first.

**Arc B (Round 4 sweep) should pivot to merge-tuning, not the originally-planned `× strategy-contribution-caps` or `× skill-graph` 2D**. The diagnostic gives us concrete, falsifiable mechanisms to test:

| hypothesis | cell |
|---|---|
| "Killing content/metadata weight entirely will surface the buried phrase hits" | `weights {:phrase 1.0 :content 0 :metadata 0}` |
| "Capping content/metadata contribution will preserve the phrase signal" | baseline weights + `:strategy-contribution-caps {:phrase 5 :content 1 :metadata 1}` |
| "Raising retrieve-top-k to 100 lifts the systemuser-api-model case" | `:retrieve-top-k 100` (vs default 30) on phrase-heavy weights |
| "These can be combined for compounding gains" | all three together |

This is **not** a faithful 2D OFAT (multiple knobs at once on the combined cell), but the upside is that we can run a small *targeted* matrix that directly answers "can we move the merge-dilution and phrase-buried failure modes?".

## Proposed Round 4 design (replaces the original Arc B plan)

**5 cells** × 18 questions × 3 repeats = **270 runs**, ~2.5h, on `prompt-minimal`:

| cell | weights | strategy-contribution-caps | retrieve-top-k |
|---|---|---|---|
| `r4-control` | `{:content 1.0 :phrase 2.0 :metadata 0.2}` | default (1 each) | 30 (default) |
| `r4-phrase-only` | `{:content 0 :phrase 1.0 :metadata 0}` | default | 30 |
| `r4-capped-noise` | control weights | `{:phrase 5 :content 1 :metadata 1}` | 30 |
| `r4-deep-retrieve` | control weights | default | 100 |
| `r4-stacked` | `{:content 0 :phrase 1.0 :metadata 0}` | `{:phrase 5 :content 0 :metadata 0}` | 100 |

The control reproduces Round-3 `prompt-minimal`. Each of the next three changes one variable. The stacked cell combines all three remediations to test compounding.

**Expected signal**: if F2 (merge-dilution) is real, `r4-phrase-only` should outperform `r4-control` on `broker-rest-usage` and `studio-datamodeling` even at unchanged retrieve-top-k. If F1 (metadata/content useless for stuck-domain goldens) is real, `r4-phrase-only` should match or beat control across the board, not regress. `r4-deep-retrieve` tests whether `systemuser-api-model` becomes findable; if it doesn't move that cell from 0%, retrieval-pool size isn't the bottleneck.

The agent-read failure case (`studio-create-user`) will *not* be moved by any of these. That needs a separate intervention on the agent loop's read-selection logic — out of scope for Round 4.

## Why the agent isn't reading studio-create-user is a separate question worth answering

It's the highest-leverage single fix: golden at merged rank 4, agent at 0% recall, repeated across every Round-1/2/3 cell. The fix probably lives in how the agent ranks `read_chunks` candidates from search-result metadata (titles/snippets), or in how many chunks per search-call it actually pulls. Worth its own micro-investigation alongside Round 4 — same time, different lever.

## What we save by doing Arc A first

If we'd jumped straight to Arc B as originally planned (a 2D sweep on the new control), we'd have crossed `phrase-top20` with `× strategy-contribution-caps`. That would have given us another 6–8 cells × 3 hours of compute. With the diagnostic done, we can run a 5-cell targeted matrix that *directly tests the failure mechanisms we just identified*, in 2.5 hours, with much sharper hypotheses. Lower cost, higher information value.
