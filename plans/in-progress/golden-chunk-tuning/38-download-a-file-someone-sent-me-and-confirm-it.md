# Q38 v1 — Someone sent me a file. How do I get it and let them know I received it?

**Grounded source**: `45e63e477d59` (Receive files, broker). **Register**: lay — everyday phrasing ("get it", "let them know I received it") rather than corpus terms like "download stream", "confirmdownload", or "recipientStatus".

## Goldens (read-confirmed)
- `23c0bdca3b2d` — core; the download endpoint that retrieves the actual file data.
- `9e71049d5c0e` — core; confirm-download operation used to notify the solution (and sender) that the file was successfully received.
- `9e088d7b4d38` — supporting; overview endpoint to see the file's metadata and status before downloading.
- `e8fd4d7e0709` — supporting; the published event that tells a recipient a file is ready to download, and the downloadconfirmed event confirming receipt.

## Cited chunks

`23c0bdca3b2d` `9e71049d5c0e` `9e088d7b4d38` `e8fd4d7e0709`
