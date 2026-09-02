# Q27 v1 — Given an Altinn app instance id or a correspondence id, how do I find the matching dialog (and translate between the two)?

**Grounded source**: `45297c937e2d` (Dialog lookup, dialogporten). **Register**: developer — about resolving/translating between a dialog ID and an underlying instance or correspondence reference.

## Goldens (read-confirmed)
- `5aa6b9d514f8` — core; explains dialog lookup resolves dialog metadata from a supported `instanceRef`, the supported URN formats (instance-id, correspondence-id, dialog-id), and the canonical-identifier preference order.
- `2865566589ca` — core; gives the end-user endpoint `GET /api/v1/enduser/dialoglookup` and its behavior (excludes deleted, returns authorizationEvidence and resolvable title).
- `6540b07c8bad` — supporting; describes the service-owner endpoint differences (always returns title, separate nonSensitiveTitle, ownership requirement).

## Cited chunks

`5aa6b9d514f8` `2865566589ca` `6540b07c8bad`
