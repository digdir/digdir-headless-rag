# Decision batch — one pass over the `needs-discussion` board

Closes #28. **21 open issues; 19 carry `needs-discussion`.** That is the actual
blocker now — the executable backlog is essentially clear apart from #81, and
what remains is a wall of decisions being taken one round-trip at a time.

This document exists so the PI can answer **Part 1 in a single sitting** and
nothing else. Parts 2–5 are triage: what is already decided, what a measurement
settles, and what needs a written verdict from anyone competent rather than from
the PI.

Every issue below was read. Where a claim here contradicts an issue body, the
contradiction is stated rather than smoothed over.

---

## The lens that answers several of these at once

> **PI (2026-08-21, recorded in #98):** Digdir plans to migrate to a different
> tech stack for the productization phase, so this repository stays
> prototype-grade for the foreseeable future.

That is load-bearing and currently written down nowhere but a deferred
documentation issue. Applied consistently it disposes of a whole class:

- **It lowers the value of tidiness work** — legacy retirement (#26), the
  structural-cruft items (P3, P12, P16, P17), reviving an unused optimiser
  (P29), a data migration to fix a typo nobody sees (#52).
- **It does not excuse correctness or security work** — silently-wrong `2xx`
  responses (#174), an unspecified authorization contract (#27), an onboarding
  path that does not work (#81), a corpus that drops documents (#223).

The discriminator is not "how big is the cleanup" but **"does a user or an
operator get a wrong answer today?"** Recommendations below apply that test.

---

# Part 1 — For the PI. Six questions.

### Q1 · #22 — Which shared secrets manager?

Onboarding currently has to say *"ask a teammate for secrets"*, which is the
exact dependency onboarding exists to remove.

**Options:** 1Password · Doppler · Vault · stay ad-hoc.

**Recommendation:** whatever Digdir's IT already administers and pays for — this
is an org-standards question, not a technical one, and the repo only needs a
stable item path to point at. If there is no standard, **1Password** (CLI plus
CI service accounts, lowest ops burden). *Not blocking code:* the "no secrets in
tracked files" constraint holds either way; only the onboarding wording waits.

### Q2 · #24 — Do agent teams get separate GitHub identities?

**Answer this before Q3 — it gates it.** With every team posting as
`bdb-itonomi`, nothing mechanically prevents a team approving its own PR
(branch protection sees one author), and GitHub suppresses notifications for
your own activity, so `gh api notifications` returns empty after days of
comments.

**Options:** one identity per team · shared account plus enforced human approval
· shared account plus convention (status quo).

**Recommendation:** **one identity per team**, if seats allow. The cost is
one-time plumbing; the failure mode of the status quo is *silent*, and it
degrades precisely as teams are added — which is what is about to happen. A
Slack-forwarding mitigation already exists for the notification half
(`github-watch.sh`), and its own header says separate identities are the real
fix. The self-approval half has no mitigation today.

### Q3 · #23 — Per-area CODEOWNERS?

**Recommendation: defer until Q2 is answered.** Per-area review routing is
worthless while every team is the same GitHub identity — approval by
`bdb-itonomi` of a PR by `bdb-itonomi` carries no information. Answering this
first spends a decision that Q2 may invalidate. If Q2 lands on separate
identities, revisit with owners per `area:*` label and ownership that **advises**
rather than gates, so review does not become the bottleneck the parallel-lane
model was built to avoid.

### Q4 · #25 / #97 — What end-to-end latency is acceptable for v0.1?

**The model choice is already made.** #97 records *"PI (2026-08-21): ship K2.6 or
K2.7-Code, both now available in Azure Foundry"*, and explicitly tables the SLA
question there. So #25's headline question — incumbent vs frontier — is answered,
and the only thing still open is the number. See Part 2 for the bookkeeping.

**The one question:** what is an acceptable per-query wall-clock for the v0.1 use
case? K2.7-Code measured **median 114 s / mean 154 s** at conc-1 on the flaky HPC
endpoint; Foundry should improve on that but has not been measured.

**Options:** interactive (p50 ≤ 30 s) · tolerant (p50 ≤ 60 s, p95 ≤ 150 s) ·
batch (minutes acceptable).

**Recommendation:** state a **tolerant** target and let #97's Foundry measurement
confirm or refute it. Two caveats that must travel with the number: (1) the
model-independent retrieval/rerank/read share has never been isolated, so
measure it or the model gets blamed for it; (2) the quality *magnitude*
(+0.10/+0.15 effQ) is contaminated — see Part 3, #166 — though the direction is
not seriously in doubt and the decision rested on Foundry availability anyway.

### Q5 · #34 — Release promotion: fast-forward, merge, or squash-and-restart?

⚠️ **Two open issues currently say opposite things, and #34 has no decision
recorded on it at all.** #34's body recommends **fast-forward** and treats squash
as viable "only if `agentic-skills` is a throwaway snapshot target". #98's
preamble states *"Decided in #34: at release time `release-v0.1-details` is
squashed onto `agentic-skills`, the branch goes read-only."*

Those reconcile — squash-and-restart *is* #34's option 3 with its proviso
satisfied, and the divergence objection genuinely does not apply to branches
that stop syncing — but **an implementer reading #34 would fast-forward.**

**Recommendation:** ratify the squash-and-restart model, write it into
`CONTRIBUTING.md`, and close #34 restating it. Attach the retention condition
from #98: `release-v0.1-details` is kept **read-only and must not be deleted**
(it holds the head-to-head run log and the judge-truncation audit), ideally
behind a branch-protection rule so the accident is impossible rather than
discouraged.

**Sub-question, still unanswered anywhere:** is `main` created now, or at the
first real release? **Recommendation: at first real release** — an empty trunk
earns nothing and invites a third promotion path.

### Q6 · #81 — Which live skill graphs do three retired ids map to?

#81 is the **release blocker**: the committed config snapshot cannot be imported,
so documented onboarding step 2 dead-ends. Two of its three defects are
engineering calls (Part 4). This one is not.

Five snapshot agents reference retired graphs. `fact-checker` still exists, and
`agent-rag → builtin/agent-rag-graph-bundled` is evident from a seeded DB.
**`research-assistant`, `retrieve-only` and `simple-qa` have no obvious
successor** among the live five (`agent-iteration-bundled/-faithful`,
`agent-rag-graph-bundled/-faithful`, `fact-checker`).

**Options:** map all three onto `agent-rag-graph-bundled` · drop those agents from
the snapshot · regenerate the snapshot from a current DB.

**Recommendation:** **regenerate the snapshot from a current database.** It
removes this question and the plain-keyed-users defect in one move, and a
snapshot that drifts from the live registry will do this again. If regeneration
is not possible, map all three to `agent-rag-graph-bundled` and record it in the
issue — the acceptance criteria already require the mapping be written down
rather than merely applied.

---

# Part 2 — Not decisions. Close, correct, or relabel.

These carry `needs-discussion` but have nothing left to discuss. Clearing them
takes the board from 19 to 14 without anyone deciding anything.

| Issue | State | Action |
|---|---|---|
| **#25** | Model choice **already decided** in #97; SLA tabled there | Close as decided, pointing at #97. Keep the contamination note (Part 3) attached to #97 |
| **#82** | Opens with a recorded PI decision — ship incrementally as an epic | Drop `needs-discussion`; it is an epic being executed, not a question |
| **#98** | Both halves are recorded PI decisions already | Drop `needs-discussion`; it is documentation work, not a decision |
| **#214** | **Body still states the withdrawn figures** — "~3,028 documents, about 27%" — while its own comments and title record the corrected answer of **eight** | Rewrite the body or close it; the investigation is finished and the live part is #223 |
| **#171** | Already carries its own recommendation and is explicitly not a blocker | Leave for whatever PR next touches console routing; no decision needed |

**#214 is the one worth acting on quickly.** An issue whose body asserts that a
quarter of the corpus is unsearchable, when the measured answer is eight
documents, will be read by someone who does not scroll to the comments. The
retracted number is more dangerous sitting in an issue body than it ever was in
a report.

---

# Part 3 — Settleable by measurement, not by opinion

Each of these is phrased as a discussion but resolves to a fact. The measurement
that settles it is named.

### #153 — Do the graph-cutover comparisons predate the streaming fix?
**Settled by:** checking whether a graph-vs-imperative comparison was ever run
and informed a decision. If none exists, close it — nothing is contaminated. If
one exists, only latency / time-to-first-token / UX comparisons are affected; a
retrieval-quality or answer-content comparison plausibly is not.

### #165 — Is the Electric activation credential machine-bound?
**Settled by:** attempting a cold `bb dev` on a second machine with the same
credential, and inspecting where the credential is stored. The issue already
establishes the *impact* (no HTTP listener, because `dev.cljc/-main` starts jetty
after the shadow-cljs watch returns) and that a warm `.shadow-cljs` cache is why
nobody hit it. Only the machine-bound question is open.

### #223 — Why did four substantial PDFs produce zero chunks?
**Settled by:** running the text extractor over those four files and checking
whether they are image-only/scanned. The leading hypothesis is explicit and
unverified. **Three of the four are the same report series**, so this is a
systematic failure on one document family, not four accidents — that is the part
worth the measurement.

### #101 — Should the 765 duplicate documents be deduplicated?
⚠️ **This issue's central justification has been withdrawn and the retraction
never reached it — #101 has no comments.** Its argument is *"it shrinks #72
before #72 has to be solved"*, but the collision rate in the deployed corpus was
subsequently measured at **0.000%** and #72's code fix has landed. The
entanglement claim is void.
**Its independent reasons survive:** 765 duplicate registrations inflate the
corpus, waste embedding and enrichment spend proportionally, and can surface the
same content several times in one result set.
**Settled by:** establishing *why* they are duplicated — re-registration, a
versioning convention, or ingestion retry — because that determines whether
dedup is safe or destroys a real distinction. **Re-frame the issue first**; as
written it argues from a premise that no longer holds.

### #97 — Does Kimi work on Azure Foundry?
**Settled by:** the four measurements the issue already lists, in its stated
order. **Tool-calling through the wkok Azure path first** — the agent loop
depends on *forced* tool-calls and a newer `tool_choice` value can be stripped
there, so that is a functional risk that makes the other three moot if it fails.

### #166 — Which decisions rest on a pre-fix measurement?
**Settled by:** naming them, not by re-running everything. For **#25**, the
answer is known and recorded on that issue:

> The reassuring reflex — "the planner defect applied to every arm, so it is
> common-mode and the difference survives" — **is wrong here.** Arms were
> switched by setting `services.azure-openai.model-name` *globally*, and
> `query_planner.clj` resolves its own model from that same key.

**Verified in code while writing this** (`query_planner.clj:618-619` and
`:661-662`: `deployment-name` falling back to `model-name`), so the planner
changed together with the synthesizer and the comparison was never
synthesis-only. It biases toward the frontier arm, which means **the magnitude
is unreliable and the direction is not**. Since the model decision was made on
Foundry-availability grounds, this changes expectations rather than the choice.

---

# Part 4 — Needs a written verdict, but not the PI's

Any competent engineer can settle these. They need *a decision recorded*, which
is why they have stalled.

### #174 — Coercion silently strips unknown request fields *(recommend: fix)*
Every public request field on every endpoint: a typo'd or copied field is
deleted before validation, and **the symptom is always a `2xx`**, so nothing in
the logs will ever point at it. #172 is what that looks like — an operator
followed our own documentation, got `201 Created`, and received an API key with
none of the grants they asked for.
`{:closed true}` **does not fix it** and was measured not to: the decoder strips
the key before the closed check runs, so the flag fails silently in the direction
that looks like success.
**Recommendation: fix, at the transformer level — reject unknown request fields
with a 400 rather than stripping them.** This is the clearest case in the batch
where prototype-grade is not a defence: it produces confidently wrong results
for users following our documentation, and it is undetectable from logs.

### #27 — Authorization contract for `execute-skill-handler` *(recommend: decide, then document)*
`execute-skill-handler` requires an explicit `{:tenant, :dataset-config-key}`
with no API-key-scope fallback, and there is **no `:allowed-skills` gate** —
nothing restricts which skill an authenticated caller may execute. The question
that matters is whether the old `/api/rag` per-key scoping was *intentionally*
dropped or simply not carried across; one is a design decision and the other is a
gap.
**Recommendation:** treat explicit-only as intended and **document it**, but
answer the `:allowed-skills` question deliberately rather than by omission. The
MCP tool-name construction (`<agent-id>__<skill-graph-short-name>`) constrains
what a caller can *discover*, which is not the same as what they can *call* —
that distinction should be stated in the answer rather than assumed either way.

### #26 — When can the legacy config-migration surface be retired? *(recommend: defer the block, do the one slice)*
~166 `legacy` hits concentrated in the config model.
**Recommendation: defer under the prototype-grade lens** — retiring legacy paths
in a codebase slated for stack migration is close to pure cost. Do only the
narrow, safe slice already marked `agent-ready` (#14), and adopt the issue's own
suggestion for anything further: scope **one** item (P8, the API-key policy
migration), establish what "confirmed cut over" looks like as a repeatable check,
and let the rest follow that pattern *if it is ever worth doing*.

### #52 — Misspelled agent id `plain-language-qualtiy-check` *(recommend: option 2)*
The id is a **foreign key** — referenced by `bootstrap-deployment-target!`, both
runtime trees, `agents-db/get-agent`, a test that asserts on the exact string,
and persisted playground conversation rows. The display name is correct, so no
operator ever sees it.
**Recommendation: document, do not migrate** (the issue's own option 2). A data
migration carries more risk than the defect it repairs, and the real hazard is a
future contributor "fixing" the typo and silently breaking agent resolution for
existing tenants — which a comment at the definition prevents for free.

### #28 — This issue *(recommend: close on merge)*
Its stated format is one verdict per item, then close. Part 5 is that.

---

# Part 5 — #28's own batch: a verdict each

Applying the lens: **accept** what only offends tidiness, **fix** what returns a
wrong answer.

| Item | Verdict | One line |
|---|---|---|
| **P22** ColBERT rerank failure yields unranked chunks | **Fix (small)** | Silent quality degradation with no signal past `:rerank-error`; a log line plus a counter is cheap and this is the retrieval path the whole project is tuned around |
| **P23** auto-filter fallback only fires on empty results | **Defer** | Real, but "low recall yet non-empty" needs a recall threshold nobody has defined; it becomes measurable work once #97's baseline exists |
| **P24** confirmation codes in-memory only | **Accept, document** | A restart invalidating outstanding codes is acceptable at prototype scale and self-corrects on retry; note it beside the dev fallback |
| **P25** chunk ids hashed from content alone | **Accept — measured at zero** | The deployed collision rate is **0.000%**; #72's fix landed and derives ids from `doc_num`. Close this item rather than carrying it |
| **P26** file-based search-phrase cache, no cross-instance invalidation | **Defer** | Costs nothing today because deploys are single-instance; revisit only if multi-instance is actually planned |
| **P27** response-envelope inconsistency; `X-User-Id` not gated globally | **Split** | The envelope half is tidiness → defer. **The `X-User-Id` half is auth and fails late** → fold into #27's contract decision |
| **P30** no boot-time check that agent skill-graph ids exist | **Fix (small)** | This is exactly #81's defect 3 discovered at runtime by a newcomer instead of at boot by us; a startup cross-check turns a confusing import failure into a clear one |
| **P2** the half-removed "bindings" feature | **Decide dead, then remove reachability** | UI says deprecated and several paths `throw`, yet they stay reachable — the worst of both. If it is dead, make it unreachable; leaving a throwing path callable is a bug generator |
| **P29** unused `graph/optimizer.clj` | **Delete** | The runner is sequential, so it computes nothing anyone reads. Under the lens, reviving it for a parallelisation plan that may never land is speculative; git keeps it if it is ever wanted |
| **P3/P12/P16/P17** structural cruft | **Defer** | Textbook tidiness work in a prototype-grade repo; already covered by the cruft-reduction plan if that is ever prioritised |
| **`/api/rag` removal final for v0.1?** | **Ratify** | The shipped docs already assert "removed and not coming back". This needs recording, not debating — with the caveat that #27 is the issue where its *replacement's* contract is still unspecified |

---

## Appendix — board census

Counted rather than eyeballed: **21 open, 19 `needs-discussion`**
(#22 #23 #24 #25 #26 #27 #28 #34 #52 #81 #82 #97 #101 #153 #165 #166 #174 #214
#223), 2 without (#98, #171).

**If Part 1 is answered and Part 2 actioned, `needs-discussion` drops from 19 to
about 8**, and what remains is measurement work with a named measurement rather
than a decision queue.
