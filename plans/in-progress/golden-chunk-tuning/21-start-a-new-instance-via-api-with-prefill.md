# Q21 v1 — From an external system, how do I start a new instance for a given person and pass in some initial field values in the request?

**Grounded source**: `69ccc279de8b` (Instances, altinn-studio app API). **Register**: developer — talks about external system, request payload, instance owner identity, prefilled values.

## Goldens (read-confirmed)
- `6bbfb3935810` — core: POST to create an instance, instanceOwner identity (personNumber/organisationNumber), multipart/form-data with data elements, plus the simplified `/create` endpoint.
- `c1564b5e700e` — core: the simplified instantiation body with a `prefill` key-value dictionary keyed by dataModelBindings paths.

## Cited chunks

`6bbfb3935810` `c1564b5e700e`
