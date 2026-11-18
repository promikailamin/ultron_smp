#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
LOG="$LOG_DIR/test-twingate-docker-host-sysctl.log"
IMAGE="${TWINGATE_TEST_IMAGE:-twingate/connector:1}"
REPRO_NAME="ultron-twingate-sysctl-repro"
FIXED_NAME="ultron-twingate-sysctl-fixed"

if ! command -v docker >/dev/null 2>&1; then
    echo "SKIP: docker is not available."
    exit 0
fi

if ! docker info >/dev/null 2>&1; then
    echo "SKIP: the docker daemon is not reachable."
    exit 0
fi

mkdir -p "$LOG_DIR" "$TMP_DIR"
env_file="$(mktemp "$TMP_DIR/twingate-fake-env.XXXXXX")"
chmod 600 "$env_file"
cleanup() {
    rm -f "$env_file"
    docker rm -f "$REPRO_NAME" "$FIXED_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

umask 077
{
    printf 'TWINGATE_NETWORK=%s\n' 'fake-network'
    printf 'TWINGATE_ACCESS_TOKEN=%s\n' 'fake-access-token'
    printf 'TWINGATE_REFRESH_TOKEN=%s\n' 'fake-refresh-token'
    printf 'TWINGATE_LOG_LEVEL=3\n'
} >"$env_file"

{
    echo "Testing Docker host-networking sysctl compatibility"
    echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Docker server: $(docker version --format '{{.Server.Version}}')"
    echo "Image: ${IMAGE}"
    echo

    echo "1. Reproducing the original failure (host networking + ping_group_range sysctl):"
    docker rm -f "$REPRO_NAME" >/dev/null 2>&1 || true
    if docker run -d --name "$REPRO_NAME" \
        --network host \
        --sysctl net.ipv4.ping_group_range="0 2147483647" \
        "$IMAGE" >/dev/null 2>&1; then
        echo "   UNEXPECTED: docker accepted the sysctl in the host network namespace."
        failure=1
    else
        echo "   Reproduced as expected: docker rejected the sysctl in the host netns."
        failure=0
    fi
    docker rm -f "$REPRO_NAME" >/dev/null 2>&1 || true
    echo

    echo "2. Starting the connector image with host networking WITHOUT the sysctl:"
    if ! docker run -d --name "$FIXED_NAME" \
        --network host \
        --env-file "$env_file" \
        "$IMAGE" >/dev/null; then
        echo "   ERROR: container failed to start without the sysctl."
        exit 1
    fi

    sleep 8
    state="$(docker inspect --format '{{.State.Status}}' "$FIXED_NAME" 2>/dev/null || echo missing)"
    echo "   Container state: ${state}"
    echo "   Recent container logs (fake credentials are expected to be rejected):"
    docker logs --tail 20 "$FIXED_NAME" 2>&1 || true

    if [ "$state" = "missing" ]; then
        echo "   ERROR: container disappeared after start."
        exit 1
    fi
} >"$LOG" 2>&1

cat "$LOG"

if [ "${failure:-0}" -ne 0 ]; then
    exit 1
fi

echo "Twingate Docker host-networking test passed; log: $LOG"
