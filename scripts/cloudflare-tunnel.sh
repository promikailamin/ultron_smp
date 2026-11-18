#!/usr/bin/env bash
# Expose the local Minecraft TCP port through a Cloudflare Tunnel.
#
# Two modes are supported:
#
#   named  Run a pre-created Cloudflare Tunnel with a tunnel token. The tunnel
#          needs a public hostname whose service points at
#          tcp://localhost:25565 (set it in the Zero Trust dashboard or in the
#          config file supplied through CLOUDFLARE_TUNNEL_CONFIG).
#   quick  Start an account-less quick tunnel (trycloudflare.com). No domain or
#          token is required, but the hostname changes on every run.
#
# Cloudflare TCP tunnels are not plain public TCP ports: the client must use
# the Modflared Minecraft mod or run cloudflared access on their own machine.
# See README.md for the player instructions. The script starts a local
# cloudflared access proxy so the workflow can verify the route end-to-end.
#
# It writes the resulting hostname to SERVER_ADDRESS.txt and leaves the tunnel
# running in the background. The PID is stored in:
#   ${TUNNEL_PID_FILE:-${RUNNER_TEMP:-<repo>/.agent-work/tmp}/ultron-tunnel.pid}
#
# Optional environment variables:
#   MINECRAFT_PORT                 Local Minecraft port (default: 25565)
#   CLOUDFLARE_TUNNEL_TOKEN        Token for a named tunnel (repository secret)
#   CLOUDFLARE_TUNNEL_HOSTNAME     Public hostname of the named tunnel
#   CLOUDFLARE_TUNNEL_CONFIG       Optional cloudflared config file
#   CLOUDFLARE_MODE                auto, named, or quick (default: auto)
#   CLOUDFLARE_VERSION             cloudflared release to download
#                                  (default: 2026.11.1, or "latest")
#   CLOUDFLARE_BIN                 Use an existing cloudflared binary
#   CLOUDFLARE_DOWNLOAD_URL        Override the cloudflared download URL
#   CLOUDFLARE_ACCESS_PORT         Local port for the access proxy
#                                  (default: 25571, or the next free port)
#   CLOUDFLARE_ACCESS_CLIENT_ID    Access service token ID for the proxy
#   CLOUDFLARE_ACCESS_CLIENT_SECRET
#                                  Access service token secret for the proxy
#   CLOUDFLARE_BORE_FALLBACK       Fall back to bore.pub on failure
#                                  (default: true)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.agent-work/tools"
LOGS_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="$ROOT_DIR/SERVER_ADDRESS.txt"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
PID_FILE="${TUNNEL_PID_FILE:-$DEFAULT_PID_DIR/ultron-tunnel.pid}"
ACCESS_PID_FILE="${PID_FILE}.access"
VERIFY_FILE="$DEFAULT_PID_DIR/ultron-tunnel-verify-address"
TUNNEL_LOG="$LOGS_DIR/cloudflare-tunnel.log"

LOCAL_PORT="${MINECRAFT_PORT:-25565}"
TUNNEL_TOKEN="${CLOUDFLARE_TUNNEL_TOKEN:-${CLOUDFLARE_TOKEN:-${CF_TUNNEL_TOKEN:-}}}"
TUNNEL_HOSTNAME="${CLOUDFLARE_TUNNEL_HOSTNAME:-${CLOUDFLARE_HOSTNAME:-${CLOUDFLARE_HOST:-}}}"
TUNNEL_CONFIG="${CLOUDFLARE_TUNNEL_CONFIG:-${CLOUDFLARE_CONFIG:-}}"
MODE="$(printf '%s' "${CLOUDFLARE_MODE:-auto}" | tr '[:upper:]' '[:lower:]')"
VERSION="${CLOUDFLARE_VERSION:-2026.11.1}"
BORE_FALLBACK="${CLOUDFLARE_BORE_FALLBACK:-true}"
ACCESS_PORT="${CLOUDFLARE_ACCESS_PORT:-25571}"
ACCESS_CLIENT_ID="${CLOUDFLARE_ACCESS_CLIENT_ID:-${CLOUDFLARE_ACCESS_SERVICE_TOKEN_ID:-}}"
ACCESS_CLIENT_SECRET="${CLOUDFLARE_ACCESS_CLIENT_SECRET:-${CLOUDFLARE_ACCESS_SERVICE_TOKEN_SECRET:-}}"
DNS_WAIT_SECONDS="${CLOUDFLARE_DNS_WAIT_SECONDS:-60}"

