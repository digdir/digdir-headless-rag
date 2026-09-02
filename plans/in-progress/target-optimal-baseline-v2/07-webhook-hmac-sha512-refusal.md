# Q7 v2 — Does Altinn support webhook signatures using HMAC-SHA512?

**Category**: Refusal
**v1 calls**: 14
**v2 calls**: 4 (with v1 knowledge); ~5-6 with fresh exploration

## Strategy (v2)

The fundamental finding ("no HMAC-SHA512 anywhere, and Altinn
Events doesn't document webhook signatures at all — model is
TLS + auth registration + IP whitelist") was established in v1.
v2's question: does anything change in the augmented corpus,
and do the new fields help confirm absence faster?

## Trail

### Tool 1 — Direct exact-phrase + broader probes

```
bb ts-search digdir public-docs chunks "HMAC-SHA512" --query-by content_markdown
bb ts-search digdir public-docs chunks "HMAC"        --query-by content_markdown
```

- **"HMAC-SHA512" exact: 0/0 hits.** Still completely absent.
- **"HMAC" alone: 1/17 hits.** Slightly more than v1 (9), but
  the top hit is `Expressions` at `/v8/reference/logic/expressions/`
  — totally unrelated to webhooks.

### Tool 2 — Try to find setup-subscription by linktitle

```
bb ts-search digdir public-docs docs "subscription" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'language:=en && diataxis:=how-to-guides' --limit 5
```

**0 hits.** The new fields don't help here — the setup-subscription
doc's `linktitle` is "Set up a subscription" (per v1's reading
of its frontmatter), and "subscription" alone in `linktitle`
field tokenization apparently doesn't match. A v2-only operator
without v1's doc_num knowledge would need to fall back to
chunk-content search like v1 did.

### Tool 3 — Read the canonical setup-subscription doc (known from v1)

```
bb ts-get digdir public-docs chunks --range 23a9d95b4a7b:0-2 ...
# then grep for signature/HMAC/SHA/secret/signing terms
```

**Zero matches** for any signature-related term in the 3 chunks.

## Cited chunks

Same as v1 — the canonical webhook-security docs are unchanged:
- `54076a283ae5` `cb774c9cc765` `ee4e3b2757df` — `/en/events/subscribe-to-events/`
- `af6f8ec6f366` `0b06325825a5` `0384a04931e5` — `/en/events/.../setup-subscription/`
- `f2c583f5eeb5` `fd2b51bae6e8` `d4182b045852` `b52534d4d560` — `/en/dialogporten/user-guides/detecting-changes/`

## Answer

(Unchanged from v1.)

**No.** Altinn does not document webhook-signature verification —
neither HMAC-SHA512 nor any other signature algorithm. The
webhook security model is:

1. **Auth on the registration call** — Maskinporten scope
   `altinn:events.subscribe`.
2. **TLS required on subscriber endpoint** — publicly trusted
   certs; self-signed rejected.
3. **One-time endpoint validation** — Altinn POSTs a
   `platform.events.validatesubscription` event; subscription only
   activates on 200 OK.
4. **Static IPs for whitelisting** — `20.100.24.41/32` (TT02),
   `20.100.46.139/32` (production).

No `X-Hub-Signature` header, no shared secret on the subscription
request, no signing-key endpoint documented anywhere in the
corpus. A user with HMAC-SHA512 requirements would need to
escalate outside the corpus (Altinn team or
[altinn-events repo](https://github.com/Altinn/altinn-events)).

## Self-assessment vs v1

**What got better**:
- **Refusal-confirmation faster** *with v1 knowledge*. With the
  setup-subscription doc_num already known, confirming "no
  signature mechanism" is one fetch.

**What didn't improve**:
- **Refusal-confirmation didn't get easier from fresh
  exploration.** The new fields (linktitle, diataxis, language)
  didn't surface the setup-subscription doc when I searched for
  "subscription" with the EN how-to filter. A fresh v2-only
  operator would have to fall back to chunk-content search just
  like v1.

**Honest accounting**: v2's 4-call count is largely "v1 found
the docs; v2 confirmed the absence again." A truly v1-blind v2
re-exploration would likely take 5–6 calls (still better than 14,
but the win is "the refusal answer is stable across re-imports",
not "the new fields make refusal easier").

## Predicted gap on Q7 v2

Same as v1 (very large). The fluency-failure mode — "21 webhook-
signature chunks in the corpus, weave one into an answer" — is
the dominant retrieval-system failure mode for this question and
no amount of indexing improvements changes that.
