#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/twingate-resource.py"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
TEST_DIR="$TMP_DIR/twingate-resource-test"
STATE_DIR="$TEST_DIR/state"
SERVER="$TEST_DIR/fake_twingate_api.py"
PORT_FILE="$TEST_DIR/port"
CREATE_LOG="$LOG_DIR/test-twingate-resource-create.log"
EXISTING_LOG="$LOG_DIR/test-twingate-resource-existing.log"
SKIP_LOG="$LOG_DIR/test-twingate-resource-skip.log"
MULTI_LOG="$LOG_DIR/test-twingate-resource-multi.log"
SELECT_LOG="$LOG_DIR/test-twingate-resource-select.log"

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
        if "remoteNetworks" in query:
            edges = [{"node": node} for node in state["networks"]]
            data = {"remoteNetworks": {"edges": edges, "pageInfo": {"hasNextPage": False, "endCursor": None}}}
        elif "resources" in query:
            edges = [{"node": node} for node in state["resources"]]
            data = {"resources": {"edges": edges, "pageInfo": {"hasNextPage": False, "endCursor": None}}}
        elif "resourceCreate" in query:
            resource = {
                "id": "resource-{}".format(len(state["resources"]) + 1),
                "name": variables["name"],
                "address": {"type": "IP", "value": variables["address"]},
                "remoteNetwork": {"id": variables["remoteNetworkId"], "name": "selected"},
                "protocols": variables.get("protocols"),
            }
            state["resources"].append(resource)
            save_state(state)
            data = {"resourceCreate": {"ok": True, "error": None, "entity": resource}}
        elif "resourceUpdate" in query:
            resource = next(item for item in state["resources"] if item["id"] == variables["id"])
            resource["address"] = {"type": "IP", "value": variables["address"]}
            resource["remoteNetwork"] = {"id": variables["remoteNetworkId"], "name": "selected"}
            resource["protocols"] = variables.get("protocols")
            save_state(state)
            data = {"resourceUpdate": {"ok": True, "error": None, "entity": resource}}
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
{"networks": [{"id": "network-1", "name": "Ultron"}], "resources": []}
JSON

