#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
TEST_DIR="$TMP_DIR/twingate-connector-test"
FAKE_DOCKER="$TEST_DIR/fake-docker"
STATE_DIR="$TEST_DIR/state"
ENV_DIR="$TEST_DIR/env"
CONTAINER_NAME="ultron-twingate-test"
START_LOG="$LOG_DIR/test-twingate-connector-start.log"
MISSING_LOG="$LOG_DIR/test-twingate-connector-missing.log"

mkdir -p "$LOG_DIR" "$TMP_DIR"
rm -rf "$TEST_DIR"
mkdir -p "$STATE_DIR" "$ENV_DIR"

cat >"$FAKE_DOCKER" <<'PY'
#!/usr/bin/env bash
set -euo pipefail

state_dir="${FAKE_DOCKER_STATE_DIR:?}"
command="${1:-}"
shift || true

case "$command" in
    run)
        env_file=""
        name=""
        while [ "$#" -gt 0 ]; do
            case "$1" in
                --name)
                    name="$2"
                    shift 2
                    ;;
                --env-file)
                    env_file="$2"
                    shift 2
                    ;;
                --network | --sysctl | --restart)
                    shift 2
                    ;;
                -*)
                    shift
                    ;;
                *)
                    shift
                    ;;
            esac
        done

        [ -n "$name" ] || { echo "fake docker: missing name" >&2; exit 1; }
        [ -f "$env_file" ] || { echo "fake docker: env file missing" >&2; exit 1; }

        for key in TWINGATE_NETWORK TWINGATE_ACCESS_TOKEN TWINGATE_REFRESH_TOKEN; do
            value="$(sed -n "s/^${key}=//p" "$env_file")"
            [ -n "$value" ] || { echo "fake docker: ${key} is empty" >&2; exit 1; }
        done

        if [ -n "${FAKE_DOCKER_EXPECT_NETWORK:-}" ]; then
            network="$(sed -n 's/^TWINGATE_NETWORK=//p' "$env_file")"
            [ "$network" = "$FAKE_DOCKER_EXPECT_NETWORK" ] || {
                echo "fake docker: unexpected network" >&2
                exit 1
            }
        fi

        umask 077
        printf '%s\n' "$env_file" >"$state_dir/env-file-path"
        printf 'run\n' >>"$state_dir/run-calls"
        printf 'true\n' >"$state_dir/${name}.running"
        echo "fake-container-id"
        ;;
    inspect)
        name="${@: -1}"
        if [ -f "$state_dir/${name}.running" ]; then
            cat "$state_dir/${name}.running"
        else
            exit 1
        fi
        ;;
    rm)
        name="${@: -1}"
        rm -f "$state_dir/${name}.running"
        ;;
    *)
        exit 0
        ;;
esac
PY
chmod +x "$FAKE_DOCKER"

