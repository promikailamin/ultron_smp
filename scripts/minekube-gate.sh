#!/usr/bin/env bash
# Optional Gate connector for the Minekube Connect tunnel.
#
# The Connect Java plugin is convenient, but it runs inside Paper: if its
# connector gets stuck, the whole Minecraft server has to restart. Minekube
# recommends Gate as its most capable connector, and Gate runs as a separate
# process next to Paper. That lets the workflow restart just the tunnel without
# taking the world/player sessions down.
#
# Gate listens locally and forwards Connect sessions to Paper at
# 127.0.0.1:${MINECRAFT_PORT}. It never opens a public inbound port.
#
# Usage:
#   bash scripts/minekube-gate.sh start
#   bash scripts/minekube-gate.sh status
#   bash scripts/minekube-gate.sh stop
#
# Optional environment variables:
#   MINECRAFT_PORT              Local Paper port (default: 25565)
#   MINEKUBE_ENDPOINT           Endpoint name (1-16 lowercase/digit/hyphen)
#   MINEKUBE_CONNECT_TOKEN      Connector token; never printed or committed
#   MINEKUBE_WATCH_URL          Watch service URL (default: wss://watch-connect.minekube.net)
#   MINEKUBE_GATE_VERSION       Gate release (default: latest, fallback 0.82.0)
#   MINEKUBE_GATE_DOWNLOAD_URL  Override the Gate download URL
#   MINEKUBE_GATE_BIN           Use an existing Gate binary
#   MINEKUBE_BIND_ADDR          Local Gate bind address (default: 127.0.0.1)
#   MINEKUBE_BIND_PORT          Local Gate bind port (default: 25566)
#   MINEKUBE_READY_TIMEOUT      Seconds to wait for registration (default: 90)
#   MINEKUBE_DATA_DIR           State/config directory
#   TUNNEL_PID_FILE             PID file location
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.agent-work/tools"
LOGS_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
PID_FILE="${TUNNEL_PID_FILE:-$DEFAULT_PID_DIR/ultron-gate.pid}"

LOCAL_PORT="${MINECRAFT_PORT:-25565}"
BIND_ADDR="${MINEKUBE_BIND_ADDR:-127.0.0.1}"
BIND_PORT="${MINEKUBE_BIND_PORT:-25566}"
DOMAIN="${MINEKUBE_DOMAIN:-play.minekube.net}"
READY_TIMEOUT="${MINEKUBE_READY_TIMEOUT:-90}"
WATCH_ADDR="${MINEKUBE_WATCH_URL:-wss://watch-connect.minekube.net}"
ALLOW_OFFLINE="${MINEKUBE_ALLOW_OFFLINE:-true}"
GATE_VERSION="${MINEKUBE_GATE_VERSION:-latest}"
GATE_BIN="${MINEKUBE_GATE_BIN:-}"

DATA_DIR="${MINEKUBE_DATA_DIR:-$ROOT_DIR/.agent-work/tmp/minekube-gate}"
CONFIG_FILE="$DATA_DIR/config.yml"
TOKEN_FILE="$DATA_DIR/connect.json"
TUNNEL_LOG="$LOGS_DIR/minekube-gate.log"

mkdir -p "$TOOLS_DIR" "$LOGS_DIR" "$DATA_DIR" "$(dirname -- "$PID_FILE")"

detect_os() {
    case "$(uname -s)" in
        Linux) echo linux ;;
        Darwin) echo darwin ;;
        *) echo "ERROR: unsupported operating system: $(uname -s)" >&2; return 1 ;;
    esac
}

detect_arch() {
    case "$(uname -m)" in
        x86_64 | amd64) echo amd64 ;;
        aarch64 | arm64) echo arm64 ;;
        *) echo "ERROR: unsupported CPU architecture: $(uname -m)" >&2; return 1 ;;
    esac
}

resolve_gate_version() {
    if [ "$GATE_VERSION" != "latest" ]; then
        return 0
    fi
    local resolved
    resolved="$(curl -fsSL --max-time 20 -o /dev/null -w '%{url_effective}' \
        https://github.com/minekube/gate/releases/latest 2>/dev/null \
        | sed -n 's#.*/tag/v\{0,1\}##p' || true)"
    if [ -n "$resolved" ]; then
        GATE_VERSION="$resolved"
    else
        GATE_VERSION="0.82.0"
    fi
}

