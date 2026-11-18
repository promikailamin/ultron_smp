#!/usr/bin/env bash
# End-to-end test for the tunnl.gg status tunnel.
#
# A fake `ssh` client forwards a local TCP port and prints the JSON tunnel
# event that tunnl.gg emits, so the test can validate URL parsing, the local
# status page and process handling without depending on proxy.tunnl.gg.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
PID_FILE="$TMP_DIR/ultron-tunnl-tunnel-test.pid"
STATUS_URL_FILE="$TMP_DIR/SERVER_STATUS_URL.tunnl-test"
KNOWN_HOSTS="$TMP_DIR/tunnl_known_hosts_test"
SSH_KEY="$TMP_DIR/tunnl_test_ssh_key"
FAKE_SSH="$TMP_DIR/fake-tunnl-ssh"
STATUS_PORT="${TEST_TUNNL_STATUS_PORT:-28090}"

mkdir -p "$LOG_DIR" "$TMP_DIR"
rm -f "$PID_FILE" "$STATUS_URL_FILE" "$KNOWN_HOSTS" "$SSH_KEY" "$SSH_KEY.pub" "$FAKE_SSH"

cat >"$FAKE_SSH" <<'PY'
#!/usr/bin/env python3
import json
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
    elif arg in ("-o", "-p", "-i", "-l", "-F"):
        index += 2
    elif arg.startswith("-"):
        index += 1
    else:
        index += 1

if not forward or ":" not in forward:
    sys.exit("fake tunnl ssh: missing -R forward")

_remote_port, _, target = forward.partition(":")
if ":" in target:
    host, port_text = target.rsplit(":", 1)
else:
    host, port_text = "127.0.0.1", target
target_port = int(port_text)

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 0))
listener.listen(16)
listen_port = listener.getsockname()[1]
print(json.dumps({
    "event": "tunnel",
    "url": f"http://127.0.0.1:{listen_port}",
    "stable": False,
}), flush=True)


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

supervisor_pid=""

cleanup() {
    if [ -n "$supervisor_pid" ]; then
        kill "$supervisor_pid" 2>/dev/null || true
        wait "$supervisor_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE" "$STATUS_URL_FILE" "$KNOWN_HOSTS" "$SSH_KEY" "$SSH_KEY.pub" "$FAKE_SSH"
}
trap cleanup EXIT

TUNNL_SERVER="127.0.0.1" \
    TUNNL_USER="test" \
    TUNNL_SSH_BIN="$FAKE_SSH" \
    TUNNL_SSH_KEY="$SSH_KEY" \
    TUNNL_KNOWN_HOSTS="$KNOWN_HOSTS" \
    TUNNL_STATUS_PORT="$STATUS_PORT" \
    TUNNL_STATUS_URL_FILE="$STATUS_URL_FILE" \
    TUNNL_PID_FILE="$PID_FILE" \
    TUNNL_ALLOW_LOCAL_ADDRESS="true" \
    bash "$ROOT_DIR/scripts/tunnl-tunnel.sh" \
    2>&1 | tee "$LOG_DIR/tunnl-tunnel-test.log"

supervisor_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
url="$(grep -v '^#' "$STATUS_URL_FILE" | tail -n 1)"
host="${url#http://}"
host="${host%%:*}"
port="${url##*:}"

python3 - "$host" "$port" <<'PY'
import json
import sys
import urllib.request

host = sys.argv[1]
port = sys.argv[2]
base = f"http://{host}:{port}"

with urllib.request.urlopen(base + "/healthz", timeout=10) as response:
    body = response.read().decode()
    if response.status != 200 or "ok" not in body:
        raise SystemExit(f"unexpected health response: {response.status} {body!r}")

with urllib.request.urlopen(base + "/status.json", timeout=10) as response:
    data = json.load(response)
    if "minecraft" not in data:
        raise SystemExit(f"unexpected status payload: {data!r}")

print("tunnl.gg tunnel test passed: status page is reachable")
PY
