# Q31 v1 — will the files attached to a letter be deleted after some time, and does that remove the letter too?

**Grounded source**: `88aa2c5f8863` (Attachment expiry, correspondence). **Register**: lay — user says "files attached to a letter" and "deleted after some time" instead of "attachment expiry date".

## Goldens (read-confirmed)
- `9a4ca32cec33` — core: states attachments can have an expiry date, get deleted and become unavailable for download when reached, but the correspondence itself stays visible to the recipient.
- `6b9dd5097d9f` — supporting: details the lifecycle when expiry is reached (marked expired, file deleted, no download link) and confirms the correspondence continues its normal lifecycle regardless.

## Cited chunks

`9a4ca32cec33` `6b9dd5097d9f`
