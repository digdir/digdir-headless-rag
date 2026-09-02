# Q45 v1 — My Maskinporten token gets rejected when I call the Altinn API directly — how do I turn it into a token Altinn accepts?

**Grounded source**: `8c7c4061b529` (Authentication, api). **Register**: developer — phrased as a concrete integration failure ("token gets rejected", "call the API directly"), asks for the exchange step rather than using "token exchange endpoint" jargon outright.

## Goldens (read-confirmed)
- `3522fcaeae7a` — core; describes the exchange of the Maskinporten JWT for an Altinn JWT, shows the `GET /authentication/api/v1/exchange/maskinporten` call and the input/output token shapes.
- `e65d5e1e2600` — supporting; shows the consumer creating a Maskinporten client with the Altinn-provided scope (precondition for getting a token to exchange).

## Cited chunks

`3522fcaeae7a` `e65d5e1e2600`
