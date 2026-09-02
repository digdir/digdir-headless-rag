# Q22 v1 — When I'm trying out my form on my own machine, which fake people can I log in as, and can I add my own with a different ID number?

**Grounded source**: `7f3039c3bd3e` (Test users, altinn-studio). **Register**: lay — "fake people / log in as / my own ID number" rather than "test users / testData.json / party roles".

## Goldens (read-confirmed)
- `9a53ae7fdb0b` — supporting: introduces the standard local-test users (Sophie Salt and her roles) shown in the user selection.
- `2f38ea1a18f7` — supporting: lists Ola Nordmann (private individual) and the family/income test users.
- `a79b2583b9f2` — core: explains you can override the default set by serving your own `testData.json` (e.g. at `App/wwwroot/testData.json`) with alternative org/personal numbers and roles.
- `a6d6148b4fd3` — core: shows the `testData.json` schema and a full example defining custom persons/orgs.

## Cited chunks

`9a53ae7fdb0b` `2f38ea1a18f7` `a79b2583b9f2` `a6d6148b4fd3`
