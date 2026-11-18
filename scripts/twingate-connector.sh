#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
DEFAULT_TMP_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
ENV_DIR="${TWINGATE_ENV_DIR:-$DEFAULT_TMP_DIR}"

DOCKER_BIN="${TWINGATE_DOCKER_BIN:-docker}"
CONTAINER_NAME="${TWINGATE_CONNECTOR_NAME:-ultron-twingate}"
IMAGE="${TWINGATE_DOCKER_IMAGE:-twingate/connector:1}"
READY_TIMEOUT="${TWINGATE_READY_TIMEOUT:-90}"
NETWORK="${TWINGATE_NETWORK:-}"
ACCESS_TOKEN="${TWINGATE_ACCESS_TOKEN:-}"
REFRESH_TOKEN="${TWINGATE_REFRESH_TOKEN:-}"
LOG_LEVEL="${TWINGATE_LOG_LEVEL:-3}"

ENV_FILE=""

usage() {
    cat >&2 <<'EOF'
Usage: twingate-connector.sh {start|stop|status}

Required for start (provide through the environment, never on the command line):
  TWINGATE_NETWORK         Twingate network slug
  TWINGATE_ACCESS_TOKEN    Connector access token
  TWINGATE_REFRESH_TOKEN   Connector refresh token

Optional:
  TWINGATE_CONNECTOR_NAME  Docker container name (default: ultron-twingate)
  TWINGATE_DOCKER_IMAGE    Connector image (default: twingate/connector:1)
  TWINGATE_DOCKER_BIN      Docker executable (default: docker)
  TWINGATE_READY_TIMEOUT   Seconds to wait for the container (default: 90)
  TWINGATE_LOG_LEVEL       Connector log level (default: 3)
  TWINGATE_ENV_DIR         Directory for the temporary env file
EOF
}

cleanup() {
    if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
        rm -f "$ENV_FILE"
    fi
}
trap cleanup EXIT

fail() {
    echo "ERROR: $1" >&2
    exit 1
}

validate_value() {
    local name="$1"
    local value="$2"

    case "$value" in
        *$'\n'* | *$'\r'*)
            fail "$name must not contain newline characters."
            ;;
    esac
}

require_tokens() {
    local missing=""

    [ -n "$NETWORK" ] || missing="${missing} TWINGATE_NETWORK"
    [ -n "$ACCESS_TOKEN" ] || missing="${missing} TWINGATE_ACCESS_TOKEN"
    [ -n "$REFRESH_TOKEN" ] || missing="${missing} TWINGATE_REFRESH_TOKEN"

    if [ -n "$missing" ]; then
        fail "missing Twingate connector configuration:${missing}. Set these as GitHub Actions secrets/variables; never paste tokens into issues or commit them."
    fi

    validate_value TWINGATE_NETWORK "$NETWORK"
    validate_value TWINGATE_ACCESS_TOKEN "$ACCESS_TOKEN"
    validate_value TWINGATE_REFRESH_TOKEN "$REFRESH_TOKEN"
}

require_docker() {
    command -v "$DOCKER_BIN" >/dev/null 2>&1 \
        || fail "'$DOCKER_BIN' is required to run the Twingate connector."
}

container_running() {
    [ "$("$DOCKER_BIN" inspect --format '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || true)" = "true" ]
}

write_env_file() {
    mkdir -p "$ENV_DIR"
    ENV_FILE="$(mktemp "$ENV_DIR/twingate-connector-env.XXXXXX")"
    chmod 600 "$ENV_FILE"

    (
        umask 077
        {
            printf 'TWINGATE_NETWORK=%s\n' "$NETWORK"
            printf 'TWINGATE_ACCESS_TOKEN=%s\n' "$ACCESS_TOKEN"
            printf 'TWINGATE_REFRESH_TOKEN=%s\n' "$REFRESH_TOKEN"
            printf 'TWINGATE_LOG_LEVEL=%s\n' "$LOG_LEVEL"
        } >"$ENV_FILE"
    )
}

stop_connector() {
    if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
        return 0
    fi

    "$DOCKER_BIN" rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}

start_connector() {
    require_tokens
    require_docker
    mkdir -p "$LOG_DIR"

    stop_connector
    write_env_file

    "$DOCKER_BIN" run -d \
        --name "$CONTAINER_NAME" \
        --restart unless-stopped \
        --network host \
        --sysctl net.ipv4.ping_group_range="0 2147483647" \
        --env-file "$ENV_FILE" \
        "$IMAGE" >/dev/null

    local waited=0
    while [ "$waited" -lt "$READY_TIMEOUT" ]; do
        if container_running; then
            echo "Twingate connector '${CONTAINER_NAME}' is running."
            echo "Create/assign the Minecraft resource in the Twingate Admin Console; its token values were supplied only through the environment."
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done

    stop_connector
    fail "the Twingate connector did not start within ${READY_TIMEOUT}s. Check the connector tokens and network slug."
}

show_status() {
    require_docker

    if container_running; then
        echo "Twingate connector '${CONTAINER_NAME}' is running."
        return 0
    fi

    echo "Twingate connector '${CONTAINER_NAME}' is not running."
    return 1
}

case "${1:-}" in
    start)
        start_connector
        ;;
    stop)
        stop_connector
        echo "Twingate connector '${CONTAINER_NAME}' stopped."
        ;;
    status)
        show_status
        ;;
    *)
        usage
        exit 2
        ;;
esac
