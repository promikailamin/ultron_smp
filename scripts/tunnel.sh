#!/usr/bin/env bash
# Expose the local Minecraft TCP port through LocalXpose.
#
# LocalXpose is the primary tunnel provider. It is authenticated with an
# access token supplied through the LOCALXPOSE_ACCESS_TOKEN (or ACCESS_TOKEN)
# environment variable; the token is never written into this repository.
#
# LocalXpose TCP tunnels require a PRO account. If the token belongs to a
# FREE account (HTTP/HTTPS only), or the tunnel cannot be established for any
# other reason, this script falls back to the stable bore.pub tunnel unless
# LOCALXPOSE_BORE_FALLBACK=false is set.
#
# It writes the resulting address to SERVER_ADDRESS.txt and leaves the tunnel
# running in the background. The PID is stored in:
#   ${TUNNEL_PID_FILE:-${RUNNER_TEMP:-<repo>/.agent-work/tmp}/ultron-tunnel.pid}
#
# TUNNEL_PROVIDER selects the provider used by the workflow:
#   auto        Try Minekube Connect, then LocalXpose, then bore.pub (default)
#   localxpose  Force the LocalXpose tunnel
#   bore        Force the bore.pub tunnel
#   serveo      Force the Serveo SSH tunnel
#   pinggy      Force the Bangladesh-prioritised Pinggy TCP relay
#   tunnl       Use a low-latency TCP relay for Minecraft and add a
#               tunnl.gg HTTP status page (tunnl.gg cannot carry raw TCP)
#   minekube    Use the Minekube Connect network through the Gate connector
#
# Optional environment variables:
#   TUNNEL_PROVIDER             Tunnel provider (default: auto)
#   MINEKUBE_AUTO               Set to false to skip Minekube for auto
#   MINEKUBE_ENDPOINT           Stable Minekube endpoint name
#   MINEKUBE_CONNECT_TOKEN      Minekube connector token (repository secret)
#   TUNNEL_REGION               Force a free Pinggy relay region:
#                               bangladesh/asia-pacific, united-states,
#                               europe, or auto to measure all of them
#                               (default: auto)
#   PLAYER_REGION               bangladesh/bd prioritises the Asia-Pacific
#                               Pinggy relay (default: bangladesh for
#                               pinggy/tunnl, auto for the standalone script)
#   LOCALXPOSE_ACCESS_TOKEN     LocalXpose access token (required)
#   ACCESS_TOKEN                Alternative token variable name
#   MINECRAFT_PORT              Local Minecraft port (default: 25565)
#   LOCALXPOSE_REGION           Relay region: us, ap, eu, or auto
#                               (default: ap)
#   LOCALXPOSE_PORT_CANDIDATES  Space separated remote ports to try before
#                               asking LocalXpose for a random port
#   LOCALXPOSE_DOWNLOAD_URL     Override the loclx download URL
#   LOCALXPOSE_BORE_FALLBACK    Fall back to bore.pub on failure
#                               (default: true)
#   LOCALXPOSE_ALLOW_LOCAL_ADDRESS
#                               Accept a 127.0.0.1/localhost endpoint from
#                               loclx output (used by tests; default: false)
#   LOCLX_BIN                   Use an existing loclx binary (used by tests)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

