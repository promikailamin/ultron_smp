#!/usr/bin/env bash
# End-to-end test for scripts/tunnel.sh using a local HTTP server.
# It checks that the public bore.pub endpoint really forwards TCP traffic.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
BACKUP_FILE="$ROOT_DIR/.agent-work/tmp/SERVER_ADDRESS.test-backup"
LOCAL_PORT="${TEST_PORT:-25566}"

mkdir -p "$LOG_DIR"
cp "$ADDRESS_FILE" "$BACKUP_FILE"

http_pid=""
tunnel_pid=""

cleanup() {
    if [ -n "$tunnel_pid" ]; then
        kill "$tunnel_pid" 2>/dev/null || true
    fi
    if [ -n "$http_pid" ]; then
        kill "$http_pid" 2>/dev/null || true
    fi
    cp "$BACKUP_FILE" "$ADDRESS_FILE" 2>/dev/null || true
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-http.log" 2>&1 &
http_pid=$!
sleep 1

# Use test-only remote ports so the test never steals the production address.
MINECRAFT_PORT="$LOCAL_PORT" \
    TUNNEL_PORT_CANDIDATES="${TUNNEL_PORT_CANDIDATES:-25601 25602 25603}" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee "$LOG_DIR/tunnel-test.log"

address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
host="${address%:*}"
port="${address##*:}"
tunnel_pid="$(cat "${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}/ultron-tunnel.pid" 2>/dev/null || true)"

python3 - "$host" "$port" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
with socket.create_connection((host, port), timeout=10) as sock:
    sock.sendall(b"GET / HTTP/1.0\r\nHost: test\r\n\r\n")
    response = sock.recv(200)

first_line = response.split(b"\r\n")[0].decode()
print(first_line)
if b"200" not in response:
    raise SystemExit(f"unexpected response: {response!r}")
print("Tunnel end-to-end test passed: public TCP endpoint is reachable")
PY
