#!/usr/bin/env bash
# Build the plugin and run its in-server integration self-test against Paper.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
TEST_DIR="${PAPER_TEST_DIR:-$REPO_ROOT/.agent-work/tmp/paper-selftest}"
PAPER_JAR="${PAPER_JAR:-$REPO_ROOT/.agent-work/tools/paper-26.3-255.jar}"
LOG="$TEST_DIR/paper-test.log"

PAPER_BUILD_DIR="${PAPER_BUILD_DIR:-$REPO_ROOT/.agent-work/tmp/paper-build}" \
PAPER_JAR="$PAPER_JAR" \
    bash "$SCRIPT_DIR/build.sh"

rm -rf "$TEST_DIR"
mkdir -p "$TEST_DIR/plugins" "$TEST_DIR/config/economy"
printf 'eula=true\n' > "$TEST_DIR/eula.txt"
cat > "$TEST_DIR/server.properties" <<'EOF'
server-port=25566
online-mode=false
level-name=world
max-players=5
view-distance=4
spawn-protection=0
sync-chunk-writes=false
EOF
cp "$REPO_ROOT"/config/economy/*.properties "$TEST_DIR/config/economy/"
cp "$REPO_ROOT/paper/plugins/Economy-1.0.0.jar" "$TEST_DIR/plugins/"

(
    cd "$TEST_DIR"
    {
        for _ in $(seq 1 120); do
            if grep -q 'Done (' "$LOG" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        sleep 2
        echo 'ecoselftest'
        sleep 10
        echo 'stop'
    } | timeout 180 java -Xms512M -Xmx1024M -Deconomy.selftest=true \
        -jar "$PAPER_JAR" --nogui > "$LOG" 2>&1 || true
)

if grep -q 'SELFTEST PASS' "$LOG"; then
    grep 'SELFTEST PASS' "$LOG"
    echo "Paper plugin integration test passed."
else
    echo "Paper plugin integration test failed." >&2
    grep -E 'SELFTEST|ERROR|Exception|Failed check' "$LOG" | tail -60 >&2 || true
    exit 1
fi
