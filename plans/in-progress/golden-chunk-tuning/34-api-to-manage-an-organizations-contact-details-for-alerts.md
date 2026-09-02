# Q34 v1 — is there an API to register and update an organisation's email and phone for new-message alerts, and why might the address ID change?

**Grounded source**: `b8ceea927ba0` (Organization notification addresses, profile reference). **Register**: developer — asks about CRUD endpoints, the data model, and the ID-change gotcha.

## Goldens (read-confirmed)
- `206485ad6329` — core: organizations must register at least one notification address (mobile or email), kept in sync with Brønnøysund; endpoints exist to add/read/update/delete and require a logged-in user with userId and a valid role, with the org number in the path.
- `cc0ab8416fc3` — supporting: shows the model (organizationNumber, notificationAddressId, email, phone, countryCode) and warns that updating an address may return a new (or reused old) notificationAddressId, so always check the response.

## Cited chunks

`206485ad6329` `cc0ab8416fc3`
