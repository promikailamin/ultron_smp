#!/usr/bin/env bash
# Expose the local Minecraft TCP port through a Serveo SSH tunnel.
#
# Serveo is a free SSH-based relay. Unlike bore.pub it does not need a
# downloaded binary, but its public TCP ports are random and the SSH session
# can drop, so a small supervisor reconnects automatically and rewrites
# SERVER_ADDRESS.txt with the new address.
#
# It writes the resulting address to SERVER_ADDRESS.txt and leaves the
# supervisor running in the background. The PID is stored in:
#   ${TUNNEL_PID_FILE:-${RUNNER_TEMP:-<repo>/.agent-work/tmp}/ultron-tunnel.pid}
#
# Optional environment variables:
#   MINECRAFT_PORT            Local Minecraft port (default: 25565)
#   SERVEO_SERVER             Serveo relay host (default: serveo.net)
#   SERVEO_PORT               Relay SSH port (default: 22)
#   SERVEO_USER               Optional SSH user (default: none/anonymous)
#   SERVEO_RECONNECT_SECONDS  Reconnect after this many seconds; 0 disables
#                             the periodic reconnect (default: 0)
#   SERVEO_KNOWN_HOSTS        known_hosts file used for the relay
#   SERVEO_SSH_BIN            SSH executable (default: ssh; used by tests)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
PID_FILE="${TUNNEL_PID_FILE:-$DEFAULT_PID_DIR/ultron-tunnel.pid}"
TUNNEL_LOG="$LOG_DIR/serveo-tunnel.log"

LOCAL_PORT="${MINECRAFT_PORT:-25565}"
REMOTE_HOST="${SERVEO_SERVER:-serveo.net}"
SSH_PORT="${SERVEO_PORT:-22}"
SSH_USER="${SERVEO_USER:-}"
RECONNECT_SECONDS="${SERVEO_RECONNECT_SECONDS:-0}"
KNOWN_HOSTS="${SERVEO_KNOWN_HOSTS:-$ROOT_DIR/.agent-work/tmp/serveo_known_hosts}"
SSH_BIN="${SERVEO_SSH_BIN:-ssh}"

mkdir -p "$LOG_DIR" "$(dirname -- "$PID_FILE")" "$(dirname -- "$KNOWN_HOSTS")"

strip_ansi() {
    sed -E $'s/\x1B\\[[0-9;]*[mK]//g'
}

write_address() {
    local address="$1"
    local host="${address%:*}"
    local port="${address##*:}"
    local tmp="$ADDRESS_FILE.tmp"

    {
        echo "# Ultron SMP public server address"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# Provider: Serveo SSH tunnel (${REMOTE_HOST})"
        echo "# Add this server in Minecraft (host and port):"
        echo "$address"
        echo "#"
        echo "# Host: $host"
        echo "# Port: $port"
    } >"$tmp"
    mv "$tmp" "$ADDRESS_FILE"

    echo "$address"
}

extract_address() {
    local log_file="$1"
    local escaped_host="${REMOTE_HOST//./\\.}"

    # Serveo prints e.g. "Forwarding TCP connections from serveo.net:39419".
    strip_ansi <"$log_file" \
        | grep -oE "${escaped_host}:[0-9]{1,5}" \
        | head -n 1 || true
}

stop_existing_tunnel() {
    if [ ! -f "$PID_FILE" ]; then
        return 0
    fi

    local old_pid
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        kill "$old_pid" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$old_pid" 2>/dev/null || break
            sleep 0.2
        done
        kill -9 "$old_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
}

