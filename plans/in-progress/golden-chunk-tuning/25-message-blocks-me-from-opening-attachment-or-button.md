# Q25 v1 — When I open one of my messages some attachments and buttons are greyed out and one even gave a "forbidden" error — why can't I access them?

**Grounded source**: `3db194d20572` (Getting dialog details, dialogporten). **Register**: lay — describes the symptom (greyed-out actions, forbidden error) rather than asking about `isAuthorized` or authentication levels directly.

## Goldens (read-confirmed)
- `7862f7d4c489` — core; explains the authorization check sets an `isAuthorized` flag and replaces URLs with `urn:dialogporten:unauthorized` when the user is not authorized for an action or transmission.
- `506bcbea034e` — core; explains that a resource may require a minimum authentication level and that accessing with too low a level returns `403 Forbidden`.
- `2fa2b30bb3b8` — supporting; lists what the details view returns (actions, transmissions, attachments) that can be subject to these access checks.

## Cited chunks

`7862f7d4c489` `506bcbea034e` `2fa2b30bb3b8`