cleanup() {
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

bash -n "$ROOT_DIR/scripts/twingate-connector.sh"

python3 - "$ROOT_DIR/.github/workflows/run.yml" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as handle:
    workflow = yaml.safe_load(handle)

job = workflow["jobs"]["server"]
env = job["env"]
for key in ("TWINGATE_ACCESS_TOKEN", "TWINGATE_REFRESH_TOKEN"):
    if key not in env:
        raise SystemExit(f"workflow is missing {key}")

step_names = [step.get("name") for step in job["steps"]]
if "start_twingate_connector" not in step_names:
    raise SystemExit("workflow is missing the start_twingate_connector step")
PY

ACCESS_TOKEN="test-access-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
REFRESH_TOKEN="test-refresh-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
NETWORK="test-network"

TWINGATE_NETWORK="$NETWORK" \
    TWINGATE_ACCESS_TOKEN="$ACCESS_TOKEN" \
    TWINGATE_REFRESH_TOKEN="$REFRESH_TOKEN" \
    TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="$CONTAINER_NAME" \
    TWINGATE_ENV_DIR="$ENV_DIR" \
    TWINGATE_READY_TIMEOUT="5" \
    FAKE_DOCKER_STATE_DIR="$STATE_DIR" \
    FAKE_DOCKER_EXPECT_NETWORK="$NETWORK" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" start \
    >"$START_LOG" 2>&1

grep -q "is running" "$START_LOG"
if grep -qF "$ACCESS_TOKEN" "$START_LOG" \
    || grep -qF "$REFRESH_TOKEN" "$START_LOG" \
    || grep -qF "$NETWORK" "$START_LOG"; then
    echo "ERROR: connector output leaked a token value." >&2
    exit 1
fi

env_file_path="$(cat "$STATE_DIR/env-file-path")"
if [ -e "$env_file_path" ]; then
    echo "ERROR: temporary connector env file was not removed." >&2
    exit 1
fi

TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="$CONTAINER_NAME" \
    FAKE_DOCKER_STATE_DIR="$STATE_DIR" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" status \
    >>"$START_LOG" 2>&1

TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="$CONTAINER_NAME" \
    FAKE_DOCKER_STATE_DIR="$STATE_DIR" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" stop \
    >>"$START_LOG" 2>&1

if [ -f "$STATE_DIR/${CONTAINER_NAME}.running" ]; then
    echo "ERROR: connector container was not stopped." >&2
    exit 1
fi

if env -u TWINGATE_ACCESS_TOKEN \
    TWINGATE_NETWORK="$NETWORK" \
    TWINGATE_REFRESH_TOKEN="$REFRESH_TOKEN" \
    TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="$CONTAINER_NAME" \
    TWINGATE_ENV_DIR="$ENV_DIR" \
    FAKE_DOCKER_STATE_DIR="$STATE_DIR" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" start \
    >"$MISSING_LOG" 2>&1; then
    echo "ERROR: start should fail without TWINGATE_ACCESS_TOKEN." >&2
    exit 1
fi

grep -q "missing Twingate connector configuration" "$MISSING_LOG"
if grep -qF "$REFRESH_TOKEN" "$MISSING_LOG"; then
    echo "ERROR: failure output leaked a token value." >&2
    exit 1
fi

DERIVED_NETWORK="test-derived-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
DERIVED_ACCESS_TOKEN="$(python3 - "$DERIVED_NETWORK" <<'PY'
import base64
import json
import sys


def encode(value):
    raw = json.dumps(value, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


header = encode({"alg": "ES256", "typ": "DAT"})
payload = encode({"aud": sys.argv[1], "iss": "twingate"})
print(f"{header}.{payload}.test-signature")
PY
)"
DERIVED_REFRESH_TOKEN="test-refresh-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
DERIVED_STATE_DIR="$TEST_DIR/state-derived"
DERIVED_ENV_DIR="$TEST_DIR/env-derived"
DERIVED_LOG="$LOG_DIR/test-twingate-connector-derived.log"
mkdir -p "$DERIVED_STATE_DIR" "$DERIVED_ENV_DIR"

env -u TWINGATE_NETWORK \
    TWINGATE_ENABLED=auto \
    TWINGATE_ACCESS_TOKEN="$DERIVED_ACCESS_TOKEN" \
    TWINGATE_REFRESH_TOKEN="$DERIVED_REFRESH_TOKEN" \
    TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="${CONTAINER_NAME}-derived" \
    TWINGATE_ENV_DIR="$DERIVED_ENV_DIR" \
    TWINGATE_READY_TIMEOUT="5" \
    FAKE_DOCKER_STATE_DIR="$DERIVED_STATE_DIR" \
    FAKE_DOCKER_EXPECT_NETWORK="$DERIVED_NETWORK" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" start \
    >"$DERIVED_LOG" 2>&1

grep -q "is running" "$DERIVED_LOG"
if grep -qF "$DERIVED_ACCESS_TOKEN" "$DERIVED_LOG" \
    || grep -qF "$DERIVED_REFRESH_TOKEN" "$DERIVED_LOG" \
    || grep -qF "$DERIVED_NETWORK" "$DERIVED_LOG"; then
    echo "ERROR: derived-network output leaked a credential value." >&2
    exit 1
fi

derived_env_file="$(cat "$DERIVED_STATE_DIR/env-file-path")"
if [ -e "$derived_env_file" ]; then
    echo "ERROR: temporary derived-network env file was not removed." >&2
    exit 1
fi

SKIP_STATE_DIR="$TEST_DIR/state-skip"
SKIP_ENV_DIR="$TEST_DIR/env-skip"
SKIP_LOG="$LOG_DIR/test-twingate-connector-skip.log"
mkdir -p "$SKIP_STATE_DIR" "$SKIP_ENV_DIR"

env -u TWINGATE_NETWORK -u TWINGATE_ACCESS_TOKEN -u TWINGATE_REFRESH_TOKEN \
    TWINGATE_ENABLED=auto \
    TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="${CONTAINER_NAME}-skip" \
    TWINGATE_ENV_DIR="$SKIP_ENV_DIR" \
    FAKE_DOCKER_STATE_DIR="$SKIP_STATE_DIR" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" start \
    >"$SKIP_LOG" 2>&1

grep -q "skipped" "$SKIP_LOG"
if [ -f "$SKIP_STATE_DIR/run-calls" ]; then
    echo "ERROR: auto mode started Docker without connector tokens." >&2
    exit 1
fi

REQUIRED_LOG="$LOG_DIR/test-twingate-connector-required.log"
if env -u TWINGATE_NETWORK -u TWINGATE_ACCESS_TOKEN -u TWINGATE_REFRESH_TOKEN \
    TWINGATE_ENABLED=true \
    TWINGATE_DOCKER_BIN="$FAKE_DOCKER" \
    TWINGATE_CONNECTOR_NAME="${CONTAINER_NAME}-required" \
    TWINGATE_ENV_DIR="$SKIP_ENV_DIR" \
    FAKE_DOCKER_STATE_DIR="$SKIP_STATE_DIR" \
    bash "$ROOT_DIR/scripts/twingate-connector.sh" start \
    >"$REQUIRED_LOG" 2>&1; then
    echo "ERROR: TWINGATE_ENABLED=true should fail without tokens." >&2
    exit 1
fi
grep -q "are required when TWINGATE_ENABLED=true" "$REQUIRED_LOG"

echo "Twingate connector test passed: auto-detection, env-only tokens, cleanup and status handling validated."
