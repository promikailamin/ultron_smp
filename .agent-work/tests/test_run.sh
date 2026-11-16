#!/usr/bin/env bash
# Start run.sh in a disposable copy of the server and confirm it listens.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$ROOT_DIR/.agent-work/tmp/server-test"
LOG_FILE="$ROOT_DIR/.agent-work/logs/run-test.log"
TEST_PORT="${TEST_PORT:-25566}"

rm -rf "$TEST_DIR"
mkdir -p "$TEST_DIR" "$(dirname -- "$LOG_FILE")"

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

bash "$TEST_DIR/run.sh" >"$LOG_FILE" 2>&1 &
server_pid=$!

# shellcheck disable=SC2329
cleanup() {
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

for second in $(seq 1 120); do
    if (echo > "/dev/tcp/127.0.0.1/${TEST_PORT}") 2>/dev/null; then
        echo "run.sh test passed: server is listening on port ${TEST_PORT} after ${second}s"
        exit 0
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
        break
    fi
    sleep 1
done

echo "run.sh test failed; see ${LOG_FILE}"
tail -n 100 "$LOG_FILE" || true
exit 1
