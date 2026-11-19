#!/usr/bin/env bash
# First-run bootstrap for the Paper + Minekube Connect server.
#
# Starts the server once so Paper and the Connect plugin create their default
# configuration files, stops it cleanly, then applies the endpoint and (when
# provided through the environment) the Connect token to the generated files.
#
# The token is never printed and is written to plugins/connect/token.json,
# which is git-ignored. Set MINEKUBE_CONNECT_TOKEN before running this script.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="$ROOT_DIR/plugins/connect/config.yml"
TOKEN_FILE="$ROOT_DIR/plugins/connect/token.json"
LOG_FILE="${SETUP_LOG:-$ROOT_DIR/.agent-work/logs/setup-server.log}"
FIFO="$(mktemp -u "$ROOT_DIR/.agent-work/tmp/setup-fifo.XXXXXX")"
ENDPOINT="${MINEKUBE_ENDPOINT:-mikailamin}"
ALLOW_OFFLINE="${MINEKUBE_ALLOW_OFFLINE:-true}"
SERVER_PID=""

mkdir -p "$(dirname "$LOG_FILE")" "$ROOT_DIR/.agent-work/tmp"

cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    [ -p "$FIFO" ] && rm -f "$FIFO"
}
trap cleanup EXIT

rm -f "$FIFO"
mkfifo "$FIFO"

echo "Starting Paper once to load the Minekube Connect defaults..."
# Do not hand the endpoint token to the temporary random endpoint.
env -u MINEKUBE_CONNECT_TOKEN bash "$ROOT_DIR/start.sh" <"$FIFO" >"$LOG_FILE" 2>&1 &
SERVER_PID=$!
exec 3>"$FIFO"

ready=0
for _ in $(seq 1 300); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        break
    fi
    if grep -q 'Done (' "$LOG_FILE" 2>/dev/null; then
        ready=1
        break
    fi
    sleep 1
done

if [ "$ready" -eq 1 ]; then
    echo "Server started; stopping it to persist generated configs..."
    echo "stop" >&3
else
    echo "WARNING: server did not report ready; continuing with files already on disk." >&2
fi

exec 3>&-
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
rm -f "$FIFO"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: Minekube Connect did not create $CONFIG_FILE." >&2
    exit 1
fi

python3 - "$CONFIG_FILE" "$ENDPOINT" "$ALLOW_OFFLINE" <<'PY'
import re
import sys

path, endpoint, allow_offline = sys.argv[1:4]
with open(path, encoding="utf-8") as stream:
    text = stream.read()
text = re.sub(r"^endpoint:.*$", f"endpoint: {endpoint}", text,
              count=1, flags=re.MULTILINE)
value = "true" if allow_offline.lower() == "true" else "false"
text = re.sub(r"^allow-offline-mode-players:.*$",
              f"allow-offline-mode-players: {value}", text,
              count=1, flags=re.MULTILINE)
with open(path, "w", encoding="utf-8") as stream:
    stream.write(text)
PY

if [ -n "${MINEKUBE_CONNECT_TOKEN:-}" ]; then
    python3 - "$MINEKUBE_CONNECT_TOKEN" "$TOKEN_FILE" <<'PY'
import json
import os
import sys
import tempfile

token, path = sys.argv[1], sys.argv[2]
directory = os.path.dirname(path) or "."
fd, tmp = tempfile.mkstemp(dir=directory, prefix=".token-", suffix=".json")
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump({"token": token}, stream)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
finally:
    if os.path.exists(tmp):
        os.unlink(tmp)
PY
    echo "Connect token written to the git-ignored $TOKEN_FILE."
else
    echo "No MINEKUBE_CONNECT_TOKEN supplied; the plugin will generate a token on first run."
fi

echo "Endpoint: ${ENDPOINT}"
echo "Public address: ${ENDPOINT}.play.minekube.net:25565"
echo "Configs are ready. Start the server with ./start.sh"