mkdir -p "$TOOLS_DIR" "$LOGS_DIR" "$(dirname -- "$PID_FILE")" "$(dirname -- "$VERIFY_FILE")"

detect_arch() {
    case "$(uname -m)" in
        x86_64 | amd64) echo "amd64" ;;
        aarch64 | arm64) echo "arm64" ;;
        armv7l | armv7) echo "arm" ;;
        i386 | i686) echo "386" ;;
        *)
            echo "ERROR: unsupported CPU architecture: $(uname -m)" >&2
            return 1
            ;;
    esac
}

strip_ansi() {
    sed -E $'s/\x1B\\[[0-9;]*[mK]//g'
}

ensure_cloudflared() {
    if [ -n "${CLOUDFLARE_BIN:-}" ]; then
        if [ ! -x "$CLOUDFLARE_BIN" ]; then
            echo "ERROR: CLOUDFLARE_BIN is not executable: $CLOUDFLARE_BIN" >&2
            return 1
        fi
        return 0
    fi

    local arch download_url download_dir
    arch="$(detect_arch)"
    CLOUDFLARE_BIN="$TOOLS_DIR/cloudflared-linux-$arch"

    if [ -x "$CLOUDFLARE_BIN" ]; then
        return 0
    fi

    if [ -n "${CLOUDFLARE_DOWNLOAD_URL:-}" ]; then
        download_url="$CLOUDFLARE_DOWNLOAD_URL"
    elif [ "$VERSION" = "latest" ]; then
        download_url="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${arch}"
    else
        download_url="https://github.com/cloudflare/cloudflared/releases/download/${VERSION}/cloudflared-linux-${arch}"
    fi

    download_dir="$TOOLS_DIR/.cloudflared-download-$$"
    mkdir -p "$download_dir"
    trap 'rm -rf "$download_dir"' EXIT

    echo "Downloading cloudflared ${VERSION} for ${arch}..." >&2
    curl -fL --retry 3 --connect-timeout 15 "$download_url" \
        -o "$download_dir/cloudflared"
    install -m 0755 "$download_dir/cloudflared" "$CLOUDFLARE_BIN"
    rm -rf "$download_dir"
    trap - EXIT

    return 0
}

kill_pid_file() {
    local pid_file="$1"
    local pid

    if [ ! -f "$pid_file" ]; then
        return 0
    fi

    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.2
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file"
}

stop_existing_tunnel() {
    kill_pid_file "$ACCESS_PID_FILE"
    kill_pid_file "$PID_FILE"
    rm -f "$VERIFY_FILE"
}

write_address() {
    local host="$1"
    local address="${host}:25565"
    local tmp="$ADDRESS_FILE.tmp"

    {
        echo "# Ultron SMP public server address"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# Provider: Cloudflare Tunnel (${ACTIVE_MODE})"
        echo "# Cloudflare TCP tunnels require the Modflared mod or a local proxy:"
        echo "#   cloudflared access tcp --hostname ${host} --url localhost:25565"
        echo "# Then join localhost:25565 in Minecraft."
        echo "# Add this server in Minecraft (host and port):"
        echo "$address"
        echo "#"
        echo "# Host: $host"
        echo "# Port: 25565"
    } >"$tmp"
    mv "$tmp" "$ADDRESS_FILE"

    echo "$address"
}

guess_hostname_from_config() {
    if [ -z "$TUNNEL_CONFIG" ] || [ ! -f "$TUNNEL_CONFIG" ]; then
        return 0
    fi

    strip_ansi <"$TUNNEL_CONFIG" \
        | sed -n 's/^[[:space:]]*-[[:space:]]*hostname:[[:space:]]*//p' \
        | head -n 1 \
        | tr -d '"' \
        | tr -d "'"
}

