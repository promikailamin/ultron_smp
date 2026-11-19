#!/usr/bin/env bash
# Download the latest PaperMC build and Minekube Connect plugin, then start
# the server from this directory.
#
# Runtime files (paper.jar, plugins/*.jar, world, logs) are git-ignored.
# The Minekube token is never written to the repository: it is read from
# MINEKUBE_CONNECT_TOKEN and written to plugins/connect/token.json (ignored).
#
# Optional environment variables:
#   PAPER_VERSION                Paper version (default: latest supported)
#   PAPER_BUILD                  Paper build (default: latest stable build)
#   PAPER_JAVA_OPTS              JVM options (default: -Xms1G -Xmx2G)
#   PAPER_JAR_URL                Override the Paper download URL
#   PAPER_JAR_SHA256             Expected SHA-256 when PAPER_JAR_URL is set
#   MINEKUBE_ENDPOINT            Endpoint name (default: config value)
#   MINEKUBE_CONNECT_TOKEN       Connect token from repository secrets
#   MINEKUBE_PLUGIN_URL          Override the plugin download URL
#   MINEKUBE_PLUGIN_VERSION      Plugin version (default: latest release)
#   MINEKUBE_ALLOW_OFFLINE       Allow offline/cracked players (default: true)
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$ROOT_DIR/.agent-work/tools"
PLUGINS_DIR="$ROOT_DIR/plugins"
CONNECT_DIR="$PLUGINS_DIR/connect"
PAPER_JAR="$ROOT_DIR/paper.jar"

JAVA_BIN="${JAVA_BIN:-java}"
PAPER_VERSION="${PAPER_VERSION:-}"
PAPER_BUILD="${PAPER_BUILD:-}"
PAPER_JAVA_OPTS="${PAPER_JAVA_OPTS:--Xms1G -Xmx2G}"
MINEKUBE_PLUGIN_VERSION="${MINEKUBE_PLUGIN_VERSION:-latest}"
MINEKUBE_ALLOW_OFFLINE="${MINEKUBE_ALLOW_OFFLINE:-true}"

mkdir -p "$TOOLS_DIR" "$PLUGINS_DIR" "$CONNECT_DIR"

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    echo "ERROR: '$JAVA_BIN' was not found. Install Java 25 or set JAVA_BIN." >&2
    exit 1
fi

if [ ! -f "$ROOT_DIR/eula.txt" ] || ! grep -qi '^eula=true' "$ROOT_DIR/eula.txt"; then
    printf 'eula=true\n' >"$ROOT_DIR/eula.txt"
fi

resolve_paper() {
    python3 - "$PAPER_VERSION" "$PAPER_BUILD" <<'PY'
import json
import sys
import urllib.request

wanted_version, wanted_build = sys.argv[1], sys.argv[2]
base = "https://fill.papermc.io/v3/projects/paper"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ultron-smp/1.0"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)

if wanted_version:
    version = wanted_version
else:
    data = get(f"{base}/versions")
    version = None
    for entry in data["versions"]:
        item = entry["version"]
        if item["support"]["status"] == "SUPPORTED" and "-" not in item["id"]:
            version = item["id"]
            break
    if version is None:
        raise SystemExit("could not determine the latest supported Paper version")

data = get(f"{base}/versions/{version}/builds")
build = None
for entry in data:
    if entry.get("channel") != "STABLE":
        continue
    if wanted_build and str(entry["id"]) != str(wanted_build):
        continue
    build = entry
    break
if build is None:
    raise SystemExit(f"no matching stable Paper build for {version} (build={wanted_build or 'latest'})")

download = build["downloads"]["server:default"]
print("\t".join([
    version,
    str(build["id"]),
    download["checksums"]["sha256"],
    download["url"],
]))
PY
}

