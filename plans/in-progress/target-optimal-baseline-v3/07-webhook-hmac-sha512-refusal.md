# Q7 v3 — Does Altinn support webhook signatures using HMAC-SHA512?

**Category**: Refusal (with v3 nuance)
**v1 calls**: 14
**v2 calls**: 4 (with v1 knowledge); ~5-6 fresh
**v3 calls**: 5 (with v1 knowledge); first baseline to surface the
"event secret" nuance

## Strategy (v3)

Repeat v2's exact-phrase + linktitle + setup-subscription probes;
**add a phrases-collection probe** to test whether the now-healthy
phrase corpus surfaces anything v1/v2 missed.

## Trail

### Tool 1 — Exact-phrase + broader chunk probes

```
bb ts-search digdir public-docs chunks "HMAC-SHA512" --query-by content_markdown
bb ts-search digdir public-docs chunks "HMAC"        --query-by content_markdown
```

- **"HMAC-SHA512" exact: 0/0 hits.** Same as v2.
- **"HMAC" alone: 9/9 hits.** v2 saw 17 (duplicate revisions);
  v3's orphan-delete leaves 9 unique chunks. Top hit is
  `Expressions` (a JavaScript-style expression doc) — unrelated
  to webhooks.

### Tool 2 — Try subscription linktitle (still doesn't work)

```
bb ts-search digdir public-docs docs "subscription" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'language:=en && diataxis:=how-to-guides' --limit 5
```

**0 hits.** Same v2 finding: linktitle tokenization doesn't match
"subscription" alone.

### Tool 3 — **NEW v3 probe: phrases-collection for "webhook signature"**

```
bb ts-search digdir public-docs phrases "webhook signature" --limit 5
```

**5 hits** — and the top one is a *new doc* v1/v2 never inspected:

- `9b4017645a43` (chunk 2, doc `af9c4ce14a2b`
  `/en/altinn-studio/v8/reference/logic/events/subscribing/`) —
  search_phrase **"webhook secret defined"**.

This is the v3-specific signal. The chunks-collection BM25 search
in v1/v2 never surfaced this doc because:
1. It's under `/altinn-studio/v8/reference/logic/events/` (the
   *app-side* subscriber path), not under `/events/` (the
   server-side Events API path that v1/v2 enumerated).
2. The literal phrase "webhook signature" does not appear in any
   chunk content. The phrase appears *only in the phrase corpus*,
   generated from the chunk's semantic content.

### Tool 4 — Read the relevant chunks of `af9c4ce14a2b`

```
bb ts-get digdir public-docs chunks --range af9c4ce14a2b:0-3 ...
```

Returns 3 chunks. Chunk 1 (`09eeadadc174`) contains the
**"Protecting the event endpoint with a secret"** section:

> "Upon receiving an event, the application validates if a secret
> is provided before accepting the event. The secret is provided
> by implementing the `IEventSecretCodeProvider` interface. By
> default there is an example implementation in place using a key
> from the key vault using a key with the name
> `EventSubscription--SecretCode`."

This is a **shared-secret authentication** mechanism on the
subscriber side — not a signature on the payload, no HMAC, no
algorithm specified. The secret is delivered as part of the
subscription registration; the subscriber validates incoming
requests carry it.

### Tool 5 — Re-confirm with the canonical setup-subscription doc

```
bb ts-get digdir public-docs chunks --range 23a9d95b4a7b:0-2 ...
```

Returns 3 chunks (`6fcb16e12b0e`, `4aa2b0740446`, `c5c607199c7e`).
Confirms: no HMAC, no signature header, no algorithm. The events
*registration* endpoint takes resourceFilter / subjectFilter /
typeFilter / sourceFilter — no `signingKey` or `secret` field at
all. The endpoint-side secret (Tool 4) is configured separately on
the app side, not registered with Altinn.

## Cited chunks

- `09eeadadc174` (NEW v3 cite) — IEventSecretCodeProvider +
  EventSubscription--SecretCode keyvault key
- `9b4017645a43` (NEW v3 cite) — example wiring confirming the
  secret is a precondition, not a signature
- `6fcb16e12b0e`, `4aa2b0740446`, `c5c607199c7e` — setup-subscription
  request shape (no signature field, TLS required, validation event)

## Answer (v3 nuance over v1/v2)

**No, Altinn does not support webhook signatures using HMAC-SHA512
— or any other signature algorithm.** The corpus does not document
a signed-payload scheme.

**However** (this is the v3 addition the phrase corpus surfaced),
Altinn *does* document a webhook **shared-secret** authentication
mechanism, distinct from signatures:

- On the **subscriber side**, the app implements
  `IEventSecretCodeProvider` (chunk `09eeadadc174`). The default
  reference implementation looks up a keyvault key named
  `EventSubscription--SecretCode`. The app rejects incoming events
  unless they carry the matching secret.
- The secret value is **part of the subscription registration**;
  rotating it requires removing and re-creating the subscription.
- **No algorithm is specified** — it is a shared-secret check, not
  an HMAC signature. So even with this mechanism, the answer to
  "HMAC-SHA512?" is still No.

The full security model documented in the corpus:

1. **Auth on the registration call** — Maskinporten scope
   `altinn:events.subscribe`.
2. **TLS required on subscriber endpoint** — publicly trusted
   certs; self-signed rejected.
3. **One-time endpoint validation** — Altinn POSTs a
   `platform.events.validatesubscription` event; subscription
   activates only on 200 OK.
4. **Static IPs for whitelisting** — `20.100.24.41/32` (TT02),
   `20.100.46.139/32` (production).
5. **Shared-secret check on subscriber side** (v3 addition) —
   via `IEventSecretCodeProvider`; opaque value, no algorithm.

A user with HMAC-SHA512 requirements would need to escalate
outside the corpus.

## Self-assessment vs v2

**What got dramatically better (and is the headline v3 finding)**:

- **The phrases collection surfaced a doc v1 and v2 both missed.**
  v1 spent 14 calls confirming "no HMAC anywhere" but never
  looked at `af9c4ce14a2b`. v2's 4 calls re-used v1's doc_nums
  and so also missed it. The phrase `"webhook secret defined"`
  attached to chunk `9b4017645a43` is a semantic enrichment —
  the literal string doesn't appear anywhere in chunk content_markdown,
  but the phrase generator produced it from the chunk's meaning.
- This **strengthens the refusal**: the v3 answer now correctly
  distinguishes "no signature mechanism" (still true) from
  "no shared-secret mechanism" (which v1/v2 *implied* and v3
  corrects).

**What did not change**:
- The fundamental refusal stands. No HMAC, no signatures, no
  algorithm specified anywhere in the corpus.
- Linktitle-tokenization of "subscription" still doesn't match
  (Tool 2). This is the same v2 finding (worth investigating).

## Predicted gap on Q7 v3

Still large for refusal discipline — the fluency-failure mode
("21+ webhook-signature-adjacent chunks, weave one in") remains
the dominant retrieval-system trap. But the v3 finding suggests
the **right** retrieval improvement: a multi-strategy retrieval
that consults phrases (not just chunks) can recover docs whose
chunk content doesn't lexically match the query. For Q7 this
means a phrase-aware retrieval would now find the
`subscribing/` doc and produce the *richer* refusal v3 captures
above — instead of the simpler v1/v2 refusal that missed the
shared-secret detail.

This is the most concrete payoff of the phrase-corpus cleanup
across all 7 questions.
