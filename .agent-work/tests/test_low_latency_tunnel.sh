#!/usr/bin/env bash
# End-to-end test for scripts/low-latency-tunnel.sh using a local HTTP server.
# It starts the Pinggy relay, confirms the public endpoint forwards TCP, and
# then stops the supervisor.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS_LOW_LATENCY.txt"
BACKUP_FILE="$ROOT_DIR/.agent-work/tmp/SERVER_ADDRESS_LOW_LATENCY.test-backup"
LOCAL_PORT="${TEST_PORT:-25567}"
PID_FILE="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}/ultron-low-latency-tunnel.pid"

mkdir -p "$LOG_DIR"
if [ -f "$ADDRESS_FILE" ]; then
    cp "$ADDRESS_FILE" "$BACKUP_FILE"
else
    rm -f "$BACKUP_FILE"
fi

http_pid=""
supervisor_pid=""

cleanup() {
    if [ -n "$supervisor_pid" ]; then
        kill "$supervisor_pid" 2>/dev/null || true
        wait "$supervisor_pid" 2>/dev/null || true
    fi
    if [ -n "$http_pid" ]; then
        kill "$http_pid" 2>/dev/null || true
    fi
    if [ -f "$BACKUP_FILE" ]; then
        cp "$BACKUP_FILE" "$ADDRESS_FILE"
    else
        rm -f "$ADDRESS_FILE"
    fi
    rm -f "$PID_FILE"
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-latency-http.log" 2>&1 &
http_pid=$!
sleep 1

PINGGY_RECONNECT_SECONDS="${PINGGY_RECONNECT_SECONDS:-180}" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    bash "$ROOT_DIR/scripts/low-latency-tunnel.sh" \
    2>&1 | tee "$LOG_DIR/low-latency-tunnel-test.log"

supervisor_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
host="${address%:*}"
port="${address##*:}"

python3 - "$host" "$port" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
with socket.create_connection((host, port), timeout=15) as sock:
    sock.sendall(b"GET / HTTP/1.0\r\nHost: test\r\n\r\n")
    response = sock.recv(200)

first_line = response.split(b"\r\n")[0].decode()
print(first_line)
if b"200" not in response:
    raise SystemExit(f"unexpected response: {response!r}")
print("Low-latency tunnel test passed: public TCP endpoint is reachable")
PY
