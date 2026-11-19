#!/usr/bin/env bash
# Start and stop the Minecraft server through a controllable console pipe.
#
# A normal `nohup bash ./run.sh &` cannot receive console commands, so the
# previous workflow could only copy world files that the server still had in
# memory. That is why changes made during a run disappeared when the run was
# cancelled. This script gives the server a named-pipe (FIFO) stdin so the
# workflow can send `save-all flush` and `stop` before backing the world up.
#
# Commands:
#   server-control.sh start            Start the server (idempotent)
#   server-control.sh command "text"   Send one console command
#   server-control.sh save             Flush all chunks to disk
#   server-control.sh stop [seconds]   Graceful stop, then terminate if needed
#   server-control.sh status           Exit 0 when the server process is alive
#
# Optional environment variables:
#   MC_STATE_DIR     Directory for PID/FIFO files (default: RUNNER_TEMP)
#   MC_CONSOLE_FIFO  Console pipe path
#   MC_PID_FILE      Server PID file
#   MC_LOG_FILE      Server console log (default: <repo>/server.log)
#   MC_RUN_SCRIPT    Server launch script (default: <repo>/run.sh)
#   JAVA_BIN         Forwarded to run.sh
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${MC_STATE_DIR:-${RUNNER_TEMP:-$ROOT_DIR/.agent-work/tmp}}"
CONSOLE_FIFO="${MC_CONSOLE_FIFO:-$STATE_DIR/ultron-console.fifo}"
PID_FILE="${MC_PID_FILE:-$STATE_DIR/minecraft-server.pid}"
HOLDER_PID_FILE="${MC_HOLDER_PID_FILE:-$STATE_DIR/ultron-console-holder.pid}"
LOG_FILE="${MC_LOG_FILE:-$ROOT_DIR/server.log}"
RUN_SCRIPT="${MC_RUN_SCRIPT:-$ROOT_DIR/run.sh}"
case "$RUN_SCRIPT" in
    /*) ;;
    *) RUN_SCRIPT="$ROOT_DIR/$RUN_SCRIPT" ;;
esac

mkdir -p "$STATE_DIR" "$(dirname -- "$CONSOLE_FIFO")" "$(dirname -- "$LOG_FILE")"

server_pid() {
    cat "$PID_FILE" 2>/dev/null || true
}

is_running() {
    local pid
    pid="$(server_pid)"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

write_console() {
    local line="$1"
    # A FIFO opened for writing blocks when nobody is reading. The timeout
    # keeps a dead server from hanging the workflow backup step forever.
    # shellcheck disable=SC2016
    timeout 5 bash -c 'printf "%s\n" "$1" > "$2"' _ "$line" "$CONSOLE_FIFO"
}

start_server() {
    if is_running; then
        echo "Minecraft server is already running (pid $(server_pid))."
        return 0
    fi

    rm -f "$CONSOLE_FIFO" "$PID_FILE" "$HOLDER_PID_FILE"
    mkfifo "$CONSOLE_FIFO"

    # Hold the write end open so the server's stdin never sees EOF and exits.
    # shellcheck disable=SC2016
    nohup bash -c 'exec 3>"$1"; while :; do sleep 3600; done' \
        _ "$CONSOLE_FIFO" >/dev/null 2>&1 </dev/null &
    echo $! >"$HOLDER_PID_FILE"

    nohup bash "$RUN_SCRIPT" >"$LOG_FILE" 2>&1 <"$CONSOLE_FIFO" &
    echo $! >"$PID_FILE"

    echo "Started Minecraft server (pid $(server_pid)); console pipe ${CONSOLE_FIFO}."
}

stop_server() {
    local wait_seconds="${1:-120}"

    if ! is_running; then
        echo "Minecraft server is not running."
    else
        local pid
        pid="$(server_pid)"

        # Flush first so a failed/blocked stop still leaves fresh data on disk.
        write_console "save-all flush" 2>/dev/null \
            || echo "WARNING: could not send save-all flush." >&2
        sleep 2
        write_console "stop" 2>/dev/null \
            || echo "WARNING: could not send stop; terminating the process." >&2

        local waited=0
        while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$wait_seconds" ]; do
            sleep 1
            waited=$((waited + 1))
        done

        if kill -0 "$pid" 2>/dev/null; then
            echo "Server did not stop within ${wait_seconds}s; sending SIGTERM." >&2
            kill "$pid" 2>/dev/null || true
            sleep 5
        fi

        if kill -0 "$pid" 2>/dev/null; then
            echo "Server is still running; sending SIGKILL." >&2
            kill -9 "$pid" 2>/dev/null || true
        fi

        echo "Minecraft server stopped."
    fi

    local holder_pid
    holder_pid="$(cat "$HOLDER_PID_FILE" 2>/dev/null || true)"
    if [ -n "$holder_pid" ] && kill -0 "$holder_pid" 2>/dev/null; then
        kill "$holder_pid" 2>/dev/null || true
    fi

    rm -f "$PID_FILE" "$HOLDER_PID_FILE" "$CONSOLE_FIFO"
}

case "${1:-}" in
    start)
        start_server
        ;;
    command)
        if [ -z "${2:-}" ]; then
            echo "ERROR: command requires console text." >&2
            exit 2
        fi
        if ! is_running; then
            echo "ERROR: Minecraft server is not running." >&2
            exit 1
        fi
        write_console "$2"
        ;;
    save)
        if ! is_running; then
            echo "ERROR: Minecraft server is not running." >&2
            exit 1
        fi
        write_console "save-all flush"
        ;;
    stop)
        stop_server "${2:-120}"
        ;;
    status)
        if is_running; then
            echo "Minecraft server is running (pid $(server_pid))."
        else
            echo "Minecraft server is not running."
            exit 1
        fi
        ;;
    *)
        echo "Usage: $0 {start|command <text>|save|stop [seconds]|status}" >&2
        exit 2
        ;;
esac
