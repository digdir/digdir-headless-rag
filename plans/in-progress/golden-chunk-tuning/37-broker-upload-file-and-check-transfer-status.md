# Q37 v1 — How do I upload a file and then check whether it went through?

**Grounded source**: `375dd431865e` (Send files, broker). **Register**: developer — uses plain "upload a file" / "check whether it went through" instead of corpus terms like "initialize filetransfer", "FileTransferOverviewExt", or "uploadprocessing".

## Goldens (read-confirmed)
- `593b1001cb56` — core; the streaming Upload endpoint, describing how upload completion leads to the file becoming available for download.
- `4a6828d24054` — core; GET overview endpoint to see current status and recipient status (the "did it go through" check).
- `050f67e402d9` — supporting; the combined initialize-and-upload endpoint as an alternative upload path.
- `8992cbc6bb8c` — supporting; the details endpoint for detailed transfer/recipient statuses when troubleshooting.

## Cited chunks

`593b1001cb56` `4a6828d24054` `050f67e402d9` `8992cbc6bb8c`
