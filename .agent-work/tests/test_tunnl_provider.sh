#!/usr/bin/env bash
# End-to-end test for TUNNEL_PROVIDER=tunnl.
#
# The provider must expose Minecraft through a TCP relay (Pinggy-style) and
# additionally publish the tunnl.gg status page. A fake ssh client handles
# both protocols so the test does not depend on the public relays.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
ADDRESS_BACKUP="$TMP_DIR/SERVER_ADDRESS.tunnl-provider-backup"
STATUS_URL_FILE="$TMP_DIR/SERVER_STATUS_URL.tunnl-provider-test"
GAME_PID_FILE="$TMP_DIR/ultron-tunnel-tunnl-provider.pid"
STATUS_PID_FILE="$TMP_DIR/ultron-tunnl-provider.pid"
PINGGY_KNOWN_HOSTS="$TMP_DIR/pinggy_known_hosts_provider_test"
TUNNL_KNOWN_HOSTS="$TMP_DIR/tunnl_known_hosts_provider_test"
SSH_KEY="$TMP_DIR/tunnl_provider_test_ssh_key"
FAKE_SSH="$TMP_DIR/fake-dual-ssh"
LOCAL_PORT="${TEST_PORT:-28091}"
STATUS_PORT="${TEST_TUNNL_PROVIDER_STATUS_PORT:-28092}"

mkdir -p "$LOG_DIR" "$TMP_DIR"
had_address=0
if [ -f "$ADDRESS_FILE" ]; then
    cp "$ADDRESS_FILE" "$ADDRESS_BACKUP"
    had_address=1
fi
rm -f "$STATUS_URL_FILE" "$GAME_PID_FILE" "$STATUS_PID_FILE" \
    "$PINGGY_KNOWN_HOSTS" "$TUNNL_KNOWN_HOSTS" "$SSH_KEY" "$SSH_KEY.pub"

cat >"$FAKE_SSH" <<'PY'
#!/usr/bin/env python3
import json
import signal
import socket
import sys
import threading

args = sys.argv[1:]
forward = None
tunnl_mode = "output=json" in args
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
    sys.exit("fake dual ssh: missing -R forward")

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
if tunnl_mode:
    print(json.dumps({
        "event": "tunnel",
        "url": f"http://127.0.0.1:{listen_port}",
        "stable": False,
    }), flush=True)
else:
    print(f"tcp://127.0.0.1:{listen_port}", flush=True)


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

cleanup() {
    for file in "$GAME_PID_FILE" "$STATUS_PID_FILE"; do
        if [ -f "$file" ]; then
            pid="$(cat "$file" 2>/dev/null || true)"
            if [ -n "$pid" ]; then
                kill "$pid" 2>/dev/null || true
                wait "$pid" 2>/dev/null || true
            fi
        fi
    done
    if [ -n "$http_pid" ]; then
        kill "$http_pid" 2>/dev/null || true
        wait "$http_pid" 2>/dev/null || true
    fi
    if [ "$had_address" -eq 1 ]; then
        cp "$ADDRESS_BACKUP" "$ADDRESS_FILE"
    else
        rm -f "$ADDRESS_FILE"
    fi
    rm -f "$GAME_PID_FILE" "$STATUS_PID_FILE" "$STATUS_URL_FILE" \
        "$PINGGY_KNOWN_HOSTS" "$TUNNL_KNOWN_HOSTS" "$SSH_KEY" "$SSH_KEY.pub" \
        "$FAKE_SSH"
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-tunnl-provider-http.log" 2>&1 &
http_pid=$!
sleep 1

TUNNEL_PROVIDER="tunnl" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    PLAYER_REGION="bangladesh" \
    PINGGY_SERVER="127.0.0.1" \
    PINGGY_SSH_BIN="$FAKE_SSH" \
    PINGGY_KNOWN_HOSTS="$PINGGY_KNOWN_HOSTS" \
    PINGGY_RECONNECT_SECONDS="0" \
    TUNNL_SERVER="127.0.0.1" \
    TUNNL_USER="test" \
    TUNNL_SSH_BIN="$FAKE_SSH" \
    TUNNL_SSH_KEY="$SSH_KEY" \
    TUNNL_KNOWN_HOSTS="$TUNNL_KNOWN_HOSTS" \
    TUNNL_STATUS_PORT="$STATUS_PORT" \
    TUNNL_STATUS_URL_FILE="$STATUS_URL_FILE" \
    TUNNL_PID_FILE="$STATUS_PID_FILE" \
    TUNNL_ALLOW_LOCAL_ADDRESS="true" \
    TUNNEL_PID_FILE="$GAME_PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee "$LOG_DIR/tunnl-provider-test.log"

game_address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
status_url="$(grep -v '^#' "$STATUS_URL_FILE" | tail -n 1)"

python3 - "$game_address" "$status_url" <<'PY'
import sys
import urllib.request

game = sys.argv[1]
status = sys.argv[2]

if not game or ":" not in game:
    raise SystemExit(f"missing Minecraft address: {game!r}")

with urllib.request.urlopen(f"http://{game}/", timeout=10) as response:
    if response.status != 200:
        raise SystemExit(f"game relay returned {response.status}")

with urllib.request.urlopen(status.rstrip("/") + "/healthz", timeout=10) as response:
    if response.status != 200:
        raise SystemExit(f"status page returned {response.status}")

print("tunnl provider test passed: game TCP relay and tunnl.gg status page are reachable")
PY
