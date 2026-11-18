#!/usr/bin/env bash
# Validate the runner/Twingate network diagnostic without external network
# access. A local HTTP server provides deterministic download/upload endpoints.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
TEST_DIR="$TMP_DIR/network-diagnostic-test"
LOG_FILE="$LOG_DIR/test-network-diagnostic.log"
JSON_FILE="$TEST_DIR/report.json"
SERVER_PORT_FILE="$TEST_DIR/server-port"
SERVER_LOG="$TEST_DIR/server.log"
SECRET_LOG="$TEST_DIR/secret-check.log"
SERVER_PID=""

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

mkdir -p "$LOG_DIR" "$TMP_DIR"
rm -rf "$TEST_DIR"
mkdir -p "$TEST_DIR"

python3 -m py_compile "$ROOT_DIR/scripts/network-diagnostic.py"
python3 "$ROOT_DIR/scripts/network-diagnostic.py" --help >/dev/null

cat >"$TEST_DIR/http-server.py" <<'PY'
import http.server
import sys
import urllib.parse


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path not in {"/down", "/small"}:
            self.send_error(404)
            return
        values = urllib.parse.parse_qs(parsed.query)
        try:
            size = int(values.get("bytes", ["100000"])[0])
        except ValueError:
            size = 100000
        size = max(1, min(size, 50_000_000))
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        chunk = b"x" * min(65536, size)
        remaining = size
        while remaining > 0:
            payload = chunk[:remaining]
            self.wfile.write(payload)
            remaining -= len(payload)

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        remaining = length
        while remaining > 0:
            chunk = self.rfile.read(min(65536, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt, *args):
        print(fmt % args, file=sys.stderr)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    handle.write(str(server.server_port))
server.serve_forever()
PY

python3 "$TEST_DIR/http-server.py" "$SERVER_PORT_FILE" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 50); do
    if [ -s "$SERVER_PORT_FILE" ]; then
        break
    fi
    sleep 0.1
done

if [ ! -s "$SERVER_PORT_FILE" ]; then
    echo "ERROR: local diagnostic HTTP server did not start." >&2
    cat "$SERVER_LOG" >&2 || true
    exit 1
fi

PORT="$(cat "$SERVER_PORT_FILE")"
BASE_URL="http://127.0.0.1:${PORT}"

DIAG_JSON_OUTPUT="$JSON_FILE" python3 "$ROOT_DIR/scripts/network-diagnostic.py" \
    --label local-test \
    --download-url "$BASE_URL/down?bytes={bytes}" \
    --upload-url "$BASE_URL/up" \
    --download-bytes 100000 \
    --upload-bytes 50000 \
    --bandwidth-rounds 2 \
    --latency-hosts "127.0.0.1:${PORT}" \
    --latency-probes 3 \
    --latency-interval 0 \
    --soak-seconds 4 \
    --soak-interval 1 \
    --soak-bytes 20000 \
    --soak-host 127.0.0.1 \
    --soak-port "$PORT" \
    --twingate-monitor-seconds 0 \
    --skip-public-ip \
    | tee "$LOG_FILE"

for expected in \
    "Download: avg" \
    "Upload: avg" \
    "TCP connect latency" \
    "127.0.0.1:${PORT}" \
    "Soak (4s" \
    "Twingate connector monitor: skipped" \
    "Verdict: bandwidth="; do
    if ! grep -qF "$expected" "$LOG_FILE"; then
        echo "ERROR: diagnostic output is missing: $expected" >&2
        exit 1
    fi
done

python3 - "$JSON_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    report = json.load(handle)

assert report["label"] == "local-test"
assert report["bandwidth"]["download"]["rounds_ok"] == 2
assert report["bandwidth"]["upload"]["rounds_ok"] == 2
assert report["bandwidth"]["download"]["mbps_avg"] > 0
assert report["bandwidth"]["upload"]["mbps_avg"] > 0
assert len(report["latency"]) == 1
assert report["latency"][0]["host"] == "127.0.0.1"
assert report["latency"][0]["successful"] == 3
assert report["latency"][0]["loss_percent"] == 0
assert report["soak"]["enabled"] is True
assert report["soak"]["samples"] >= 2
assert report["soak"]["failed_samples"] == 0
assert report["twingate_monitor"]["enabled"] is False
PY

SECRET="test-secret-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
TWINGATE_ACCESS_TOKEN="$SECRET" \
    TWINGATE_NETWORK="test" \
    python3 "$ROOT_DIR/scripts/network-diagnostic.py" \
    --label secret-test \
    --skip-bandwidth \
    --skip-latency \
    --skip-soak \
    --skip-public-ip \
    --json-output "$TEST_DIR/secret-report.json" \
    >"$SECRET_LOG" 2>&1

if grep -qF "$SECRET" "$SECRET_LOG" "$TEST_DIR/secret-report.json"; then
    echo "ERROR: diagnostic output leaked the Twingate token." >&2
    exit 1
fi
grep -q "Verdict:" "$SECRET_LOG"

echo "Network diagnostic test passed: local bandwidth, latency, soak, JSON and token secrecy validated." | tee -a "$LOG_FILE"
