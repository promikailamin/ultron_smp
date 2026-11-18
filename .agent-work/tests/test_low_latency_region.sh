#!/usr/bin/env bash
# Validate TUNNEL_REGION mapping without contacting public Pinggy relays.
#
# A fake ssh client just publishes a tcp:// address, so the low-latency
# script can be exercised for each forced region and the resulting relay can
# be checked in the generated address file.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
LOG_FILE="$LOG_DIR/test-low-latency-region.log"
FAKE_SSH="$TMP_DIR/fake-region-ssh"

mkdir -p "$LOG_DIR" "$TMP_DIR"
: >"$LOG_FILE"

cat >"$FAKE_SSH" <<'PY'
#!/usr/bin/env python3
import signal
import socket
import sys
import time

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 0))
listener.listen(8)
print(f"tcp://127.0.0.1:{listener.getsockname()[1]}", flush=True)


def stop(_signum, _frame):
    listener.close()
    sys.exit(0)


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)

while True:
    time.sleep(1)
PY
chmod +x "$FAKE_SSH"

cleanup() {
    for pid_file in "$TMP_DIR"/region-*.pid; do
        [ -f "$pid_file" ] || continue
        pid="$(cat "$pid_file" 2>/dev/null || true)"
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    rm -f "$FAKE_SSH" "$TMP_DIR"/region-*.txt "$TMP_DIR"/region-*.pid \
        "$TMP_DIR"/region-*-known-hosts
}
trap cleanup EXIT

run_case() {
    local region="$1"
    local expected="$2"
    local address_file="$TMP_DIR/region-${region}-address.txt"
    local pid_file="$TMP_DIR/region-${region}.pid"
    local known_hosts="$TMP_DIR/region-${region}-known-hosts"

    rm -f "$address_file" "$pid_file" "$known_hosts"
    {
        echo "=== TUNNEL_REGION=${region} (expected ${expected}) ==="
    } >>"$LOG_FILE"

    TUNNEL_REGION="$region" \
        PINGGY_SSH_BIN="$FAKE_SSH" \
        PINGGY_KNOWN_HOSTS="$known_hosts" \
        PINGGY_RECONNECT_SECONDS="0" \
        MINECRAFT_PORT="25565" \
        LOW_LATENCY_ADDRESS_FILE="$address_file" \
        LOW_LATENCY_PID_FILE="$pid_file" \
        bash "$ROOT_DIR/scripts/low-latency-tunnel.sh" >>"$LOG_FILE" 2>&1

    if ! grep -q "# Relay: Pinggy ${expected}" "$address_file"; then
        echo "ERROR: TUNNEL_REGION=${region} did not select ${expected}." >&2
        cat "$address_file" >&2 || true
        return 1
    fi

    local supervisor_pid
    supervisor_pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [ -n "$supervisor_pid" ]; then
        kill "$supervisor_pid" 2>/dev/null || true
        for _ in $(seq 1 20); do
            kill -0 "$supervisor_pid" 2>/dev/null || break
            sleep 0.1
        done
    fi
    echo "TUNNEL_REGION=${region} -> ${expected}" >>"$LOG_FILE"
}

bash -n "$ROOT_DIR/scripts/low-latency-tunnel.sh"

run_case "bangladesh" "ap.free.pinggy.io"
run_case "asia-pacific" "ap.free.pinggy.io"
run_case "united-states" "us.free.pinggy.io"
run_case "europe" "eu.free.pinggy.io"

python3 - "$ROOT_DIR/.github/workflows/run.yml" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as handle:
    workflow = yaml.safe_load(handle)

on_block = workflow.get("on") or workflow.get(True) or {}
dispatch = on_block.get("workflow_dispatch", {})
inputs = dispatch.get("inputs", {})
if "tunnel_region" not in inputs:
    raise SystemExit("workflow is missing the tunnel_region input")
choices = inputs["tunnel_region"].get("options", [])
for choice in ("auto", "bangladesh", "asia-pacific", "united-states", "europe"):
    if choice not in choices:
        raise SystemExit(f"tunnel_region input is missing choice {choice}")

env = workflow["jobs"]["server"]["env"]
if "TUNNEL_REGION" not in env:
    raise SystemExit("workflow is missing the TUNNEL_REGION environment variable")
PY

echo "Low-latency region test passed: Bangladesh/AP, US and EU relays are selectable."
