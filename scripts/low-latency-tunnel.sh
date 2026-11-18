#!/usr/bin/env bash
# Expose the local Minecraft port through Pinggy's free TCP relay.
#
# Pinggy has relays in Asia-Pacific (Singapore), the US and Europe. By
# default the script measures the runner-side latency to each relay and adds
# an approximate South Asian player-side cost, then picks the lowest total.
# This avoids sending players in Bangladesh through the public bore.pub relay
# in New Jersey (US East) when a closer route is available.
#
# Free Pinggy tunnels expire after about 60 minutes, so this script runs a
# supervisor that reconnects before the expiry and rewrites
# SERVER_ADDRESS_LOW_LATENCY.txt with the new address. The stable bore.pub
# address in SERVER_ADDRESS.txt remains available as a fallback.
#
# Optional environment variables:
#   MINECRAFT_PORT            Local Minecraft port (default: 25565)
#   PINGGY_SERVER             Relay host, or "auto" to measure and pick the
#                             best relay for a South Asian player
#                             (default: auto)
#   PINGGY_PORT               Relay SSH port (default: 443)
#   PINGGY_USER               Relay user (default: tcp)
#   PINGGY_RECONNECT_SECONDS  Reconnect before Pinggy's 60 minute limit
#                             (default: 3300)
#   PINGGY_KNOWN_HOSTS        known_hosts file used for the relay
#   PINGGY_SSH_BIN            SSH executable (default: ssh; used by tests)
#   PLAYER_REGION             "bangladesh"/"bd" gives the Asia-Pacific relay a
#                             preference margin, "auto" picks the lowest total
#                             (default: auto)
#   BANGLADESH_RELAY_MARGIN_MS Margin used for the Bangladesh preference
#                             (default: 75)
#   LOW_LATENCY_ADDRESS_FILE  Address output file
#                             (default: <repo>/SERVER_ADDRESS_LOW_LATENCY.txt)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.agent-work/logs"
ADDRESS_FILE="${LOW_LATENCY_ADDRESS_FILE:-$ROOT_DIR/SERVER_ADDRESS_LOW_LATENCY.txt}"
DEFAULT_PID_DIR="${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}"
PID_FILE="${LOW_LATENCY_PID_FILE:-$DEFAULT_PID_DIR/ultron-low-latency-tunnel.pid}"
TUNNEL_LOG="$LOG_DIR/pinggy-tunnel.log"

LOCAL_PORT="${MINECRAFT_PORT:-25565}"
PINGGY_SERVER="${PINGGY_SERVER:-auto}"
RELAY_HOST=""
RELAY_SSH_PORT="${PINGGY_PORT:-443}"
RELAY_USER="${PINGGY_USER:-tcp}"
RECONNECT_SECONDS="${PINGGY_RECONNECT_SECONDS:-3300}"
KNOWN_HOSTS="${PINGGY_KNOWN_HOSTS:-$ROOT_DIR/.agent-work/tmp/pinggy_known_hosts}"
SSH_BIN="${PINGGY_SSH_BIN:-ssh}"
PLAYER_REGION="${PLAYER_REGION:-auto}"
BANGLADESH_RELAY_MARGIN_MS="${BANGLADESH_RELAY_MARGIN_MS:-75}"

mkdir -p "$LOG_DIR" "$(dirname -- "$PID_FILE")" "$(dirname -- "$KNOWN_HOSTS")"

strip_ansi() {
    sed -E $'s/\x1B\\[[0-9;]*[mK]//g'
}

measure_relay_latency_ms() {
    local relay="$1"

    python3 - "$relay" "$RELAY_SSH_PORT" <<'PY' 2>/dev/null || echo 9999
import socket
import sys
import time

relay = sys.argv[1]
port = int(sys.argv[2])
best = None
for _ in range(2):
    started = time.perf_counter()
    try:
        with socket.create_connection((relay, port), timeout=4):
            pass
    except OSError:
        continue
    elapsed = (time.perf_counter() - started) * 1000
    if best is None or elapsed < best:
        best = elapsed
print(int(best) if best is not None else 9999)
PY
}

select_relay() {
    if [ "$PINGGY_SERVER" != "auto" ]; then
        RELAY_HOST="$PINGGY_SERVER"
        return 0
    fi

    # Approximate one-way network cost from Bangladesh/South Asia to each
    # relay. The runner-side latency is measured live, so the choice still
    # adapts when GitHub places the job in a different region.
    local best_relay=""
    local best_score=99999
    local ap_relay=""
    local ap_score=99999
    local relay player_leg runner_leg score

    for relay in ap.free.pinggy.io us.free.pinggy.io eu.free.pinggy.io; do
        runner_leg="$(measure_relay_latency_ms "$relay")"
        case "$relay" in
            ap.*) player_leg=55 ;;
            us.*) player_leg=200 ;;
            eu.*) player_leg=150 ;;
            *) player_leg=150 ;;
        esac
        score=$((runner_leg + player_leg))
        echo "Relay candidate ${relay}: runner ${runner_leg}ms + player ~${player_leg}ms = ${score}ms" >&2
        if [ "$score" -lt "$best_score" ]; then
            best_score="$score"
            best_relay="$relay"
        fi
        if [ "$relay" = "ap.free.pinggy.io" ]; then
            ap_relay="$relay"
            ap_score="$score"
        fi
    done

    if [ -z "$best_relay" ] || [ "$best_score" -ge 99999 ]; then
        best_relay="us.free.pinggy.io"
    fi

    # Bangladesh/South Asia players get the Singapore relay unless another
    # route is clearly faster. This avoids switching them to a distant relay
    # for a small runner-side measurement difference.
    case "$(printf '%s' "$PLAYER_REGION" | tr '[:upper:]' '[:lower:]')" in
        bangladesh | bd | south-asia | southasia)
            if [ -n "$ap_relay" ] && [ "$ap_score" -lt 99999 ] \
                && [ "$ap_score" -le $((best_score + BANGLADESH_RELAY_MARGIN_MS)) ]; then
                best_relay="$ap_relay"
                best_score="$ap_score"
                echo "Prioritising the Asia-Pacific relay for Bangladesh/South Asia players." >&2
            fi
            ;;
    esac

    RELAY_HOST="$best_relay"
    echo "Selected low-latency relay: ${RELAY_HOST} (estimated ${best_score}ms)" >&2
}

