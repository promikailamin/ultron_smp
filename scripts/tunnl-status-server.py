#!/usr/bin/env python3
"""Tiny read-only status page exposed by the tunnl.gg HTTP tunnel.

tunnl.gg only carries HTTP and WebSocket traffic, so it cannot be the
Minecraft TCP endpoint itself. This page gives players the current TCP
address and is reachable from a browser through the tunnl.gg URL.
"""

import html
import json
import os
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(os.environ.get("TUNNL_ROOT_DIR", os.getcwd()))
BIND = os.environ.get("TUNNL_STATUS_BIND", "127.0.0.1")
PORT = int(os.environ.get("TUNNL_STATUS_PORT", "8080"))


def read_address(name):
    path = ROOT / name
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return ""
    for line in lines:
        line = line.strip()
        if line and not line.startswith("#"):
            return line
    return ""


def current_addresses():
    stable = read_address("SERVER_ADDRESS.txt")
    low_latency = read_address("SERVER_ADDRESS_LOW_LATENCY.txt")
    return {
        "minecraft": stable,
        "low_latency": low_latency,
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "UltronStatus/1.0"

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/healthz", "/health"):
            self.respond(200, "text/plain; charset=utf-8", "ok\n")
            return
        if path == "/status.json":
            body = json.dumps(current_addresses(), indent=2) + "\n"
            self.respond(200, "application/json; charset=utf-8", body)
            return
        self.respond(200, "text/html; charset=utf-8", self.page())

    def page(self):
        data = current_addresses()
        safe_address = html.escape(data["minecraft"] or "starting...")
        low_latency = html.escape(data["low_latency"] or "")
        low_latency_block = ""
        if low_latency and low_latency != data["minecraft"]:
            low_latency_block = (
                "<p>South-Asia low-latency relay: "
                f"<code>{low_latency}</code></p>"
            )
        return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ultron SMP server status</title>
<style>
body {{ font-family: system-ui, sans-serif; margin: 2rem auto; max-width: 42rem;
       padding: 0 1rem; line-height: 1.5; }}
code {{ background: #eee; padding: .15rem .35rem; border-radius: .25rem; }}
</style>
</head>
<body>
<h1>Ultron SMP</h1>
<p>Minecraft Java Edition address:</p>
<p><code>{safe_address}</code></p>
{low_latency_block}
<p>Add that host and port in Minecraft under <em>Multiplayer &gt; Add Server</em>.</p>
<p>This page is served through a tunnl.gg HTTP tunnel. tunnl.gg does not
carry raw TCP, so the game itself uses the TCP relay address above.</p>
<p><small>Updated {html.escape(data['updated'])}</small></p>
</body>
</html>
"""

    def respond(self, status, content_type, body):
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        return


def main():
    server = ThreadingHTTPServer((BIND, PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