run_tunnel_once() {
    local attempt_log="$LOG_DIR/serveo-attempt.log"
    local ssh_pid=""
    local target="$REMOTE_HOST"
    local -a ssh_args=(
        -T
        -o BatchMode=yes
        -o ConnectTimeout=20
        -o ExitOnForwardFailure=yes
        -o ServerAliveInterval=15
        -o ServerAliveCountMax=3
        -o StrictHostKeyChecking=accept-new
        -o UserKnownHostsFile="$KNOWN_HOSTS"
        -p "$SSH_PORT"
        -R "0:localhost:${LOCAL_PORT}"
    )

    if [ -n "$SSH_USER" ]; then
        target="${SSH_USER}@${REMOTE_HOST}"
    fi

    {
        echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') connecting to ${target} ==="
    } >>"$TUNNEL_LOG"

    : >"$attempt_log"
    if [ "$RECONNECT_SECONDS" -gt 0 ] 2>/dev/null; then
        timeout --signal=TERM "$RECONNECT_SECONDS" \
            "$SSH_BIN" "${ssh_args[@]}" "$target" \
            >"$attempt_log" 2>&1 </dev/null &
    else
        "$SSH_BIN" "${ssh_args[@]}" "$target" \
            >"$attempt_log" 2>&1 </dev/null &
    fi
    ssh_pid=$!
    ACTIVE_SSH_PID="$ssh_pid"

    local address=""
    for _ in $(seq 1 120); do
        if ! kill -0 "$ssh_pid" 2>/dev/null; then
            break
        fi

        address="$(extract_address "$attempt_log")"
        if [ -n "$address" ]; then
            echo "Serveo tunnel is live at $(write_address "$address")"
            strip_ansi <"$attempt_log" | tail -n 5 >>"$TUNNEL_LOG" || true
            break
        fi

        sleep 0.5
    done

    if [ -z "$address" ]; then
        strip_ansi <"$attempt_log" | tail -n 20 >>"$TUNNEL_LOG" || true
        echo "WARNING: could not establish a Serveo tunnel on this attempt." >&2
    fi

    wait "$ssh_pid" 2>/dev/null || true
    ACTIVE_SSH_PID=""
    sleep 5
}

supervise() {
    ACTIVE_SSH_PID=""

    # Make sure the relay SSH process is gone when the supervisor is stopped.
    # shellcheck disable=SC2329
    cleanup() {
        if [ -n "$ACTIVE_SSH_PID" ]; then
            kill "$ACTIVE_SSH_PID" 2>/dev/null || true
            wait "$ACTIVE_SSH_PID" 2>/dev/null || true
        fi
    }
    trap 'cleanup; exit 0' TERM INT
    trap cleanup EXIT

    while true; do
        run_tunnel_once
    done
}

if [ "${1:-}" = "--supervise" ]; then
    supervise
    exit 0
fi

if ! command -v "$SSH_BIN" >/dev/null 2>&1; then
    echo "ERROR: '$SSH_BIN' is required for the Serveo tunnel." >&2
    exit 1
fi

stop_existing_tunnel
rm -f "$ADDRESS_FILE"

: >"$TUNNEL_LOG"
nohup "$0" --supervise >>"$TUNNEL_LOG" 2>&1 </dev/null &
supervisor_pid=$!
echo "$supervisor_pid" >"$PID_FILE"

# Wait for the supervisor to publish the first address.
for _ in $(seq 1 90); do
    if ! kill -0 "$supervisor_pid" 2>/dev/null; then
        break
    fi

    if [ -s "$ADDRESS_FILE" ] \
        && grep -qE ':[0-9]+$' "$ADDRESS_FILE" 2>/dev/null; then
        address="$(grep -v '^#' "$ADDRESS_FILE" | grep -E ':[0-9]+$' | tail -n 1)"
        if [ -n "$address" ]; then
            echo "Serveo tunnel is live at ${address} (pid ${supervisor_pid})"
            exit 0
        fi
    fi

    sleep 1
done

kill "$supervisor_pid" 2>/dev/null || true
wait "$supervisor_pid" 2>/dev/null || true
rm -f "$PID_FILE"
echo "ERROR: could not establish a Serveo tunnel." >&2
strip_ansi <"$TUNNEL_LOG" | tail -n 30 >&2 || true
exit 1