install_paper() {
    local version build sha url cached
    IFS=$'\t' read -r version build sha url < <(resolve_paper)
    cached="$TOOLS_DIR/paper-${version}-${build}.jar"

    if [ -n "${PAPER_JAR_URL:-}" ]; then
        url="$PAPER_JAR_URL"
        sha="${PAPER_JAR_SHA256:-}"
    fi

    if [ ! -s "$PAPER_JAR" ]; then
        if [ -s "$cached" ]; then
            cp "$cached" "$PAPER_JAR"
        else
            echo "Downloading Paper ${version} build ${build}..."
            curl -fsSL --retry 3 --connect-timeout 20 --max-time 300 \
                -o "$PAPER_JAR.tmp" "$url"
            mv "$PAPER_JAR.tmp" "$PAPER_JAR"
            cp "$PAPER_JAR" "$cached" 2>/dev/null || true
        fi
    fi

    if [ -n "$sha" ]; then
        actual="$(sha256sum "$PAPER_JAR" | awk '{print $1}')"
        if [ "$actual" != "$sha" ]; then
            echo "ERROR: Paper checksum mismatch (expected $sha, got $actual)." >&2
            rm -f "$PAPER_JAR"
            exit 1
        fi
    fi

    echo "Using Paper ${version} build ${build}."
}

install_plugin() {
    local version url target cached
    version="$MINEKUBE_PLUGIN_VERSION"
    target="$PLUGINS_DIR/connect-spigot.jar"

    if [ -z "${MINEKUBE_PLUGIN_URL:-}" ]; then
        if [ "$version" = "latest" ]; then
            version="$(curl -fsSL --max-time 30 -o /dev/null \
                -w '%{url_effective}' \
                https://github.com/minekube/connect-java/releases/latest \
                | sed -n 's#.*/tag/##p' || true)"
            [ -n "$version" ] || version="latest"
        fi
        url="https://github.com/minekube/connect-java/releases/download/${version}/connect-spigot.jar"
    else
        url="$MINEKUBE_PLUGIN_URL"
    fi

    cached="$TOOLS_DIR/connect-spigot-${version}.jar"
    if [ ! -s "$target" ]; then
        if [ -s "$cached" ]; then
            cp "$cached" "$target"
        else
            echo "Downloading Minekube Connect ${version}..."
            curl -fsSL --retry 3 --connect-timeout 20 --max-time 300 \
                -o "$target.tmp" "$url"
            mv "$target.tmp" "$target"
            cp "$target" "$cached" 2>/dev/null || true
        fi
    fi

    echo "Using Minekube Connect ${version}."
}

configure_connect() {
    python3 - "$CONNECT_DIR/config.yml" \
        "${MINEKUBE_ENDPOINT:-}" "$MINEKUBE_ALLOW_OFFLINE" <<'PY'
import re
import sys

path, endpoint, allow_offline = sys.argv[1:4]
try:
    with open(path, encoding="utf-8") as stream:
        text = stream.read()
except FileNotFoundError:
    raise SystemExit(0)

if endpoint:
    text = re.sub(r"^endpoint:.*$", f"endpoint: {endpoint}", text,
                  count=1, flags=re.MULTILINE)
if allow_offline:
    value = "true" if allow_offline.lower() == "true" else "false"
    text = re.sub(r"^allow-offline-mode-players:.*$",
                  f"allow-offline-mode-players: {value}", text,
                  count=1, flags=re.MULTILINE)

with open(path, "w", encoding="utf-8") as stream:
    stream.write(text)
PY

    if [ -n "${MINEKUBE_CONNECT_TOKEN:-}" ]; then
        python3 - "$MINEKUBE_CONNECT_TOKEN" "$CONNECT_DIR/token.json" <<'PY'
import json
import os
import sys
import tempfile

token, path = sys.argv[1], sys.argv[2]
directory = os.path.dirname(path) or "."
fd, tmp = tempfile.mkstemp(dir=directory, prefix=".token-", suffix=".json")
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump({"token": token}, stream)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
finally:
    if os.path.exists(tmp):
        os.unlink(tmp)
PY
    fi
}

install_paper
install_plugin
configure_connect

cd "$ROOT_DIR"
# shellcheck disable=SC2206
JAVA_ARGS=($PAPER_JAVA_OPTS)
exec "$JAVA_BIN" "${JAVA_ARGS[@]}" -jar "$PAPER_JAR" --nogui "$@"
