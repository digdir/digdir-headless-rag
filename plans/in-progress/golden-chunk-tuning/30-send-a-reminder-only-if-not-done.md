# Q30 v1 — how can I send a reminder only if the person hasn't done the task yet?

**Grounded source**: `381a15b18ef8` (Send condition, notifications). **Register**: lay — user says "reminder" and "hasn't done the task", avoiding corpus terms "send condition" / "condition endpoint".

## Goldens (read-confirmed)
- `789dee63f7b4` — core: explains the feature lets you order a notification that is only sent if a condition (e.g. user hasn't completed an action) is met, and that the initial message plus a reminder can be ordered together with different send dates.
- `0350c34c81b1` — supporting: describes the response (`sendNotification: true/false`) the system uses to decide whether the reminder actually goes out.

## Cited chunks

`789dee63f7b4` `0350c34c81b1`
