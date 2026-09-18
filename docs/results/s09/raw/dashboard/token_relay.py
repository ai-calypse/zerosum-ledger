# Serves the reader/admin tokens from .env, held in memory only, to a same-machine page on 127.0.0.1:8080, so the
# headless browser can type them into the Explorer without the token ever appearing in a command line or a log file.
# Exits after 10 minutes.
import http.server, sys, threading, os

env = {}
with open(sys.argv[1]) as f:
    for line in f:
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
tokens = {"/reader": env["ZS_READER_TOKEN"], "/admin": env["ZS_ADMIN_TOKEN"]}

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = tokens.get(self.path)
        self.send_response(200 if body else 404)
        self.send_header("Access-Control-Allow-Origin", "http://127.0.0.1:8080")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body.encode())
    def log_message(self, *a):
        pass

server = http.server.HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
threading.Timer(600, lambda: os._exit(0)).start()
server.serve_forever()
