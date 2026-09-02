# Q4 v1 — How can my system automatically find out when something happens to one of my Altinn forms, instead of checking all the time?

**Grounded source**: `37e9eba5fd6b` (Subscribe to events, events).
**Topic**: Altinn Events (product_events) · **Diataxis**: (overview)
**Register**: lay / mismatch (avoids "events", "subscribe", "webhook", "polling").

## Goldens (read-confirmed)
- `fb897d22bdf4` — ch0: event-driven solution; recipients register an
  endpoint/webhook and receive data asynchronously; recommended over polling.
  Core answer ("instead of checking all the time").
- `f183fac18900` — ch1: how to make a subscription request (endpoint + source/
  subject filters). Supporting (the how-to).

## Cited chunks

`fb897d22bdf4` `f183fac18900`
