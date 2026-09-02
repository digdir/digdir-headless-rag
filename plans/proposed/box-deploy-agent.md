# The box-side deploy agent — design, for review before any code exists

**Status: proposal. Nothing is built. Nothing runs as root anywhere.**

This is stage 2 of the approved 3b design. Stage 1 (CI publishes a green marker
ref) is a separate change and carries nothing privileged. This document exists
because the agent is the most privileged thing this work adds: **a root-owned
process on the production host that builds and deploys on an external signal.**
A design doc costs an hour against a thing that will run unattended for months.

## What it is

A process on `5.75.220.232` that watches one git ref and deploys `test`.

    CI (ephemeral runner)  --pushes-->  refs/heads/deploy/test-green
    agent (on the box)     --fetches--> that ref, decides, builds, deploys test

**It holds no GitHub API token.** The signal arrives over git, read with a
**read-only, single-repository, git-protocol-only deploy key** that never leaves
the box. GitHub stores nothing for this. That is the whole point of 3b: no
credential in GitHub secrets can cause a deploy, and no credential on the box
can do anything but read one repository.

## ⚠️ Stated non-goals — decisions, not omissions

**THE AGENT CANNOT BRING UP A DEAD SERVICE.** If `test` is down, the agent
refuses and says so. It will not restore it.

This will be asked for. The request arrives as *"it would be handy if it just
restarted things"*, and by then nobody will remember it was chosen. It is
chosen: automatic recovery is a different component with a different risk
profile, and it should be decided rather than inherited. The agent's job is to
move a **working** deployment forward, never to decide what a broken one should
become.

**AUTOMATIC ROLLBACK IS GIVEN UP DELIBERATELY.** Monotonicity (rule 2) forbids
deploying anything that is not a descendant of what is running, which means the
agent will never roll back. Rolling back becomes a human action — revert
forward as a new commit, or use the single override path. This is the trade
that buys protection against a silent downgrade to genuine, reviewed, *old*
code with the security fixes removed. Do not "fix" this without understanding
what it bought.

## The decision table

Evaluated in order. **Any refusal stops the run and is reported (see Reporting).**

| # | Check | On failure |
|---|---|---|
| 1 | The marker ref resolves to a SHA | refuse — cannot fetch or ref absent |
| 2 | That SHA exists locally after fetch | refuse — repository or key problem |
| 3 | **Baseline:** read the image tag of the *running* container | refuse — no baseline (see below) |
| 4 | Baseline tag parses as a 40-hex commit SHA | refuse — cannot compare against a non-commit tag |
| 5 | **Ancestry:** marker SHA is an ancestor of `release-v0.1-details` | refuse — commit is not on the branch |
| 6 | **Monotonicity:** marker SHA is a *descendant* of the baseline | refuse — would move backward or sideways |
| 7 | Marker SHA ≠ baseline SHA | no-op, quietly — already deployed, nothing to do |
| 8 | This SHA is not in the failed-builds record | refuse — already failed, needs a new signal |
| 9 | Build and deploy `--destination=test` | record the failure against this SHA, then refuse |

Checks 5 and 6 are two `git merge-base --is-ancestor` calls in opposite
directions. Both are local operations after a fetch; neither needs a credential
beyond the read-only deploy key.

### ⛔ Do not order by the image's build timestamp (#426)

The image now bakes `BUILD_TIMESTAMP`, and it is tempting to use it here — the
agent already reads the running container, and a build time looks like exactly
the freshness signal monotonicity wants.

**It is not, and using it would be a subtle correctness bug.** Build time orders
*images*; monotonicity must order *commits*. An old commit rebuilt today carries
a newer build stamp than a new commit built yesterday, so a build-time comparison
would accept a deploy that moves the branch **backwards** while looking forward —
which is precisely the silent downgrade rule 6 exists to prevent, arriving
through the field added to make deployments legible.

The baseline stays the commit SHA, and ordering stays `git merge-base`.
`BUILD_TIMESTAMP` is for a human reading the Diagnostics panel.

### Why the baseline comes from the container, never from a state file

Rule 3 reads the **running container's image tag**. That is ground truth. A
state file is the agent's *belief* about ground truth, and the two diverge
exactly when something has gone wrong — which is the moment the check matters
most. Ask the artifact, not the record of the artifact.

### Why there is no first-run mode

Rule 3 refuses when there is no baseline, and the obvious objection is that the
very first run legitimately has none. **The category does not exist, and it
must not be created.**

