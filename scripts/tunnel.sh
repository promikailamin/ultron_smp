#!/usr/bin/env bash
# Expose the local Minecraft TCP port through bore.pub.
#
# The script tries a short list of fixed remote ports first so that players
# can keep using the same address between runs. If every fixed port is already
# taken on the public bore server it falls back to a random port.
#
# It writes the resulting address to SERVER_ADDRESS.txt and leaves the tunnel
# running in the background. The PID is stored in:
#   ${TUNNEL_PID_FILE:-${RUNNER_TEMP:-<repo>/.agent-work/tmp}/ultron-tunnel.pid}
#
# Optional environment variables:
#   MINECRAFT_PORT         Local Minecraft port (default: 25565)
#   BORE_SERVER            bore server hostname (default: bore.pub)
#   BORE_VERSION           bore release to download (default: 0.5.3)
#   TUNNEL_PORT_CANDIDATES Space separated remote ports to try (default below)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.agent-work/tools"
LOGS_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
PID_FILE="${TUNNEL_PID_FILE:-$DEFAULT_PID_DIR/ultron-tunnel.pid}"
TUNNEL_LOG="$LOGS_DIR/tunnel.log"

LOCAL_PORT="${MINECRAFT_PORT:-25565}"
REMOTE_HOST="${BORE_SERVER:-bore.pub}"
BORE_VERSION="${BORE_VERSION:-0.5.3}"
PORT_CANDIDATES="${TUNNEL_PORT_CANDIDATES:-25565 25566 25567 25568 25569 25570}"

mkdir -p "$TOOLS_DIR" "$LOGS_DIR" "$(dirname -- "$PID_FILE")"

detect_arch() {
    case "$(uname -m)" in
        x86_64 | amd64) echo "x86_64" ;;
        aarch64 | arm64) echo "aarch64" ;;
        *)
            echo "ERROR: unsupported CPU architecture: $(uname -m)" >&2
            return 1
            ;;
    esac
}

strip_ansi() {
    sed -E $'s/\x1B\\[[0-9;]*[mK]//g'
}

ARCH="$(detect_arch)"
BORE_BIN="$TOOLS_DIR/bore-$BORE_VERSION-$ARCH"

if [ ! -x "$BORE_BIN" ]; then
    echo "Downloading bore ${BORE_VERSION} for ${ARCH}..." >&2
    DOWNLOAD_DIR="$TOOLS_DIR/.bore-download-$$"
    mkdir -p "$DOWNLOAD_DIR"
    trap 'rm -rf "$DOWNLOAD_DIR"' EXIT
    curl -fsSL --retry 3 --connect-timeout 15 \
        "https://github.com/ekzhang/bore/releases/download/v${BORE_VERSION}/bore-v${BORE_VERSION}-${ARCH}-unknown-linux-musl.tar.gz" \
        -o "$DOWNLOAD_DIR/bore.tar.gz"
    tar -xzf "$DOWNLOAD_DIR/bore.tar.gz" -C "$DOWNLOAD_DIR"
    install -m 0755 "$DOWNLOAD_DIR/bore" "$BORE_BIN"
    rm -rf "$DOWNLOAD_DIR"
    trap - EXIT
fi

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

write_address() {
    local port="$1"
    local address="${REMOTE_HOST}:${port}"
    local tmp="$ADDRESS_FILE.tmp"

    {
        echo "# Ultron SMP public server address"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# Add this server in Minecraft (host and port):"
        echo "$address"
        echo "#"
        echo "# Host: $REMOTE_HOST"
        echo "# Port: $port"
    } >"$tmp"
    mv "$tmp" "$ADDRESS_FILE"

    echo "$address"
}

start_bore() {
    local requested_port="$1"
    local -a args=(local "$LOCAL_PORT" --to "$REMOTE_HOST")

    if [ -n "$requested_port" ]; then
        args+=(--port "$requested_port")
    fi

    : >"$TUNNEL_LOG"
    nohup "$BORE_BIN" "${args[@]}" >"$TUNNEL_LOG" 2>&1 </dev/null &
    local tunnel_pid=$!

    local escaped_host="${REMOTE_HOST//./\\.}"
    local port=""
    for _ in $(seq 1 50); do
        if ! kill -0 "$tunnel_pid" 2>/dev/null; then
            break
        fi

        port="$(strip_ansi <"$TUNNEL_LOG" \
            | grep -oE "${escaped_host}:[0-9]+" \
            | head -n 1 | cut -d: -f2 || true)"
        if [ -n "$port" ]; then
            echo "$tunnel_pid" >"$PID_FILE"
            echo "Tunnel is live at $(write_address "$port")"
            strip_ansi <"$TUNNEL_LOG" | tail -n 5 >&2 || true
            return 0
        fi

        sleep 0.2
    done

    kill "$tunnel_pid" 2>/dev/null || true
    wait "$tunnel_pid" 2>/dev/null || true
    return 1
}

stop_existing_tunnel

for port in $PORT_CANDIDATES; do
    if start_bore "$port"; then
        exit 0
    fi
    echo "Remote port ${port} is unavailable; trying the next one..." >&2
done

if start_bore ""; then
    exit 0
fi

echo "ERROR: could not establish a bore.pub tunnel." >&2
strip_ansi <"$TUNNEL_LOG" | tail -n 20 >&2 || true
exit 1