ensure_gate() {
    if [ -n "$GATE_BIN" ]; then
        [ -x "$GATE_BIN" ] || {
            echo "ERROR: MINEKUBE_GATE_BIN is not executable: $GATE_BIN" >&2
            return 1
        }
        return 0
    fi

    resolve_gate_version
    local os arch asset url download_dir
    os="$(detect_os)"
    arch="$(detect_arch)"
    GATE_BIN="$TOOLS_DIR/gate-${GATE_VERSION}-${os}-${arch}"

    if [ -x "$GATE_BIN" ]; then
        return 0
    fi

    asset="gate_${GATE_VERSION}_${os}_${arch}"
    url="${MINEKUBE_GATE_DOWNLOAD_URL:-https://github.com/minekube/gate/releases/download/v${GATE_VERSION}/${asset}}"
    download_dir="$TOOLS_DIR/.gate-download-$$"
    rm -rf "$download_dir"
    mkdir -p "$download_dir"
    trap 'rm -rf "$download_dir"' EXIT

    echo "Downloading Minekube Gate ${GATE_VERSION} for ${os}/${arch}..."
    curl -fsSL --retry 3 --connect-timeout 20 --max-time 300 \
        -o "$download_dir/gate" "$url"
    install -m 0755 "$download_dir/gate" "$GATE_BIN"
    rm -rf "$download_dir"
    trap - EXIT
}

valid_endpoint() {
    local name="$1"
    [ "${#name}" -ge 1 ] && [ "${#name}" -le 16 ] \
        && printf '%s' "$name" | grep -qE '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
}

generate_endpoint() {
    local suffix
    suffix="$(od -An -N4 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' || true)"
    [ -n "$suffix" ] || suffix="$(printf '%04x%04x' "$RANDOM" "$RANDOM")"
    printf 'ultron-%s' "$suffix"
}

stop_gate() {
    if [ ! -f "$PID_FILE" ]; then
        return 0
    fi
    local old_pid
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        kill "$old_pid" 2>/dev/null || true
        for _ in $(seq 1 20); do
            kill -0 "$old_pid" 2>/dev/null || break
            sleep 0.25
        done
        kill -9 "$old_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
}

write_token_file() {
    if [ -z "${MINEKUBE_CONNECT_TOKEN:-}" ]; then
        return 0
    fi
    python3 - "$MINEKUBE_CONNECT_TOKEN" "$TOKEN_FILE" <<'PY'
import json
import os
import sys
import tempfile

token, path = sys.argv[1], sys.argv[2]
directory = os.path.dirname(path) or "."
fd, tmp = tempfile.mkstemp(dir=directory, prefix=".connect-", suffix=".json")
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump({"token": token}, stream)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
finally:
    if os.path.exists(tmp):
        os.unlink(tmp)
PY
}

write_config() {
    local endpoint="$1"
    local offline="false"
    [ "$ALLOW_OFFLINE" = "true" ] && offline="true"

    {
        echo "# Generated by scripts/minekube-gate.sh - do not commit."
        echo "config:"
        echo "  bind: \"${BIND_ADDR}:${BIND_PORT}\""
        echo "  onlineMode: false"
        echo "  forceKeyAuthentication: false"
        echo "  forwarding:"
        echo "    mode: none"
        echo "  connectionTimeout: 10s"
        echo "  readTimeout: 60s"
        echo "  compression:"
        echo "    threshold: 128"
        echo "  servers:"
        echo "    ultron: \"127.0.0.1:${LOCAL_PORT}\""
        echo "  try:"
        echo "    - ultron"
        echo "  status:"
        echo "    motd: |"
        echo "      §bUltron SMP"
        echo "      §7via Minekube Connect (Gate)"
        echo "    showMaxPlayers: 100"
        echo "connect:"
        echo "  enabled: true"
        echo "  name: \"${endpoint}\""
        echo "  allowOfflineModePlayers: ${offline}"
        echo "  tokenFilePath: \"${TOKEN_FILE}\""
        echo "  watchServiceAddr: \"${WATCH_ADDR}\""
    } >"$CONFIG_FILE"
}

