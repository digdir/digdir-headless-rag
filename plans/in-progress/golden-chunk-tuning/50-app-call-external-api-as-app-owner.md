# Q50 v1 — How do I make my Altinn app call an external service authenticated as the organisation that owns the app, instead of as the person currently using it?

**Grounded source**: `8ac5bf4e1b4b` (Maskinporten, api). **Register**: developer — app builder describing acting "as the organisation that owns the app" rather than the active user, asking for the built-in client wiring without leaning on "IMaskinportenClient" / "client credentials" jargon in the question.

## Goldens (read-confirmed)
- `01b85984aa49` — core; states the built-in Maskinporten client makes authorized requests on behalf of the app owner (not the active user) and lists the setup steps.
- `31aed2185e7f` — core; shows wiring the app to the Maskinporten client, the appsettings/Key Vault secrets, and `UseMaskinportenAuthorization` / `UseMaskinportenAltinnAuthorization` for external vs Altinn APIs.
- `6d1b2c61879c` — supporting; registering the Maskinporten integration and adding the authentication key/scopes that the app then uses.

## Cited chunks

`01b85984aa49` `31aed2185e7f` `6d1b2c61879c`
