# Self-hosted CI runners — `digdir-rag-1` and `digdir-rag-2`

Two GitHub Actions runner containers for `itonomi/digdir-headless-rag`, running
on **kamal-1** (`5.75.220.232`) as a co-tenant beside closser's `ci-fleet`
(`htz-a1..a4`) and the Kamal-deployed production services.

**This box serves `rag.digdir.cloud`.** PR code therefore executes on the same
machine as production. That is a known and explicitly accepted trade, made by
the PI over a raised flag, because the deploy path needs it — the repo's Kamal
plan builds on the Hetzner box so the runner never needs Docker. It is not an
oversight, and the sizing below is not a security control.

## If CI stops — read this first

**`ci.yml` runs on `digdir-rag-1` and `digdir-rag-2`, both on kamal-1.** Two
runners cover one of them being down; they do **not** cover the box being down,
and they do not cover both being restarted together. If both stop, every PR in
this repo stops.

**What it looks like:** your PR's checks sit at *Queued* and never start. The
Actions tab shows no progress. **The runner settings page will very likely say
`status=online`, and that means nothing** — a runner can be online, idle,
correctly labelled, and still never pick up a queued job. That exact state was
observed here for 13+ minutes (see the restart-window finding below). Do not
spend time interpreting the green dot; it is not a signal.

**Unblock in this order:**

1. **Re-dispatch the job.** Cancel the queued run and push again, or
   `gh run rerun <id>`. A job stranded by a reconnect window is never picked
   up on its own, but a fresh dispatch is taken instantly. This costs seconds
   and fixes the most likely cause.
2. **Restart the container** (safe; it is ours, and it does not touch closser's
   runners or the prod service):
   ```bash
   ssh kamal-1 'docker restart digdir-rag-1'   # or digdir-rag-2
   ```
   Restart only the one that is stuck; the other is your cover while it
   reconnects. No registration token is needed — credentials persist inside
   the container.
   Give it ~2 minutes to get past `A session for this runner already exists`,
   then re-dispatch per step 1.
3. **Revert to hosted runners.** One line in `.github/workflows/ci.yml`.
   Replace:
   ```yaml
   runs-on: [self-hosted, digdir-rag]
   ```
   with exactly:
   ```yaml
   runs-on: ubuntu-latest
   ```
   That is the whole revert. CI ran this way until 2026-08-31 and the
   `ci-fallback-canary.yml` workflow keeps proving it still works — run it on
   demand with `gh workflow run ci-fallback-canary.yml` if you want to confirm
   the hosted path is healthy before switching.

**There IS an automatic alert, but it is hourly, not instant.**
`ci-queue-watchdog.yml` goes red within ~75 minutes of a job stranding, and
separately if an open PR has no run at all. Do not reach for a job-level
`timeout-minutes` to make it faster — that was measured and does not fire on a
queued job. See "The job timeout does not do what it looks like" below.

## What two runners actually buy — measured, not assumed

**Tested by producing the failure**: runner 1 was restarted and a job dispatched
inside its reconnect window.

> **Result: `digdir-rag-2` picked it up after 63s and the job passed**, while
> runner 1 sat in `A session for this runner already exists`. Compare the
> one-runner case, where the equivalent job was never picked up at all.

So the sibling rescue is real. **Read its bounds precisely:**

- It covers **one** runner reconnecting. It does **not** cover both restarting
  together — which is exactly what `docker compose restart`, a host reboot, or
  an image update does, and those are the common causes.
- That is what the **90-second `entrypoint` stagger on runner 2** is for: it
  defeats compose's default of starting everything in the same instant, so the
  two reconnect windows do not coincide. It reduces the overlap; it does not
  remove it.
- 63s is not instant. The job still waited.

## The job timeout does not do what it looks like

A job-level `timeout-minutes` was the obvious way to turn a silent stranding
into a visible red X. **It does not work, and this was measured rather than
assumed.**

A probe job requesting a label no runner has, with `timeout-minutes: 2`, was
left queued and observed for **640 seconds — over 5x its own timeout — and
never failed.** It stayed `queued`.