extract_quick_host() {
    local log_file="$1"

    strip_ansi <"$log_file" \
        | grep -oE '[A-Za-z0-9][A-Za-z0-9.-]*\.trycloudflare\.com' \
        | head -n 1 || true
}

wait_for_dns() {
    local host="$1"
    local attempts

    if [ "$DNS_WAIT_SECONDS" -le 0 ] 2>/dev/null; then
        return 0
    fi

    # Quick Tunnel hostnames can take a moment to appear in DNS. Starting the
    # access proxy before that makes cloudflared fail with "no such host".
    attempts=$((DNS_WAIT_SECONDS * 2))
    for _ in $(seq 1 "$attempts"); do
        if getent hosts "$host" >/dev/null 2>&1 \
            || python3 -c 'import socket,sys; socket.gethostbyname(sys.argv[1])' "$host" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.5
    done

    return 1
}

port_is_free() {
    python3 - "$1" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(("127.0.0.1", port))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
}

start_access_proxy() {
    local host="$1"
    local port="$ACCESS_PORT"
    local access_log="$LOGS_DIR/cloudflare-access.log"
    local access_pid=""
    local -a args=(access tcp --hostname "$host" --url "127.0.0.1:${port}")

    for _ in $(seq 1 20); do
        if port_is_free "$port"; then
            break
        fi
        port=$((port + 1))
    done

    if [ -n "$ACCESS_CLIENT_ID" ] && [ -n "$ACCESS_CLIENT_SECRET" ]; then
        args+=(--service-token-id "$ACCESS_CLIENT_ID" \
            --service-token-secret "$ACCESS_CLIENT_SECRET")
    fi

    : >"$access_log"
    "$CLOUDFLARE_BIN" "${args[@]}" >"$access_log" 2>&1 </dev/null &
    access_pid=$!

    for _ in $(seq 1 100); do
        if ! kill -0 "$access_pid" 2>/dev/null; then
            break
        fi
        if strip_ansi <"$access_log" | grep -qiE 'Start Websocket listener|listener'; then
            echo "$access_pid" >"$ACCESS_PID_FILE"
            printf '%s\n' "127.0.0.1:${port}" >"$VERIFY_FILE"
            strip_ansi <"$access_log" | tail -n 5 >>"$TUNNEL_LOG" || true
            return 0
        fi
        sleep 0.2
    done

    if kill -0 "$access_pid" 2>/dev/null; then
        # The listener may not log a line on every cloudflared version.
        echo "$access_pid" >"$ACCESS_PID_FILE"
        printf '%s\n' "127.0.0.1:${port}" >"$VERIFY_FILE"
        return 0
    fi

    strip_ansi <"$access_log" | tail -n 20 >>"$TUNNEL_LOG" || true
    return 1
}

start_named_tunnel() {
    local attempt_log="$LOGS_DIR/cloudflare-named-attempt.log"
    local cf_pid=""
    local -a args=(tunnel --no-autoupdate run --token "$TUNNEL_TOKEN")

    if [ -n "$TUNNEL_CONFIG" ]; then
        args=(tunnel --no-autoupdate --config "$TUNNEL_CONFIG" run --token "$TUNNEL_TOKEN")
    fi

    {
        echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') named tunnel host=${TUNNEL_HOSTNAME} ==="
    } >>"$TUNNEL_LOG"

    : >"$attempt_log"
    "$CLOUDFLARE_BIN" "${args[@]}" >"$attempt_log" 2>&1 </dev/null &
    cf_pid=$!

    for _ in $(seq 1 300); do
        if ! kill -0 "$cf_pid" 2>/dev/null; then
            break
        fi
        if strip_ansi <"$attempt_log" \
            | grep -qiE 'Registered tunnel connection|Connection .* registered'; then
            echo "$cf_pid" >"$PID_FILE"
            ACTIVE_MODE="named"
            echo "Tunnel is live at $(write_address "$TUNNEL_HOSTNAME")"
            strip_ansi <"$attempt_log" | tail -n 10 >>"$TUNNEL_LOG" || true
            wait_for_dns "$TUNNEL_HOSTNAME" \
                || echo "WARNING: ${TUNNEL_HOSTNAME} did not resolve in DNS yet." >&2
            start_access_proxy "$TUNNEL_HOSTNAME" || \
                echo "WARNING: the local Cloudflare access proxy could not be started." >&2
            return 0
        fi
        sleep 0.2
    done

    kill "$cf_pid" 2>/dev/null || true
    wait "$cf_pid" 2>/dev/null || true
    strip_ansi <"$attempt_log" | tail -n 30 >>"$TUNNEL_LOG" || true
    return 1
}

