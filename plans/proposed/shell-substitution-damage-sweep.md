# Sweep: shell-substitution damage in commit messages and PR bodies

Repository-wide audit for the hazard recorded in CONTRIBUTING today — prose
passed as a double-quoted shell argument, where backticked spans are executed
and silently deleted.

> **Read the instrument's limits before the numbers.** A zero here means *no
> scar found*, **not** *not corrupted*.

---

## Result

| corpus | scanned | genuine hits |
|---|---|---|
| authored (non-merge) commits on `release-v0.1-details` | **861** | **2** |
| merged PR bodies | **161** | **0** |

**The two hits are `2b9b74f4` and `04a676de`, both 2026-03-29/30, both
Playground UI, both committed directly to the branch rather than through a PR.**

---

## How strong each result is

Substitution **consumes the backticks along with their contents**, so surviving
backticks are *positive evidence* that a message was never substituted. That is
the stronger of the two detectors, and it is what makes the PR result solid and
the commit result partial.

| | positive evidence | unprovable either way |
|---|---|---|
| PR bodies | **159 of 161** carry surviving backticks | 2 |
| commits | **199 of 861** carry surviving backticks | **662** |

**662 commit messages contain no backticks at all.** They may never have had
any, or may have had them eaten — the corpus cannot distinguish those. Of those
662, sixteen carried a scar pattern; each was inspected individually and
**fourteen were deliberate column alignment**, not damage.

### Blind spots, stated with the numbers rather than under them

- Deletion at a **line start or end** leaves no double space and is invisible.
- Deletion that happened to leave **single spacing** is invisible.
- **`$variable` expansion leaves no scar at all** — it expands to empty with no
  trace whatsoever. Nothing in this sweep can see it.
- The scar pattern is lowercase-only, so a gap between **capitalised** words does
  not fire.
- **Column-aligned tables produce false positives** — 22 raw scar hits reduced to
  2 after reading every match. A count alone would have been useless.

---

## The two hits

Both are unmistakable: every backticked identifier is gone, leaving sentences
that name nothing.

**`2b9b74f4` — "Fix Playground UI refactoring regressions…"**

```
- Restored missing  and  functions in .
- Fixed unresolved symbol  and arity of  calls in  and .
- Corrected parenthesis balance and regex syntax in  and .
- Restored missing response rendering in  and .
- Enhanced Phase 2 budget regression test documentation in .
```

**`04a676de` — "Refactor Playground UI into modular namespaces…"**

```
- Extracted shared base UI components to .
- Extracted agent-loop diagnostics to .
- Reduced  from ~4000 to ~1200 lines for better maintainability.
- Fixed agentic search attribution in  to correctly populate type counts.
```

### Why they survived five months

**The diff made sense.** Reviewers read diffs; nobody re-reads a commit body
against what the author meant to write. And these particular sentences fail
*gracefully* — they scan as terse notes rather than as corruption.

### The content is recoverable from the diffs

Not rewriting history for a merged commit, per the reported-PR-is-closed rule.
Recording the reconstruction here instead, so it is not lost twice:

**`04a676de`** created `playground/ui/components.cljc` (+392) and
`playground/ui/observability.cljc` (+1512) while cutting
`playground/ui.cljc` by 1,279 lines. So:

- *"Extracted shared base UI components to ."* → to **`digdir.playground.ui.components`**
- *"Extracted agent-loop diagnostics to ."* → to **`digdir.playground.ui.observability`**
- *"Reduced  from ~4000 to ~1200 lines"* → reduced **`digdir.playground.ui`**

**`2b9b74f4`** touched `playground/ui.cljc` (−795), `ui/components.cljc`,
`playground/core.cljc`, `data/db.cljc` and `api/routes.clj` — the restored
functions and rendering are in that set.

---

## One methodological note, because it cost three false starts

Auditing for silent damage is *harder to get right than the damage is to make*,
and every wrong build of the check returns the same alarming answer.

Three ways this sweep was got wrong before it was got right:

1. **`gh pr view --json mergeCommit` returns the MERGE commit, not the authored
   one.** Its message is GitHub's. On this repo the authored commit is the second
   parent — `git log -1 --format=%P <merge-sha> | awk '{print $2}'` — or, more
   simply, use `git log --no-merges` and never touch `mergeCommit`.
2. **Case-sensitive grep** against phrases written in capitals.
3. **Grepping for a phrase that was in the report, not in the commit.**

Each produced a uniform `MISSING` that reads as catastrophic corruption.

**Incidental, and load-bearing elsewhere:** this repo's merges are **real merge
commits with two parents, not squashes**, so authored commit bodies *are*
preserved on base. #34's retention argument depends on that property, which had
been assumed rather than checked.