PROVIDER="$(printf '%s' "${TUNNEL_PROVIDER:-auto}" | tr '[:upper:]' '[:lower:]')"
case "$PROVIDER" in
    "" | auto)
        if [ "${MINEKUBE_AUTO:-true}" = "true" ]; then
            default_pid_dir="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
            if TUNNEL_PID_FILE="${TUNNEL_PID_FILE:-$default_pid_dir/ultron-tunnel.pid}" \
                bash "$ROOT_DIR/scripts/minekube-tunnel.sh"; then
                exit 0
            fi
            echo "WARNING: Minekube Connect could not be started; trying LocalXpose..." >&2
        fi
        ;;
    minekube | minekube-connect | connect)
        exec bash "$ROOT_DIR/scripts/minekube-tunnel.sh"
        ;;
    localxpose | loclx)
        # An explicit provider choice should not silently switch to bore.pub.
        # Set LOCALXPOSE_BORE_FALLBACK=true to opt back into the fallback.
        if [ -z "${LOCALXPOSE_BORE_FALLBACK:-}" ]; then
            export LOCALXPOSE_BORE_FALLBACK=false
        fi
        ;;
    bore | bore.pub)
        exec bash "$ROOT_DIR/scripts/bore-tunnel.sh"
        ;;
    serveo)
        exec bash "$ROOT_DIR/scripts/serveo-tunnel.sh"
        ;;
    pinggy | low-latency)
        default_pid_dir="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
        export PLAYER_REGION="${PLAYER_REGION:-bangladesh}"
        export LOW_LATENCY_ADDRESS_FILE="${LOW_LATENCY_ADDRESS_FILE:-$ROOT_DIR/SERVER_ADDRESS.txt}"
        export LOW_LATENCY_PID_FILE="${LOW_LATENCY_PID_FILE:-${TUNNEL_PID_FILE:-$default_pid_dir/ultron-tunnel.pid}}"
        exec bash "$ROOT_DIR/scripts/low-latency-tunnel.sh"
        ;;
    tunnl | tunnl.gg)
        # tunnl.gg only carries HTTP/WebSocket, so it cannot be the Minecraft
        # TCP endpoint. Prioritise the Asia-Pacific TCP relay for Bangladeshi
        # players, then publish a tunnl.gg status page alongside it.
        default_pid_dir="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
        export PLAYER_REGION="${PLAYER_REGION:-bangladesh}"
        export LOW_LATENCY_ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
        export LOW_LATENCY_PID_FILE="${TUNNEL_PID_FILE:-$default_pid_dir/ultron-tunnel.pid}"
        if bash "$ROOT_DIR/scripts/low-latency-tunnel.sh"; then
            bash "$ROOT_DIR/scripts/tunnl-tunnel.sh" \
                || echo "WARNING: the tunnl.gg status page could not be started; the Minecraft tunnel is still online." >&2
            exit 0
        fi
        echo "WARNING: the low-latency TCP relay failed; falling back to bore.pub for Minecraft." >&2
        exec bash "$ROOT_DIR/scripts/bore-tunnel.sh"
        ;;
    *)
        echo "ERROR: unsupported TUNNEL_PROVIDER '${PROVIDER}' (use auto, localxpose, bore, serveo, pinggy, tunnl or minekube)." >&2
        exit 2
        ;;
esac

TOOLS_DIR="$ROOT_DIR/.agent-work/tools"
LOGS_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
PID_FILE="${TUNNEL_PID_FILE:-$DEFAULT_PID_DIR/ultron-tunnel.pid}"
TUNNEL_LOG="$LOGS_DIR/localxpose-tunnel.log"

LOCAL_PORT="${MINECRAFT_PORT:-25565}"
REGION="${LOCALXPOSE_REGION:-ap}"
PORT_CANDIDATES="${LOCALXPOSE_PORT_CANDIDATES:-25565 25566 25567 25568 25569 25570}"
ACCESS_TOKEN="${LOCALXPOSE_ACCESS_TOKEN:-${ACCESS_TOKEN:-${LX_ACCESS_TOKEN:-}}}"
BORE_FALLBACK="${LOCALXPOSE_BORE_FALLBACK:-true}"

mkdir -p "$TOOLS_DIR" "$LOGS_DIR" "$(dirname -- "$PID_FILE")"

detect_arch() {
    case "$(uname -m)" in
        x86_64 | amd64) echo "amd64" ;;
        aarch64 | arm64) echo "arm64" ;;
        *)
            echo "ERROR: unsupported CPU architecture: $(uname -m)" >&2
            return 1
            ;;
    esac
}

