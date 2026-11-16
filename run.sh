#!/usr/bin/env bash
# Launch the Fabric server.
#
#  * native/libjnidispatch.so is the bionic (Android) JNA native library.
#    It is only used on Termux/Android. On regular Linux/macOS the bundled
#    x86_64/glibc JNA library must be allowed to load instead.
#  * log4j2.xml colors console output (INFO green, WARN yellow, ERROR red).
#  * --sun-misc-unsafe-memory-access silences the JDK 25 warning from JOML.
#
# Optional environment variables:
#   JAVA_BIN     Java executable to use (default: java)
#   JAVA_OPTS    Extra JVM options, split on whitespace
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAVA_BIN="${JAVA_BIN:-java}"

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    echo "ERROR: '$JAVA_BIN' was not found. Install Java 25 or set JAVA_BIN." >&2
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/loader.jar" ]; then
    echo "ERROR: loader.jar is missing from $SCRIPT_DIR" >&2
    exit 1
fi

JAVA_ARGS=(
    --sun-misc-unsafe-memory-access=allow
    -Doshi.os.linux.allowudev=false
)

if [ -f "$SCRIPT_DIR/log4j2.xml" ]; then
    JAVA_ARGS+=("-Dlog4j.configurationFile=$SCRIPT_DIR/log4j2.xml")
fi

# The checked-in JNA library is built for Android/bionic. Loading it on a
# normal Linux/glibc host makes JNA fail, so only point at it on Termux.
if [ -n "${TERMUX_VERSION:-}" ] \
    || [ -d "/data/data/com.termux/files/usr" ] \
    || [ "$(uname -o 2>/dev/null || true)" = "Android" ]; then
    if [ -f "$SCRIPT_DIR/native/libjnidispatch.so" ]; then
        JAVA_ARGS+=("-Djna.boot.library.path=$SCRIPT_DIR/native")
    fi
fi

if [ -n "${JAVA_OPTS:-}" ]; then
    # shellcheck disable=SC2206
    EXTRA_JAVA_ARGS=($JAVA_OPTS)
    JAVA_ARGS+=("${EXTRA_JAVA_ARGS[@]}")
fi

exec "$JAVA_BIN" "${JAVA_ARGS[@]}" -jar "$SCRIPT_DIR/loader.jar" "$@"
