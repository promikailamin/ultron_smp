#!/usr/bin/env bash
# Tests the optional Gate connector with a fake Gate binary.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp/gate-test"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
ADDRESS_BACKUP="$TMP_DIR/SERVER_ADDRESS.backup"
FAKE_GATE="$TMP_DIR/fake-gate"
DATA_DIR="$TMP_DIR/data"
PID_FILE="$TMP_DIR/ultron-gate.pid"
GATE_PORT="${TEST_GATE_PORT:-28202}"
ENDPOINT="ultron-gate-test"

mkdir -p "$LOG_DIR" "$TMP_DIR" "$DATA_DIR"
rm -f "$FAKE_GATE" "$PID_FILE"
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"

had_address=0
if [ -f "$ADDRESS_FILE" ]; then
    cp "$ADDRESS_FILE" "$ADDRESS_BACKUP"
    had_address=1
fi

cleanup() {
    if [ -f "$PID_FILE" ]; then
        TUNNEL_PID_FILE="$PID_FILE" MINEKUBE_DATA_DIR="$DATA_DIR" \
            bash scripts/minekube-gate.sh stop >/dev/null 2>&1 || true
    fi
    if [ "$had_address" -eq 1 ]; then
        cp "$ADDRESS_BACKUP" "$ADDRESS_FILE" 2>/dev/null || true
    else
        rm -f "$ADDRESS_FILE"
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

cat >"$FAKE_GATE" <<'PY'
#!/usr/bin/env python3
import pathlib
import sys
import time

args = sys.argv[1:]
config = None
for index, arg in enumerate(args):
    if arg in ("-c", "--config") and index + 1 < len(args):
        config = pathlib.Path(args[index + 1])
        break
if config is None or not config.is_file():
    raise SystemExit("fake gate: missing config")

print('INFO connect.watch.proposal config/setup_client.go:110 connected {"took": "1ms"}', flush=True)
while True:
    time.sleep(60)
PY
chmod +x "$FAKE_GATE"

run_gate() {
    MINEKUBE_GATE_BIN="$FAKE_GATE" \
        MINEKUBE_ENDPOINT="$ENDPOINT" \
        MINEKUBE_CONNECT_TOKEN="fake-connector-token" \
        MINEKUBE_DATA_DIR="$DATA_DIR" \
        MINEKUBE_BIND_PORT="$GATE_PORT" \
        MINEKUBE_READY_TIMEOUT=5 \
        MINECRAFT_PORT=25565 \
        TUNNEL_PID_FILE="$PID_FILE" \
        bash scripts/minekube-gate.sh "$@"
}

bash -n scripts/minekube-gate.sh
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml"))'

run_gate start >"$LOG_DIR/test-minekube-gate.log" 2>&1

address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
if [ "$address" != "${ENDPOINT}.play.minekube.net:25565" ]; then
    echo "FAIL: unexpected address '${address}'" >&2
    exit 1
fi

config="$DATA_DIR/config.yml"
grep -q 'enabled: true' "$config" || { echo "FAIL: Connect is not enabled" >&2; exit 1; }
grep -q "name: \"${ENDPOINT}\"" "$config" || { echo "FAIL: endpoint missing from config" >&2; exit 1; }
grep -q 'allowOfflineModePlayers: true' "$config" || { echo "FAIL: offline players are not allowed" >&2; exit 1; }
grep -q "127.0.0.1:25565" "$config" || { echo "FAIL: Paper backend is not configured" >&2; exit 1; }
grep -q "watchServiceAddr: \"wss://watch-connect.minekube.net\"" "$config" \
    || { echo "FAIL: watch service address is not configured" >&2; exit 1; }

token_file="$DATA_DIR/connect.json"
[ -f "$token_file" ] || { echo "FAIL: token file was not created" >&2; exit 1; }
mode="$(stat -c '%a' "$token_file")"
[ "$mode" = "600" ] || { echo "FAIL: token file mode is ${mode}, expected 600" >&2; exit 1; }
python3 - "$token_file" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as stream:
    data = json.load(stream)
if data != {"token": "fake-connector-token"}:
    raise SystemExit("FAIL: token file content is wrong")
PY

run_gate status | grep -q 'running' || { echo "FAIL: status did not report running" >&2; exit 1; }

if grep -q 'fake-connector-token' "$LOG_DIR/test-minekube-gate.log"; then
    echo "FAIL: the connector token was printed to the log" >&2
    exit 1
fi

run_gate stop >/dev/null
[ ! -f "$PID_FILE" ] || { echo "FAIL: stop left a PID file behind" >&2; exit 1; }

MINEKUBE_GATE_BIN="$FAKE_GATE" \
    MINEKUBE_ENDPOINT="INVALID ENDPOINT" \
    MINEKUBE_DATA_DIR="$DATA_DIR" \
    TUNNEL_PID_FILE="$PID_FILE" \
    bash scripts/minekube-gate.sh start >/dev/null 2>&1 \
    && { echo "FAIL: invalid endpoint was accepted" >&2; exit 1; }

echo "Minekube Gate connector checks passed."
