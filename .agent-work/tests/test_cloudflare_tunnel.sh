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

args_file = os.environ.get("FAKE_CLOUDFLARE_ARGS_FILE")
if args_file:
    with open(args_file, "a", encoding="utf-8") as handle:
        handle.write(" ".join(args) + "\n")


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

QUICK_ARGS_FILE="$TMP_DIR/fake-cloudflared-args-quick.log"
rm -f "$QUICK_ARGS_FILE"

echo "=== quick tunnel ==="
RUNNER_TEMP="$RUN_TEMP" \
    TUNNEL_PROVIDER="cloudflare" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    CLOUDFLARE_BIN="$FAKE_CLOUDFLARED" \
    CLOUDFLARE_MODE="quick" \
    CLOUDFLARE_ALLOW_QUICK_TCP="true" \
    CLOUDFLARE_REGION="bangladesh" \
    CLOUDFLARE_BORE_FALLBACK="false" \
    CLOUDFLARE_DNS_WAIT_SECONDS="0" \
    CLOUDFLARE_ACCESS_PORT="$ACCESS_PORT" \
    FAKE_CLOUDFLARE_PUBLIC_PORT="$PUBLIC_PORT" \
    FAKE_CLOUDFLARE_ARGS_FILE="$QUICK_ARGS_FILE" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee "$LOG_DIR/test-cloudflare-tunnel.log"

check_route "quick tunnel test" "fake-cloudflare.trycloudflare.com:25565" "quick"

if grep -q -- "--region" "$QUICK_ARGS_FILE"; then
    echo "FAIL: the Bangladesh/global edge request must not force a region"
    cat "$QUICK_ARGS_FILE"
    exit 1
fi

for pid_file in "$ACCESS_PID_FILE" "$PID_FILE"; do
    if [ -f "$pid_file" ]; then
        kill "$(cat "$pid_file")" 2>/dev/null || true
    fi
done
sleep 1
rm -f "$VERIFY_FILE"

NAMED_ARGS_FILE="$TMP_DIR/fake-cloudflared-args-named.log"
rm -f "$NAMED_ARGS_FILE"

echo "=== named tunnel ==="
RUNNER_TEMP="$RUN_TEMP" \
    TUNNEL_PROVIDER="cloudflare" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    CLOUDFLARE_BIN="$FAKE_CLOUDFLARED" \
    CLOUDFLARE_TUNNEL_TOKEN="test-token" \
    CLOUDFLARE_TUNNEL_HOSTNAME="mc.example.com" \
    CLOUDFLARE_REGION="united-states" \
    CLOUDFLARE_BORE_FALLBACK="false" \
    CLOUDFLARE_DNS_WAIT_SECONDS="0" \
    CLOUDFLARE_ACCESS_PORT="$((ACCESS_PORT + 1))" \
    FAKE_CLOUDFLARE_PUBLIC_PORT="$((PUBLIC_PORT + 1))" \
    FAKE_CLOUDFLARE_ORIGIN_PORT="$LOCAL_PORT" \
    FAKE_CLOUDFLARE_ARGS_FILE="$NAMED_ARGS_FILE" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee -a "$LOG_DIR/test-cloudflare-tunnel.log"

check_route "named tunnel test" "mc.example.com:25565" "named"

if ! grep -q -- "--region us" "$NAMED_ARGS_FILE"; then
    echo "FAIL: the US region request was not passed to cloudflared"
    cat "$NAMED_ARGS_FILE"
    exit 1
fi

echo "=== quick tunnel TCP guard ==="
set +e
guard_output="$(RUNNER_TEMP="$RUN_TEMP" \
    TUNNEL_PROVIDER="cloudflare" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    CLOUDFLARE_BIN="$FAKE_CLOUDFLARED" \
    CLOUDFLARE_MODE="quick" \
    CLOUDFLARE_TCP_FALLBACK="none" \
    CLOUDFLARE_DNS_WAIT_SECONDS="0" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" 2>&1)"
guard_status=$?
set -e

if [ "$guard_status" -eq 0 ]; then
    echo "FAIL: the HTTP-only quick tunnel was published for Minecraft TCP"
    exit 1
fi
case "$guard_output" in
    *"only proxy HTTP"*) ;;
    *)
        echo "FAIL: the quick-tunnel TCP guard did not explain the limitation"
        printf '%s\n' "$guard_output"
        exit 1
        ;;
esac
echo "quick tunnel TCP guard passed"

echo "=== Bangladesh TCP fallback ==="
FAKE_SSH="$TMP_DIR/fake-pinggy-ssh"
FALLBACK_PORT="${TEST_FALLBACK_PORT:-25699}"
cat >"$FAKE_SSH" <<'SH'
#!/usr/bin/env bash
printf 'tcp://127.0.0.1:%s\n' "${FAKE_PINGGY_PUBLIC_PORT:-25699}"
while :; do sleep 3600; done
SH
chmod +x "$FAKE_SSH"

RUNNER_TEMP="$RUN_TEMP" \
    TUNNEL_PROVIDER="cloudflare" \
    MINECRAFT_PORT="$LOCAL_PORT" \
    CLOUDFLARE_BIN="$FAKE_CLOUDFLARED" \
    CLOUDFLARE_MODE="quick" \
    CLOUDFLARE_TCP_FALLBACK="pinggy" \
    CLOUDFLARE_DNS_WAIT_SECONDS="0" \
    TUNNEL_REGION="bangladesh" \
    PLAYER_REGION="bangladesh" \
    PINGGY_SSH_BIN="$FAKE_SSH" \
    PINGGY_RECONNECT_SECONDS="10" \
    PINGGY_KNOWN_HOSTS="$TMP_DIR/fake-pinggy-known-hosts" \
    FAKE_PINGGY_PUBLIC_PORT="$FALLBACK_PORT" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash "$ROOT_DIR/scripts/tunnel.sh" \
    2>&1 | tee -a "$LOG_DIR/test-cloudflare-tunnel.log"

fallback_address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
if [ "$fallback_address" != "127.0.0.1:${FALLBACK_PORT}" ]; then
    echo "FAIL: expected the Pinggy fallback address 127.0.0.1:${FALLBACK_PORT}, got ${fallback_address}"
    exit 1
fi
if ! grep -qi "Pinggy" "$ADDRESS_FILE"; then
    echo "FAIL: the fallback address was not labelled as Pinggy"
    cat "$ADDRESS_FILE"
    exit 1
fi

fallback_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
if [ -n "$fallback_pid" ]; then
    kill "$fallback_pid" 2>/dev/null || true
    wait "$fallback_pid" 2>/dev/null || true
fi
rm -f "$PID_FILE" "$ACCESS_PID_FILE" "$VERIFY_FILE" "$FAKE_SSH" \
    "$TMP_DIR/fake-pinggy-known-hosts"
echo "Bangladesh TCP fallback passed"

echo "cloudflare-tunnel test passed"
