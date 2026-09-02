# Kimi on Azure Foundry, and the latency SLA (#97)

**Established 2026-08-24.** No batch was run. This answers "is it reachable, what would it cost,
and what target should we measure against".

## How each fact here was established

Recorded because the last two measurement efforts died of missing provenance — the corpus was
resolved and discarded, and the HPC endpoint was reached through a tunnel nobody wrote down. **If
Foundry becomes the path, it must not become the third.**

| claim | source | as of |
|---|---|---|
| model ids, capabilities | [Foundry Models sold by Azure](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure) — MS Learn, `ms.date` **2026-08-20** | doc date |
| deployment types / regions | [Region availability](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure-region-availability) — MS Learn, `ms.date` **2026-08-21** | doc date |
| token volumes, latency | `server/results/sweep-2026-06-15T19-10-11-048722Z` and `…-06-12T20-24-51-098156Z`, parsed with a **real CSV reader** (these files contain quoted response text with commas) | 2026-06 |

## 1. Is Kimi on Foundry? **Yes — and three constraints matter more than the answer**

| model id | type | tool calling | response formats | languages |
|---|---|---|---|---|
| `Kimi-K2.7-Code` **Preview** | chat-completion (with reasoning content) | **Yes** | Text | `en`, `zh` |
| `Kimi-K2.6` **Preview** | chat-completion (with reasoning content) | **Yes** | Text | `en`, `zh` |
| `Kimi-K2.5` **Preview** | chat-completion (with reasoning content) | **Yes** | Text | `en`, `zh` |

Both models the PI named are there, **sold by Azure** — hosted and operated by Azure, billed through
our subscription, covered by Azure SLAs. That is exactly the property the HPC allocation lacked: no
tunnel, no allocation, no single person's memory.

**Tool calling: Yes** settles #97's risk 1 at the capability level. It does *not* settle whether our
**wkok** Azure path passes `tool_choice: required` through intact — that is a property of our client,
not of the model, and it still needs the functional test #97 asks for.

### ⚠️ Constraint A — all three are **Preview**, and preview deployments are auto-upgraded

> *"We don't recommend using preview models in production. We'll upgrade all deployments of preview
> models to either future preview versions or to the latest stable, generally available version.
> Models that are designated preview don't follow the standard Azure OpenAI model lifecycle."*

**This is the re-baseline register's rule arriving as a product risk.** A quality baseline measured
on a preview deployment can be invalidated by Microsoft upgrading the deployment underneath it —
without a commit, a config change, or any event on our side. We have spent this week learning that a
number is only comparable against the same code and the same data; a preview model adds a third
axis that we do not control and cannot pin.

### ⚠️ Constraint B — **Global Standard only.** No EU data zone.

Every Kimi row in the region-availability tables sits under **Global Standard**. There are **zero**
rows under Data Zone Standard, Standard/Regional, or any Provisioned type.

> **Global** types: *"Might be processed in any Azure region where the model is deployed."*
> **Data Zone** types: processed within the specified data zone (US, **EU**, or APAC).

Data *at rest* stays in the designated geography, but **inferencing data — the user's query and the
retrieved corpus text in the prompt — may be processed in any region.** For a Norwegian
public-sector product this is a compliance question, not a preference, and it is upstream of the
quality argument: if EU-only processing is required, **Kimi on Foundry is not deployable at all
today**, regardless of how good it is.

### ⚠️ Constraint C — documented languages are `en` and `zh`. Not Norwegian.

The field is populated meaningfully elsewhere in the same table — `Mistral-Large-3` lists eleven
languages including `nl` — so the omission is a statement, not an oversight.

Our corpus is Norwegian, and the June measurements show Kimi performing *well* on it, so this is
not "it does not work". It is **unsupported rather than broken**: no commitment, and no recourse if
a future version regresses on a language Microsoft never claimed.

## 2. Reachability with our credentials: **UNVERIFIED — and that is a statement about this lane**

I could not test it, and I am not reporting a dead probe as a negative answer.

- `lane-d`'s config DB has **no `digdir` tenant** (`Canonical tenant root node not found`)
- **no** `AZURE_OPENAI_*` environment variables are set in this shell

So I hold no Azure credentials here. **This says nothing about whether Foundry is reachable** — the
same error as reading a dead `localhost` port as a statement about a remote service.

