#!/usr/bin/env bash
# Build the network optimizer and verify that a disposable server loads it
# without mixin failures.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$ROOT_DIR/.agent-work/tmp/network-optimizer-test"
STATE_DIR="$ROOT_DIR/.agent-work/tmp/network-optimizer-state"
LOG_FILE="$ROOT_DIR/.agent-work/logs/network-optimizer-test.log"
TEST_PORT="${TEST_PORT:-25569}"
CONTROL="$ROOT_DIR/scripts/server-control.sh"
JAR="$ROOT_DIR/mods/ultron-network-optimizer-1.0.0.jar"
SERVER_JAR="$ROOT_DIR/versions/26.3/server-26.3.jar"

strip_ansi() { sed -E $'s/\x1B\\[[0-9;]*[mK]//g'; }

fail() {
    echo "$1"
    tail -n 150 "$LOG_FILE" 2>/dev/null || true
    exit 1
}

mkdir -p "$(dirname -- "$LOG_FILE")"
bash "$ROOT_DIR/network-optimizer/build.sh" >/dev/null

for entry in fabric.mod.json ultron-network-optimizer.mixins.json \
    com/ultron/network/UltronNetworkOptimizer.class \
    com/ultron/network/mixin/ServerCommonPacketListenerImplMixin.class; do
    unzip -Z1 "$JAR" | grep -qx "$entry" || fail "Missing $entry in $JAR"
done

unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | grep -qx 'Fabric-Mapping-Namespace: official' \
    || fail "The mod manifest does not declare the official mapping namespace."

javap -classpath "$SERVER_JAR" net.minecraft.server.network.ServerCommonPacketListenerImpl \
    | grep -Fq 'void send(net.minecraft.network.protocol.Packet<?>)' \
    || fail "ServerCommonPacketListenerImpl.send(Packet) was not found in the server jar."
javap -classpath "$SERVER_JAR" net.minecraft.server.network.ServerCommonPacketListenerImpl \
    | grep -Fq 'void send(net.minecraft.network.protocol.Packet<?>, io.netty.channel.ChannelFutureListener)' \
    || fail "ServerCommonPacketListenerImpl.send(Packet, ChannelFutureListener) was not found in the server jar."
javap -classpath "$SERVER_JAR" net.minecraft.server.MinecraftServer \
    | grep -Fq 'void tickServer(java.util.function.BooleanSupplier)' \
    || fail "MinecraftServer.tickServer(BooleanSupplier) was not found in the server jar."

rm -rf "$TEST_DIR" "$STATE_DIR"
mkdir -p "$TEST_DIR" "$STATE_DIR"

ln -s ../../../libraries "$TEST_DIR/libraries"
ln -s ../../../versions "$TEST_DIR/versions"
ln -s ../../../mods "$TEST_DIR/mods"
ln -s ../../../.fabric "$TEST_DIR/.fabric"
ln -s ../../../native "$TEST_DIR/native"
cp "$ROOT_DIR/loader.jar" "$ROOT_DIR/log4j2.xml" "$ROOT_DIR/eula.txt" \
    "$ROOT_DIR/server.properties" "$ROOT_DIR/run.sh" "$TEST_DIR/"
cp -a "$ROOT_DIR/world" "$TEST_DIR/world"
cp -a "$ROOT_DIR/config" "$TEST_DIR/config"
sed -i "s/^server-port=.*/server-port=${TEST_PORT}/" "$TEST_DIR/server.properties"
chmod +x "$TEST_DIR/run.sh"

export MC_STATE_DIR="$STATE_DIR"
export MC_LOG_FILE="$LOG_FILE"
export MC_RUN_SCRIPT="$TEST_DIR/run.sh"

cleanup() {
    bash "$CONTROL" stop 30 >/dev/null 2>&1 || true
}
trap cleanup EXIT

bash "$CONTROL" start >/dev/null

ready=0
for _ in $(seq 1 120); do
    if (echo > "/dev/tcp/127.0.0.1/${TEST_PORT}") 2>/dev/null; then
        ready=1
        break
    fi
    if ! bash "$CONTROL" status >/dev/null 2>&1; then
        fail "Server exited while starting."
    fi
    sleep 1
done

if [ "$ready" -ne 1 ]; then
    fail "Server did not start within 120 seconds."
fi

sleep 3
bash "$CONTROL" stop 60 >/dev/null
strip_ansi <"$LOG_FILE" >"$STATE_DIR/console.log"

grep -q "Ultron Network Optimizer enabled" "$STATE_DIR/console.log" \
    || fail "The network optimizer did not initialize."

grep -q "particles=32.0m" "$STATE_DIR/console.log" \
    || fail "The network optimizer did not load its config values."

if grep -qE "Mixin apply failed|InjectionError|Critical injection failure|MixinApplyError" "$STATE_DIR/console.log"; then
    fail "A mixin failed to apply."
fi

echo "network optimizer test passed: mod builds, targets resolve, and the server loads it cleanly."
