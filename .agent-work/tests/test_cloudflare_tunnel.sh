#!/usr/bin/env bash
# End-to-end test for scripts/cloudflare-tunnel.sh.
#
# A fake cloudflared client implements both a quick tunnel and a named tunnel,
# plus the `access tcp` proxy, by forwarding real TCP connections to a local
# HTTP server. This validates address parsing, the access proxy and the
# TUNNEL_PROVIDER=cloudflare dispatcher without contacting Cloudflare.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
BACKUP_FILE="$TMP_DIR/SERVER_ADDRESS.cloudflare-test-backup"
LOCAL_PORT="${TEST_PORT:-25580}"
PUBLIC_PORT="${TEST_PUBLIC_PORT:-25640}"
ACCESS_PORT="${TEST_ACCESS_PORT:-25641}"
RUN_TEMP="$TMP_DIR/cloudflare-test-run"
PID_FILE="$RUN_TEMP/ultron-tunnel.pid"
ACCESS_PID_FILE="${PID_FILE}.access"
VERIFY_FILE="$RUN_TEMP/ultron-tunnel-verify-address"
FAKE_CLOUDFLARED="$TMP_DIR/fake-cloudflared"

mkdir -p "$LOG_DIR" "$TMP_DIR" "$RUN_TEMP"
had_address=0
if [ -f "$ADDRESS_FILE" ]; then
    cp "$ADDRESS_FILE" "$BACKUP_FILE"
    had_address=1
fi

cat >"$FAKE_CLOUDFLARED" <<'PY'
#!/usr/bin/env python3
import os
import signal
import socket
import sys
import threading

args = sys.argv[1:]
public_port = int(os.environ.get("FAKE_CLOUDFLARE_PUBLIC_PORT", "25640"))


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


def serve(listener, host, port):
    def handle(client):
        try:
            upstream = socket.create_connection((host, port), timeout=10)
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


def bind(port):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        listener.bind(("127.0.0.1", port))
    except OSError as exc:
        sys.exit(f"fake cloudflared: bind failed on {port}: {exc}")
    listener.listen(16)
    return listener


def option(name):
    if name in args:
        return args[args.index(name) + 1]
    return None


if args and args[0] == "access":
    listen = option("--url") or option("--listener") or option("-L")
    if not listen:
        sys.exit("fake cloudflared access: missing --url")
    host, port_text = listen.rsplit(":", 1)
    listener = bind(int(port_text))
    print(f"INF Start Websocket listener host={listen}", flush=True)
    serve(listener, "127.0.0.1", public_port)
else:
    target = option("--url") or ""
    if target.startswith("tcp://"):
        target = target[len("tcp://"):]
    if target:
        host, port_text = target.rsplit(":", 1)
        target_host, target_port = host, int(port_text)
    else:
        target_host = "127.0.0.1"
        target_port = int(os.environ["FAKE_CLOUDFLARE_ORIGIN_PORT"])

    listener = bind(public_port)
    if "--token" in args:
        print("INF Registered tunnel connection connIndex=0 location=test", flush=True)
    else:
        print("INF |  https://fake-cloudflare.trycloudflare.com  |", flush=True)
    serve(listener, target_host, target_port)
PY
chmod +x "$FAKE_CLOUDFLARED"

http_pid=""
cleanup() {
    for pid_file in "$ACCESS_PID_FILE" "$PID_FILE"; do
        if [ -f "$pid_file" ]; then
            pid="$(cat "$pid_file" 2>/dev/null || true)"
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
        cp "$BACKUP_FILE" "$ADDRESS_FILE" 2>/dev/null || true
    else
        rm -f "$ADDRESS_FILE"
    fi
    rm -f "$PID_FILE" "$ACCESS_PID_FILE" "$VERIFY_FILE" "$FAKE_CLOUDFLARED"
}
trap cleanup EXIT

python3 -m http.server "$LOCAL_PORT" --bind 127.0.0.1 \
    >"$LOG_DIR/test-cloudflare-http.log" 2>&1 &
http_pid=$!
sleep 1

check_route() {
    local label="$1"
    local expected_address="$2"
    local expected_mode="$3"

    address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
    if [ "$address" != "$expected_address" ]; then
        echo "FAIL: expected address ${expected_address}, got ${address}"
        exit 1
    fi
    if ! grep -qi "Cloudflare Tunnel (${expected_mode})" "$ADDRESS_FILE"; then
        echo "FAIL: expected provider mode ${expected_mode} in SERVER_ADDRESS.txt"
        cat "$ADDRESS_FILE"
        exit 1
    fi

    verify_address="$(grep -v '^#' "$VERIFY_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
    host="${verify_address%:*}"
    port="${verify_address##*:}"
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
print("Cloudflare route is reachable through the access proxy")
PY
    echo "${label} passed"
}

echo "=== quick tunnel ==="
RUNNER_TEMP="$RUN_TEMP" \
    TUNNEL_PROVIDER="cloudflare" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    CLOUDFLARE_BIN="$FAKE_CLOUDFLARED" \
    CLOUDFLARE_MODE="quick" \
    CLOUDFLARE_BORE_FALLBACK="false" \
    CLOUDFLARE_DNS_WAIT_SECONDS="0" \
    CLOUDFLARE_ACCESS_PORT="$ACCESS_PORT" \
    FAKE_CLOUDFLARE_PUBLIC_PORT="$PUBLIC_PORT" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee "$LOG_DIR/test-cloudflare-tunnel.log"

check_route "quick tunnel test" "fake-cloudflare.trycloudflare.com:25565" "quick"

for pid_file in "$ACCESS_PID_FILE" "$PID_FILE"; do
    if [ -f "$pid_file" ]; then
        kill "$(cat "$pid_file")" 2>/dev/null || true
    fi
done
sleep 1
rm -f "$VERIFY_FILE"

echo "=== named tunnel ==="
RUNNER_TEMP="$RUN_TEMP" \
    TUNNEL_PROVIDER="cloudflare" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    CLOUDFLARE_BIN="$FAKE_CLOUDFLARED" \
    CLOUDFLARE_TUNNEL_TOKEN="test-token" \
    CLOUDFLARE_TUNNEL_HOSTNAME="mc.example.com" \
    CLOUDFLARE_BORE_FALLBACK="false" \
    CLOUDFLARE_DNS_WAIT_SECONDS="0" \
    CLOUDFLARE_ACCESS_PORT="$((ACCESS_PORT + 1))" \
    FAKE_CLOUDFLARE_PUBLIC_PORT="$((PUBLIC_PORT + 1))" \
    FAKE_CLOUDFLARE_ORIGIN_PORT="$LOCAL_PORT" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee -a "$LOG_DIR/test-cloudflare-tunnel.log"

check_route "named tunnel test" "mc.example.com:25565" "named"

echo "cloudflare-tunnel test passed"
