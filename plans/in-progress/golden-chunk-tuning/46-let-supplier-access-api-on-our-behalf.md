# Q46 v1 — We use an outside software supplier and want to let them pull our data through the API on our behalf — how do we give them that access, and later take it away?

**Grounded source**: `41a2397495f1` (Delegate scopes, authentication). **Register**: lay — business owner describing supplier delegation in everyday terms ("outside software supplier", "give them access", "take it away"), avoiding "delegate scopes" / "subcontractor scope delegation" jargon.

## Goldens (read-confirmed)
- `f6a34be9fbeb` — core; walks through granting API access to the supplier (App Instances full/read access) as a key-role user.
- `8e9adf466174` — core; explains and shows how delegations are removed when the supplier no longer needs access.
- `61b3757a8553` — supporting; frames why a service owner delegates API access to a subcontractor and whose responsibility removal is.

## Cited chunks

`f6a34be9fbeb` `8e9adf466174` `61b3757a8553`