> **"First run" and "someone destroyed the container" are the same observable
> state.**

An agent that has never deployed sees exactly what an agent whose container was
just removed sees: nothing to read. It cannot tell them apart, and it must not
try — any mechanism that could distinguish them would be the agent's own
memory, which rule 3 exists to distrust.

So a first-run exemption is not a bootstrap convenience. **It is a permanent
bypass reachable by deleting a container.** Anyone who can stop the service can
re-enter the exempt state at will, and monotonicity — the only thing standing
between us and a silent downgrade — evaporates precisely when someone wants it
to. **A special case that fires once in the design fires whenever the
precondition recurs in reality.**

And in practice there is nothing to bootstrap: `test` is already running a
commit-SHA-tagged image, so the first real run reads a real baseline and rule 3
never fires. (Honestly: that state came from a manual deploy that was
architecturally the wrong way to do this. The wrong method produced exactly the
state the right method needs to start from.)

If there is no baseline anyway — a fresh environment, or a crashed container —
the agent refuses and a human deploys once, deliberately, through the override
path below.

## The trigger: polling — settled, with reasons

This was left open for a long time because it looked like it did not matter. It
does: it silently parameterised **two** separate arguments about the failed-build
record, from opposite directions, and both were wrong until it was pinned down.
A free variable does that to every argument that touches it.

**Polling, for two reasons — and the second is the one that decides it for an
unattended process.**

**1. Exposure.** Event-driven, in practice, means an inbound endpoint on the
production host: a listening port, a reachable URL, and a shared secret to verify
payloads. **This entire design exists to make that box uninteresting** — we
removed every credential we could, and put the remaining one (a read-only,
single-repository deploy key) where a leak buys nothing. Adding an inbound
listener to save a poll interval spends that.

**2. A poller recovers from its own downtime; a listener does not.** An event
delivered while the agent is stopped, restarting, or wedged **is simply lost** —
nothing replays it, and the agent resumes believing there is nothing to do. That
is a *permanent* silent stall from a *transient* fault, and it is exactly the
failure this design keeps finding new doors into.

A poller has no such state. It re-derives everything from the world on its next
tick: the marker, the baseline from the running container, and the branch. **It
cannot miss an event because it does not depend on events** — a restart costs one
interval, and the interval is the entire failure window.

That property matters more here than anywhere else, because the agent is
explicitly **not** a recovery mechanism (see non-goals). It must survive its own
restarts without a human, and a design that can permanently miss a signal cannot.

### What polling costs, honestly

Latency, bounded by the interval. And it makes the failed-build record
**load-bearing** rather than decorative — under event-driven triggering the
"needs a new signal" requirement would have bounded retries by itself. The capped
retry answers that, and it was chosen precisely because **it is invariant to this
question** — which is the general principle worth keeping:

> **When a parameter is unsettled, prefer designs that are invariant to it.**

That invariance is what made it safe to defer this decision. It is not a reason
to have deferred it.

## The single override path

**One mechanism serves bootstrap, recovery, and deliberate rollback.** Three
separate paths would mean two that are never exercised, and the rarely-exercised
path is the one that is broken when you finally need it.

The override is a human running the deploy directly. It is not a flag the agent
accepts, not an environment variable, and not a file the agent reads — anything
the agent can be *told* is something an attacker can tell it.

## Status and alerting are two needs, and only one of them is still open

These travelled together as "where do refusals surface" and they are not the same
question:

| | question | how it reaches a person | where |
| --- | --- | --- | --- |
| **Status** | what is deployed, is it current? | they go and **look** | **the Admin UI** — settled |
| **Alerting** | something is wrong, *now* | it must **come to them** | out of band — **still open** |

The status half is cheap and needs nothing new: the deployed commit is already
baked into the running image as an environment variable, so a panel showing it
requires no credential, no plumbing and no extra moving part.

**The panel is NOT approved and is not part of this design.** It was proposed as
an *alternative* to an alerting channel; the answer here is that both are needed,
and that has not been agreed. Nothing in this document authorises building it —
it is app-level UI work, and "cheap and easy" is written above as a cost estimate,
not as a recommendation to proceed.

### ⛔ Why the Admin UI cannot carry the alerting

**It is served by the thing being monitored.**

When the agent refuses, the UI is being served by the **old** deployment — so if
the display feature shipped after that deployment, *the feature is not there*.
The UI can only report failures that occurred **after the last successful
deploy**, which excludes precisely the failures worth reporting: the ones where
deployment stopped.

