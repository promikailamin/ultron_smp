#!/usr/bin/env bash
# Expose a small Ultron SMP status page through a tunnl.gg HTTP tunnel.
#
# tunnl.gg is an SSH-based reverse tunnel for HTTP and WebSocket traffic. It
# deliberately does not carry raw TCP, so it cannot be the Minecraft endpoint
# itself. This script therefore publishes a read-only status page that shows
# the current Minecraft TCP address (SERVER_ADDRESS.txt) while the game stays
# on a TCP relay such as the Bangladesh-prioritised Pinggy relay or bore.pub.
#
# The public URL is written to SERVER_STATUS_URL.txt. A supervisor reconnects
# automatically if the tunnl.gg SSH session drops.
#
# Optional environment variables:
#   TUNNL_SERVER                Relay host (default: proxy.tunnl.gg)
#   TUNNL_PORT                  Relay SSH port (default: 22)
#   TUNNL_USER                  SSH user (default: stable; empty = random URL)
#   TUNNL_SSH_KEY               SSH private key (default: generated in .agent-work/tmp)
#   TUNNL_KNOWN_HOSTS           known_hosts file used for the relay
#   TUNNL_STATUS_PORT           Local status-page port (default: 8080)
#   TUNNL_STATUS_BIND           Local status-page bind address (default: 127.0.0.1)
#   TUNNL_STATUS_URL_FILE       Output file (default: <repo>/SERVER_STATUS_URL.txt)
#   TUNNL_RECONNECT_SECONDS     Reconnect after this many seconds; 0 waits for
#                               the SSH session to end (default: 0)
#   TUNNL_SSH_BIN               SSH executable (default: ssh; used by tests)
#   TUNNL_ALLOW_LOCAL_ADDRESS   Accept http://127.0.0.1 URLs (tests; default: false)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
TMP_DIR="$ROOT_DIR/.agent-work/tmp"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$TMP_DIR}"
PID_FILE="${TUNNL_PID_FILE:-$DEFAULT_PID_DIR/ultron-tunnl-tunnel.pid}"
STATUS_URL_FILE="${TUNNL_STATUS_URL_FILE:-$ROOT_DIR/SERVER_STATUS_URL.txt}"
TUNNEL_LOG="$LOG_DIR/tunnl-tunnel.log"
STATUS_LOG="$LOG_DIR/tunnl-status.log"

REMOTE_HOST="${TUNNL_SERVER:-proxy.tunnl.gg}"
SSH_PORT="${TUNNL_PORT:-22}"
SSH_USER="${TUNNL_USER:-stable}"
RECONNECT_SECONDS="${TUNNL_RECONNECT_SECONDS:-0}"
KNOWN_HOSTS="${TUNNL_KNOWN_HOSTS:-$TMP_DIR/tunnl_known_hosts}"
SSH_BIN="${TUNNL_SSH_BIN:-ssh}"
SSH_KEY="${TUNNL_SSH_KEY:-$TMP_DIR/tunnl_ssh_key}"
STATUS_PORT="${TUNNL_STATUS_PORT:-8080}"
STATUS_BIND="${TUNNL_STATUS_BIND:-127.0.0.1}"
ALLOW_LOCAL="${TUNNL_ALLOW_LOCAL_ADDRESS:-false}"

TUNNL_HOST_KEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN3HFkIcqgWI6Sa1E31KiUbcfcrX/5MZl4LnaQnUYL8K"

mkdir -p "$LOG_DIR" "$TMP_DIR" "$(dirname -- "$PID_FILE")" "$(dirname -- "$STATUS_URL_FILE")"

strip_ansi() {
    sed -E $'s/\x1B\\[[0-9;]*[mK]//g'
}

