# Q8 v1 — How does it work when I allow my bank to fetch my income information from a public agency on my behalf?

**Grounded source**: `d575a2d80339` (Consent, authorization). **Register**: lay — describes the consent data-sharing flow in plain terms ("allow my bank to fetch my income data") rather than corpus jargon "consent token", "data consumer", "delegation", "Maskinporten".

## Goldens (read-confirmed)
- `9e1666d9a336` — ch3: defines the roles (end user grants consent, data consumer e.g. a bank requests the data, service owner owns it) and lays out the step-by-step flow: user starts service, consumer requests consent, user is redirected to approve, consumer gets a token and fetches the data. Core.
- `e396875b9f1f` — ch0: explains consent is a way to share data from a service owner to a data consumer based on the individual's consent, and provides the GDPR/confidentiality legal basis. Core (the "how does it work / why" framing).
- `362792b4400e` — ch5: the user can withdraw consent at any time in the Altinn portal, and consents have a defined duration. Supporting (answers the "am I in control" angle).

## Cited chunks

`9e1666d9a336` `e396875b9f1f` `362792b4400e`