**To verify it, run the probe against a config that has the tenant.** It reports each condition
separately rather than collapsing them into one nil, because `list-deployment-names` returns nil for
*azure-off*, *missing endpoint*, *missing key* and *API failure* alike:

```
GET {api-endpoint}/openai/deployments?api-version=2024-08-01-preview
    header: api-key: {api-key}
```

That is `digdir.llm.azure-deployments/fetch-deployments` — **our own instrument, the same endpoint
and key the product already uses for chat completions**, no service principal needed. A 200 lists
the deployment ids; grep them for `Kimi`. Record the endpoint, the api-version and the response.

## 3. What the quality comparison would cost

Measured from the June runs rather than estimated, with a real CSV parser:

| sweep | rows | prompt tokens (mean) | completion (mean) | reasoning (mean) |
|---|---:|---:|---:|---:|
| headline full-42, N=3 | 126 | 13,504 | 696 | 0 |
| latency bench, conc-1 | 16 | 10,555 | 391 | 77 |

**Per model arm, for the same comparison as the weekend batch (A full-42 + B broad-118, N=3):**

| | runs | prompt tokens | completion tokens |
|---|---:|---:|---:|
| A — full-42 × 3 | 126 | ~1.70 M | ~0.09 M |
| B — broad-118 × 3 | 354 | ~4.78 M | ~0.25 M |
| **per arm** | **480** | **~6.5 M** | **~0.33 M** |
| **two arms (K2.6 + K2.7-Code)** | **960** | **~13 M** | **~0.66 M** |

Plus a `gpt-5.5` judging pass over the same runs.

**The workload is ~19:1 prompt-dominated** — it is a RAG pipeline, so almost all of the cost is
context, not generation. Two consequences: pricing is driven by the *input* rate, and **prompt
caching is the lever worth checking** before costing anything (the harness already records a
`cached-tokens` column).

**Not established: the price.** Preview-model rates are not in the docs I read, so the currency
figure needs the Foundry pricing page or the subscription's own rate card. The token volumes above
are the durable half and do not change with the price.

## 4. The latency SLA

No target has ever been stated. That is the actual open decision, and it is upstream of the model
choice: without it, *"is 114 s acceptable?"* has no answer.

### First, what 114 s is and is not

Reproduced independently here: **median 114,034 ms across 16 runs at conc-1**, matching the reported
figure exactly.

**But it is end-to-end completion, measured on a path that could not emit a first token.** The graph
variants did not stream until `b7c00e8` (2026-08-21), so time-to-first-token was not merely
unmeasured — it was *unobtainable*. And the 126-run batch's median of 539 s is **conc-48 queueing,
not per-query latency**; it must never be quoted as a latency figure.

### The proposal

**Time-to-first-token is the interactive metric, not completion.** Now that streaming works, a
114 s answer whose first token lands in 3 s is a fundamentally different product from a 114 s wall
with nothing on screen. An SLA written only on completion time would reject a design that is
perfectly acceptable, and accept one that is not.

Proposed, to be argued with rather than adopted silently:

| tier | metric | target |
|---|---|---|
| **Interactive** | **TTFT** p50 | **≤ 3 s** |
| | TTFT p95 | ≤ 8 s |
| | completion p50 | ≤ 30 s |
| | completion p95 | ≤ 90 s |
| **Asynchronous** | completion p95 | ≤ 5 min, for questions the router sends to a batch tier |

Two things this deliberately does *not* assume:

1. **That one tier fits.** An AI-overview surface (#240) lives or dies on TTFT; a research-style
   answer can take longer if it streams. The tier split is the product decision.
2. **That the model is the variable.** End-to-end includes retrieval, rerank and read, which are
   model-independent. **If that share alone approaches the interactive target, no model choice can
   meet it** — which is why the decomposition being measured separately is the prerequisite, not a
   nice-to-have.

## Decisions this needs, none of which are research

1. **Is EU-only inference processing required?** If yes, Constraint B is disqualifying today and the
   rest is moot.
2. **Is a Preview model acceptable for v0.1**, given deployments are auto-upgraded and a baseline
   can be invalidated without our action?
3. **Is `en`/`zh`-documented support acceptable for a Norwegian product?**
4. **What are the SLA tiers and targets?** — the table above is a starting position.
