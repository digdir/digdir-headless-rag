# Q47 v1 — Why does Altinn make my app swap the login token from the national login service for a different token before I can call its APIs?

**Grounded source**: `a681044d6aa6` (Authentication api, authentication). **Register**: developer — asks about the design reason for the token swap, using "national login service" instead of naming ID-porten/Maskinporten, and "swap the login token" instead of "exchange external token".

## Goldens (read-confirmed)
- `ecd87b44b6a9` — core; states clients must exchange external tokens for an Altinn token to reduce complexity/improve performance so apps need not know about every ID provider.
- `a897814457e3` — supporting; describes the end-user OIDC login getting an ID/access token from ID-porten that is then exchanged for an Altinn JWT.
- `7d91de0e6236` — supporting; explains the same exchange for org/system tokens from Maskinporten and why it boosts performance.

## Cited chunks

`ecd87b44b6a9` `a897814457e3` `7d91de0e6236`
