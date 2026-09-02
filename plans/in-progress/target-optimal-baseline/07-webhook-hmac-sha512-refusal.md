# Q7 — Does Altinn support webhook signatures using HMAC-SHA512?

**Category**: Refusal — the question is precisely-worded to be either
"Yes, with citation" or a confident "No / not documented".
**Expected locality**: zero supporting chunks → explicit refusal
**Actual finding**: HMAC-SHA512 specifically is absent; furthermore,
the corpus's webhook security model is **TLS + authenticated
registration + IP whitelisting**, with no signature mechanism of any
kind documented. The answer is a confident, fully-cited **No**.
**Retrieval calls used**: 14 (over budget — appropriate for a
refusal-case where confirming absence matters more than minimising
calls)

## Strategy

Refusal-case. The risk is fabricating an answer because BM25 matches
on partial query terms ("webhook", "signature", "HMAC") will surface
*some* chunk and it'll be tempting to over-summarize. Plan: probe
the specific term ("HMAC-SHA512"), the broader feature area
("webhook signature"), and the broader cryptographic primitive
("HMAC") in parallel; identify the canonical webhook setup doc;
read it in full; verify no signature mechanism is documented.

## Trail

### Tool 1 — Four parallel probes

```
bb ts-search digdir public-docs chunks "HMAC-SHA512"  ...     →  0/0 hits
bb ts-search digdir public-docs chunks "webhook signature" ... →  5/21 hits
bb ts-search digdir public-docs chunks "HMAC" ...             →  5/9  hits
bb ts-search digdir public-docs docs   "webhook"              →  0/0 title hits
```

First clear signal: **"HMAC-SHA512" appears nowhere in the corpus.**
"Webhook signature" appears in 21 chunks; "HMAC" alone in 9.

### Tool 2 — Identify the parent doc of the chunk-index=0 webhook-signature hit + probe alternative algorithms

```
bb ts-get digdir public-docs docs --ids 89a4d6316770
bb ts-search digdir public-docs chunks "SHA256" ...
bb ts-search digdir public-docs chunks "webhook signature sha" ...
```

- `89a4d6316770` is "Detecting changes" at
  `/en/dialogporten/user-guides/detecting-changes/` (4 chunks).
- "SHA256" full-text: only 3 chunk hits, all at chunk_index 6 of
  three different docs (not webhook docs).
- Adding "sha" to the webhook query didn't surface anything new —
  same 3 of the original 21 hits.

### Tool 3 — Read the Dialogporten "Detecting changes" doc + identify other webhook-signature parent doc

```
bb ts-get digdir public-docs chunks --range 89a4d6316770:0-3
bb ts-get digdir public-docs docs   --ids adb879dfbcac
```

- "Detecting changes" (full 4 chunks): discusses how to detect
  Dialogporten changes via Altinn Events (webhooks) or GraphQL
  subscriptions. **No mention of signatures, HMAC, or any hash
  algorithm.** Delegates webhook setup to
  `/en/events/subscribe-to-events/`.
- `adb879dfbcac` is "Receivingdata" at
  `/en/api/guides/appownerintegration/receivingdata/` (9 chunks).

### Tool 4 — Receivingdata chunk 5 + find canonical events doc + scan more webhook-signature hits

```
bb ts-get digdir public-docs chunks --ids 025dfba543d5
bb ts-search digdir public-docs docs "subscribe events" --query-by title
bb ts-search digdir public-docs chunks "webhook signature" --limit 8
```

- Receivingdata chunk 5 just *points at*
  `/en/events/subscribe-to-events/developer-guides/setup-subscription/#request`
  for webhook requirements; doesn't itself document signatures.
- "Subscribe to events" (EN + NB) doc titles found
  (`37e9eba5fd6b`, 3 chunks).
- The expanded 8-hit list confirms all 3 chunks of
  `37e9eba5fd6b` ("Subscribe to events") appear in the webhook-
  signature hit set; also `88a046dc8cb8` chunk 4 appears.

### Tool 5 — Read "Subscribe to events" + find the deeper "Setup subscription" doc

```
bb ts-get digdir public-docs chunks --range 37e9eba5fd6b:0-2
bb ts-search digdir public-docs docs "setup subscription" --query-by title
```

