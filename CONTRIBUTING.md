# Contributing

> **PROTOTYPE.** This project is a working prototype, not a hardened production
> system. Expect rough edges — see `plans/proposed/release-v0.1-rough-edges-inventory.md`
> for the known ones. Contributions that shrink that list are welcome.

## Before you start

Read `README.md` and `docs/onboarding.md` first. `docs/system-overview.md` is the
architectural spine — link into it from your PR description rather than repeating it.

## No secrets in this repo — ever

**Never commit credentials, API keys, tokens, or `.kamal/secrets*` files.** Secrets
are managed in a shared secrets vault and injected as GitHub Environment secrets at
deploy time; `.kamal/secrets*` is gitignored on purpose and must stay that way. If
you need a value to develop locally (DB credentials, `CONFIG_MASTER_KEY`,
`JWT_SECRET`, API keys), ask a maintainer for vault access — don't paste a working
value into a file that gets tracked, a commit message, or a PR description.

If you accidentally commit a secret, don't just delete it in a follow-up commit —
it's still in history. Tell a maintainer immediately so the credential can be
rotated.

### If you sweep for secrets, redact once at the output

A sweep for hard-coded credentials had **six patterns. Five redacted the matched
value; the sixth did not**, and printed a live key into a session transcript —
**while looking for exactly that class of problem.**

**The redaction was per pattern, so it was six separate decisions and one was
wrong.** Redact in the pipeline, once, after the match and before anything is
printed. Then adding a seventh pattern cannot reintroduce the bug.

*Marginal exposure there was small — the value was already tracked and in
history. But an agent transcript is a different system with different retention
and access than the repository, so it is a real widening, and it argues for
rotation rather than against it.*

**Characterise, do not print.** A value can usually be described precisely
enough to reason about without being revealed: *32 characters, base64-ish, six
digits and twenty-five letters, no `dev`/`test`/`change-me` substring* is enough
to tell a generated key from a placeholder. And **compare by hash** — SHA-256
prefixes settle *"are these three copies the same value"* without any of them
appearing anywhere.

## Branching

Branch off the current release line (`release-v0.1-details`, or whatever
`README.md` names as the active line) using a short, descriptive, kebab-case topic
name — e.g. `fix-mcp-auth-header`, `docs-onboarding-rewrite`. No enforced
`feature/`/`fix/` prefix convention; match what you see in recent branch names.

### At release: snapshot and restart

Decided in #34. When the release is cut, `release-v0.1-details` is **squashed
onto `agentic-skills`**, the release branch goes **read-only**, and all future
work bases off the squashed commit — not off the archive.

Nothing syncs the two branches afterwards, so there is no merge base to lose:
the usual objection to long-lived divergence does not apply to a
snapshot-and-restart. *(As of 2026-08-24 the release has not been cut;
`agentic-skills` is still at the pre-release commit. Until then, branch off
`release-v0.1-details` as above.)*

### 🔒 The archive branch must not be deleted

`release-v0.1-details` is retained **read-only and permanently**. It is not a
stale branch awaiting tidy-up.

It carries the reasoning several current beliefs rest on — the model
head-to-head run log, the judge-truncation contamination audit, the negative
results that stopped us repeating work. Squashing preserves the *code*; only
the branch preserves *why*. Deleted in a routine cleanup a year from now it
goes silently, and nobody will know what they lost, because the absence of a
justification looks exactly like a decision nobody wrote down.

> The count is not the point and it moves — it was 618 when #98 was filed and
> 943 on 2026-08-24 — but the shape is: this is most of the project's
> reasoning, and one `git push --delete` ends it.

**This is enforced, not merely asked for** — a rule that depends on everyone
remembering it survives until the first person who has not read this file.
Verified 2026-08-24 on the live branch protection:

```
allow_deletions:    false     <- deletion is already blocked
allow_force_pushes: false
lock_branch:        false     <- still writable, correct until the release is cut
```

So the archive cannot be deleted today, by anyone, including by accident.

**The remaining step belongs at release time, not now.** Making the branch
read-only is `lock_branch`, and enabling it before the release is cut would
stop every lane landing work:

```sh
gh api -X PUT repos/itonomi/digdir-headless-rag/branches/release-v0.1-details/protection/lock_branch \
  -f enabled=true
```

Run that as part of cutting the release, after the squash onto
`agentic-skills`.

### A worktree is not a backup

This repo is used through many worktrees at once, and unsaved work in one is
invisible from everywhere else — no claim check, no CI, no `git log` on any
other branch will show it.

**Before removing a worktree, check both**, because they fail differently:

```sh
git -C <worktree> status --short          # uncommitted — exists only on that disk
git -C <worktree> log --oneline @{u}..HEAD  # unpushed — exists only in that clone
```

And the sharper question, when a branch looks like it has unpushed work:

```sh
git -C <worktree> log --oneline HEAD --not --remotes   # reachable from NO remote
```

That last one is what separates *alarming* from *at risk*. Surveying every
worktree on 2026-08-24, one branch showed **88 unpushed commits** — and **0**
of them were unreachable from a remote, because they were merge commits for
PRs that had already landed. The commits were safe. What was genuinely at risk
in that same worktree was **156 tracked files staged or modified**, which
existed nowhere else at all. Count the commits and you raise a false alarm;
count the working tree and you find the real one.

#### The third axis: untracked and not ignored

The two checks above are about *commits* and *tracked* files. There is a third
category, and it is the only one with **no copy anywhere and no recovery
path**:

```sh
git -C <worktree> status --porcelain | grep '^??'
```

`--porcelain` already excludes ignored files, so every `??` line is a file
that exists **in exactly one place on earth**. A modified tracked file still
has its committed version; an unpushed commit still lives in the object
store. An untracked, unignored file has neither — one `git clean` and it is
gone, with nothing to recover it from and no warning that it existed.

**And the count misleads here too, in the same way.** Surveyed across all
worktrees on 2026-08-24: one held **103** such files and another **11**, but
almost all of those were generated sweep output under `server/results/`. Four
other worktrees held **exactly one file each** — and those were hand-written
documents and plans, the things with no way to regenerate them. So:

> Read the `??` list, do not count it. Generated output inflates the number;
> the signal is a hand-written file under `docs/`, `plans/` or `scripts/`.

Snapshot, not a standing claim — like every count in this section, it was true
on the day it was taken.

### Preserving a branch makes work recoverable, not known

Both are needed and only one of them is a git operation.

- **Findings belong in the findings document, not only in a branch.** The
  document is what survives a stack migration; a branch is what survives a
  `gc`.
- **Tag anything worth keeping that no branch references.** An unreferenced
  commit is one `gc` from gone, and nothing warns you.

## Commit style

- Concise, imperative subject lines (`Fix X`, `Add Y`, `Remove Z`) — look at
  `git log --oneline` on the branch you're targeting for the house style.
- **Do not add a `Co-Authored-By:` trailer or any other attribution/co-author
  footer**, including AI-assistant attribution lines. This applies whether you're
  committing by hand or through an AI coding tool.
- Prefer a small number of coherent commits over one giant diff, but don't force a
  history rewrite just to satisfy this — ask before force-pushing a shared branch.

## Before you push

Run, from the repo root:

```bash
bb lint          # clj-kondo
bb test-config   # config-resolution tests
bb test          # full unit test suite
```

All three must pass locally. CI (`.github/workflows/ci.yml`) runs the same three
commands on every pull request — if it's red locally, it'll be red there too, so
catch it first.

Then check what your commits **delete**:

```bash
git log --diff-filter=D --name-only origin/release-v0.1-details..HEAD
```

**If it names a file you never touched, stop.** The base moves several times an
hour here, and `git reset --soft` onto a ref that has moved records other
people's merged work as deletions in your commit. One lane produced exactly
that — 367 lines from two other branches, staged as removals — and it was never
pushed only because the push failed for an unrelated reason. Luck, not process.

Use this form, not `git diff <base>..HEAD`. The diff form asks *does my branch
differ from a base that moved*, so on a stale branch it reports every file added
since you branched as a deletion — the same alarming output as the real thing,
for a benign reason. Its answer also changes over time without your code
changing. The form above asks *does my commit delete anything*, and that answer
is stable.

**The same trap catches the file count.** If you predict how many files you
changed — and you should — read that count from your commit, not from a
two-dot diff:

```bash
git show --stat HEAD                       # what did I change
git diff <base>...HEAD --stat              # three dots: diff from the MERGE-BASE
git diff <base>..HEAD --stat               # two dots: WRONG once the base moves
```

A lane predicted four files. `git diff <base>..HEAD` reported **five** — the
extra being `CONTRIBUTING.md` with **44 deletions it never made**, which were
another branch's *additions* merged after it branched. **The prediction was
right and the instrument was wrong.**

**And the same stat catches a format-destroying edit, which is not what it was
added for.** A lane predicted 7 files and saw **+3,413 lines** — it had
pretty-printed a config snapshot that is stored as a single compact line. The
content was correct and *the diff was unreviewable*. **A 3,200-line diff on a
config file is a change nobody can review, which means it is a change nobody
would have checked.** Redone in the original format: one line changed. It caught this by checking what its own
commit contained rather than hunting for work it could not remember doing —
the alternative being to go looking for phantom changes, or to "fix" them.

`<base>..HEAD` silently starts answering a different question the moment anyone
else merges, which on this repo is several times an hour.

## After you push a correction, check it landed

`git push` exiting 0 answers *did the push work*, not *did it land on base*. If
the PR for that branch has already merged, a push updates the branch and changes
nothing — and **"pushed but stranded" is the quiet answer nobody goes looking
for.** Three steps, because each one catches a different failure:

```bash
git fetch origin                                              # 0. REQUIRED
grep -q needle path/to/file                                   # 1. pattern valid
git show origin/release-v0.1-details:path/to/file | grep needle   # 2. present now
git show <your-commit>^:path/to/file | grep -q needle         # 3. absent before
```

**Step 0 is not optional.** `git show origin/<branch>` reads your **local
remote-tracking ref**, not the remote — it is only as fresh as your last fetch.
You run this check immediately after a merge, which is exactly the moment that
ref is *guaranteed* to be behind, so without the fetch **the rule fails most
reliably in its primary use case.** One lane got a clean, mechanical-looking zero
on a pattern it had already validated locally: before fetch 0, after fetch 1,
nothing else changed.

The cost of a false strand is not only wasted time. It is a re-push, a duplicate
PR, or **re-applying work already on base** — which turns a stranded change into
a duplicated one.

So a **failing** base-grep has three causes, worth ruling out in this order: your
ref is stale (step 0, and **the most likely of the three in practice**), your
pattern is wrong (step 1), or it is genuinely stranded.

This *strengthens* the asymmetry rather than qualifying it. All three are
**false-negative mechanisms** — a stale ref is *behind*, so it can only fail to
show text that is there; a wrong pattern can only fail to match. **A pass is safe
from all three; a fail has three explanations.**

One exception, so nobody reads the pass as unconditional: if base moved
**backwards** — a force-push or a revert — a stale ref can show text that is no
longer there, giving a false pass. That needs history rewriting, which is
precisely when someone would be checking.

**1 — validate the pattern locally.** If it does not match your own file, the
pattern is wrong and the base result means nothing. This is the presence-check
analogue of `assert s.count(old) == 1`: it turns the one outcome needing
judgement into one that does not.

**If you are checking that something is GONE, validate the pattern against a
revision that still has it.** An absence check is not self-validating: a zero
means *removed* or *my pattern was wrong*, and those are indistinguishable. You
cannot use your working file, because you deleted the thing. So use git:

```bash
git show <a-revision-that-still-has-it>:path/to/file | grep -c needle   # expect >0
git show origin/release-v0.1-details:path/to/file    | grep -c needle   # expect 0
```

Both halves answer the same question — **can this pattern match at all?** For a
presence check your own file proves it; for an absence check an older revision
does. Neither needs judgement.

*The one case with no mechanism* is genuinely **new** content: the pattern is
absent locally *and* on base by definition, and no revision has it. This section
was written that way. There the honest check is to read the section on base and
know that you did, rather than trust a mechanical zero with nothing behind it.
Note this is narrower than it first appears — for anything you **removed**, git
always has a revision where it existed.

