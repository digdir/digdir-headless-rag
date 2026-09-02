# Q18 v1 — How do I wire up a payment step in my app's process flow by hand, including the BPMN task and the data types it needs?

**Grounded source**: `b297413242a5` (Add process task, altinn-studio). **Register**: developer — uses BPMN/data-type vocabulary a developer doing manual config would use.

## Goldens (read-confirmed)
- `1959e6ab6e4f` — core: shows the BPMN process step + gateway for a payment task (taskType `payment`, pay/confirm/reject actions, paymentConfig) and the matching layoutSet config.
- `c0db84ac5786` — core: defines the `paymentInformation` and `paymentReceiptPdf` data types in applicationmetadata.json with `allowedContributors: app:owned`.

## Cited chunks

`1959e6ab6e4f` `c0db84ac5786`
