# Q9 v1 — Which endpoints do I call to attach and detach the companies an accountant integration represents?

**Grounded source**: `02bd1e0d49a8` (Client delegation for system vendors, authorization). **Register**: developer — asks about "attach/detach companies" and "endpoints" rather than the corpus terms "client delegation", "agent system user", "delegate clients".

## Goldens (read-confirmed)
- `32ee6aa61499` — ch1: GET endpoints to find the agent system users linked to the org and to retrieve the available clients that can be delegated, with scopes and example requests/responses. Core.
- `438d68cacb4b` — ch2: GET already-delegated clients and POST to delegate (attach) a client to the system user, with the `altinn:clientdelegations.write` scope and example calls. Core.
- `1f6dc956fee4` — ch3: DELETE endpoint to remove (detach) a client from the system user, plus the AuthorizedParties endpoint to find which clients the system user is authorised for. Core.

## Cited chunks

`32ee6aa61499` `438d68cacb4b` `1f6dc956fee4`
