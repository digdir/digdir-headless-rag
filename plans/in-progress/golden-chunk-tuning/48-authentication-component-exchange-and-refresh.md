# Q48 v1 — How does the Altinn authentication component exchange and refresh JWT tokens from external identity providers like ID-porten and Maskinporten?

**Grounded source**: `073c948598d5` (Authentication, authentication). **Register**: aligned (control) — uses corpus terminology directly ("authentication component", "exchange and refresh JWT tokens", "external identity providers").

## Goldens (read-confirmed)
- `6f1a3cf6c1c8` — core; organizations (Maskinporten) and end users (ID-porten) exchange their JWT for an Altinn Platform JWT; gives the authentication base URL and the refresh endpoint.
- `4edb724802b0` — core; documents the `/exchange/{tokenProvider}` endpoint, accepted providers, and that only the id-porten access token is exchanged.
- `16b9b65b6df7` — supporting; the component is not an identity provider itself but builds sessions on ID-porten / Maskinporten / Feide and issues JWTs.

## Cited chunks

`6f1a3cf6c1c8` `4edb724802b0` `16b9b65b6df7`
