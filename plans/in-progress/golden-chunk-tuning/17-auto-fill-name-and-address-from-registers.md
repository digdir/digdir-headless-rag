# Q17 v1 — Can my form automatically fill in the person's name and address so they don't have to type it themselves, and what details can it pull in?

**Grounded source**: `2d2262b56bec` (Prefill, altinn-studio). **Register**: lay — end-user-builder framing ("auto fill name and address") instead of "prefill from DSF/ER".

## Goldens (read-confirmed)
- `ac3a1bc9aec5` — core: explains the prefill sources (ER = business register, DSF = population register, UserProfile, QueryParameters) and that they fill fields from the data model.
- `0eb04db689d9` — supporting: the list of person/population-register values available (Name, FirstName, address fields, SSN, phone, etc.).
- `a590caa6182f` — supporting: the list of organization-register values available (OrgNumber, Name, addresses, etc.).

## Cited chunks

`ac3a1bc9aec5` `0eb04db689d9` `a590caa6182f`