- "Subscribe to events" (full 3 chunks): documents the subscription
  request shape, the validation event, the retry schedule (12
  retries on HTTP non-200), and static IP whitelisting. **No
  signature or HMAC mention anywhere.**
- "Setup subscription" docs exist EN + NB at
  `/en/events/subscribe-to-events/developer-guides/setup-subscription/`
  (`23a9d95b4a7b`, 3 chunks). This is exactly the doc Receivingdata
  linked to.

### Tool 6 — Read "Setup subscription" in full (the canonical API doc)

```
bb ts-get digdir public-docs chunks --range 23a9d95b4a7b:0-2
```

The 3 chunks document:

- API: `POST /subscriptions`, requires Maskinporten scope
  `altinn:events.subscribe` (plus `altinn:serviceowner` for
  service-owner subscriptions).
- Subscription request properties: `endpoint`, `resourceFilter`,
  `sourceFilter`, `subjectFilter`, `alternativeSubjectFilter`,
  `typeFilter`.
- **HTTPS endpoints must use publicly trusted TLS-certificates.
  Self-signed certificates are not supported.**
- Endpoint must respond `200 OK` to a custom validation event when
  the subscription is first registered.
- Response codes (201, 401, 403); example `curl` request showing
  bearer-token auth on the *registration* call.

**No webhook signature mechanism documented.** The security model
is: (a) authenticate the registration via Maskinporten,
(b) require TLS at the subscriber's endpoint, (c) Altinn validates
the endpoint owns the URL by POSTing a validation event,
(d) inbound webhook receivers can whitelist Altinn's static IPs
(`20.100.24.41/32` for TT02, `20.100.46.139/32` for production).

### Tool 7 — Sanity-check that the 9 HMAC chunks aren't about webhooks

```
bb ts-get digdir public-docs docs --ids a979511a6b1b,d9d0b39cdbfd,bf205f3b0499
```

Top 3 HMAC-mentioning docs:
- `d9d0b39cdbfd` — "Eformidling" at
  `/en/altinn-studio/v8/reference/configuration/eformidling/`
  (electronic-delivery integration; HMAC in a different context)
- `bf205f3b0499` — "Wcag" at
  `/en/altinn-studio/v8/guides/design/guidelines/wcag/`
  (accessibility — HMAC is incidental, possibly a content-hash
  example)
- (first result truncated)

**None of the HMAC chunks are about webhook signatures.**
Cross-verification complete: HMAC in the corpus is for Eformidling
and unrelated concerns, not for webhook authenticity.

## Cited chunks

- `f2c583f5eeb5`, `fd2b51bae6e8`, `d4182b045852`, `b52534d4d560`
  (the 4 chunks of `/en/dialogporten/user-guides/detecting-changes/`)
- `54076a283ae5`, `cb774c9cc765`, `ee4e3b2757df`
  (the 3 chunks of `/en/events/subscribe-to-events/`)
- `af6f8ec6f366`, `0b06325825a5`, `0384a04931e5`
  (the 3 chunks of `/en/events/subscribe-to-events/developer-guides/setup-subscription/`)

## Answer

**No.** Based on the corpus, Altinn does **not** document
webhook-signature verification at all — neither HMAC-SHA512 nor any
other signature algorithm.

The corpus documents the webhook *security model* explicitly,
across the three relevant docs:

1. **Authentication is on the subscription-registration call**, not
   on inbound webhook deliveries. To create a subscription via
   `POST /events/api/v1/subscriptions`, the caller authenticates
   via Maskinporten with the scope `altinn:events.subscribe`
   (plus `altinn:serviceowner` for service-owner subscriptions).
   (`af6f8ec6f366`)
2. **TLS is required on the subscriber's webhook endpoint.**
   HTTPS endpoints must use publicly trusted TLS certificates;
   self-signed certs are explicitly rejected and cause
   subscription validation to fail. (`0b06325825a5`)
3. **Endpoint ownership is verified by a one-time POST**, not by
   per-event signatures. Once a subscription is registered, Altinn
   POSTs a `platform.events.validatesubscription` cloud event to
   the endpoint and expects a `200 OK` response. The subscription
   only starts receiving real events once validated.
   (`0b06325825a5`, `cb774c9cc765`)
