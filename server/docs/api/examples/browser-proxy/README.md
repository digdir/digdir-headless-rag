# Reference BFF — calling this API from a browser

A browser cannot call this API directly, and that is deliberate. This directory
is the pattern that replaces trying: a small backend-for-frontend that holds the
API key, so the browser never sees it.

| File | What it is |
| --- | --- |
| [`proxy.mjs`](proxy.mjs) | The reference BFF. ~200 lines, zero dependencies, Node 18+. |
| [`check-streaming.mjs`](check-streaming.mjs) | Proves the SSE route actually streams, against a deliberately buffering control. |

See [`../../authentication.md`](../../authentication.md#the-api-key-is-server-side-only)
for why the browser path is closed, with the measurements.

## This is a reference, not a product

Nobody maintains this as a supported artifact. It deliberately does **not** do
any of the following, and the list is repeated at the top of `proxy.mjs` so it
travels with the code someone copies:

- **Authentication or sessions.** Every caller is anonymous and equal — as
  written, anyone who can reach the port can spend your API quota.
- **Rate limiting**, quotas, or abuse controls.
- **Error mapping.** Upstream statuses pass through roughly; what your frontend
  sees is not a designed contract.
- **Input validation** beyond a length cap, and no prompt-injection defence.
- **Logging, tracing, metrics**, or redaction of what users typed.
- **TLS** — terminate it in front of this process.

What it *does* do is the part that is easy to get wrong: hold the key, pin the
tool server-side, and stream without buffering.

## Run it

```sh
export DIGDIR_API_KEY=<your key>            # never hard-code it
export DIGDIR_API_BASE=http://localhost:8081
node proxy.mjs                              # listens on 8787
```

Then, as a browser would — note there is no key in this request:

```sh
curl -sS -X POST http://localhost:8787/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"query":"Hva er Digdir?"}'
```

The streaming route is the same shape at `/api/chat/stream`, and returns
`text/event-stream`.

## The tool is pinned server-side, on purpose

The browser sends a **question**, not a target. `proxy.mjs` chooses the tool,
tenant and dataset scope itself and ignores anything the client says about them.

Your API key is scoped to particular agents and datasets. If the browser could
name the tool, it would have the run of that entire scope — the key would be
server-side in the letter and client-controlled in effect.

## Proving the stream actually streams

**A buffering proxy is not visibly broken.** The client receives every byte and
the correct answer; it just receives them all at the end, so the app looks slow
rather than wrong. "I got the events" is therefore not evidence of streaming —
arrival *times* are.

`check-streaming.mjs` times every SSE frame through the real proxy, and through
a control proxy that awaits the whole upstream body before writing (the exact
bug). If both scored the same, the check could not tell the two states apart and
would prove nothing — so it fails loudly in that case rather than passing.

```sh
node proxy.mjs &
node check-streaming.mjs
```

Observed on a live server (local model, so the absolute times are slow — it is
the *shape* that matters):

```
── REAL proxy (proxy.mjs, streams through) ──
  frames                : 27
  total elapsed         : 47842 ms
  first frame at        : 350 ms  (0.7% of the way through)
  spread first→last     : 47492 ms
  gaps >= 200ms         : 20 of 26

── CONTROL (awaits whole body — the bug) ──
  frames                : 24
  total elapsed         : 41744 ms
  first frame at        : 41744 ms  (100.0% of the way through)
  spread first→last     : 0 ms
  gaps >= 200ms         : 0 of 23
```

The control's two numbers are the whole point: **first frame at 100.0% of the
way through, spread 0 ms.** Every frame arrived together, at the end. That is
what a buffering proxy looks like from the outside, and it is why this is
measured rather than asserted.

## What keeps it incremental

Four things, and all four matter. Removing any one reintroduces the bug
silently:

1. **Write each chunk as it arrives.** Never accumulate, and never
   `await upstream.text()` — that single line is the control above.
2. **`flushHeaders()`**, so the client can open the stream before the first byte.
3. **`socket.setNoDelay(true)`** — Nagle otherwise holds small frames back.
4. **`X-Accel-Buffering: no`** — nginx and several PaaS proxies buffer
   `text/event-stream` by default and will re-introduce the bug *outside* your
   process. Do not drop it because it works locally.

And one thing not to add: **no compression middleware on the streaming route.**
gzip buffers to fill its window and undoes all four.

If you deploy behind something this repo does not control, re-run
`check-streaming.mjs` against the deployed URL. Point 4 is the one that
usually bites, and it bites in production only.
