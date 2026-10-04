"""An OpenAI-compatible stub for the ingest e2e. NOT a model.

It exists so ingestion's search-phrase generation can run end to end in CI with
no cloud credential, and so the harness can read back WHAT the server sent:
which path, which model, which structured-output mode, and which bearer key
(as a sha256 prefix, never the key). That record is the measurement.

The phrases are deliberately trivial: one `stub phrase <digest>` per request,
derived from nothing but a hash of the prompt. They carry NO document signal,
so retrieval ranking in the harness rests on the chunk text alone. (An earlier
version claimed phrases built from each chunk's title pointed retrieval at the
right document. The title never reaches the model - the chunk text arrives
without its markdown heading - so that claim was false.)

  POST */chat/completions   answer deterministically and log the request
  GET  /_log                every request logged so far, as a JSON array
  GET  /health              200, for the compose healthcheck

Standard library only, so it runs on a stock python image with nothing
installed.
"""

import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = []
LOG_LOCK = threading.Lock()


def phrases_for(content):
    """One phrase, from a hash of the prompt. Byte-identical to what the harness's
    retrieval rankings were measured under."""
    digest = hashlib.sha256((content or "").encode("utf-8")).hexdigest()[:8]
    return [f"stub phrase {digest}"]


def sha8(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:8]


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
        elif self.path == "/_log":
            with LOG_LOCK:
                self._send(200, list(LOG))
        else:
            self._send(404, {"error": f"no route {self.path}"})

    def do_POST(self):
        if not self.path.endswith("/chat/completions"):
            self._send(404, {"error": f"no route {self.path}"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        request = json.loads(self.rfile.read(length) or b"{}")
        messages = request.get("messages") or []
        content = "\n".join(str(m.get("content") or "") for m in messages)
        auth = self.headers.get("Authorization") or ""
        entry = {
            "path": self.path,
            "model": request.get("model"),
            "response_format_type": (request.get("response_format") or {}).get("type"),
            "bearer": auth.startswith("Bearer ") and len(auth) > len("Bearer "),
            # WHICH key, as a sha256 prefix - the harness compares it with the
            # configured key's, so presence alone cannot pass.
            "bearer_sha8": sha8(auth[len("Bearer "):]) if auth.startswith("Bearer ") else None,
            "messages": len(messages),
            # The whole prompt, so the harness can see WHICH chunk each request
            # was about. The chunk text reaches the model without its markdown
            # heading, so a title cannot identify it - the first run of this
            # harness learned that. Fixture text only; nothing secret.
            "content": content,
            "tools": bool(request.get("tools")),
        }
        with LOG_LOCK:
            LOG.append(entry)
        answer = json.dumps({"phrases": phrases_for(content)})
        self._send(200, {
            "id": f"stub-{len(LOG)}",
            "object": "chat.completion",
            "model": request.get("model"),
            "choices": [{"index": 0,
                         "message": {"role": "assistant", "content": answer},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        })

    def log_message(self, fmt, *args):
        # One line per request on stdout, so `docker logs` shows the traffic.
        print(f"stub: {self.command} {self.path}", flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