This is the same defect as the heartbeat, through a nicer door. A component
cannot report its own death, and a UI served by a stalled deployment cannot
report the stall. **It is a genuinely attractive wrong answer** — which is why
the reasoning is recorded here rather than the conclusion alone, because someone
will re-propose it.

### The two halves support each other

Once "what is running" has a home people can check themselves, **the out-of-band
channel only ever carries exceptions.** That is what makes a low-volume alert
path credible — and a channel that cries wolf is distrusted permanently, so
keeping routine traffic off it is a property worth having rather than a
convenience.

## Reporting: a refusal is an event

**Refusals must surface where a person will see them, not into a log on the box.**

An agent that refuses correctly, logs quietly, and does nothing produces a test
environment that silently stops updating — and nobody notices until someone
asks. **We have already lived this exact failure: `test` sat three months stale
and what surfaced it was a person asking, not a system telling.**

> **The design has to assume nobody is watching the box's logs, because for
> three months nobody was.**

So every refusal, and every failed build, is reported to a channel a human reads.
A silent refusal is a red that reaches nobody — the mirror of a green that means
nothing, and the same defect at the opposite polarity.

## The trigger and the failed-build record — one section, because they are one decision

**These were written as separate sections in the first draft, and that structure
produced a reasoning error.** Arguing them apart let each be justified under an
unstated assumption about the other, and nothing in either section looked wrong.
They are coupled and are presented together.

A build failure is recorded against its SHA and not retried without a **new**
signal. A retry loop on a permanently broken commit is a pinned CPU on the
production host — worse than a failed deploy, because it degrades the thing it
is deploying to, quietly and indefinitely.

**How load-bearing that record is depends entirely on the trigger:**

| Trigger | Status of the record |
| --- | --- |
| Event-driven, on the marker moving | belt-and-braces — no new signal, no retry |
| **Polling** | **the only thing preventing the loop.** After a failed build the container is still at the old baseline, so check 7 is true on *every* poll |

### The argument that does not work

The first draft argued the record is safe because it can only make the agent do
**less** — it can refuse a deploy, never authorise one, so rule 3's distrust of
agent memory is not violated.

**That is a property of a present entry, not of the mechanism.** A present entry
restricts; **a missing entry restores the default, and the default is to try.**
So corruption *toward present* is safe and corruption *toward absent* is
permissive. Under polling, the permissive direction is exactly the one that
reopens the loop the record exists to close.

### The rule that fixes it — rule 4, one level down

    store present, no entry for this SHA   ->  proceed. The normal case.
    store present, entry for this SHA      ->  refuse. Already failed; needs a new signal.
    STORE ABSENT OR UNREADABLE             ->  REFUSE. A missing precondition, not an empty set.

**No record is a missing precondition, not permission** — the same sentence as
rule 4, applied to the record instead of the baseline. Losing the store is no
longer indistinguishable from having nothing to say.

This does not make the record non-load-bearing under polling. It makes its
**failure mode fail closed**, which is the property the first draft claimed and
had not earned.

### ⚠️ Where the store lives is a correctness property, not an implementation detail

If the store sits anywhere a deploy can destroy — inside the app container, in a
path the deploy recreates, in a volume kamal replaces — then **every successful
deploy wipes it, and the rule above then refuses forever.** The agent would
deploy exactly once and then stop, and the symptom reads as a broken trigger
rather than a storage-location bug.

It must live somewhere the deploy provably cannot touch.

### What this protects against, precisely

**Accidental loss** — a disk problem, container recreation, a changed path. It
is **not** an adversarial control: someone who can write the store can empty it,
and present-and-empty is the permissive state.

That is acceptable, and the reason belongs here rather than being assumed: **the
store is root-owned on the box, so anyone who can write it already has root and
does not need this path.** Accidental loss is the realistic failure and it is
the one this addresses.

### ⚠️ The store does not exist on a fresh agent, and it refuses — deliberately

**This is the first thing that will happen**, exactly as with the container
baseline. Creating the store is a deliberate act through the override path.

The argument against "fixing" this is the same one made for the baseline, and it
is repeated here rather than cross-referenced, **because the person who hits this
will not connect the two sections**: an absent store and a destroyed store are
the same observable state. Making absent mean empty is not a bootstrap
convenience — it is a permanent bypass that reopens the retry loop whenever the
store is lost. A special case that fires once in the design fires whenever the
precondition recurs in reality.

