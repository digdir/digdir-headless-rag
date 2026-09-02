# API Conversations Diagnostics Plan

Status: Completed on 2026-04-11.

Review note: `GET /api/conversations/:id` now exposes normalized message
diagnostics behind `include_diagnostics=true`, `/api/rag` persists normalized
diagnostics into `:message/diagnostics`, and tests cover default behavior,
opt-in responses, malformed stored diagnostics, and persistence.

## Goal

Expose normalized assistant-message diagnostics to `GET /api/conversations/:id`
clients behind an opt-in query parameter, while preserving the current default
response shape and behavior.

## Constraints

- Default `GET /api/conversations/:id` responses must remain unchanged.
- Diagnostics should only be returned when explicitly requested.
- Public API responses should expose normalized diagnostics, not raw internal
  execution payloads.
- Existing conversations may not have diagnostics stored; the endpoint must
  handle mixed historical data safely.

## Implementation Steps

1. Add an opt-in query parameter to `GET /api/conversations/:id`, using
   `include_diagnostics=true`.
2. Add a conversations API helper that:
   - reads `:message/diagnostics` when present
   - parses EDN safely
   - normalizes the payload with
     `digdir.playground.diagnostics/normalize-diagnostics`
   - omits the field when diagnostics are absent, malformed, or not requested
3. Update the public conversation message serializer to attach `diagnostics`
   only when requested, alongside the existing `text`, `filterValue`, and
   `chunks` fields.
4. Extend the `/api/rag` persistence path so assistant messages can store
   normalized diagnostics in the existing `:message/diagnostics` field.
5. Add tests covering:
   - default conversation responses remain unchanged
   - `include_diagnostics=true` returns normalized diagnostics when present
   - absent diagnostics remain absent
   - malformed stored diagnostics fail safe without breaking the endpoint
   - `/api/rag` persists diagnostics when present
6. Update API docs and OpenAPI to document the opt-in query parameter and the
   optional `message.diagnostics` field.
7. Manually verify payload size and response usefulness with a conversation that
   contains larger `agent-trace` or `iteration-history` sections.

## Recommended Data Shape

Persist normalized diagnostics rather than raw diagnostics.

Why:

- It creates a more stable public contract.
- It reduces the risk of leaking unnecessary internal fields.
- It avoids repeated normalization work on every read.

## Expected Outcome

- Existing clients see no behavior change by default.
- Clients that opt in can fetch assistant-message diagnostics through the
  conversation history API.
- Newly created API conversations can expose diagnostics because `/api/rag`
  starts persisting them.
