#!/usr/bin/env bash
# Launch the Fabric server with the Android/Termux fixes applied.
#
#  * native/libjnidispatch.so is the bionic (Android) JNA native library.
#    Termux's OpenJDK reports "Linux", so JNA otherwise tries the glibc build
#    and fails with "libc.so.6 not found".
#  * The OSHI library in .fabric/server/26.3-server.jar is patched to stay
#    quiet about Android's unreadable /proc and /etc files.
#  * log4j2.xml colors console output (INFO green, WARN yellow, ERROR red).
#  * --sun-misc-unsafe-memory-access silences the JDK 25 warning from JOML.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
cd "$SCRIPT_DIR"

exec java \
  --sun-misc-unsafe-memory-access=allow \
  -Dlog4j.configurationFile="$SCRIPT_DIR/log4j2.xml" \
  -Djna.boot.library.path="$SCRIPT_DIR/native" \
  -Doshi.os.linux.allowudev=false \
  -jar loader.jar "$@"