cleanup() {
    if [ -n "${SERVER_PID:-}" ]; then
        kill "$SERVER_PID" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

python3 -m py_compile "$SCRIPT"

env -u TWINGATE_API_URL python3 - "$SCRIPT" <<'PY'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("twingate_resource", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
endpoint = module.Client("token", "testnet").endpoint
if endpoint != "https://testnet.twingate.com/api/graphql/":
    raise SystemExit("unexpected Twingate Admin API endpoint: {}".format(endpoint))
PY

bash -n "$ROOT_DIR/.github/workflows/run.yml" 2>/dev/null || true

python3 - "$ROOT_DIR/.github/workflows/run.yml" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as handle:
    workflow = yaml.safe_load(handle)

job = workflow["jobs"]["server"]
env = job["env"]
for key in ("TWINGATE_API_TOKEN", "TWINGATE_RESOURCE_NAME"):
    if key not in env:
        raise SystemExit(f"workflow is missing {key}")

step_names = [step.get("name") for step in job["steps"]]
if "ensure_twingate_resource" not in step_names:
    raise SystemExit("workflow is missing the ensure_twingate_resource step")
PY

python3 "$SERVER" "$STATE_DIR" "$PORT_FILE" &
SERVER_PID=$!
for _ in $(seq 1 50); do
    [ -s "$PORT_FILE" ] && break
    sleep 0.1
done
[ -s "$PORT_FILE" ] || { echo "fake server did not start" >&2; exit 1; }
PORT="$(cat "$PORT_FILE")"
API_URL="http://127.0.0.1:${PORT}/api/graphql/"
TOKEN="fake-api-token-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"

env -u TWINGATE_API_TOKEN -u TWINGATE_ACCESS_TOKEN \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_RESOURCE_ENABLED="auto" \
    python3 "$SCRIPT" ensure >"$SKIP_LOG" 2>&1
grep -q "skipped" "$SKIP_LOG"
if [ -f "$STATE_DIR/requests.jsonl" ]; then
    echo "ERROR: resource script contacted the API without a token." >&2
    exit 1
fi

TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    TWINGATE_RESOURCE_NAME="minecraft" \
    TWINGATE_RESOURCE_ADDRESS="127.0.0.1" \
    TWINGATE_RESOURCE_PORT="25565" \
    python3 "$SCRIPT" ensure >"$CREATE_LOG" 2>&1

grep -q "created at 127.0.0.1:25565" "$CREATE_LOG"
if grep -qF "$TOKEN" "$CREATE_LOG"; then
    echo "ERROR: resource output leaked the API token." >&2
    exit 1
fi

python3 - "$STATE_DIR/state.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
if len(state["resources"]) != 1:
    raise SystemExit("expected exactly one resource")
resource = state["resources"][0]
if resource["name"] != "minecraft":
    raise SystemExit("resource name is not minecraft")
if resource["address"]["value"] != "127.0.0.1":
    raise SystemExit("resource address is wrong")
protocols = resource["protocols"]
if protocols["tcp"]["policy"] != "RESTRICTED":
    raise SystemExit("tcp policy is not RESTRICTED")
if protocols["tcp"]["ports"] != [{"start": 25565, "end": 25565}]:
    raise SystemExit("tcp port is wrong")
if protocols["udp"] != {"policy": "RESTRICTED", "ports": []}:
    raise SystemExit("udp should have no allowed ports")
if protocols["allowIcmp"] is not False:
    raise SystemExit("ICMP should be disabled")
PY

TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    TWINGATE_RESOURCE_NAME="minecraft" \
    TWINGATE_RESOURCE_ADDRESS="127.0.0.1" \
    TWINGATE_RESOURCE_PORT="25565" \
    python3 "$SCRIPT" ensure >"$EXISTING_LOG" 2>&1
grep -q "already exists" "$EXISTING_LOG"

python3 - "$STATE_DIR/state.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
if len(state["resources"]) != 1:
    raise SystemExit("resource script created a duplicate")
PY

if grep -qF "$TOKEN" "$STATE_DIR/requests.jsonl"; then
    echo "ERROR: API token was written to the request log." >&2
    exit 1
fi
if grep -q '"has_api_key": false' "$STATE_DIR/requests.jsonl"; then
    echo "ERROR: a request was sent without the X-API-KEY header." >&2
    exit 1
fi

cat >"$STATE_DIR/state.json" <<'JSON'
{"networks": [{"id": "network-1", "name": "Ultron"}, {"id": "network-2", "name": "Home"}], "resources": []}
JSON

if TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    TWINGATE_RESOURCE_NAME="minecraft" \
    python3 "$SCRIPT" ensure >"$MULTI_LOG" 2>&1; then
    echo "ERROR: multiple remote networks should require a selection." >&2
    exit 1
fi
grep -q "Multiple Twingate Remote Networks" "$MULTI_LOG"

TWINGATE_API_TOKEN="$TOKEN" \
    TWINGATE_NETWORK="testnet" \
    TWINGATE_API_URL="$API_URL" \
    TWINGATE_REMOTE_NETWORK="Home" \
    TWINGATE_RESOURCE_NAME="minecraft" \
    python3 "$SCRIPT" ensure >"$SELECT_LOG" 2>&1
grep -q "created at 127.0.0.1:25565" "$SELECT_LOG"

python3 - "$STATE_DIR/state.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
if not state["resources"]:
    raise SystemExit("resource was not created in the selected network")
if state["resources"][0]["remoteNetwork"]["id"] != "network-2":
    raise SystemExit("resource was created in the wrong remote network")
PY

echo "Twingate resource test passed: create, idempotency, network selection, API token secrecy and workflow wiring validated."
