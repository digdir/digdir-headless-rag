# Admin-login confirmation codes: what breaks, where they belong, what a server can tell

**Issue:** #63 (inventory P24) · **Base commit:** `69973f7` · **Measured:** 2026-08-21

Three questions, answered by exercising a live server rather than by reading
the code. Where a measurement contradicted my expectation it is called out
rather than smoothed over.

---

## 1. What actually breaks today

Codes lived in `(def confirmation-codes (atom {}))` — process-global, so a
restart drops every outstanding one. #58 made that degrade gracefully (the
invalid-code page instead of a 500) but did not change where they live.

### The exercise

A real dev instance on `:8605`, a seeded user with a permission, and `curl`
carrying a real cookie jar — the same session throughout, as a browser would.

| step | on | result |
|---|---|---|
| 1. `POST /auth` | server #1 | `302` → `/auth/confirm-email`, session cookie set |
| 2. code minted | server #1 | `212106` (read from the dev-login log line) |
| 3. `POST` a **wrong** code | server #1 | `200`, `1251` bytes, sha `043fc5ed77519792` |
| 4. **restart** | — | server #1 killed, server #2 started, same env |
| 5. `POST` the **correct pre-restart** code | server #2 | `200`, `1251` bytes, sha `043fc5ed77519792` |
| 6. compare 3 vs 5 | — | **byte-for-byte identical** |
| 7. mint a fresh code and use it | server #2 | `302` → `/`, auth cookie issued |

Step 7 is the positive control: the flow itself is fine, and the restart is
what broke it.

**The failure is indistinguishable from a genuinely wrong code.** Both render:

> Login error — The code is invalid or expired. Continue

The copy is already honest that it does not know which. What it cannot say is
*"the server restarted"*, because nothing in the process knows that either.

### The detail that makes it worse, and I did not expect it

**The session survives the restart; only the code dies.** Step 5 returned
`200`, not the `400` that a missing `:pending-email` produces — sessions use
`cookie-store` keyed off `JWT_SECRET`, so they live in the client's cookie and
are unaffected by a restart.

So the user is not bounced to the start. They are sitting on a valid session,
on the confirm-email page, holding a code that worked thirty seconds ago,
being told it is wrong. The one piece of state that would have explained it is
the one piece that did not survive.

### Multi-instance

Not exercised — inference from the same mechanism, flagged as such. A code in
process memory is unknown to any other process, so a code minted on instance A
fails on instance B. Whether persisting fixes that depends on the backend:
`:file` (`DATAHIKE_FILE_PATH`) is local to one host, `:jdbc` (`POSTGRES_URL`)
is shared. The issue states this flatly; it is true only for the second.

---

## 2. Where they belong — the lifetime, not just the storage

A confirmation code is short-lived (10 minutes), single-use, one-in-flight per
address, and a bearer credential for account access.

**Not the session.** Sessions here are `cookie-store` — the client holds them.
Putting the code there hands the user the secret they are supposed to receive
out of band. (A *hash* in the session would be sound cryptographically, but
it gives up server-side single-use: the server has nothing to retract, so an
old cookie can replay the code inside its window.)

**The config DB, which is what the issue recommends, and two facts support it
more than the issue claims.** Both databases are configured
`:keep-history? false`, so a retracted code is genuinely removed rather than
shelved in history — which is what makes storing a short-lived secret in an
append-only-flavoured store acceptable. And `all-schemas` /
`transact-registered-schemas!` runs idempotently on every boot, so a new
schema fragment needs no migration step.

**A TTL store (Redis) would fit the lifetime best** — native expiry, no sweep
to write. It is not available: `taoensso.carmine` appears only as a transitive
dependency, nothing in `src/` uses it, and adding a datastore to fix one
10-minute value is not proportionate.

**Chosen: the config DB, with the expiry as data and a sweep on mint.** The
sweep runs at the only moment rows are created, so the table stays bounded
without a scheduler.

---

## 3. Can a restarted server tell a stale code from a wrong one?

**Before this change: no, and no error message could have fixed it.** After a
restart there is no record that a code was ever minted, so "stale" and "wrong"
are the same state — the absence of an entry. Storage is not a nicety here;
it is the only thing that creates the information the diagnosis needs.

There is a second, quieter half. *Within* one process the server already had
the information and threw it away: an expired entry stays in the map (nothing
sweeps it), so `valid-code?` could see "right code, too late" and instead
collapsed it to `false`.

**After this change: yes, four ways** — `:valid`, `:mismatch` (a code is
outstanding, this is not it), `:expired` (it was outstanding, the window
passed), `:unknown` (no record). That last one is why expired rows are
*retained* for a grace window instead of deleted at expiry: delete them and
`:expired` collapses back into `:unknown`, which is the gap this issue is
about.

The restart case itself no longer arises — the code simply still works.

---

## 4. The constraint, and where I stopped

**The distinction goes to the operator, not to the caller.** Every non-valid
status renders the same page as before; `code-status` is logged server-side.
Telling an unauthenticated caller `expired` rather than `wrong` also tells
someone guessing codes that the address has a login in flight, and the brief
said not to widen what an unauthenticated caller can learn.

That is the safe half, and it is a real improvement on its own: today an
operator cannot answer "why did this user's code stop working" either.

**The tension I am naming rather than resolving:** whether the *user* should
be told is a product decision with a security cost, and it is not obviously
wrong to pay it — the same address already leaks more at the previous step,
where `/not-approved` distinguishes "User not found" from "Access pending" for
any unauthenticated caller.

### A finding that changes the calculus, and does not belong to this issue

The reason to be careful about telling stale from wrong is the person guessing
codes. **They are not currently throttled.**

`wrap-rate-limit` records an attempt only when the response status is `nil`,
`< 200`, or `>= 300` — "non-2xx responses". The invalid-code page returns
**`200`**. A successful login returns **`302`**. So the middleware counts
successes and ignores failures, which is the opposite of its docstring.

Exercised, not inferred:

| probe | result |
|---|---|
| 15 wrong confirmation codes | **0 rate-limited** |
| 12 successful `POST /auth` | blocked after **7** (budget already partly spent by earlier successes) |

So the throttle protects the "send me a code" button and ignores brute force
against a 6-digit code. Separately, `rate-limit-store` is *also* a
process-global atom, so a restart clears whatever counters it did accumulate.

This is a distinct defect from #63. It was filed as **#211** and fixed there;
what follows is what changed, because it settles question 4 above.

**The limiter no longer infers.** The handler calls
`rate-limit/mark-failed-attempt`, and the middleware records what it is told —
there is no status to misread. `POST /auth` and `POST /auth/confirm-email` are
now separate rules with separate budgets: sending counts every request
(volume, deliberately unlike the bug) and is bucketed per IP **and** per
email; guessing counts only signalled failures and is bucketed per IP.

**The real defence is per-code, not per-source.** The code row carries a
failure count and is retracted after five wrong guesses, so a brute-force run
is capped at five attempts *against that code* however many source addresses
it comes from — and, being data rather than the in-memory counter, it survives
the restart that clears the limiter. Exercised: five wrong guesses destroy the
code, the tenth guess is refused with a 429, and three successful logins in a
row consume none of the budget.

**On the disclosure question**, this narrows but does not close it: guessing is
now bounded, so the argument against telling a user `expired` rather than
`wrong` is weaker than it was. It remains a product call.

The log line is now bounded too: one line per rejected attempt, on a path that
is throttled at ten per source per fifteen minutes and whose codes die after
five wrong guesses.