strip_ansi() {
    sed -E $'s/\x1B\\[[0-9;]*[mK]//g'
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

write_address() {
    local address="$1"
    local host="${address%:*}"
    local port="${address##*:}"
    local tmp="$ADDRESS_FILE.tmp"

    {
        echo "# Ultron SMP public server address"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# Provider: LocalXpose TCP tunnel (region ${REGION:-default})"
        echo "# Add this server in Minecraft (host and port):"
        echo "$address"
        echo "#"
        echo "# Host: $host"
        echo "# Port: $port"
    } >"$tmp"
    mv "$tmp" "$ADDRESS_FILE"

    echo "$address"
}

fallback_to_bore() {
    local reason="$1"

    echo "WARNING: ${reason}" >&2
    if [ "$BORE_FALLBACK" = "true" ] && [ -x "$ROOT_DIR/scripts/bore-tunnel.sh" ]; then
        echo "Falling back to the stable bore.pub tunnel..." >&2
        exec bash "$ROOT_DIR/scripts/bore-tunnel.sh"
    fi

    echo "ERROR: ${reason}" >&2
    exit 1
}

ensure_loclx() {
    if [ -n "${LOCLX_BIN:-}" ]; then
        if [ ! -x "$LOCLX_BIN" ]; then
            echo "ERROR: LOCLX_BIN is not executable: $LOCLX_BIN" >&2
            return 1
        fi
        return 0
    fi

    local arch download_url download_dir
    arch="$(detect_arch)"
    LOCLX_BIN="$TOOLS_DIR/loclx-$arch"

    if [ -x "$LOCLX_BIN" ]; then
        return 0
    fi

    download_url="${LOCALXPOSE_DOWNLOAD_URL:-https://api.localxpose.io/api/downloads/loclx-linux-${arch}.zip}"
    download_dir="$TOOLS_DIR/.loclx-download-$$"
    mkdir -p "$download_dir"
    trap 'rm -rf "$download_dir"' EXIT

    echo "Downloading LocalXpose client for ${arch}..." >&2
    curl -fsSL --retry 3 --connect-timeout 15 "$download_url" \
        -o "$download_dir/loclx.zip"
    if command -v unzip >/dev/null 2>&1; then
        unzip -oq "$download_dir/loclx.zip" -d "$download_dir"
    else
        python3 -m zipfile -e "$download_dir/loclx.zip" "$download_dir"
    fi
    install -m 0755 "$download_dir/loclx" "$LOCLX_BIN"
    rm -rf "$download_dir"
    trap - EXIT

    return 0
}

extract_address() {
    local log_file="$1"
    local address=""

    # Prefer the public *.loclx.io endpoint over the local forwarded address.
    address="$(strip_ansi <"$log_file" \
        | grep -oE '[A-Za-z0-9][A-Za-z0-9.-]*\.loclx\.io:[0-9]{1,5}' \
        | head -n 1 || true)"
    if [ -z "$address" ] && [ "${LOCALXPOSE_ALLOW_LOCAL_ADDRESS:-false}" = "true" ]; then
        address="$(strip_ansi <"$log_file" \
            | grep -oE '(localhost|([0-9]{1,3}\.){3}[0-9]{1,3}):[0-9]{1,5}' \
            | head -n 1 || true)"
    fi

    printf '%s' "$address"
}

start_localxpose() {
    local requested_port="${1:-}"
    local -a args=(tunnel --raw-mode tcp --to "127.0.0.1:${LOCAL_PORT}")
    local attempt_log="$LOGS_DIR/localxpose-attempt.log"
    local address=""
    local loclx_pid=""

    if [ -n "$REGION" ] && [ "$REGION" != "auto" ]; then
        args+=(--region "$REGION")
    fi
    if [ -n "$requested_port" ]; then
        args+=(--port "$requested_port")
    fi

    {
        echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') attempt port=${requested_port:-random} region=${REGION:-default} ==="
    } >>"$TUNNEL_LOG"

    : >"$attempt_log"
    (
        export ACCESS_TOKEN
        exec "$LOCLX_BIN" "${args[@]}"
    ) >"$attempt_log" 2>&1 </dev/null &
    loclx_pid=$!

    for _ in $(seq 1 100); do
        if ! kill -0 "$loclx_pid" 2>/dev/null; then
            break
        fi

        address="$(extract_address "$attempt_log")"
        if [ -n "$address" ]; then
            echo "$loclx_pid" >"$PID_FILE"
            echo "Tunnel is live at $(write_address "$address")"
            strip_ansi <"$attempt_log" | tail -n 10 >>"$TUNNEL_LOG" || true
            return 0
        fi

        sleep 0.2
    done

    kill "$loclx_pid" 2>/dev/null || true
    wait "$loclx_pid" 2>/dev/null || true

    strip_ansi <"$attempt_log" | tail -n 20 >>"$TUNNEL_LOG" || true
    if grep -qiE 'PRO account only|authentication|unauthorized|invalid[^ ]* token' "$attempt_log"; then
        echo "ERROR: LocalXpose rejected the tunnel. The supplied access token may be invalid or belong to a FREE account; LocalXpose TCP tunnels require a PRO account." >&2
        return 2
    fi

    return 1
}

if [ -z "$ACCESS_TOKEN" ]; then
    fallback_to_bore "LOCALXPOSE_ACCESS_TOKEN is not set."
fi

if ! ensure_loclx; then
    fallback_to_bore "The LocalXpose client could not be installed."
fi

stop_existing_tunnel
: >"$TUNNEL_LOG"

fatal=0
for port in $PORT_CANDIDATES; do
    if start_localxpose "$port"; then
        exit 0
    else
        status=$?
    fi
    if [ "$status" -eq 2 ]; then
        fatal=1
        break
    fi
    echo "LocalXpose port ${port} is unavailable; trying the next one..." >&2
done

if [ "$fatal" -eq 0 ]; then
    if start_localxpose ""; then
        exit 0
    else
        status=$?
    fi
    if [ "$status" -eq 2 ]; then
        fatal=1
    fi
fi

fallback_to_bore "Could not establish a LocalXpose TCP tunnel."
