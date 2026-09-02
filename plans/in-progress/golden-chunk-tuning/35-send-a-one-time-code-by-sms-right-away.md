# Q35 v1 — how do I send a one-time login code to someone by SMS straight away instead of waiting in a queue?

**Grounded source**: `ce838151ccd5` (Instant notifications, notifications). **Register**: lay — user says "one-time login code" and "straight away", avoiding "instant notifications" / "OTP" / "shipmentId".

## Goldens (read-confirmed)
- `f0ccc345d13b` — core: explains instant notifications send a message immediately to a single recipient (bypassing the queue), useful for one-time passwords during login, returning 201/200 with tracking info while delivery status is fetched asynchronously.
- `a9c433dc3520` — supporting: for SMS instant notifications you set `timeToLiveInSeconds` (important so an OTP isn't delivered after it expires) and notes they are for single time-critical messages, not bulk.
- `4acc9a2cbb60` — supporting: idempotency via `idempotencyId` prevents the same code being sent twice on retries/timeouts.

## Cited chunks

`f0ccc345d13b` `a9c433dc3520` `4acc9a2cbb60`