write_status_url() {
    local url="$1"
    local tmp="$STATUS_URL_FILE.tmp"

    {
        echo "# Ultron SMP tunnl.gg status page"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# tunnl.gg carries HTTP/WebSocket only; connect to the address"
        echo "# in SERVER_ADDRESS.txt for Minecraft itself."
        echo "# Provider: tunnl.gg SSH tunnel (${REMOTE_HOST})"
        echo "$url"
    } >"$tmp"
    mv "$tmp" "$STATUS_URL_FILE"

    echo "$url"
}

extract_url() {
    local log_file="$1"

    python3 - "$log_file" "$ALLOW_LOCAL" <<'PY'
import json
import sys

path, allow_local = sys.argv[1], sys.argv[2].lower() == "true"
try:
    text = open(path, encoding="utf-8", errors="replace").read()
except OSError:
    raise SystemExit(0)

for line in text.splitlines():
    line = line.strip()
    if not line.startswith("{"):
        continue
    try:
        event = json.loads(line)
    except ValueError:
        continue
    if event.get("event") != "tunnel":
        continue
    url = event.get("url") or ""
    if url.startswith("https://") or (allow_local and url.startswith("http://")):
        print(url)
        raise SystemExit(0)
PY
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

ensure_ssh_key() {
    if [ -z "$SSH_USER" ] || [ "$SSH_USER" = "anonymous" ]; then
        SSH_USER=""
        return 0
    fi

    if [ -f "$SSH_KEY" ]; then
        chmod 600 "$SSH_KEY" 2>/dev/null || true
        return 0
    fi

    if ! command -v ssh-keygen >/dev/null 2>&1; then
        echo "WARNING: ssh-keygen is unavailable; using a random tunnl.gg URL." >&2
        SSH_USER=""
        return 0
    fi

    mkdir -p "$(dirname -- "$SSH_KEY")"
    ssh-keygen -q -t ed25519 -N "" -f "$SSH_KEY" >/dev/null
    chmod 600 "$SSH_KEY" 2>/dev/null || true
}

ensure_known_hosts() {
    mkdir -p "$(dirname -- "$KNOWN_HOSTS")"
    touch "$KNOWN_HOSTS"

    if [ "$REMOTE_HOST" = "proxy.tunnl.gg" ] \
        && ! grep -qF "proxy.tunnl.gg ${TUNNL_HOST_KEY}" "$KNOWN_HOSTS" 2>/dev/null; then
        printf 'proxy.tunnl.gg %s\n' "$TUNNL_HOST_KEY" >>"$KNOWN_HOSTS"
    fi
}

start_status_server() {
    TUNNL_ROOT_DIR="$ROOT_DIR" \
        TUNNL_STATUS_PORT="$STATUS_PORT" \
        TUNNL_STATUS_BIND="$STATUS_BIND" \
        python3 "$ROOT_DIR/scripts/tunnl-status-server.py" \
        >>"$STATUS_LOG" 2>&1 &
    STATUS_PID=$!

    for _ in $(seq 1 50); do
        if ! kill -0 "$STATUS_PID" 2>/dev/null; then
            return 1
        fi
        if (echo >"/dev/tcp/${STATUS_BIND}/${STATUS_PORT}") 2>/dev/null; then
            return 0
        fi
        sleep 0.2
    done

    return 1
}

run_tunnel_once() {
    local attempt_log="$LOG_DIR/tunnl-attempt.log"
    local ssh_pid=""
    local target="$REMOTE_HOST"
    local -a ssh_args=(
        -n
        -T
        -o BatchMode=yes
        -o ConnectTimeout=20
        -o ExitOnForwardFailure=yes
        -o ServerAliveInterval=15
        -o ServerAliveCountMax=3
        -o StrictHostKeyChecking=accept-new
        -o UserKnownHostsFile="$KNOWN_HOSTS"
        -p "$SSH_PORT"
        -R "80:localhost:${STATUS_PORT}"
    )

    if [ -n "$SSH_USER" ]; then
        target="${SSH_USER}@${REMOTE_HOST}"
    fi
    if [ -f "$SSH_KEY" ]; then
        ssh_args+=(-i "$SSH_KEY")
    fi

    {
        echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') connecting to ${target} ==="
    } >>"$TUNNEL_LOG"

    : >"$attempt_log"
    if [ "$RECONNECT_SECONDS" -gt 0 ] 2>/dev/null; then
        timeout --signal=TERM "$RECONNECT_SECONDS" \
            "$SSH_BIN" "${ssh_args[@]}" "$target" output=json \
            >"$attempt_log" 2>&1 </dev/null &
    else
        "$SSH_BIN" "${ssh_args[@]}" "$target" output=json \
            >"$attempt_log" 2>&1 </dev/null &
    fi
    ssh_pid=$!
    ACTIVE_SSH_PID="$ssh_pid"

    local url=""
    for _ in $(seq 1 120); do
        if ! kill -0 "$ssh_pid" 2>/dev/null; then
            break
        fi

        url="$(extract_url "$attempt_log")"
        if [ -n "$url" ]; then
            echo "tunnl.gg status page is live at $(write_status_url "$url")"
            strip_ansi <"$attempt_log" | tail -n 3 >>"$TUNNEL_LOG" || true
            break
        fi

        sleep 0.5
    done

    if [ -z "$url" ]; then
        strip_ansi <"$attempt_log" | tail -n 20 >>"$TUNNEL_LOG" || true
        echo "WARNING: could not establish a tunnl.gg tunnel on this attempt." >&2
    fi

    wait "$ssh_pid" 2>/dev/null || true
    ACTIVE_SSH_PID=""
    sleep 5
}

supervise() {
    ACTIVE_SSH_PID=""
    STATUS_PID=""

    # shellcheck disable=SC2329
    cleanup() {
        if [ -n "$ACTIVE_SSH_PID" ]; then
            kill "$ACTIVE_SSH_PID" 2>/dev/null || true
            wait "$ACTIVE_SSH_PID" 2>/dev/null || true
        fi
        if [ -n "$STATUS_PID" ]; then
            kill "$STATUS_PID" 2>/dev/null || true
            wait "$STATUS_PID" 2>/dev/null || true
        fi
    }
    trap 'cleanup; exit 0' TERM INT
    trap cleanup EXIT

    if ! start_status_server; then
        echo "ERROR: could not start the local tunnl.gg status page on ${STATUS_BIND}:${STATUS_PORT}." >&2
        strip_ansi <"$STATUS_LOG" | tail -n 20 >&2 || true
        exit 1
    fi

    while true; do
        run_tunnel_once
    done
}

if [ "${1:-}" = "--supervise" ]; then
    ensure_ssh_key
    ensure_known_hosts
    supervise
    exit 0
fi

if ! command -v "$SSH_BIN" >/dev/null 2>&1; then
    echo "ERROR: '$SSH_BIN' is required for the tunnl.gg tunnel." >&2
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 is required for the tunnl.gg status page." >&2
    exit 1
fi

ensure_ssh_key
ensure_known_hosts
stop_existing_tunnel
rm -f "$STATUS_URL_FILE"

: >"$TUNNEL_LOG"
nohup "$0" --supervise >>"$TUNNEL_LOG" 2>&1 </dev/null &
supervisor_pid=$!
echo "$supervisor_pid" >"$PID_FILE"

for _ in $(seq 1 120); do
    if ! kill -0 "$supervisor_pid" 2>/dev/null; then
        break
    fi

    if [ -s "$STATUS_URL_FILE" ]; then
        url="$(grep -v '^#' "$STATUS_URL_FILE" | tail -n 1)"
        if [ -n "$url" ]; then
            echo "tunnl.gg status page is live at ${url} (pid ${supervisor_pid})"
            exit 0
        fi
    fi

    sleep 1
done

kill "$supervisor_pid" 2>/dev/null || true
wait "$supervisor_pid" 2>/dev/null || true
rm -f "$PID_FILE"
echo "ERROR: could not establish a tunnl.gg tunnel." >&2
strip_ansi <"$TUNNEL_LOG" | tail -n 30 >&2 || true
exit 1