start_quick_tunnel() {
    local attempt_log="$LOGS_DIR/cloudflare-quick-attempt.log"
    local cf_pid=""
    local host=""

    {
        echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') quick tunnel ==="
    } >>"$TUNNEL_LOG"

    : >"$attempt_log"
    "$CLOUDFLARE_BIN" tunnel --no-autoupdate \
        --url "tcp://127.0.0.1:${LOCAL_PORT}" \
        >"$attempt_log" 2>&1 </dev/null &
    cf_pid=$!

    for _ in $(seq 1 300); do
        if ! kill -0 "$cf_pid" 2>/dev/null; then
            break
        fi

        host="$(extract_quick_host "$attempt_log")"
        if [ -n "$host" ]; then
            echo "$cf_pid" >"$PID_FILE"
            ACTIVE_MODE="quick"
            echo "Tunnel is live at $(write_address "$host")"
            strip_ansi <"$attempt_log" | tail -n 10 >>"$TUNNEL_LOG" || true
            wait_for_dns "$host" \
                || echo "WARNING: ${host} did not resolve in DNS yet." >&2
            start_access_proxy "$host" || \
                echo "WARNING: the local Cloudflare access proxy could not be started." >&2
            return 0
        fi

        sleep 0.2
    done

    kill "$cf_pid" 2>/dev/null || true
    wait "$cf_pid" 2>/dev/null || true
    strip_ansi <"$attempt_log" | tail -n 30 >>"$TUNNEL_LOG" || true
    return 1
}

fallback_to_bore() {
    local reason="$1"

    echo "WARNING: ${reason}" >&2
    rm -f "$VERIFY_FILE"
    if [ "$BORE_FALLBACK" = "true" ] && [ -x "$ROOT_DIR/scripts/bore-tunnel.sh" ]; then
        echo "Falling back to the stable bore.pub tunnel..." >&2
        exec bash "$ROOT_DIR/scripts/bore-tunnel.sh"
    fi

    echo "ERROR: ${reason}" >&2
    exit 1
}

if ! ensure_cloudflared; then
    fallback_to_bore "The cloudflared client could not be installed."
fi

stop_existing_tunnel
: >"$TUNNEL_LOG"

case "$MODE" in
    "" | auto | named) ;;
    quick) ;;
    *)
        echo "ERROR: unsupported CLOUDFLARE_MODE '${MODE}' (use auto, named or quick)." >&2
        exit 2
        ;;
esac

if [ -z "$TUNNEL_HOSTNAME" ]; then
    TUNNEL_HOSTNAME="$(guess_hostname_from_config)"
fi

named_ready=0
if [ "$MODE" != "quick" ] && [ -n "$TUNNEL_TOKEN" ]; then
    if [ -z "$TUNNEL_HOSTNAME" ]; then
        echo "WARNING: CLOUDFLARE_TUNNEL_TOKEN is set but CLOUDFLARE_TUNNEL_HOSTNAME is missing; using a quick tunnel so an address can be published." >&2
    elif start_named_tunnel; then
        named_ready=1
    else
        echo "WARNING: the named Cloudflare Tunnel could not be started; trying a quick tunnel..." >&2
    fi
fi

if [ "$named_ready" -eq 0 ]; then
    if [ "$MODE" = "named" ] && [ -z "$TUNNEL_TOKEN" ]; then
        echo "ERROR: CLOUDFLARE_MODE=named needs CLOUDFLARE_TUNNEL_TOKEN." >&2
        fallback_to_bore "No Cloudflare Tunnel token was configured."
    fi

    if ! start_quick_tunnel; then
        fallback_to_bore "Could not establish a Cloudflare Tunnel."
    fi
fi