## Concurrent runs

Two agent runs must not deploy at once. The agent takes a lock before check 1
and holds it through check 9; a run that cannot take the lock exits quietly
rather than queueing, since the next trigger will re-evaluate from current state
anyway.

**A tick that cannot take the lock exits quietly, and that is deliberate.**
Reporting every overlap would put routine traffic on the alert path, which is
what makes a channel ignored.

A lock left by a *killed* process is not a hazard: `flock` on a descriptor is
released when the process dies. **The remaining case is a live-but-stuck agent**,
which holds the lock indefinitely while every later tick exits quietly.

**That case is covered at the system level, not by the agent** — a wedged agent
stops advancing the deployment, so the tip-versus-deployed comparison goes red.
This is deliberately not delegated to the agent: adding a skipped-tick counter
would put the detection of a possibly-wedged process near the process itself, and
would need state carrying the same absent-versus-empty problem as the
failed-build store. **The outcome check already covers it, from an instrument
with an independent failure domain.**

(An earlier draft of this document claimed the agent reports a persistent lock.
It does not, and it should not.)

(Kamal takes its own deploy lock, which protects the deploy itself. This lock
protects the *decision*: two agents could otherwise both read the same baseline,
both pass monotonicity, and both act on it.)

## Hard constraints

- **TEST ONLY.** `--destination=test`, which loads `deploy.test.yml`. Never
  `deploy.yml` alone, never `rag.digdir.cloud`.
- **No secret values** in logs, output, or reports. Names, presence, length,
  `sha256[0:8]`.
- The deploy key is **read-only, one repository, git protocol only** — it cannot
  reach the API, cannot read other repositories, cannot write.

## Known-answer control, written down in advance

A 3b deploy of `6576352` must produce running image tag

    657635272287848e4db152ab5a9cd370c97121c8

That is the tag the manual deploy produced and which is running now. **Same tag
means the path reproduces a known-good result. A different tag means it built
something else** — which "it did not error" would hide.

## Enforcement prerequisites — what must be true for each check to answer at all

**Specifying a safety property is not the same as specifying its enforcement,
and the enforcement has prerequisites that live somewhere else.** That is not a
general worry: stage 1 shipped a forward-only guarantee whose enforcement needed
git history the runner did not have, because `actions/checkout` defaults to
`fetch-depth: 1`. The property was right, the enforcement was right, and the
prerequisite was in a default nobody was looking at.

Every check below is therefore listed with **what it needs in order to be able
to answer**, and what it does when it cannot. **A check that cannot answer must
refuse, never pass** — an instrument that cannot discriminate is not a weak
check, it is not a check.

| Property | Prerequisite | If the prerequisite is missing |
| --- | --- | --- |
| **Ancestry** (rule 5) | A clone with **enough history to compute `merge-base`** between the marker SHA and the branch. A shallow or partial clone cannot answer, and `git merge-base --is-ancestor` on a shallow repo can report a *wrong* answer rather than an error. | Refuse. **This is the shallow-checkout failure one layer down** and it is the most likely prerequisite to be violated, because a deploy agent's clone is exactly the kind of thing someone later "optimises" with `--depth`. |
| **Monotonicity** (rule 6) | The same history, plus the **baseline SHA being present in it** — the baseline comes from the running container and may be older than a truncated clone reaches. | Refuse. Note this fails *differently* from ancestry: history deep enough for one is not necessarily deep enough for the other. |
| **Baseline read** (rule 3) | The agent can **query the container runtime** and the running container **exposes a parseable image tag**. | Refuse — already rule 4. Rule 4 of the decision table exists because this prerequisite has been observed absent: the service ran a `:latest-test` tag for three months, which is not a commit and cannot be compared. |
| **Lock** (concurrency) | A lock location that **survives a deploy** and whose staleness can be judged — which needs a **clock and a recorded acquisition time**, not merely the lock's existence. | Refuse, and report. A lock whose age cannot be determined can only be treated as held, which wedges the agent — hence the requirement that a persistently unavailable lock surfaces as an event. |
| **Reporting channel** (startup) | `DEPLOY_AGENT_WEBHOOK_URL` set in the environment. The channel is **`ops-digdir-rag`**. | **Refuse to start.** Not a warning and not a config default: an agent with no way to report refusals *is* the silent failure this design exists to prevent, so it must decline to run rather than run mutely. Rule 4 applied to the agent's own startup. |
| **Failed-build store** (above) | A path the **deploy provably cannot destroy**, and the ability to distinguish *absent* from *present-and-empty*. | Refuse. Already stated as a correctness property, and it is a prerequisite rather than an implementation detail for exactly this reason. |

