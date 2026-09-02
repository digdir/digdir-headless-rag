# Q29 v1 — How do I search for dialogs in the end-user API, filtering by party and status, and paginate through the results?

**Grounded source**: `ce2c0416fe68` (Searching for dialogs, dialogporten). **Register**: aligned (control) — uses corpus terminology (search, party, status, pagination) directly.

## Goldens (read-confirmed)
- `82cf3242bd7f` — core; documents `GET /api/v1/enduser/dialogs`, AND/OR semantics of parameters, `party`/`org`/`serviceResource` formats, and the requirement that at least one party or serviceResource be supplied.
- `a9b270a01eed` — core; explains continuation-token pagination with `hasNextPage` and `continuationToken`.
- `8ddf45a6eabd` — supporting; lists orderable columns and the default `contentupdatedat_desc` ordering.

## Cited chunks

`82cf3242bd7f` `a9b270a01eed` `8ddf45a6eabd`