**2 — confirm it is present on base.** This is a presence check on the artifact,
so by the asymmetry rule a *pass* is self-validating **about the target**: the
path resolved and the file exists.

**3 — confirm it was absent before your change.** The asymmetry does **not**
cover specificity. A needle generic enough to match text that was already there
passes without your change ever landing, and it passes *loudly*, which is the
dangerous direction. Wrong-target and non-specific-needle are different failure
modes and step 2 protects against only the first. **If step 3 matches, your pass
proves nothing.** Running it over thirteen merged changes is the difference
between thirteen passes and thirteen *established* passes.

**Two patterns that cannot match even a landed change**, both of which break
steps 1 and 2 alike:

- *Namespace-map syntax.* A key you think of as `:config.node/id` is stored in
  source as `:id`, so **any grep for a qualified keyword in Clojure source is
  unreliable** and a zero is not evidence of absence. Worked example under *use
  the parser, not a regex over the format*.
- *Hard-wrapped prose.* `grep` is line-based, so a multi-word needle that
  straddles a line break matches nothing. Writing this section produced one:
  the phrase chosen to test it spanned lines 111-112 of this file and step 1
  caught it immediately.

Neither of these is hypothetical. A correction to a wrong comment in
`openapi.yaml` sat on a dead branch while base carried the false claim it had
already fixed, because its author verified their reasoning rather than that it
shipped. And on this rule's first live use, a base grep returned zero, read as
*my fix is stranded too*, and the fix was fine — the pattern was a qualified
keyword that could not have matched a landed change either. Acting on that zero
would have "recovered" a fix already on base, most likely producing a duplicate
definition, which is worse than the strand.

The corollary, on process rather than on git: **once you report a PR as ready,
treat it as merged.** Anything further belongs on a new branch. If you already
know you want a follow-up, say so in the report so the merge can be held.

## The propagation gap: correct the claim, not the artifact

**The rule above asks *did my correction land* — singular, in the artifact you
were already looking at. It never asks how many artifacts carried the claim.**

A false explanation of a config defect was corrected on the issue where it was
raised, and left standing in a published readiness page. **Same sentence, four
places, one fixed.**

> **The asymmetry is the whole problem: the origin of a claim is a fact you
> hold. Its copies are not.** Nobody keeps a list of where they have said
> something.

**So when you correct a claim, grep for the claim.** The two checks compose:
confirm the fix landed on base, *and* search the claim's distinctive phrasing
across issues, pull-request bodies, docs and anything published.

**What makes this tractable is that a propagated claim is usually verbatim.**
You do not re-derive a sentence each time you repeat it — you paste it. So the
copies are findable by their phrasing rather than by their meaning, which is the
one lucky property of this failure mode. *"the schema has no parent attribute"
would have found the page in one command.*

**Include the artifacts that are not in the repository.** A published page, an
issue comment, a message to a stakeholder — those are where a wrong claim does
its damage, and none of them appear in a `git grep`.

### The documented example is part of the change

**If you change a public request, response, header or status, the documented
example changes with it, in the same commit.**

This is the propagation gap pointing forward rather than back. Above, a *claim*
was copied to four places and corrected in one. Here the copy is
`server/docs/api/**`, which is a second implementation of the contract
maintained by hand — and four separate changes moved behaviour, updated tests,
and left it behind:

- MCP request-metadata headers became mandatory; seven published examples still
  send none.
- The commit that fixed those headers in `docs/onboarding.md` edited three other
  files carrying the identical break and fixed only the cosmetic defect in them.
- `/v1`'s 401 became OpenAI's object shape *because* a bare string leaves
  `error.message` undefined — and two documents still warn readers that
  `error.message` is undefined. The warning outlived the defect it warned about.
- The `2026-07-28` migration removed `initialize` and added `server/discover`;
  `openapi.yaml` still lists the old set.

> Each of those touched handlers and tests. **None of them was careless.** The
> documentation is simply not where the author was looking, and nothing made it
> disagree with them.

There is no hook for this and deliberately no check that fires on every handler
change — that becomes noise and joins the instruments that are present and
measure nothing. It is a sentence to point at in review.

## If you stack a PR, say so and keep saying so

Deleting the base branch of an open stacked PR **auto-closes it, irreversibly.**
GitHub then refuses both reopen (*"Could not open the pull request"*) and
retarget (*"Cannot change the base branch of a closed pull request"*). The head
branch survives, so the work is recoverable by opening a fresh PR from it — but
the PR, its review and its CI history are not.

So: flag a stack in the report, and repeat it. **Whoever merges should check for
stacked PRs before `--delete-branch`**, which is easy to use reflexively and has
a blast radius that is not obvious.

This cost a PR within an hour of the rule that would have caught it — and the PR
it cost was the one adding that rule.

## Verifying your own work

Twelve rules, each paid for by a real failure — and every one of them has since
been exercised by the person who wrote it, on their own work, usually within
hours. That is the argument for the page: these are not style preferences, they
are the checks that caught us.

If you keep only one line: **a wrong number that agrees with your prior gets less
scrutiny, not more.** Every rule below is a way of not needing to notice.

And one about the rules themselves: **the check is an artifact too, and it can be
wrong in ways that look exactly like the finding it is meant to produce.** A
stale ref reads as a stranded change; a sloppy prediction reads as a defect.
Several of the steps below exist only to tell those apart. They are written as commands and
assertions rather than advice on purpose: a principle gets skipped under time
pressure, and `assert s.count(old) == 1` cannot be.

### Spend verification effort on the quiet result

**A wrong target always produces the quiet answer.** Which answer is quiet
depends on what you are checking for:

| you are checking | quiet answer | verify the target when |
|---|---|---|
| presence (curl for a 200, grep for a match) | failure | **it fails** |
| absence (did my sabotage break the test?) | the test passing | **it passes** |

A mis-aimed curl cannot return 200, and a mis-aimed sabotage cannot make a test
fail. So a passing curl and a failing sabotage are both self-validating about
their target and need no second look.

Both halves cost us a day. A curl was aimed at a port that `mise.local.toml`
had already overridden, so its failure was guaranteed regardless of the thing
under test. A sabotage missed its target, and reported a pass that looked like
success. Take only this rule and you still come out ahead — it is what makes the
rest affordable.