**`timeout-minutes` clocks a job's execution, not the time it spends waiting
for a runner.** So it is inert for precisely the failure it appears to guard
against. Do not add one and believe the repo is covered.

**What is there instead: `ci-queue-watchdog.yml`.** It runs on `ubuntu-latest`
hourly and fails loudly if any run has been queued beyond 15 minutes — so a
stranded job surfaces within about 75 minutes, against the *forever* it sits
undetected today.

The substrate is half the design: a monitor that runs on the runners it is
monitoring shares their failure domain and goes quiet exactly when it is
needed. Do not move it onto `[self-hosted, digdir-rag]`.

**Both arms were tested before it landed**, on a throwaway branch with a
permanently-queued job present:

| threshold | output | result |
|---|---|---|
| 15 min | `queued runs examined: 2` / `No run has been queued longer than 15 minutes` | **success** |
| 0 min | named all three queued runs with their wait times | **failure** |

A guard nobody has watched go red is not a guard. It also prints the number of
runs examined beside its verdict, because "0 stranded" means something
different when 0 runs were examined than when 12 were.

Two known limits, stated rather than discovered later: the runner-count step
needs `administration:read`, which the default token does not carry, so it
reports **"this check was NOT performed"** instead of a reassuring nothing;
and scheduled workflows are disabled after 60 days of repository inactivity.

### The other half: a PR with no run *at all*

The queue check above is blind to a run that is **never created** — zero queued
runs reads as green. So the watchdog also flags any open PR whose head has no
workflow run, after a **5-minute grace**.

**The grace is measured, not picked.** Sampling too early reports "no run" for
something perfectly healthy, and a red that means nothing degrades a guard
faster than no guard at all:

| window | measured |
|---|---|
| head commit → run object exists | 3–12s (n=12 PRs) |
| run created → job started | 2s at p50 *and* p90, max 3s (n=20) |
| total, head → running | **~15s worst observed** |

Five minutes is ~20x that. **⚠️ Limit: n=16, all in one afternoon with both
runners mostly idle — do not read 15 seconds as a guarantee under load.**

It does not need to scale with the fleet, and this is why: the *no run at all*
state is GitHub-side **association**, which runner pressure cannot widen. A
busy fleet produces a **queued** run — a different state, owned by the check
above. Two failure modes, two places, two guards.

### The third: post-merge CI on the default branch

The two checks above watch **silence**. This one watches **failure**, and the
distinction is why it was missing for so long: **a PR's red X gets seen because
somebody is blocked on it. A post-merge run has no audience at all** — it goes
red and the tab is already closed.

**This was not hypothetical.** It was written down as a theoretical gap and
found to be live minutes later: merging #417 left `mark-green` failing while
`lint-and-test` passed, so the deploy marker ref `refs/heads/deploy/test-green`
stayed pinned at an older commit and nobody noticed — *including the person who
had just described the failure mode*, who reported the merge "verified green"
on the strength of a different workflow on the same commit.

⚠️ **This is why it was built before any check that compares deployed-vs-marker.**
Such a check trusts the marker, and **a marker produced by an unwatched job is
not trustworthy** — a stale marker makes deployed-equals-marker true, so the
check reads green while nothing is deploying. Watch the producer before relying
on the product.

Both arms, with only the watched branch changed:

| watched branch | output | result |
|---|---|---|
| `release-v0.1-details` | `newest completed push run … 5c684c8d -> failure`, named `mark-green: failure` and the stale-marker consequence | **failure** |
| `test/green-target` | `newest completed push run … 83083317 -> success` | **success** |

The red arm was **not synthetic** — the default branch was genuinely red at the
time.

## Why self-hosted at all — it is NOT cost

This repo had contributed **zero** to the org's Actions spend when these
runners were built — it appeared in no billing record, and a run costs about
three cents. The reason for self-hosting is the deploy path, not minutes. If
you find yourself tuning this for minutes saved, you have the wrong objective.

**That zero is no longer strictly true, and it is worth knowing why:**
`ci-queue-watchdog.yml` and `ci-fallback-canary.yml` both run on
`ubuntu-latest` deliberately — the watchdog because a monitor must not share
its subject's failure domain, the canary because its whole job is proving the
hosted fallback still works. Together they are roughly **720 hosted
minutes/month**, and that is the price of the two things that would otherwise
have to be trusted rather than checked.