write_address() {
    local endpoint="$1"
    local address="${endpoint}.${DOMAIN}:25565"
    local tmp="$ADDRESS_FILE.tmp"
    {
        echo "# Ultron SMP public server address"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# Provider: Minekube Connect (Gate ${GATE_VERSION}, endpoint ${endpoint})"
        echo "# Add this server in Minecraft (host and port):"
        echo "$address"
        echo "#"
        echo "# Host: ${endpoint}.${DOMAIN}"
        echo "# Port: 25565"
    } >"$tmp"
    mv "$tmp" "$ADDRESS_FILE"
    printf '%s\n' "$address"
}

start_gate() {
    local endpoint="$1"
    local gate_pid="" log_start=0 new_log
    write_config "$endpoint"

    printf '\n=== %s starting Gate %s endpoint=%s ===\n' \
        "$(date -u '+%Y-%m-%d %H:%M:%S UTC')" "$GATE_VERSION" "$endpoint" >>"$TUNNEL_LOG"
    log_start="$(wc -l <"$TUNNEL_LOG" 2>/dev/null || echo 0)"

    nohup "$GATE_BIN" -c "$CONFIG_FILE" >>"$TUNNEL_LOG" 2>&1 </dev/null &
    gate_pid=$!

    for _ in $(seq 1 $((READY_TIMEOUT * 4))); do
        if ! kill -0 "$gate_pid" 2>/dev/null; then
            break
        fi
        new_log="$(tail -n "+$((log_start + 1))" "$TUNNEL_LOG" 2>/dev/null || true)"
        if printf '%s' "$new_log" \
            | grep -qE 'connect[^ ]*[[:space:]].*connected|connected[[:space:]]+\{"took"'; then
            printf '%s\n' "$gate_pid" >"$PID_FILE"
            return 0
        fi
        if printf '%s' "$new_log" \
            | grep -qiE 'invalid endpoint name format|missing required header|status[^ ]*401|unauthorized'; then
            break
        fi
        sleep 0.25
    done

    kill "$gate_pid" 2>/dev/null || true
    wait "$gate_pid" 2>/dev/null || true
    return 1
}

show_status() {
    if [ ! -f "$PID_FILE" ]; then
        echo "Gate connector is not running (no PID file)."
        return 1
    fi
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        echo "Gate connector is running (pid ${pid})."
        [ -f "$ADDRESS_FILE" ] && grep -v '^#' "$ADDRESS_FILE" | tail -n 1 || true
        return 0
    fi
    echo "Gate connector is not running (stale PID file)."
    return 1
}

command="${1:-start}"
case "$command" in
    stop)
        stop_gate
        echo "Gate connector stopped."
        exit 0
        ;;
    status)
        show_status
        exit $?
        ;;
    start)
        ;;
    *)
        echo "Usage: $0 [start|stop|status]" >&2
        exit 2
        ;;
esac

if ! ensure_gate; then
    echo "ERROR: the Minekube Gate connector could not be installed." >&2
    exit 1
fi

stop_gate
write_token_file

requested="$(printf '%s' "${MINEKUBE_ENDPOINT:-}" | tr '[:upper:]' '[:lower:]')"
endpoint="$requested"
[ -n "$endpoint" ] || endpoint="$(generate_endpoint)"
if ! valid_endpoint "$endpoint"; then
    echo "ERROR: MINEKUBE_ENDPOINT '${endpoint}' is invalid (use 1-16 lowercase letters, digits or hyphens)." >&2
    exit 1
fi

if start_gate "$endpoint"; then
    address="$(write_address "$endpoint")"
    echo "Gate connector is live at ${address}"
    exit 0
fi

echo "ERROR: could not establish the Minekube Gate tunnel for endpoint '${endpoint}'." >&2
tail -n 40 "$TUNNEL_LOG" 2>/dev/null || true
exit 1