### When you explain away an absence, check it against what you already know

An absence with a local explanation is only innocuous if **nothing you have
already seen contradicts it** — and the contradiction is usually something you
looked at for a different reason, which is exactly why it does not announce
itself.

A lane hit the #275 config defect **days before it was found**. In one session
it read the shipped snapshot and printed its Typesense host, and separately
concluded *"my lane DB has no Typesense config."* **It held both facts at
once**, twenty minutes apart, in service of different questions. Each had its
own tidy explanation, so neither felt like it needed the other.

> *The value exists in the shipped snapshot* **and** *is absent from a database
> imported from that snapshot* is not a state anyone should accept.

**It is not that the evidence was missing. It was that the two observations
never met.** A silent defect survives not because nobody hits it, but because
everybody who hits it has a reasonable story ready.

So when you are about to write *"X is just not configured here"* or *"that file
doesn't exist in this worktree"* — spend one minute checking it against what you
have already established is present. That check is cheap and it is the only one
that would have caught this.

**And check hardest when the tidy explanation implicates someone else.** An
absence explained by your own local cause — *"my database is fresh"*, *"my
scanner found nothing"* — costs you an hour and a wrong belief. An absence
explained by another person's error — *"the token they gave us is bad"* — costs
an hour, a wrong belief, **and a false accusation that is expensive to
withdraw.** Same defect, strictly worse outcome.

**The incentive runs the wrong way, which is why it needs saying out loud.**
When the tidy explanation is *"someone else supplied a bad input"*, checking it
feels like doubting them — so the check is socially costly exactly where it is
most valuable.

That is the mirror image of the constraint rule below: there, the explanation
**excused** work and nobody was motivated to test it; here the explanation
**accuses**, and nobody is comfortable testing it. **Both go unchecked for
reasons that have nothing to do with the evidence.**

This nearly happened. A lane was briefed to place an authentication token in a
location the compiler gate does not read. Following the brief would have
produced no change from a **good** token, and the tidy conclusion was *"the
token we were given is bad."* The lane checked the gate instead — and the
evidence that settled it was already in its own report from that morning.

### Mark measurements established; do not mark structural claims

Telling the next person *"this is settled, do not re-derive it"* saves real time
— and it is exactly what discourages the check that catches a wrong fact.

**The distinction that keeps the useful half is what kind of fact it is:**

- **A measurement** — someone ran something and got a number — **is worth
  marking established**, because re-running it is expensive. Reproduce it as a
  known-answer check before extending it; that is the cheap version of
  verifying, and it is what caught a 4-document discrepancy in a population
  count.
- **A structural claim about the source** — *"there is no parent attribute on
  that entity"*, *"nothing reads that key"* — **is not**, because checking it
  costs one grep. **It is already cheaper to verify than it was to write down.**

An orchestrator marked three structural claims as established on #275. All
three were false — the attribute, the code that sets it, and the code that walks
it all existed, one file reference each. They were caught only because the lane
happened to be looking at the contradicting query **while reading something
else.**

### Sabotage: assert before, confirm after

Before the edit, in your edit script:

```python
assert s.count(old) == 1      # refuse to write if the anchor is ambiguous
```

After the edit, **confirm the sabotage actually fired.** Neither substitutes for
the other: the assert prevents a mis-target, and only a fired sabotage proves
the assertion you are testing is load-bearing.

