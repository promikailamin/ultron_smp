#!/usr/bin/env bash
# Static checks for the issue 22 Paper + Minekube setup.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

[ -f .github/workflows/opencode.yml ] || fail "opencode.yml is missing"
[ -f .github/workflows/run.yml ] || fail "run.yml is missing"
[ -f start.sh ] || fail "start.sh is missing"
[ -f scripts/setup-server.sh ] || fail "scripts/setup-server.sh is missing"
[ -f scripts/network-tune.sh ] || fail "scripts/network-tune.sh is missing"
[ -f scripts/tunnel-latency.py ] || fail "scripts/tunnel-latency.py is missing"
[ -f scripts/minekube-gate.sh ] || fail "scripts/minekube-gate.sh is missing"
[ -f scripts/twingate-connector.sh ] || fail "scripts/twingate-connector.sh is missing"
[ -f scripts/twingate-resource.py ] || fail "scripts/twingate-resource.py is missing"
[ -f scripts/twingate-admin.py ] || fail "scripts/twingate-admin.py is missing"
[ -f .github/workflows/twingate-admin.yml ] || fail "twingate-admin.yml is missing"
[ -f .github/workflows/twingate-resource.yml ] || fail "twingate-resource.yml is missing"
[ -f .github/workflows/twingate-verify.yml ] || fail "twingate-verify.yml is missing"
[ -f plugins/connect/config.yml ] || fail "Connect config is missing"
[ -f eula.txt ] || fail "eula.txt is missing"
grep -qi '^eula=true' eula.txt || fail "eula.txt is not accepted"

bash -n start.sh || fail "start.sh has a syntax error"
bash -n scripts/setup-server.sh || fail "setup-server.sh has a syntax error"
bash -n scripts/network-tune.sh || fail "network-tune.sh has a syntax error"
bash -n scripts/minekube-gate.sh || fail "minekube-gate.sh has a syntax error"
bash -n scripts/twingate-connector.sh || fail "twingate-connector.sh has a syntax error"
python3 -m py_compile scripts/tunnel-latency.py scripts/twingate-resource.py scripts/twingate-admin.py \
    || fail "a Python helper does not compile"

grep -q 'fill.papermc.io/v3/projects/paper' start.sh \
    || fail "start.sh does not resolve Paper from the fill API"
grep -q 'connect-spigot.jar' start.sh \
    || fail "start.sh does not install the Minekube Paper plugin"
grep -q '^endpoint: *mikailamin$' plugins/connect/config.yml \
    || fail "Connect endpoint is not mikailamin"
grep -q '^allow-offline-mode-players: *true$' plugins/connect/config.yml \
    || fail "Connect does not allow offline players"
grep -q 'MINEKUBE_CONNECTOR' start.sh \
    || fail "start.sh does not support the Gate connector option"
grep -q 'scripts/network-tune.sh' .github/workflows/run.yml \
    || fail "run.yml does not apply network tuning"
grep -q 'scripts/tunnel-latency.py' .github/workflows/run.yml \
    || fail "run.yml does not measure the tunnel path"
grep -q 'scripts/minekube-gate.sh' .github/workflows/run.yml \
    || fail "run.yml does not support the Gate connector"
grep -q 'scripts/twingate-connector.sh start' .github/workflows/run.yml \
    || fail "run.yml does not start the Twingate connector"
grep -q 'scripts/twingate-resource.py ensure' .github/workflows/run.yml \
    || fail "run.yml does not ensure the Twingate resource"
grep -q '^view-distance=5$' server.properties \
    || fail "server.properties does not use the low-ping view distance"
grep -q '^simulation-distance=4$' server.properties \
    || fail "server.properties does not use the low-ping simulation distance"
grep -q '^entity-broadcast-range-percentage=50$' server.properties \
    || fail "server.properties does not reduce entity broadcast range"
grep -q '^network-compression-threshold=128$' server.properties \
    || fail "server.properties does not compress more tunnel traffic"

if git ls-files --error-unmatch plugins/connect/token.json >/dev/null 2>&1; then
    fail "plugins/connect/token.json must never be committed"
fi
if grep -Rqs --exclude='*.log' '"token"' plugins/connect; then
    fail "a token appears in a committed Connect config"
fi

echo "All Paper + Minekube setup checks passed."
