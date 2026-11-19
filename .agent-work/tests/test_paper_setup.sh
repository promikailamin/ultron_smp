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
[ -f plugins/connect/config.yml ] || fail "Connect config is missing"
[ -f eula.txt ] || fail "eula.txt is missing"
grep -qi '^eula=true' eula.txt || fail "eula.txt is not accepted"

bash -n start.sh || fail "start.sh has a syntax error"
bash -n scripts/setup-server.sh || fail "setup-server.sh has a syntax error"

grep -q 'fill.papermc.io/v3/projects/paper' start.sh \
    || fail "start.sh does not resolve Paper from the fill API"
grep -q 'connect-spigot.jar' start.sh \
    || fail "start.sh does not install the Minekube Paper plugin"
grep -q '^endpoint: *mikailamin$' plugins/connect/config.yml \
    || fail "Connect endpoint is not mikailamin"
grep -q '^allow-offline-mode-players: *true$' plugins/connect/config.yml \
    || fail "Connect does not allow offline players"

if git ls-files --error-unmatch plugins/connect/token.json >/dev/null 2>&1; then
    fail "plugins/connect/token.json must never be committed"
fi
if grep -Rqs --exclude='*.log' '"token"' plugins/connect; then
    fail "a token appears in a committed Connect config"
fi

echo "All Paper + Minekube setup checks passed."
