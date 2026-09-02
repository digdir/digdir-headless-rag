# Q41 v1 — How do I POST an app event to Altinn and what status codes come back?

**Grounded source**: `90f11c1fe0b5` (Publish app events, events). **Register**: developer — concrete "POST" and "status codes" framing; avoids corpus phrasing like "AppCloudEventRequestModel", "Platform Access Token", or "alternativesubject".

## Goldens (read-confirmed)
- `06684b790585` — core; the curl example showing the actual POST request body and the response containing the cloud event ID.
- `8714c09bef05` — core; the response codes (201 Created, 400, 401, 403) and what each means.
- `bdf5fc355e77` — supporting; the POST /app endpoint, required auth header, and the request body fields (type, source, subject, specversion).
- `e302cb8c6c8d` — supporting; the 400 problem-details body for missing required fields.

## Cited chunks

`06684b790585` `8714c09bef05` `bdf5fc355e77` `e302cb8c6c8d`