One lane's anchor occurred three times and its edit landed 644 lines from the
construct it meant to break — a quiet pass. The same one-line assert fired twice
in work that is now merged: it caught two API paths whose prose descriptions are
byte-identical (without it, one would today advertise the other's schema), and a
test stub that existed twice where only one copy would have been fixed.

**Commit a safety point before you start a sabotage cycle.** The cleanup step is
by construction a destructive git operation — `git checkout -- <file>` — which
discards uncommitted work in that file along with the sabotage. One lane's new
tests were uncommitted when it ran that cleanup, so the next two sabotages
reported clean **against tests that no longer existed** — an observable identical
to a genuinely inert guard.

**Restore from a file copy, not from git, and diff to prove the restore.** A
safety point is not always available — a lane whose commit was declined ran the
cycle anyway and hit both halves of the trap in one pass. On the *tracked* file
`git checkout --` reverted the whole file to HEAD, silently taking that lane's
unrelated feature edits with the sabotage. On the *new, untracked* file it could
not restore anything at all: it failed with `pathspec ... did not match any
file(s) known to git`, a line that scrolls past in a loop, so the sabotage
**stayed in place** and the next two runs were measured against a file that was
still broken. Their failure lists were cumulative and every one of them looked
like a healthy red.

```sh
cp "$F" "$SCRATCH/$(basename $F).pre-sabotage"   # before
# ... sabotage, run, observe ...
cp "$SCRATCH/$(basename $F).pre-sabotage" "$F"   # after
diff "$SCRATCH/$(basename $F).pre-sabotage" "$F" || echo "RESTORE FAILED"
```

The `diff` is the part that matters: a restore you did not verify is
indistinguishable from one that silently did nothing, and the next sabotage
inherits it.

**Run one sabotage at a time and confirm the control is green between them.** A
cumulative failure list still reads as red, so a sabotage that never fired is
invisible once an earlier one is still in place.

**A compile error is not a fired sabotage.** Reordering two `let` bindings to
break an ordering guarantee left a later binding referring to an earlier one and
the namespace simply would not compile — which proves nothing about the
assertion under test. Re-cut the injection so the code still builds, then look
at the result. When that lane did, the guard did *not* go red: the effectful
call it was ordering was a lazy `keep`, so the order held by accident of where
the sequence happened to be realized rather than by where it was bound. The
sabotage only earned its keep after the producer was made eager.

### A screen for substitution damage, and what it is worth

The class above is *mostly* detectable after the fact, for the cost of two
greps. **Two signals, one negative and one positive:**

```sh
grep -cE '[a-z] {2,}[a-z.,]' body    # 1. scar: a DOUBLE SPACE where words were
grep -c '`' body                     # 2. backticks still PRESENT
```

**The scar.** When substitution eats backticked text mid-sentence it leaves a
double space — *the visible residue of an invisible edit.*

**The backticks.** Substitution **consumes** the backticks along with their
contents, so a body that still contains them was not substituted. **That is
positive evidence rather than absence of evidence, which makes it the stronger
of the two.**

**Validate it before believing it**, with a real corrupted sentence as the
known-bad specimen — *a scar detector that never fires is exactly the shape of
the inert guards this page exists to prevent.*

**What it is worth, stated so a zero is not read as a guarantee:**

- It catches deletion **mid-sentence**. It misses one at a line start or end,
  and one where the deleted span happened to leave single spacing.
- It cannot see damage in a message that had `$variables` rather than backticks
  — **those expand to empty with no scar at all.**
- **So a zero means "no scar found", not "not corrupted".** The backticks
  signal carries the weight, and only for bodies that were meant to contain
  backticks.

**It is a screen, not a proof** — and worth exactly what a screen is worth: it
turns an undetectable class into a mostly-detectable one in ten seconds.

**Read every match. Do not report the count.** Swept across the repository, the
scar signal returned **22 hits — 20 of them deliberate column alignment in
tables**, which we write in commit bodies constantly. A count alone would have
reported *22 damaged commits*, with the two real ones buried among eighteen
belonging to the person running the sweep. **The signal is worth having and its
raw output is not an answer.**

**And say which artifacts the instrument cannot speak to.** In that sweep,
**159 of 161 pull-request bodies carried backticks** — a solid result — but only
**199 of 861 commits did.** The other 662 **cannot be distinguished** between
*never had backticks* and *had them eaten*. The honest headline was *"no scar
found across 1,022 artifacts, with 662 the instrument cannot speak to"*.

**Use `git log --no-merges`** rather than remembering that `mergeCommit` returns
the wrong object. *Sidestepping a trap beats recalling it.*

### Auditing for a silent defect is easier to get wrong than the defect

When corruption is silent, the natural response is to go looking for it. **The
search is the harder half.**

A lane auditing its own commit bodies for command-substitution damage produced
**three false positives in a row, each looking exactly like the finding it was
hunting:**

1. It read the body of `gh pr view --json mergeCommit`. **That is the *merge*
   commit, whose message is GitHub's** — *"Merge pull request #281 from …"* —
   not the author's. Five phrases came back missing across five pull requests,
   which reads as catastrophic corruption.
2. Corrected that, and three were still missing — **the grep was
   case-sensitive** and the phrases had been written in capitals.
3. One remained missing because **the phrase had never been in that commit.** It
   was in the lane's report, not the message.

**Then it read the bodies, which took less time than any of the three broken
checks and settled it immediately.**

*A defect survives review because reviewers read the diff. It survives a
deliberate audit because the audit is easy to build wrong — and every wrong
build produces the same alarming answer.* When a check can only return "missing",
read the artifact once before believing it.

**The mechanical note, because it will bite the next person the same way:**
`gh pr view --json mergeCommit` returns the merge commit. On a merge-commit
repository the authored commit is its **second parent**:

```sh
git log -1 --format=%P <merge-sha> | awk '{print $2}'
```

### The safety point is a placeholder — amend it before you push

The rule above says to commit a safety point **before** a destructive git
operation. That is right, and it has a side effect nobody flagged: **the
message is written before you know what you learned.**

A lane took its safety point, ran the sabotage cycle, restored, and went
straight to push. **The one-line placeholder became the permanent record**, and
merged that way — while the full reasoning lived in the pull-request body, three
clicks from the git log someone will read in a year.

**Unlike a corrupted message, there is nothing anomalous to notice.** A short
commit subject is a perfectly normal thing.

So: **amend the safety point after the cycle completes, before you push.**

### Inject into the producer, not the test

A fabricated key added to the **test** proves your assertion can read a key you
handed it. Added to the **producer**, it proves your assertion catches a key the
real code emits. Most sabotage demonstrations take the first route and prove
nothing.

It also surfaces asymmetries that live in the producer and are invisible from
the test side: removing one default made `tools/list` fail and `server/discover`
pass, because only the former relied on it — which was the original production
bug.

### Assert and abort; do not warn

Where validity depends on a value, fail on it rather than logging about it. A
warning is skipped under time pressure; an abort is not.

Two instances of this are in the tree, both readable:

- `bb dev-stop` polls after signalling and calls `System/exit 1` if any process
  survived, rather than printing a tick. The reason is in the docstring on the
  helper: `kill` exits 0 when the signal was **delivered**, which says nothing
  about whether the process died, and reporting success on that exit code was
  the defect it replaced. A dev JVM ignores SIGTERM entirely — measured at 30s,
  still alive — so the escalation is the path that actually runs.
- `assert s.count(old) == 1` in an edit script, above. It has fired twice in
  work that is now merged; both outcomes are visible in `openapi.yaml` and
  `routes_test.clj`.

Prefer an anchor a reader can open over one they have to take on trust: an
earlier draft of this section cited a harness that was built as a throwaway
script and torn down, so nobody can now inspect it.

### Assert on the key set, not only on values

When you assert on a response, a config map, or any shaped map, assert the whole
key set **at least once** — not only the fields you care about:

```clojure
(is (= #{:jsonrpc :id :result} (set (keys body))))   ; closed shape
(is (contains? choice :logprobs))                    ; open shape: required subset
```

**A fabricated key is invisible to assertions on individual values**, because
nothing reads it. A stub emitted `:config-key`, the handler's `dissoc` therefore
matched nothing, and the field survived into a response under test — the same
field the OpenAPI spec wrongly advertised. Nothing failed for as long as it
stood.

Use equality only where the producer builds a closed map literal. Where the body
is data-driven, assert the required subset and say in the schema why the set is
not closed, rather than enumerating a shape that will go stale.

### Assert the element shape, not only that the collection key is present

A key-set assertion cannot see inside a collection. The key is present either
way; only its **contents** differ:

```clojure
(is (every? string? (:agent-refs out)))                  ; element shape
(is (= [{:tenant "digdir" :dataset-config-key "x"}]      ; not just (contains? out :dataset-scopes)
       (:dataset-scopes out)))
```

The worked example is a single pull request that **found a real schema bug and
introduced a false one at the same time**. `ApiKeyInfo` genuinely declared a
field no handler emits and omitted eight it does — that half was right. But the
test fixture supplied a *pre*-normalisation value for `agent-refs`, a shape no
caller of that producer ever passes it, so the producer faithfully echoed it
back and the namespaced element shape went into the schema as a finding. The
key-set assertions all passed, because `agent-refs` was present either way.

Only checking what the producer **receives** — not what it emits — separated the
two. So when a stub stands in for a real value, go and look at what the real
value is at that point in the chain; a value that exists in the database is not
necessarily a value that reaches the function you are testing.

### State the scope of "I checked"

A search that was scoped narrowly and a search that came back empty **produce
the same sentence**. Three of these landed in a single day, each one an honest
check answering a narrower question than the claim it was used for:

| the claim made | what the check actually answered |
|---|---|
| *"nothing references this"* | **grep answers appearance, not reach** — a name can appear in a comment, or be reached through a var nothing names |
| *"the collection does not store this field"* | **a schema answers what is *indexed*, not what is *stored*** — Typesense returns undeclared fields it never indexed |
| *"this config key is used by nobody"* | **production callers were checked** — removing it broke **11 tests at once**. *The test suite is a caller.* |

Each was made in good faith by someone who had genuinely looked. **The failure
is not laziness — it is that a scoped search feels finished.**

So write the scope into the claim: *"no callers in `src`"*, *"absent from the
documents schema"*, *"not referenced in production code"*. A reader can then
tell whether your search covers their question, and **you can tell whether it
covered yours.**

**And when the answer decides something expensive, pick an instrument that
answers at the right level** — a closure or the analysis output rather than a
text scan, the artifact rather than the schema, the whole repository rather than
one directory.

### Read the producer, not the neighbour

Before documenting or asserting a shape, read the function that actually serves
the path. A neighbouring schema, a symmetric endpoint, or a function that builds
the shape but never reaches the wire will all look convincing.

This has failed in both directions: a schema looked live because a neighbouring
producer built that shape, and a field looked dead for the same reason — the
list endpoint's summary omits `tenant`, the detail response includes it, and
they are different handlers.

### Never pass prose as a double-quoted shell argument

**This is a different command, not a discipline to remember.**

```sh
git commit -F - <<'EOF'
Subject line

Body with `backticks` and $vars, safe because the heredoc is QUOTED.
EOF
```

The same applies to `gh pr create --body-file`, `gh issue comment --body-file`,
and anything else that would otherwise take prose inside `"..."`.

> **The distinction that matters is not `-F` versus `-m`. It is `<<'EOF'`
> versus `<<EOF`.** The quotes on the delimiter are the entire mechanism —
> without them you have the original bug back, in a shape that looks like the
> fix. `"$(cat <<'EOF' … EOF)"` is equally safe for the same reason.

**Three people were bitten by this in one afternoon**, two of them within hours
of warning someone else about it. Reasoning about which strings are safe failed
every time, which is why the rule is mechanical.

**What makes it worse than the other format hazards: it is silent in the
artifact.** One commit message read

> *"a timeout that produced no agent output.  and  must not collapse"*

— **both quoted phrases deleted by command substitution**, leaving a sentence
that says nothing. The commit itself looked fine. It was caught only because the
shell printed `command not found: the` into unrelated tool output, which is a
side channel rather than a check.

**And a mangled commit message survives review, because reviewers read the
diff.** Nobody re-reads a commit body against what the author meant to write, so
this corruption has no natural detector anywhere in the process.

**There is a second reason it survives, and it is worse: the damage leaves
grammatical text.** Two real instances sat in this repository for five months:

> *"Extracted shared base UI components to ."*

That scans as a terse note, not as corruption. **Compare a truncated stack
trace, which announces itself.** A defect that degrades into something
well-formed has no reader-side detector either — *the sentence has to be wrong
in a way a reader would notice, and this one is only wrong in a way its author
would.*

*A scoping note, because the first version of this rule was too narrow: it was
originally given as "drop backticks from message bodies", meaning inter-agent
messages. **The actual scope is any double-quoted shell argument.** It was then
hit in a pull-request description — one arguing that searches should state their
scope.*

If you suspect it has already happened: `git commit --amend -F - <<'EOF'` fixes
a message in place, and **then read the result back** rather than assuming the
amend took.

### Use the parser, not a regex over the format

If a parser for the format is on the classpath, use it. A regex over a
structured format is a heuristic, and **a heuristic needs every candidate
verified individually** before you report a count or act on a hit.

```clojure
(yaml/parse-string (slurp f))                       ; not grep over openapi.yaml
(read {:read-cond :allow :eof ::eof} rdr)           ; not regex over .clj/.cljc
```

Three failures in one day, all by people who knew the format:

- A shell extraction of a schema's properties silently dropped `enabled?`,
  because `[a-zA-Z-]+:` does not match a question mark — briefly producing an
  "emitted but undeclared" field that did not exist. The tests, which parse the
  YAML, were right where the grep was wrong.
- A count of test files lacking key-set assertions said 9, then said 7 when the
  pattern was broadened, and was 8 when each file was actually read. Two
  heuristics, wrong in opposite directions.
- A sweep for stubs whose keys diverge from their producer returned 9
  candidates, **8 of them false positives of the method itself** — two
  identifiable mechanisms: ring-middleware stubs return a *response* rather than
  the producer's shape, and a *delegating* producer always false-positives,
  because its real keys live in a different namespace from the one stubbed.

Two more in one day, both by people who owned the format:

- Positional `awk -F','` over a sweep `runs.csv` returned "1, 1, 5" and 14
  numeric rows out of hundreds. The file carries **quoted response text
  containing commas**, so a delimiter split is wrong for the same reason a
  regex is wrong for arity: **a quoting rule makes field boundaries a
  parse-level fact.** And the rows it corrupts are the long or unusual
  responses — which is what anyone is usually investigating.
- An enumeration of pipeline stage keywords used `[a-z-]+`, **which does not
  match underscore**, silently dropping every snake_case stage — including
  `:read_chunks` and `:rerank_results`, the two largest items in the set.

A regex over a structured format approximates a parser. The parser is usually
already a dependency. The one place a text scan is sound is where the fact
really is text-level — delimiter balance is, nesting and field boundaries are
not.

### Ask which direction your instrument fails in

An instrument that can be wrong is usually wrong in **one** direction, and only
one of those directions gets caught.

The `[a-z-]+` stage enumeration above dropped the two largest I/O stages, so it
**understated** a latency floor we were measuring to decide whether a target was
reachable at all. That is the dangerous direction: **an overstatement would have
been caught by disbelief; an understatement agrees with what everyone hopes.**

So before trusting a number, ask which way a flaw in the method would push it,
and prefer a construction that fails the safe way:

- Route what you could not classify into a **visible** bucket rather than
  dropping it — an unexplained residue is a question; a silent omission is a
  wrong answer.
- When measuring a floor, prefer over-inclusion; when measuring a ceiling,
  prefer under-inclusion.
- State the direction in the report. A number whose error direction is named is
  auditable by someone who does not repeat the measurement.

### Measure a constraint before you obey it

Every rule above operates on something that **exists** — a number, a file, a test
result. A constraint produces no artifact at all. It produces an **absence**, and
we have no instrument that reads absences.

Two cost this repo real work in one week, both stated confidently from a
plausible mechanism nobody had tested:

- *"`bb dev` is single-instance across the fleet, because shadow-cljs binds
  9630."* It is not. shadow-cljs retries on the next port unless `:strict` is
  set in an `:http` map our config does not have. Lanes queued for a week.
- *"An indexed `sha256` field means a schema change, therefore a reindex."* True
  for the nested path, false for a flat one. **The measured cost was 0.91
  seconds, and reversible.** An option that was defensible against a reindex was
  not a trade at all against one second.

**The ones worth testing first are the ones that make you do less** — a
constraint that costs you work gets challenged the first time it is
inconvenient, while a constraint that *excuses* work is accepted gratefully by
everyone it touches. Both of the above told people not to do something, so
nobody pushed back, and both looked like prudence.

Test the constraint the way you would test a claim: with a known answer, and
**without creating the condition you are testing**. The 9630 claim was settled by
occupying port 9730 and calling `start-http` against it directly — no `bb dev`,
9630 never touched, and the log printed which port it actually bound.

### A shared default port is an ambiguous address

**Any default port shared by several instances becomes an ambiguous address the
moment more than one can run.** Local `:8108` reached dev, test or prod
Typesense depending on which forward won. `:9630` reaches whichever dev server
started first.

**The fix is not to avoid the second instance — it is to ask the thing what it
is, rather than asking the address.** For Typesense that is `num_documents`
asserted against the expected value for the named environment; for shadow-cljs
it is confirming which build the dashboard is attached to.

Removing a serialisation does not remove the hazard, it **converts a queue into
an ambiguity** — which is the better trade only if people know to interrogate the
thing rather than trust the port.

### Write down what it is *not* for

The constraints someone hands you describe the feature. **The bug is usually in
the boundary nobody stated.**

A read-time de-duplication was specified with three constraints, each turned
into a test: do not force a winner, do not touch the legitimately-shared case,
invent nothing. **The first implementation satisfied all three and was still
severely wrong** — it keyed on the file digest alone, and every chunk of a
document shares that document's file, so it dropped every chunk after the first
and would have collapsed every multi-chunk answer to a single chunk. A silent
retrieval regression, invisible in any aggregate we watch.

**The test that caught it was not one of the three.** It was
*"several chunks from the same document are not this function's job"* — added
only because separating this from the per-document cap felt worth stating. The
stated constraints were about the **policy**; the defect was in the **unit**.

So before you finish: name the neighbouring thing your function is *not*, and
assert it. That sentence is usually one line and it is where the bug is.

**And check your sabotages have distinct radii.** Three sabotages that each
break every test tell you nothing — that is one test wearing three names. The
same work above sabotaged three constraints and confirmed each fired on *its
own* tests and nothing else, which is what makes them independent rather than
restatements.

### Record the conditions, not only the result

A number without the conditions it was taken under is not a weaker result — it
is a **different kind of object**. It cannot be reproduced, contradicted or
defended. It can only be quoted.

Three instances in one day, found by three people who were not looking for the
same thing:

- **The corpus.** `resolve-dataset-config!` resolved the collections at run time
  **and threw them away**. `models.edn` captured every LLM endpoint, provider,
  engine, model id and sampling parameter — and nothing about the data. The
  config DB that could have answered it afterwards had since been deleted, so a
  headline latency figure's provenance was **unreconstructible**.
- **The endpoint.** Quality numbers were produced against `localhost` ports
  reached through an **SSH tunnel nobody wrote down** — not in the repo, not in
  any config, not in the notes of the person who ran the batch.
- **The model.** A hosted preview model can be upgraded by its vendor with **no
  commit, no config change and no log line**, and **the deployment name stays
  identical** — the field you would naturally record is the one guaranteed not
  to move. **A field that cannot vary is not provenance, it is decoration.**

So record what a measurement ran **against**: corpus, collections, endpoint
host, and **the model identifier the server reports in its own response**, not
the name you asked with. *Your config is your instrument; the response is not.*

**Never a credential** — the uri, the model, and what the server said.

Then **assert it before comparing two run sets.** A difference is not a
discrepancy to reconcile — **it is two different models, and the comparison is
void.**

**Corollary: a local address is not a statement about a remote thing.** A dead
`localhost:8010` proves only that no tunnel is up; a bound local `:8108` says
nothing about which service you reached. Both read as answers, and both are
wrong in the direction that closes the question.

### When a field can be absent, pair it with one that cannot

**A blank cell cannot distinguish its own causes. A present zero can.**

`llm-calls` blank means *"no usage was reported"* **and** *"this file predates
the column"* **and** *"the provider omitted usage"* — three different facts, one
empty string. `llm-ms` `"0"` says the column exists and the answer is zero.

So **key a check on a present zero, never on an absent value.** A check keyed on
absence returns *unknown* for every historical row, which looks correct and is
useless — and it will be counted as coverage.

Three instances in one week, found by three lanes building three different
things:

| absent, ambiguous | present, decisive |
|---|---|
| `ttft-ms` nil — *no streaming*, or *instant*? | `response-chunk-count` 0 |
| no model recorded — *unchanged*, or *never captured*? | `:unverifiable` as a state distinct from `:comparable` |
| `llm-calls` blank — *no call*, *old file*, or *no usage reported*? | `llm-ms` `"0"` |

**That is a rule, not three coincidences.** When you add a field that can be
absent, add or identify one that cannot, and make the check read the second.

**And give the ambiguous case its own name.** Two states force absence into
whichever bucket is nearer; a third — `:unknown`, `:unverifiable`, `:opaque` —
keeps it separable. *Treating absent as zero flags every archived artifact,
which is how a guard earns a reputation for noise and gets switched off before
it reports anything true.*

### A file read back from disk has string keys

Two lanes, an hour apart, building two unrelated guards, both over a sweep
`runs.csv`. Both keyed on **keyword** keys. On the same two files:

```
keyword keys →  {:no-llm-call 16}      ← what the author expected
string keys  →  {:unknown 16}          ← what actually happens
```

**Green forever in exactly the place the guard is needed**, because comparisons
happen against the *artifact*, not against the in-memory rows the tests were
written with.

This is a property of the artifact rather than a mistake either lane made, so it
belongs here rather than in either lane's memory: **if a check runs over parsed
CSV, JSON or EDN read from disk, accept both key shapes — and prove it does, on
a file, before believing a clean result.**

### Print a known-answer check beside every aggregate

Any number you are about to report should be printed next to something you
already know, asserted rather than eyeballed:

```
records parsed: 620420   MUST equal num_documents: 713923   <-- FAILED
```

A measurement retracted a headline figure this way. The assertion failed twice,
**identically** — and the identical repeat is what ruled out a flaky stream and
pointed at the parser: an off-by-one that dropped single-digit indices, so `12`
was read as `2`. Without that line a clean-looking 84% would have shipped.

**An unreconciled total is a defect, not noise.** That same discrepancy had
already been explained away once as probable export pagination.

**The known answer must come from a source your instrument cannot influence — not
from your own earlier run.** Otherwise you are checking reproducibility, not
correctness, and the two are easy to confuse precisely when it matters.

The off-by-one above produced **620,420 twice, identically**. A self-consistency
check — *same as last time?* — would have passed it both times, and the
repetition would have *increased* confidence. What caught it was 713,923, taken
from the collection's own `num_documents` metadata: a number the parser could not
compute or affect. Collection metadata, a count from a different tool, a figure
someone else produced, a file's own line count — anything your code did not
compute.

*The identical repeat is genuinely useful, but for a narrower reason than it
looks:* it ruled out a flaky stream. It would have been equally consistent with a
deterministic bug, which is why **the independence of the source is the
load-bearing part, not the repetition.**

**A known answer taken from the wrong source manufactures a false positive
exactly as easily as a missing one manufactures a false negative.** A lane
checking its own pull-request bodies for corruption searched one of them for a
phrase it had written in a *different* one — and had thirty seconds of
"evidence" of a defect that was its own bad control. **Check that your known
answer belongs to the artifact you are checking.**

**And state the known answer for the pattern you are actually running, not for
the concept in your head.** A lane predicted five section headers and got six,
because its pattern matched the title line as well — nothing was wrong, and the
mismatch cost an investigation that found nothing. That is worse than it sounds:
a check that cries wolf twice stops being believed, and **a check nobody believes
is worse than no check.**

The two compose: state the answer for the pattern you are running, and take that
answer from somewhere else. *The only thing that broke the loop was a number from
outside the instrument.*

#### The worked example you already have: test counts

`Ran 1755 tests containing 7241 assertions` **is** a known-answer check printed
beside an aggregate. The aggregate is *0 failures*; the known answer is the count
you expected.

So a green suite is not evidence your new test ran — passing tells you about the
tests that **executed**. Quote the counts, for the namespace and for the suite,
before and after. It costs nothing and catches the whole family: file deleted,
namespace not required, `deftest` nested in a `comment`, or the runner's regex
excluding it.

That last one is live here. `bb test` passes `-r` with a pattern that **excludes
`digdir.tools.diagnostics-test`**, so our green runs 23 tests and 95 assertions
narrower than a bare `clojure -M:test`. Both are 0F/0E today, but a baseline
quoted from one does not match the other.

It is the same failure as the parser case, which is why it lives here rather than
as a rule of its own: **the summary was true about a smaller population than
anyone thought it covered.** Zero failures over 3 tests when you expected 5;
84% over 620,420 records when you expected 713,923. Both headlines are
arithmetically correct and completely misleading.

**Clojure has a specific instance of this worth knowing, because it defeats an
otherwise-correct grep.** Namespace-map syntax means a key you think of as
`:config.node/id` is stored in source as `:id`:

```clojure
(assoc node :config.node/parent #:config.node{:id parent-id})   ; sync.clj
```

A grep for `:config.node/id` does not match that line, though both denote the
same key. So **any grep for qualified keys in Clojure source is unreliable**, and
a zero result is not evidence of absence. This is what produced the false
"stranded fix" above.

## Opening a pull request

- Use the PR template (`.github/pull_request_template.md`) — fill in Summary,
  Changes, Test plan, and Risk/rollback honestly. A thin "no risk" note is fine for
  docs-only changes; a functional change needs a real test plan.
  - **Do not** add a `🤖 Generated with [Claude Code]` (or equivalent AI-attribution)
    footer to the PR description.
- Keep PRs scoped to one coherent change. Don't bundle an unrelated drive-by fix
  into a feature PR.
- CI must be green before requesting review.
- If your change touches a functional rough edge cataloged in
  `plans/proposed/release-v0.1-rough-edges-inventory.md`, reference the item ID
  (`P*`) in your PR description so the inventory can be updated.

## Review bar

- At least one approving review before merge.
- Reviewer confirms: CI green, the PR does what the description says, no secrets
  or machine-local paths (e.g. `/Users/<you>/...`) leaked into docs or code, and the
  change doesn't quietly reintroduce a removed API (see the `/api/rag` → `/api/mcp`
  migration in `docs/system-overview.md`).
