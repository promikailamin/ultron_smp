#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/twingate-admin.py"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
TEST_DIR="$TMP_DIR/twingate-admin-test"
STATE_DIR="$TEST_DIR/state"
SERVER="$TEST_DIR/fake_twingate_admin.py"
PORT_FILE="$TEST_DIR/port"
STATUS_LOG="$LOG_DIR/test-twingate-admin-status.log"
PROVISION_LOG="$LOG_DIR/test-twingate-admin-provision.log"
REPEAT_LOG="$LOG_DIR/test-twingate-admin-repeat.log"
TOKEN_FILE="$TEST_DIR/tokens.env"

mkdir -p "$LOG_DIR" "$TMP_DIR"
rm -rf "$TEST_DIR"
mkdir -p "$STATE_DIR"

cat >"$SERVER" <<'PY'
#!/usr/bin/env python3
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state_dir = sys.argv[1]
port_file = sys.argv[2]
state_path = os.path.join(state_dir, "state.json")
log_path = os.path.join(state_dir, "requests.jsonl")


def load_state():
    with open(state_path, encoding="utf-8") as handle:
        return json.load(handle)


def save_state(state):
    with open(state_path, "w", encoding="utf-8") as handle:
        json.dump(state, handle)


def connection(nodes):
    return {
        "edges": [{"node": node} for node in nodes],
        "pageInfo": {"hasNextPage": False, "endCursor": None},
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        query = payload.get("query", "")
        variables = payload.get("variables") or {}
        state = load_state()

        with open(log_path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps({
                "query": query,
                "variables": variables,
                "has_api_key": bool(self.headers.get("X-API-KEY")),
            }) + "\n")

        data = {}
        if "remoteNetworks" in query and "remoteNetworkCreate" not in query:
            data = {"remoteNetworks": connection(state["networks"])}
        elif "connectors" in query and "connectorCreate" not in query:
            data = {"connectors": connection(state["connectors"])}
        elif "resources" in query:
            data = {"resources": connection(state["resources"])}
        elif "groups" in query:
            data = {"groups": connection(state["groups"])}
        elif "remoteNetworkCreate" in query:
            network = {
                "id": "network-{}".format(len(state["networks"]) + 1),
                "name": variables["name"],
                "networkType": "REGULAR",
                "location": "OTHER",
                "isActive": True,
            }
            state["networks"].append(network)
            save_state(state)
            data = {"remoteNetworkCreate": {"ok": True, "error": None, "entity": network}}
        elif "connectorCreate" in query:
            connector = {
                "id": "connector-{}".format(len(state["connectors"]) + 1),
                "name": variables["name"],
                "hostname": "runner",
                "state": "ALIVE",
                "remoteNetwork": {
                    "id": variables["remoteNetworkId"],
                    "name": "Ultron SMP",
                },
            }
            state["connectors"].append(connector)
            save_state(state)
            data = {"connectorCreate": {"ok": True, "error": None, "entity": connector}}
        elif "connectorGenerateTokens" in query:
            data = {
                "connectorGenerateTokens": {
                    "ok": True,
                    "error": None,
                    "connectorTokens": {
                        "accessToken": "test-access-token-value",
                        "refreshToken": "test-refresh-token-value",
                    },
                }
            }
        else:
            data = {}

        body = json.dumps({"data": data}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
with open(port_file, "w", encoding="utf-8") as handle:
    handle.write(str(server.server_address[1]))
server.serve_forever()
PY

cat >"$STATE_DIR/state.json" <<'JSON'
{"networks": [], "connectors": [], "resources": [], "groups": [{"id": "group-everyone", "name": "Everyone", "isActive": true}]}
JSON

cleanup() {
    if [ -n "${SERVER_PID:-}" ]; then
        kill "$SERVER_PID" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

python3 -m py_compile "$SCRIPT"
bash -n "$SCRIPT" 2>/dev/null || true

python3 "$SERVER" "$STATE_DIR" "$PORT_FILE" &
SERVER_PID=$!
for _ in $(seq 1 50); do
    [ -s "$PORT_FILE" ] && break
    sleep 0.1
done
[ -s "$PORT_FILE" ] || { echo "fake server did not start" >&2; exit 1; }
PORT="$(cat "$PORT_FILE")"
API_URL="http://127.0.0.1:${PORT}/api/graphql/"
TOKEN="fake-admin-token-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"

TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    python3 "$SCRIPT" status >"$STATUS_LOG" 2>&1

grep -q "Remote Networks (0)" "$STATUS_LOG"
grep -q "Connectors (0)" "$STATUS_LOG"
grep -q "Groups (1)" "$STATUS_LOG"
grep -q "Everyone" "$STATUS_LOG"
if grep -qF "$TOKEN" "$STATUS_LOG"; then
    echo "ERROR: status output leaked the API token." >&2
    exit 1
fi

TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    TWINGATE_NEW_REMOTE_NETWORK="Ultron SMP" \
    TWINGATE_CONNECTOR_NAME="twingate-mccon" \
    TWINGATE_GENERATE_TOKENS="true" \
    TWINGATE_TOKEN_ENV_FILE="$TOKEN_FILE" \
    python3 "$SCRIPT" provision >"$PROVISION_LOG" 2>&1

grep -q "Created Remote Network 'Ultron SMP'" "$PROVISION_LOG"
grep -q "Created Connector 'twingate-mccon'" "$PROVISION_LOG"
grep -q "Connector tokens written" "$PROVISION_LOG"
grep -q "Provisioned Remote Network" "$PROVISION_LOG"
if grep -qF "$TOKEN" "$PROVISION_LOG" \
    || grep -qF "test-access-token-value" "$PROVISION_LOG" \
    || grep -qF "test-refresh-token-value" "$PROVISION_LOG"; then
    echo "ERROR: provision output leaked a credential." >&2
    exit 1
fi

mode="$(stat -c '%a' "$TOKEN_FILE")"
if [ "$mode" != "600" ]; then
    echo "ERROR: token file permissions are ${mode}, expected 600." >&2
    exit 1
fi
grep -q '^TWINGATE_NETWORK=testnet$' "$TOKEN_FILE"
grep -q '^TWINGATE_ACCESS_TOKEN=test-access-token-value$' "$TOKEN_FILE"
grep -q '^TWINGATE_REFRESH_TOKEN=test-refresh-token-value$' "$TOKEN_FILE"

python3 - "$STATE_DIR/state.json" <<'PYCHECK'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
if len(state["networks"]) != 1 or state["networks"][0]["name"] != "Ultron SMP":
    raise SystemExit("remote network was not created")
if len(state["connectors"]) != 1 or state["connectors"][0]["name"] != "twingate-mccon":
    raise SystemExit("connector was not created")
PYCHECK

TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    TWINGATE_NEW_REMOTE_NETWORK="Ultron SMP" \
    TWINGATE_CONNECTOR_NAME="twingate-mccon" \
    TWINGATE_GENERATE_TOKENS="false" \
    python3 "$SCRIPT" provision >"$REPEAT_LOG" 2>&1

if grep -q "Created Remote Network" "$REPEAT_LOG" \
    || grep -q "Created Connector" "$REPEAT_LOG"; then
    echo "ERROR: provision created duplicate resources." >&2
    exit 1
fi
grep -q "Provisioned Remote Network 'Ultron SMP' and Connector 'twingate-mccon'" "$REPEAT_LOG"

if grep -qF "$TOKEN" "$STATE_DIR/requests.jsonl"; then
    echo "ERROR: API token was written to the request log." >&2
    exit 1
fi
if grep -q '"has_api_key": false' "$STATE_DIR/requests.jsonl"; then
    echo "ERROR: a request was sent without the X-API-KEY header." >&2
    exit 1
fi

echo "Twingate admin test passed: status, provisioning, token rotation file, idempotency and credential secrecy validated."