## The envelope, and why each number

| Setting | Value | Reason |
|---|---|---|
| `cpuset` | `24-25` | closser pins `htz-a1..a4` to cores 0-23 and nothing else on the box pins a cpuset, so these two cores overlap no other pinned workload. The box is **32 cores** — the `ci-fleet` compose header calls it 24-core, which is stale. |
| `mem_limit` | `8g` | GitHub's `ubuntu-latest` is 2 cores / 7 GB and our suite already passes there, so this is the configuration we have been running on all along, plus headroom. Measured peak on a cold run: **1.8 GiB**. |
| `memswap_limit` | `8g` | Set **equal** to `mem_limit` on purpose: this box has **zero swap**, so there is no cushion to grant. Docker's default would be `2 x mem_limit`, implying an allowance that does not exist. |
| `cpu_shares` | `512` | Copied from `ci-fleet`. The Kamal co-tenants run at the default ~1024, so under contention the **production service outranks CI 2:1**. Do not raise this to make CI faster. |
| `restart` | `always` | Survives a reboot; `docker` and `containerd` are both `systemctl is-enabled`. |
| docker socket | **not mounted** | Matches `ci-fleet`. Mounting it would let workflow code reach the production containers. |

## Image

Reuses `closser-runner:latest` — the image closser's four runners already run —
rather than building our own. Its `entrypoint.sh` is repo-agnostic: `config.sh`
is driven entirely by `REPO_URL`, `RUNNER_TOKEN`, `RUNNER_NAME` and
`RUNNER_LABELS`, so pointing it at a different repository is the whole change.
Building our own on a production host would spend disk and CPU for no
behavioural difference.

**Known coupling, accepted:** closser owns that tag. If they rebuild it, our
container picks the new image up on the next `up -d` recreate — never
spontaneously. Image ID at adoption:

```
sha256:d21476d5c2a75e09ede502fdf66ef7520ac565a7e85b0a84e43f4ea3e87a89fb
```

If that no longer matches `docker image inspect closser-runner:latest`, the
image has moved under us. That is not necessarily a problem; it is a thing to
know before debugging a mystery.

## Labels

`self-hosted`, `Linux` and `X64` are applied by the runner itself. **`digdir-rag`**
is what makes a job land on ours rather than one of closser's four.

Their runners are registered to `itonomi/closser` at **repository scope**, and
the org has zero org-level runners — so they can never run our jobs, whatever
labels are applied. That is why we run our own container rather than reusing
theirs.

## Bringing it up

```bash
# On the box. The token is short-lived (~1h) and consumed only when the
# container is FIRST created; restarts and reboots reuse the credentials
# already written into /actions-runner/.runner inside the container.
gh api -X POST repos/itonomi/digdir-headless-rag/actions/runners/registration-token --jq .token \
  | ssh kamal-1 'umask 077; R=/mnt/HC_Volume_106331869/digdir-ci
      { printf "RUNNER_TOKEN="; tr -d "\n"; printf "\nDIGDIR_CI_ROOT=%s\n" "$R"; } > "$R/.env"'

ssh kamal-1 'cd /mnt/HC_Volume_106331869/digdir-ci && docker compose up -d'
```

Never echo the token. Pipe it.

## Caching

There is deliberately **no `actions/cache` step** in the self-hosted workflow.
`~/.m2` is a bind-mounted volume on the box, so the cache is simply present
between jobs — no upload/download round trip. This is the same reasoning
`ci-fleet` applies to its `cargo-home` mount.

## Measured, not assumed

Three green runs on this runner (via the pre-cutover canary, since replaced by
`ci-fallback-canary.yml` — see "If CI stops"), against three
recent `ci.yml` runs on `ubuntu-latest`:

| | mise | lint | config tests | unit tests | **total** |
|---|---|---|---|---|---|
| self-hosted, run 1 (**cold**, no caches) | 43s | 95s | 68s | 118s | **338s** |
| self-hosted, run 2 (warm) | 16s | 28s | 61s | 117s | **230s** |
| self-hosted, run 4 (warm, post-restart) | 15s | 29s | 61s | 117s | **229s** |
| `ubuntu-latest` x3 | 7-9s | 27-40s | 57-83s | 106-149s | **206 / 294 / 258s** |