**Two of these five share a single prerequisite — sufficient git history — and it
is the same one that just failed in stage 1.**

### Two prerequisites the first pass missed

**The fetch is a prerequisite of the entire table, not of one check.** Every
comparison below operates on whatever the last fetch produced. If the fetch
fails — expired deploy key, network, the ref renamed — and the agent proceeds on
the previous fetch's data, **every check answers correctly about a stale world.**
The failure is silent in the worst way: marker equals baseline, so the agent
does nothing, reports nothing, and looks idle rather than broken. The fetch must
therefore be checked for success explicitly rather than for output, and a failed
fetch is a refusal like any other.

**⛔ The failed-build record cannot distinguish a broken commit from a broken
environment — and it must, or it will permanently block good code.**

Rule: a build failure is recorded against its SHA and not retried without a new
signal. That is right for a commit that does not compile. It is **wrong** for a
build that failed because the registry login had expired, the disk was full, or
the network dropped — all of which we have reason to expect, since **the
registry login is exactly what failed twice during this project's first manual
deploy**, and the box hosts production, CI and image storage on one disk.

In those cases the commit is fine, the environment was not, and **fixing the
environment does not clear the record.** The good commit stays blocked until an
unrelated new commit happens to land — and the symptom is "deploys stopped after
that one bad afternoon", with the cause weeks behind.

**The fix is not to classify the failure — it is to cap the retries.**

    a SHA may be attempted at most N times. Exhaustion is reported loudly.

That bounds *both* sides. A transient failure gets its retries and recovers on
its own once the environment is fixed; a genuinely broken commit stops after N
and says so. Neither "permanently blocked and silent" nor "retried on every
signal forever" remains reachable.

**And it removes the need to tell a broken commit from a broken environment at
all** — which matters, because in practice they are not distinguishable: a
compile error and an expired registry credential both surface as a non-zero exit
from the same `kamal` invocation. A rule that depends on separating them would
be a rule that almost never fires, leaving the record nearly inert.

**This is also the only option that is correct under both triggers**, which is
the deciding argument given that the trigger is still open:

| | event-driven | polling |
| --- | --- | --- |
| block permanently on first failure | safe, but poisons good commits | same |
| retry on any new signal | bounded — one per marker move | **reintroduces the loop the record exists to prevent** |
| **capped retries** | **bounded** | **bounded** |

The record's coupling to the trigger has now appeared twice from opposite
directions: *losing* the record is permissive under polling, and *weakening* it
is permissive under polling. A capped retry is the shape that does not care
which trigger is chosen.

*(Historical check, and it is why this is not theoretical: under the
block-on-first-failure rule, the registry-login failures during this project's
first manual deploy would have permanently blocked `6576352` — the commit being
used as the known-answer control. A rule whose first real-world encounter would
have poisoned the reference artifact does not survive contact.)*

### What a truncated clone actually does — measured, not assumed

| situation | `git merge-base --is-ancestor` |
| --- | --- |
| commit **absent** from the clone | `fatal: Not a valid commit name`, **exit 128** — loud |
| commit **present but disconnected** by the shallow boundary | **exit 1**, "not an ancestor" — a **silent false NO** |

A shallow boundary makes commits look **parentless**, which only ever *reduces*
reachability. So it can under-report ancestry and **cannot over-report it**:
there is no false YES. On the plain reading that is reassuring — a truncated
clone costs availability, not safety.

### ⛔ Except that the same false NO is safe or fatal depending on how the test is phrased

    is-ancestor(baseline, marker) ? proceed : refuse     false NO -> REFUSE    safe
    is-ancestor(marker, baseline) ? refuse  : proceed    false NO -> PROCEED   MONOTONICITY DEFEATED

**Both sentences mean "only move forward".** They are logically equivalent when
the data is complete, so nothing in review would flag the difference — and the
second is the more natural way to write *"refuse if it would go backward"*.
Under a truncated clone the second one silently permits exactly the downgrade
monotonicity exists to prevent.

> **RULE: every ancestry test must be phrased so that the FAILING exit means
> REFUSE.** Never so that a failure falls through to the permissive branch.

