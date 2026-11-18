#!/usr/bin/env bash
# End-to-end test for the Serveo tunnel and the TUNNEL_PROVIDER dispatcher.
#
# A fake `ssh` client forwards a local TCP port and prints the forwarding line
# in the same format as Serveo, so the test can validate address parsing,
# process handling and forwarding without depending on serveo.net.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
BACKUP_FILE="$TMP_DIR/SERVER_ADDRESS.serveo-test-backup"
LOCAL_PORT="${TEST_PORT:-25568}"
PID_FILE="$TMP_DIR/ultron-tunnel-serveo-test.pid"
KNOWN_HOSTS="$TMP_DIR/serveo_known_hosts_test"
FAKE_SSH="$TMP_DIR/fake-serveo-ssh"

mkdir -p "$LOG_DIR" "$TMP_DIR"
cp "$ADDRESS_FILE" "$BACKUP_FILE"

cat >"$FAKE_SSH" <<'PY'
#!/usr/bin/env python3
import os
import signal
import socket
import sys
import threading

args = sys.argv[1:]
forward = None
index = 0
while index < len(args):
    arg = args[index]
    if arg == "-R":
        forward = args[index + 1]
        index += 2
    elif arg in ("-o", "-p", "-l", "-i", "-F"):
        index += 2
    elif arg.startswith("-"):
        index += 1
    else:
        index += 1

if not forward or ":" not in forward:
    sys.exit("fake serveo ssh: missing -R forward")

remote_port, _, target = forward.partition(":")
if ":" in target:
    host, port_text = target.rsplit(":", 1)
else:
    host, port_text = "127.0.0.1", target
target_port = int(port_text)

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    listener.bind(("127.0.0.1", int(remote_port) if remote_port != "0" else 0))
except OSError as exc:
    sys.exit(f"fake serveo ssh: bind failed: {exc}")
listener.listen(16)
listen_port = listener.getsockname()[1]
remote_host = os.environ.get("FAKE_SERVEO_HOST", "serveo.net")
print(f"Forwarding TCP connections from {remote_host}:{listen_port}", flush=True)


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
chmod +x "$FAKE_SSH"

http_pid=""
supervisor_pid=""

cleanup() {
    if [ -n "$supervisor_pid" ]; then
        kill "$supervisor_pid" 2>/dev/null || true
        wait "$supervisor_pid" 2>/dev/null || true
    fi
    if [ -n "$http_pid" ]; then
        kill "$http_pid" 2>/dev/null || true
        wait "$http_pid" 2>/dev/null || true
    fi
    cp "$BACKUP_FILE" "$ADDRESS_FILE" 2>/dev/null || true
    rm -f "$PID_FILE" "$FAKE_SSH" "$KNOWN_HOSTS"
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-serveo-http.log" 2>&1 &
http_pid=$!
sleep 1

TUNNEL_PROVIDER="serveo" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    SERVEO_SERVER="127.0.0.1" \
    SERVEO_SSH_BIN="$FAKE_SSH" \
    SERVEO_KNOWN_HOSTS="$KNOWN_HOSTS" \
    SERVEO_RECONNECT_SECONDS="0" \
    FAKE_SERVEO_HOST="127.0.0.1" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee "$LOG_DIR/serveo-tunnel-test.log"

supervisor_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
host="${address%:*}"
port="${address##*:}"

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
print("Serveo tunnel test passed: public TCP endpoint is reachable")
PY
