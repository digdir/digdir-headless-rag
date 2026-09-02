# Q58 v1 — Where does a stateful Altinn app actually store its data, and is that storage encrypted at rest?

**Grounded source**: `5e3391959242` (Encryption, technology/security). **Register**: developer — concrete "stateful app", "storage", "encrypted at rest"; aligns fairly closely with corpus but is a real implementation question.

## Goldens (read-confirmed)
- `4d29e5a16c6d` — core; storage uses Azure Cosmos DB (metadata) and Azure Blob Storage; both encrypt all data at rest transparently; Blob supports customer-managed keys.
- `babbd8059b38` — supporting; stateful apps store data in the Platform Storage component, and the Org sets encryption/confidentiality requirements per data sensitivity.

## Cited chunks

`4d29e5a16c6d` `babbd8059b38`