write_address() {
    local address="$1"
    local tmp="$ADDRESS_FILE.tmp"

    {
        echo "# Ultron SMP low-latency public server address"
        echo "# Updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
        echo "# Relay: Pinggy ${RELAY_HOST} (free relay, reconnects about once an hour)"
        if [ "$ADDRESS_FILE" != "$ROOT_DIR/SERVER_ADDRESS.txt" ]; then
            echo "# Stable fallback: see SERVER_ADDRESS.txt"
        fi
        echo "# Add this server in Minecraft (host and port):"
        echo "$address"
    } >"$tmp"
    mv "$tmp" "$ADDRESS_FILE"

    echo "$address"
}

run_tunnel_once() {
    local attempt_log="$LOG_DIR/pinggy-attempt.log"
    local ssh_pid=""

    {
        echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') connecting to ${RELAY_HOST} ==="
    } >>"$TUNNEL_LOG"

    # Pinggy's free relay uses password authentication with an empty
    # password. BatchMode would refuse that, so keep it disabled and feed
    # /dev/null to avoid waiting for terminal input.
    local -a ssh_args=(
        -T
        -o BatchMode=no
        -o ConnectTimeout=20
        -o ExitOnForwardFailure=yes
        -o NumberOfPasswordPrompts=1
        -o PasswordAuthentication=yes
        -o PreferredAuthentications=password
        -o PubkeyAuthentication=no
        -o KbdInteractiveAuthentication=no
        -o ServerAliveInterval=15
        -o ServerAliveCountMax=3
        -o StrictHostKeyChecking=accept-new
        -o UserKnownHostsFile="$KNOWN_HOSTS"
        -p "$RELAY_SSH_PORT"
        -R "0:localhost:${LOCAL_PORT}"
    )

    if [ "$RECONNECT_SECONDS" -gt 0 ] 2>/dev/null; then
        timeout --signal=TERM "$RECONNECT_SECONDS" \
            "$SSH_BIN" "${ssh_args[@]}" "${RELAY_USER}@${RELAY_HOST}" \
            >"$attempt_log" 2>&1 </dev/null &
    else
        "$SSH_BIN" "${ssh_args[@]}" "${RELAY_USER}@${RELAY_HOST}" \
            >"$attempt_log" 2>&1 </dev/null &
    fi
    ssh_pid=$!
    ACTIVE_SSH_PID="$ssh_pid"

    local address=""
    for _ in $(seq 1 60); do
        if ! kill -0 "$ssh_pid" 2>/dev/null; then
            break
        fi

        address="$(strip_ansi <"$attempt_log" \
            | grep -oE 'tcp://[^[:space:]]+' \
            | head -n 1 \
            | sed 's#^tcp://##' || true)"
        if [ -n "$address" ]; then
            echo "Low-latency tunnel is live at $(write_address "$address")"
            strip_ansi <"$attempt_log" | tail -n 5 >>"$TUNNEL_LOG" || true
            break
        fi

        sleep 0.5
    done

    if [ -z "$address" ]; then
        strip_ansi <"$attempt_log" | tail -n 20 >>"$TUNNEL_LOG" || true
        echo "WARNING: could not establish a Pinggy tunnel on this attempt." >&2
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

if [ "${1:-}" = "--supervise" ]; then
    select_relay
    supervise
    exit 0
fi

select_relay
export PINGGY_SERVER="$RELAY_HOST"

if ! command -v "$SSH_BIN" >/dev/null 2>&1; then
    echo "ERROR: '$SSH_BIN' is required for the low-latency tunnel." >&2
    exit 1
fi

stop_existing_tunnel
rm -f "$ADDRESS_FILE"

: >"$TUNNEL_LOG"
nohup "$0" --supervise >/dev/null 2>&1 </dev/null &
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
            echo "Low-latency tunnel is live at ${address} (pid ${supervisor_pid})"
            exit 0
        fi
    fi

    sleep 1
done

kill "$supervisor_pid" 2>/dev/null || true
wait "$supervisor_pid" 2>/dev/null || true
rm -f "$PID_FILE"
echo "ERROR: could not establish a Pinggy low-latency tunnel." >&2
strip_ansi <"$TUNNEL_LOG" | tail -n 30 >&2 || true
exit 1
