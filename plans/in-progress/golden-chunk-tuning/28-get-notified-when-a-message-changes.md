# Q28 v1 — Instead of constantly re-checking a message, can my system get told automatically whenever it changes or a new one arrives?

**Grounded source**: `89a4d6316770` (Detecting changes, dialogporten). **Register**: lay — frames it as "get told automatically" rather than "subscribe to events / GraphQL subscriptions".

## Goldens (read-confirmed)
- `fd8381537a60` — core; explains the two ways to detect changes (Altinn Events with webhook/polling for new and existing dialogs, vs GraphQL subscriptions for low-latency monitoring of a few known dialogs) and when to pick each.
- `fd2b51bae6e8` — supporting; points to how to subscribe to Altinn Events via webhook (recommended) or by polling the event API.
- `00aa34d5df46` — supporting; shows the GraphQL subscription delivering DIALOG_UPDATED / DIALOG_DELETED pushes for a monitored dialog.

## Cited chunks

`fd8381537a60` `fd2b51bae6e8` `00aa34d5df46`
