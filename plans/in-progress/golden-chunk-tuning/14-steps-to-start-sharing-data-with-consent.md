# Q14 v1 — What setup does each side need before we can start sharing data based on a user's permission?

**Grounded source**: `aba5d8e0ceac` (Getting Started with Consent, authorization). **Register**: lay — frames it as "what setup does each side need" and "user's permission" rather than corpus terms "consent solution", "data consumer", "Resource Registry", "scopes".

## Goldens (read-confirmed)
- `0872329769ae` — ch1: the steps the service owner (data source) must complete — build an API that accepts a consent token, register scopes in Maskinporten, create a consent resource in the Resource Registry, manage access lists, document requirements, and validate the token. Core.
- `36cee5a43220` — ch2: the steps the data consumer (the side that wants the data) must complete — register a Maskinporten client, get the consent scopes/API key from Digdir, request access to the service owner's APIs, and integrate with the consent APIs. Core.

## Cited chunks

`0872329769ae` `36cee5a43220`