4. **For inbound-traffic filtering, Altinn provides static IPs**
   subscribers can whitelist: `20.100.24.41/32` (TT02) and
   `20.100.46.139/32` (production). This is the corpus's
   recommended way for a subscriber to verify that an inbound
   webhook actually came from Altinn. (`ee4e3b2757df`)

Search confirmation (negative results):

- `"HMAC-SHA512"` full-text on chunks: **0 hits**
- `"HMAC"` alone: 9 hits, all in unrelated docs
  (Eformidling, WCAG, etc.) — none about webhook
  authenticity.
- `"webhook signature"`: 21 chunks. The 8 highest-ranked all
  come from the three docs cited above, none of which specifies a
  signature algorithm.
- The canonical setup doc
  (`/en/events/subscribe-to-events/developer-guides/setup-subscription/`)
  describes the request shape, validation event, response codes,
  and an example `curl` — **no `X-Hub-Signature`-style header is
  documented, no shared-secret field on the subscription request,
  no signing-key endpoint.**

If HMAC-SHA512 verification is *required* for a particular use
case, this corpus does not document a way to obtain that property
from Altinn Events. A user with that requirement would need to
either (a) raise this with the Altinn team, (b) check the
underlying [altinn-events repository](https://github.com/Altinn/altinn-events)
or release notes outside this corpus, or (c) verify webhook
authenticity through the IP-whitelist + TLS chain documented
here instead.

## Self-assessment

**What was hard**: this question is *easy to fail toward fluency*.
A retrieval system that found "webhook signature" chunks could
have produced an authoritative-sounding affirmative answer by
weaving together "Altinn Events supports webhooks" + "HMAC is
mentioned elsewhere" + plausible-sounding default behavior. The
honest answer requires (a) actually reading the canonical setup
doc, (b) noticing the *absence* of a signature mechanism, and
(c) cross-checking that "HMAC" hits aren't in webhook docs.

**What was easy**: once I committed to refusing if the answer
wasn't directly supported, the search itself was deterministic.
HMAC-SHA512 → 0 chunks. That's a hard refusal trigger.

**What surprised me**: **Altinn Events has a substantially
different webhook trust model than most "webhook signature"
ecosystems.** Most webhook providers (Stripe, GitHub, etc.) use
per-event HMAC signatures with a subscriber-shared secret. Altinn
instead uses: (a) authenticated *registration* via Maskinporten,
(b) TLS-only delivery, (c) endpoint validation via a one-shot
challenge, (d) static IP whitelisting for subscriber-side filtering.
This is a real architectural choice, not a documentation gap — the
absence of signatures isn't an oversight, it's the design. An
honest answer to "does Altinn support HMAC-SHA512?" needs to
explain *what Altinn does instead*, not just say "not found".

**What would surprise an automated retrieval system**:

1. **The fluency-failure mode is the dominant risk.** A
   chunk-ranking system that sees "webhook signature" matches
   could easily quote chunk text adjacent to the literal phrase
   ("subscribing via webhook (recommended)") without registering
   that no algorithm is named. The fabrication path of least
   resistance is "Altinn Events supports webhook signatures —
   [unspecified algorithm]". A user reading that would assume
   the algorithm exists somewhere they haven't found yet.
2. **HMAC-only retrieval would give the wrong picture.** A
   system retrieving on "HMAC" alone would return Eformidling
   and WCAG chunks — making it look like HMAC *is* used somewhere
   in Altinn. Without scoping to webhook docs specifically, this
   creates a misleading partial answer.
3. **Recognising "asked about feature X, found context-without-X"
   as refusal-worthy.** The system has to distinguish (i) "found
   the answer Yes" from (ii) "found the area but the specific
   thing isn't there". This is the same judgment Q6 required —
   refusing fluently is harder than answering fluently.
4. **The *positive* part of the refusal**. The best refusal
   isn't just "not documented" — it's "not documented, AND here's
   what *is* documented (IP whitelisting, validation event,
   Maskinporten reg), AND here's where to look outside the
   corpus". Each layer makes the answer more useful and harder
   for a one-pass retrieval system to produce.

**Predicted gap on Q7**: very large. The right answer requires
discipline that retrieval+synthesis systems rarely have by
default: refusing the literal question, accurately describing the
adjacent feature instead, and pointing to the external sources.
The chunks that *do* surface on the query are seductive in their
combination — easy to weave into a confident wrong answer.
