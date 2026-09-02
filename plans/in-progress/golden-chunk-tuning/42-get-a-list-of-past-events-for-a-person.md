# Q42 v1 — Can I fetch a list of past events that happened for a particular person instead of waiting for them?

**Grounded source**: `1c1c23ebf524` (Query app events as end user, events). **Register**: lay — "fetch a list of past events" and "instead of waiting" rather than corpus terms like "query app events", "polling", or "cloudevents+json".

## Goldens (read-confirmed)
- `157fd6461ec1` — core; the GET /app/party endpoint, its query parameters (party, person, time bounds, source, type), pagination via the next header, and a sample response with events.
- `d7270862768a` — core; explains this HTTP query API exists for scheduled requests of the same event data otherwise delivered via webhooks.
- `f665c42dcdb6` — supporting; the 400 error when the subject (party/unit/person) is not specified.

## Cited chunks

`157fd6461ec1` `d7270862768a` `f665c42dcdb6`
