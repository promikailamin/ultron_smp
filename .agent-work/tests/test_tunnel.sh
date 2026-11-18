#!/usr/bin/env bash
# End-to-end test for scripts/tunnel.sh using a fake loclx client.
#
# The fake client forwards a local TCP port and prints an address in the same
# way LocalXpose does. This validates address parsing, process handling and
# forwarding without requiring a real LocalXpose PRO account.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
BACKUP_FILE="$TMP_DIR/SERVER_ADDRESS.test-backup"
LOCAL_PORT="${TEST_PORT:-25566}"
PID_FILE="$TMP_DIR/ultron-tunnel-test.pid"
FAKE_LOCLX="$TMP_DIR/fake-loclx"

mkdir -p "$LOG_DIR" "$TMP_DIR"
cp "$ADDRESS_FILE" "$BACKUP_FILE"

cat >"$FAKE_LOCLX" <<'PY'
#!/usr/bin/env python3
import signal
import socket
import sys
import threading

args = sys.argv[1:]
target = None
requested_port = None
index = 0
while index < len(args):
    arg = args[index]
    if arg in ("--to", "-t"):
        target = args[index + 1]
        index += 2
    elif arg in ("--port", "-p"):
        requested_port = int(args[index + 1])
        index += 2
    elif arg in ("--region", "-g"):
        index += 2
    else:
        index += 1

if not target:
    sys.exit("fake loclx: missing --to")

host, port_text = target.rsplit(":", 1)
target_port = int(port_text)

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    listener.bind(("127.0.0.1", requested_port or 0))
except OSError as exc:
    sys.exit(f"fake loclx: bind failed: {exc}")
listener.listen(16)
listen_port = listener.getsockname()[1]
print(f"Tunneling TCP: 127.0.0.1:{listen_port} --> {host}:{target_port}", flush=True)


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
        upstream = socket.create_connection((host, target_port), timeout=10)
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
chmod +x "$FAKE_LOCLX"

http_pid=""
tunnel_pid=""

cleanup() {
    if [ -n "$tunnel_pid" ]; then
        kill "$tunnel_pid" 2>/dev/null || true
        wait "$tunnel_pid" 2>/dev/null || true
    fi
    if [ -n "$http_pid" ]; then
        kill "$http_pid" 2>/dev/null || true
    fi
    cp "$BACKUP_FILE" "$ADDRESS_FILE" 2>/dev/null || true
    rm -f "$PID_FILE" "$FAKE_LOCLX"
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-http.log" 2>&1 &
http_pid=$!
sleep 1

MINECRAFT_PORT="$LOCAL_PORT" \
    LOCALXPOSE_ACCESS_TOKEN="test-token" \
    LOCALXPOSE_BORE_FALLBACK="false" \
    LOCALXPOSE_PORT_CANDIDATES="" \
    LOCLX_BIN="$FAKE_LOCLX" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee "$LOG_DIR/tunnel-test.log"

address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
host="${address%:*}"
port="${address##*:}"
tunnel_pid="$(cat "$PID_FILE" 2>/dev/null || true)"

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
