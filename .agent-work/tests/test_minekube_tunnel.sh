#!/usr/bin/env bash
# End-to-end test for TUNNEL_PROVIDER=minekube using a fake Gate connector.
#
# The fake Gate binary parses the generated config, prints the same "connected"
# log line as Gate, and forwards TCP to the local server. This validates the
# Minekube provider, address file, config generation and process handling
# without registering a real endpoint on the public Connect network.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
BACKUP_FILE="$TMP_DIR/SERVER_ADDRESS.minekube-test-backup"
LOCAL_PORT="${TEST_PORT:-28101}"
GATE_PORT="${TEST_GATE_PORT:-28102}"
PID_FILE="$TMP_DIR/ultron-tunnel-minekube-test.pid"
DATA_DIR="$TMP_DIR/minekube-test"
CONFIG_FILE="$DATA_DIR/config.yml"
TOKEN_FILE="$DATA_DIR/connect.json"
STATE_FILE="$DATA_DIR/endpoint"
FAKE_GATE="$TMP_DIR/fake-gate"
ENDPOINT="ultron-test-abc"

mkdir -p "$LOG_DIR" "$TMP_DIR"
had_address=0
if [ -f "$ADDRESS_FILE" ]; then
    cp "$ADDRESS_FILE" "$BACKUP_FILE"
    had_address=1
fi
rm -rf "$DATA_DIR" "$PID_FILE" "$FAKE_GATE"

cat >"$FAKE_GATE" <<'PY'
#!/usr/bin/env python3
import os
import pathlib
import signal
import socket
import sys
import threading
import time

args = sys.argv[1:]
config_path = None
index = 0
while index < len(args):
    if args[index] in ("-c", "--config"):
        config_path = args[index + 1]
        index += 2
    else:
        index += 1

if not config_path:
    sys.exit("fake gate: missing -c/--config")

config = pathlib.Path(config_path).read_text(encoding="utf-8")
endpoint = ""
for line in config.splitlines():
    if line.strip().startswith("name:"):
        endpoint = line.split(":", 1)[1].strip().strip('"')
        break

bind = os.environ.get("FAKE_GATE_BIND", "127.0.0.1:25566")
target = os.environ.get("FAKE_GATE_TARGET", "127.0.0.1:25565")
bind_host, bind_port_text = bind.rsplit(":", 1)
bind_port = int(bind_port_text)
target_host, target_port_text = target.rsplit(":", 1)
target_port = int(target_port_text)

print(
    'INFO connect.watch.proposal config/setup_client.go:97 '
    f'connecting to watch service... {{"endpoint": "{endpoint}"}}',
    flush=True,
)

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind((bind_host, bind_port))
listener.listen(16)

print(
    'INFO connect.watch.proposal config/setup_client.go:110 '
    'connected {"took": "1ms"}',
    flush=True,
)


def pump(source, destination):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            destination.sendall(data)
    except OSError:
        pass
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client):
    try:
        upstream = socket.create_connection((target_host, target_port), timeout=10)
    except OSError:
        client.close()
        return

    threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
    pump(upstream, client)
    client.close()
    upstream.close()


def stop(_signum, _frame):
    listener.close()
    sys.exit(0)


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)

while True:
    try:
        client, _addr = listener.accept()
    except OSError:
        break
    threading.Thread(target=handle, args=(client,), daemon=True).start()
PY
chmod +x "$FAKE_GATE"

http_pid=""

cleanup() {
    if [ -f "$PID_FILE" ]; then
        pid="$(cat "$PID_FILE" 2>/dev/null || true)"
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    fi
    if [ -n "$http_pid" ]; then
        kill "$http_pid" 2>/dev/null || true
        wait "$http_pid" 2>/dev/null || true
    fi
    if [ "$had_address" -eq 1 ]; then
        cp "$BACKUP_FILE" "$ADDRESS_FILE" 2>/dev/null || true
    else
        rm -f "$ADDRESS_FILE"
    fi
    rm -rf "$DATA_DIR" "$PID_FILE" "$FAKE_GATE" "$BACKUP_FILE"
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-minekube-http.log" 2>&1 &
http_pid=$!
sleep 1

run_case() {
    local provider="$1"

    TUNNEL_PROVIDER="$provider" \
        MINECRAFT_PORT="$LOCAL_PORT" \
        MINEKUBE_ENDPOINT="$ENDPOINT" \
        MINEKUBE_BIND_PORT="$GATE_PORT" \
        MINEKUBE_DATA_DIR="$DATA_DIR" \
        MINEKUBE_CONFIG_FILE="$CONFIG_FILE" \
        MINEKUBE_TOKEN_FILE="$TOKEN_FILE" \
        MINEKUBE_STATE_FILE="$STATE_FILE" \
        MINEKUBE_READY_TIMEOUT="10" \
        GATE_BIN="$FAKE_GATE" \
        FAKE_GATE_BIND="127.0.0.1:${GATE_PORT}" \
        FAKE_GATE_TARGET="127.0.0.1:${LOCAL_PORT}" \
        TUNNEL_PID_FILE="$PID_FILE" \
        bash "$ROOT_DIR/scripts/tunnel.sh" \
        2>&1 | tee "$LOG_DIR/minekube-${provider}-test.log"

    local address expected
    address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
    expected="${ENDPOINT}.play.minekube.net:25565"
    if [ "$address" != "$expected" ]; then
        echo "ERROR: expected address '${expected}', got '${address}'" >&2
        exit 1
    fi

    if [ "$(cat "$STATE_FILE" 2>/dev/null)" != "$ENDPOINT" ]; then
        echo "ERROR: endpoint state was not saved." >&2
        exit 1
    fi

    grep -q 'allowOfflineModePlayers: true' "$CONFIG_FILE"
    grep -q 'mode: none' "$CONFIG_FILE"
    grep -q "127.0.0.1:${LOCAL_PORT}" "$CONFIG_FILE"

    python3 - "$GATE_PORT" <<'PY'
import socket
import sys

port = int(sys.argv[1])
with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
    sock.sendall(b"GET / HTTP/1.0\r\nHost: test\r\n\r\n")
    response = sock.recv(200)

if b"200" not in response:
    raise SystemExit(f"unexpected response: {response!r}")
PY

    echo "Minekube ${provider} test passed: ${address}"
}

run_case minekube
run_case auto