Peak container memory observed: **1978 MiB of the 8192 MiB ceiling** (24%).

**Verdict on the envelope: 2 cores / 8 GB is not too tight.** Warm runs land at
229-230s, inside the `ubuntu-latest` spread of 206-294s. Note what this does
*not* say: with N=2 warm self-hosted runs against N=3 hosted runs whose own
spread is 43%, this is evidence of **parity, not of an improvement** — do not
quote it as "faster". The first run is slower because it is genuinely cold;
`~/.m2` is a persistent volume, so that cost is paid once, not per job.

## Ephemeral runners — DECIDED AGAINST. This is a closed question.

**Read this as a recorded fact, not as a task somebody will get to.** The PI
decided against ephemeral runners on 2026-08-31 and the need was routed around
instead: the privileged mark-green job runs on `ubuntu-latest`, which is
ephemeral by construction, so **nothing of ours depends on these runners being
ephemeral.**

### The residual risk, stated plainly

**Untrusted PR code still executes non-ephemerally on the box that hosts
production.** A job can leave state behind — in the workspace, in `~/.m2`, in
the container filesystem — that a later job on the same runner can read.

- This is **pre-existing**, not introduced by anything here, and it is the same
  posture as closser's four runners on the same box.
- Whether to change it is **closser's fleet decision, not ours** — they own the
  image and the majority of the runners.
- **It is the reason no privileged job may run on these runners.** That is the
  live constraint this section exists to carry. If someone later wants a job
  with deploy credentials, a package-publish token, or write access to
  production, the answer is `ubuntu-latest`, and this paragraph is why.

### Why it was not done, kept for the next person who proposes it

An ephemeral runner deregisters after every job and must re-register, and a
registration token expires in about an hour — so a container holding a one-shot
token works **once** and then silently stops coming back, tomorrow rather than
today. It needs a credential that can *mint* tokens each cycle: a PAT with repo
administration write, or a GitHub App.

**Checked at the time, and the box had neither:** no `gh` config for root, no
`gh` binary on the host, no `~/.netrc` or `~/.git-credentials`; the only GitHub
material in `.env` was the two one-shot registration tokens.

Note also that the shared `closser-runner:latest` image's entrypoint has no
`--ephemeral` path, and **that image must not be modified** — closser's four
runners run from it. The clean route would be overriding `entrypoint` in this
compose file, the same mechanism already used for the stagger.

<details>
<summary>Original framing, superseded</summary>

Ephemeral was investigated as the **root fix** for the reconnect-window
stranding — no long-lived session means nothing for a reconnect to conflict
with. That reasoning still holds; it simply stopped being worth the credential.
The stranding is instead covered by two runners, the stagger, and the queue
watchdog.
</details>

## What is NOT verified, and one thing found

**Host reboot: not verified.** `restart: always` is set and `docker` and
`containerd` are both `systemctl is-enabled`, so the container should return —
but confirming it would mean rebooting a production box, which is prohibited.
Reasoned, not measured.

**A container restart strands jobs dispatched during its reconnect window.**
Measured, not predicted. On `docker restart`:

- credentials persist correctly (`already configured as digdir-rag-1 — skipping
  config`), so **no fresh registration token is needed** to restart or reboot;
- the runner self-updated 2.335.1 -> 2.336.0 during the restart;
- for ~2 minutes it logged `A session for this runner already exists` /
  `Runner connect error: Conflict. Retrying until reconnected.` before
  `Runner reconnected`;
- **a job dispatched inside that window sat queued for 13+ minutes and was
  never picked up, even once the runner was back online and idle.** Cancelling
  and re-dispatching it was picked up instantly, and that run passed in 229s.

So the runner recovers on its own but the *queue entry* does not. After a
reboot or a deliberate restart, re-dispatch anything that was queued during
the window rather than waiting on it. `status=online` on the settings page was
true throughout and told you nothing — which is why the check here is a job,
not a status field.
