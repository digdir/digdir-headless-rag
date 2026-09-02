# Q11 v1 — What do I need in place and what payload do I post to ask a user to approve data sharing?

**Grounded source**: `7da06f05db4f` (Request consent, authorization). **Register**: developer — asks about "what do I need in place" and "what payload do I post" rather than corpus terms "consent request", "consentRights", "redirectUrl", "PortalViewMode".

## Goldens (read-confirmed)
- `628290aa1d8d` — ch0: prerequisites — a registered Maskinporten client, the consent scopes delegated from Digdir (`altinn:consentrequests.read`/`.write`), the scopes added to the client, and access granted for the relevant resource(s). Core.
- `b5bafba34ce0` — ch1: the test/production POST endpoints and the full JSON request/response body (from, to, validTo, consentRights, redirectUrl), plus how the redirect URL carries identifiers and how PortalViewMode controls synchronous vs portal approval. Core.

## Cited chunks

`628290aa1d8d` `b5bafba34ce0`
