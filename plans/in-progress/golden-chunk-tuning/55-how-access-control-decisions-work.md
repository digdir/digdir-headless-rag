# Q55 v1 — How does Altinn decide whether a user or system is allowed to read or write something in an app, and what pieces make that decision?

**Grounded source**: `3e7786809300` (Authorization, technology/security). **Register**: developer — asks about the decision mechanism and "what pieces make that decision"; the corpus answers in ABAC/XACML/PAP-PDP-PEP terms.

## Goldens (read-confirmed)
- `0b72efa29c30` — core; authorization is ABAC-based using XACML 3.0, and lists the policy/decision/enforcement capabilities.
- `a17ce69bcbf1` — core; the components that make the decision (PAP, PDP, PEP, PIP, PRP, Context Handler).
- `4b909d757fe8` — supporting; what a "right" is (permission to act — read/write/sign — on a resource for a party).
- `7f9430d5a733` — supporting; rule elements: subject, action (Read/Write/Sign/Confirm/Delete), condition, obligation (min auth level).

## Cited chunks

`0b72efa29c30` `a17ce69bcbf1` `4b909d757fe8` `7f9430d5a733`
