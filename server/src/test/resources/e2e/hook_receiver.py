# Test webhook receiver. Keeps every POST it gets and lists them on GET, as JSON.
# Runs in a python container on the installation's Docker network.
import http.server
import json
import threading

requests = []
lock = threading.Lock()


def read_body(handler):
    if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
        body = b""
        while True:
            size = int(handler.rfile.readline().strip().split(b";")[0], 16)
            if size == 0:
                handler.rfile.readline()
                return body
            body += handler.rfile.read(size)
            handler.rfile.readline()
    return handler.rfile.read(int(handler.headers.get("Content-Length") or 0))


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = read_body(self).decode("utf-8", "replace")
        with lock:
            requests.append({"path": self.path, "contentType": self.headers.get("Content-Type"), "body": body})
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        with lock:
            data = json.dumps(requests).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("", 8000), Handler).serve_forever()
