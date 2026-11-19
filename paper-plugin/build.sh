#!/usr/bin/env bash
# Build the Heads Paper plugin.
#
# Paper ships its API and server classes inside the paperclip bootstrapper, so
# this script initialises a throw-away Paper server once to extract
# versions/<mc>/paper-<mc>.jar and the libraries it downloads, then compiles the
# plugin against that jar. Runtime files stay under .agent-work/tmp.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${PAPER_BUILD_DIR:-$REPO_ROOT/.agent-work/tmp/paper-build}"
TOOLS_DIR="$REPO_ROOT/.agent-work/tools"
OUTPUT_JAR="${OUTPUT_JAR:-$REPO_ROOT/plugins/Heads-1.0.0.jar}"

mkdir -p "$TOOLS_DIR" "$WORK_DIR" "$(dirname "$OUTPUT_JAR")"

resolve_paper() {
    python3 - "${PAPER_VERSION:-}" "${PAPER_BUILD:-}" <<'PY'
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

IFS=$'\t' read -r version build sha url < <(resolve_paper)
PAPER_JAR="${PAPER_JAR:-$TOOLS_DIR/paper-$version-$build.jar}"
PATCHED_JAR="$WORK_DIR/versions/$version/paper-$version.jar"

if [ ! -s "$PAPER_JAR" ]; then
    echo "Downloading Paper $version build $build..."
    curl -fsSL --retry 3 --connect-timeout 20 --max-time 300 \
        -o "$PAPER_JAR.tmp" "$url"
    mv "$PAPER_JAR.tmp" "$PAPER_JAR"
fi

actual_sha="$(sha256sum "$PAPER_JAR" | awk '{print $1}')"
if [ "$actual_sha" != "$sha" ]; then
    echo "ERROR: Paper jar checksum mismatch (expected $sha, got $actual_sha)." >&2
    exit 1
fi

if [ ! -f "$PATCHED_JAR" ]; then
    echo "Initialising Paper $version build $build to extract the server API..."
    printf 'eula=true\n' > "$WORK_DIR/eula.txt"
    printf 'server-port=25599\nonline-mode=false\nlevel-name=build-world\nmax-players=1\nview-distance=2\n' \
        > "$WORK_DIR/server.properties"
    (
        cd "$WORK_DIR"
        timeout 180 java -Xms256M -Xmx512M -jar "$PAPER_JAR" --nogui \
            > paper-init.log 2>&1 || true
    )
fi

if [ ! -f "$PATCHED_JAR" ]; then
    echo "ERROR: Paper did not produce $PATCHED_JAR." >&2
    tail -50 "$WORK_DIR/paper-init.log" >&2 || true
    exit 1
fi

CLASSPATH="$PATCHED_JAR"
while IFS= read -r jar; do
    CLASSPATH="$CLASSPATH:$jar"
done < <(find "$WORK_DIR/libraries" -name '*.jar' 2>/dev/null | sort)

BUILD_DIR="$SCRIPT_DIR/build"
BUILD_CLASSES="$BUILD_DIR/classes"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_CLASSES"

mapfile -t SOURCES < <(find "$SCRIPT_DIR/src/main/java" -name '*.java' | sort)
javac -encoding UTF-8 -source 25 -target 25 -Xlint:-options \
    -cp "$CLASSPATH" \
    -d "$BUILD_CLASSES" \
    "${SOURCES[@]}"

cp "$SCRIPT_DIR"/src/main/resources/* "$BUILD_CLASSES/"

(
    cd "$BUILD_CLASSES"
    jar --create --file "$OUTPUT_JAR" .
)

echo "Built $OUTPUT_JAR"
sha256sum "$OUTPUT_JAR"
