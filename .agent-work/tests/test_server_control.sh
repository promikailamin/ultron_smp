#!/usr/bin/env bash
# Start a disposable server copy through server-control.sh, verify that
# console commands are received, then confirm a graceful stop flushes the
# world and terminates the process.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$ROOT_DIR/.agent-work/tmp/server-control-test"
STATE_DIR="$ROOT_DIR/.agent-work/tmp/server-control-state"
LOG_FILE="$ROOT_DIR/.agent-work/logs/server-control-test.log"
TEST_PORT="${TEST_PORT:-25568}"
CONTROL="$ROOT_DIR/scripts/server-control.sh"

rm -rf "$TEST_DIR" "$STATE_DIR"
mkdir -p "$TEST_DIR" "$STATE_DIR" "$(dirname -- "$LOG_FILE")"

ln -s ../../../libraries "$TEST_DIR/libraries"
ln -s ../../../versions "$TEST_DIR/versions"
ln -s ../../../mods "$TEST_DIR/mods"
ln -s ../../../.fabric "$TEST_DIR/.fabric"
ln -s ../../../native "$TEST_DIR/native"
cp "$ROOT_DIR/loader.jar" "$ROOT_DIR/log4j2.xml" "$ROOT_DIR/eula.txt" \
    "$ROOT_DIR/server.properties" "$ROOT_DIR/run.sh" "$TEST_DIR/"
cp -a "$ROOT_DIR/world" "$TEST_DIR/world"
cp -a "$ROOT_DIR/config" "$TEST_DIR/config"
sed -i "s/^server-port=.*/server-port=${TEST_PORT}/" "$TEST_DIR/server.properties"
chmod +x "$TEST_DIR/run.sh"

export MC_STATE_DIR="$STATE_DIR"
export MC_LOG_FILE="$LOG_FILE"
export MC_RUN_SCRIPT="$TEST_DIR/run.sh"

cleanup() {
    bash "$CONTROL" stop 30 >/dev/null 2>&1 || true
}
trap cleanup EXIT

bash "$CONTROL" start

ready=0
for second in $(seq 1 120); do
    if (echo > "/dev/tcp/127.0.0.1/${TEST_PORT}") 2>/dev/null; then
        ready=1
        echo "Server listening after ${second}s"
        break
    fi
    if ! bash "$CONTROL" status >/dev/null 2>&1; then
        echo "Server exited while starting; see ${LOG_FILE}"
        tail -n 100 "$LOG_FILE" || true
        exit 1
    fi
    sleep 1
done

if [ "$ready" -ne 1 ]; then
    echo "Server did not start within 120s; see ${LOG_FILE}"
    tail -n 100 "$LOG_FILE" || true
    exit 1
fi

strip_ansi() { sed -E $'s/\x1B\\[[0-9;]*[mK]//g'; }

# The TCP port opens slightly before the console command dispatcher is ready,
# so retry a harmless command until the server actually answers it.
console_ready=0
for _ in $(seq 1 30); do
    bash "$CONTROL" command "list" || true
    sleep 2
    strip_ansi <"$LOG_FILE" >"$STATE_DIR/console.log"
    if grep -q "players online" "$STATE_DIR/console.log"; then
        console_ready=1
        break
    fi
done

if [ "$console_ready" -ne 1 ]; then
    echo "Console command was not received by the server."
    tail -n 80 "$LOG_FILE" || true
    exit 1
fi

bash "$CONTROL" command "say server-control online"
bash "$CONTROL" save
sleep 3

strip_ansi <"$LOG_FILE" >"$STATE_DIR/console.log"

grep -q "server-control online" "$STATE_DIR/console.log" || {
    echo "Console command was not received by the server."
    tail -n 80 "$LOG_FILE" || true
    exit 1
}

if ! grep -qE "Saved the game|Saving (the game|chunks)" "$STATE_DIR/console.log"; then
    echo "Server did not report a save-all flush."
    tail -n 80 "$LOG_FILE" || true
    exit 1
fi

bash "$CONTROL" stop 120

if bash "$CONTROL" status >/dev/null 2>&1; then
    echo "Server is still running after stop."
    exit 1
fi

grep -q "Saving chunks for level" "$STATE_DIR/console.log" || {
    echo "Final chunk save was not logged."
    exit 1
}

echo "server-control test passed: console commands and graceful stop work."
