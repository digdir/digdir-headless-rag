# Q43 v1 — Altinn Events architecture: storage queues, Azure Functions and the events database schema

**Grounded source**: `61de5962ca42` (Events, events). **Register**: aligned — corpus-aligned control using the architecture's own vocabulary (storage queues, Azure Functions, database schema).

## Goldens (read-confirmed)
- `79de299184a6` — core; the Azure Functions (EventsRegistration, EventsInbound, EventsOutbound, SubscriptionValidation), their trigger queues and retry mechanisms.
- `15c294be838e` — core; the Azure Storage Queues (events-registration, events-inbound, events-outbound, subscription-validation) used to pass data between services.
- `06705dd6f823` — supporting; the PostgreSQL events database schema (events and subscription tables).
- `2c88357cd783` — supporting; the public API controllers (App, Events, Subscription) defined by the service.

## Cited chunks

`79de299184a6` `15c294be838e` `06705dd6f823` `2c88357cd783`
