# Q13 v1 — As an API provider, how do I read the system user token and authorize the request against Altinn PDP?

**Grounded source**: `05f84fc96a40` (System-user (resource-owner/API-provider guide), authorization). **Register**: aligned (control) — deliberately uses the corpus's own terms "system user token", "API provider", "Altinn PDP", "authorize".

## Goldens (read-confirmed)
- `1071f704051a` — ch2: shows an example system user token, explains the key claims an API provider needs (systemuser_id, systemuser_org, system_id, consumer.id), then shows the PDP authorization request and the XACML Permit/Deny response logic. Core.
- `fd72a28afd94` — ch1: the surrounding setup — service owner develops the API, configures a Maskinporten scope, and registers the resource in the Resource Register with access rules. Supporting.

## Cited chunks

`1071f704051a` `fd72a28afd94`