### Exit 1 and exit 128 are different answers and must not share a branch

`1` means *answered: no*. `128` means *could not evaluate — the commit is not in
the clone*. A naive `if ! git merge-base --is-ancestor ...` treats them
identically. That is safe under the phrasing rule above, but it **reports the
wrong reason**, and an instrument must not manufacture a verdict it did not
measure — the same requirement placed on the outcome check applies to the
agent's own `merge-base` calls.

### How deep is deep enough

**The requirement is driven by the OLDEST thing compared against, not the
newest.** Ancestry needs the marker and the branch connected; monotonicity also
needs **the baseline**, which comes from the running container and may predate a
truncated clone. **So the staler the deployment, the deeper the clone must be** —
a counterintuitive coupling that nobody provisioning the box would guess, which
is why the agent asserts its own history depth at startup rather than trusting
whatever provisioned it.

## ⚠️ The outcome check needs THREE values, not two

The check as first conceived compares **deployed** against the **marker**. That
has a blind spot which was theorised and then confirmed live within minutes:

> **A failed marker job looks exactly like a quiet period.**

If the job that publishes the marker fails, the marker stays where it is. The
agent correctly deploys nothing, `deployed == marker`, and the check reads
**green** — while the branch has moved on and nothing is being deployed at all.
This is not hypothetical: `mark-green` failed on its second real merge and left
the marker stale, and nobody noticed until someone went looking.

**The marker is only as good as the job that publishes it.** So the assertion
needs the branch tip as well:

| comparison | what a persistent difference means |
| --- | --- |
| branch tip vs **marker** | CI is red, or the publisher is broken. Either way nothing new can deploy. |
| marker vs **deployed** | the agent is not acting on a signal it has been given. |

Both are worth surfacing and they have different causes and different owners.
Collapsing them into one check means the most likely failure — a broken
publisher — is invisible, because the two values it compares agree with each
other while both being wrong.

### Why it must compare STATE, not events

The strongest case for the third value is not that a failed publisher looks like
a quiet period. It is this:

> **An event-watching check can only see things that happened. A state
> comparison sees things that failed to happen.**

Concretely: rapid merges cause GitHub's concurrency group to **cancel** the
earlier run. A cancelled run never executed `mark-green`, so the marker stays
behind — **and nothing anywhere records a failure, because a cancellation is not
one.** A check watching for failed runs correctly ignores cancellations, since it
is a check about CI health. So this is a route to a stale marker that a
failure-watching check **cannot see by design, not by oversight**.

Comparing the branch tip against the marker catches it by construction, because
it asks what the world looks like rather than what events occurred. Any cause of
a stale marker — failure, cancellation, a job that never triggered, a workflow
edited into inertness — presents identically and is caught the same way.

**Neither comparison can say *why*.** A correct refusal and a dead agent produce
the identical red, and that is acceptable — in every one of those cases a human
genuinely needs to look — **but nobody should read the red as diagnostic.** The
refusal messages carry the reason; the check only ever says *that*.

## Open questions for review

1. ~~Where do exceptions go?~~ **SETTLED: `ops-digdir-rag`**, out of band, with
   routine status in the Admin UI (unbuilt, unapproved). The webhook URL is a
   *startup precondition* — see the prerequisites table.
2. **Whether the marker ref should be protected by a ruleset.** Measured: a
   ruleset can protect a ref pattern and blocks even a repo admin. **Not
   measured:** whether CI can be granted a bypass, so protected-and-CI-writable
   is not yet known to be simultaneously achievable. Without it the signal is
   *conventionally* CI-only, not CI-only — anyone with repo write can move the
   ref by hand. Harm is bounded (see below) but the limit should be stated.
3. ~~How the agent is triggered~~ — **SETTLED: polling.** See below.

## What a hostile ref-push actually buys

Worth stating plainly, because it is what makes the unprotected-ref case
tolerable. The agent builds from the commit it fetched, so an attacker cannot
choose the **bytes** — only the **commit**. Ancestry means it must be a commit
on `release-v0.1-details`; monotonicity means it must be *newer* than what is
running. So the worst outcome is **a forward deploy of real branch code that
happened to be red** — an availability and quality problem, not code execution.
It requires repo write, which is already a trusted set, and it leaves a
git-visible trace.

Compare the alternative we rejected, where the equivalent move ships **arbitrary
bytes under a legitimate commit's name**. Same class of actor, completely
different blast radius.
