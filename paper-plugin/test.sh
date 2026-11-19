#!/usr/bin/env bash
# Build the Heads plugin and verify it loads on a real Paper server.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
TEST_DIR="${PAPER_TEST_DIR:-$REPO_ROOT/.agent-work/tmp/heads-selftest}"
LOG="$TEST_DIR/paper-test.log"
mkdir -p "$REPO_ROOT/.agent-work/logs"

bash "$SCRIPT_DIR/build.sh" | tee "$REPO_ROOT/.agent-work/logs/heads-build.log"

if [ -n "${PAPER_JAR:-}" ]; then
    paper_jar="$PAPER_JAR"
else
    paper_jar="$(find "$REPO_ROOT/.agent-work/tools" -maxdepth 1 -name 'paper-*.jar' \
        -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
fi
if [ -z "$paper_jar" ] || [ ! -s "$paper_jar" ]; then
    echo "ERROR: no Paper jar found; run paper-plugin/build.sh first." >&2
    exit 1
fi

rm -rf "$TEST_DIR"
mkdir -p "$TEST_DIR/plugins"
printf 'eula=true\n' > "$TEST_DIR/eula.txt"
cat > "$TEST_DIR/server.properties" <<'PROPS'
server-port=25566
online-mode=false
level-name=world
max-players=5
view-distance=4
spawn-protection=0
sync-chunk-writes=false
PROPS
cp "$REPO_ROOT/plugins/Heads-1.0.0.jar" "$TEST_DIR/plugins/"

(
    cd "$TEST_DIR"
    {
        for _ in $(seq 1 180); do
            if grep -q 'Done (' "$LOG" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        sleep 2
        echo 'head list'
        sleep 1
        echo 'heads list'
        sleep 3
        echo 'stop'
    } | timeout 300 java -Xms512M -Xmx1024M -Dheads.selftest=true \
        -jar "$paper_jar" --nogui > "$LOG" 2>&1 || true
)

cp "$LOG" "$REPO_ROOT/.agent-work/logs/heads-selftest.log"

if grep -q 'HEADS SELFTEST PASS' "$LOG" && grep -q 'HEADS GUI SELFTEST PASS' "$LOG"; then
    grep -E 'Heads enabled|HEADS SELFTEST|HEADS GUI SELFTEST|Available mob heads' "$LOG" || true
    echo "Heads Paper integration test passed."
else
    echo "Heads Paper integration test failed." >&2
    grep -E 'Heads|HEADS|ERROR|Exception|Failed' "$LOG" | tail -80 >&2 || true
    exit 1
fi
