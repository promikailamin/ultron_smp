#!/usr/bin/env bash
# Runs the tunnel latency diagnostic against the real Minekube endpoints.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

LOG_FILE="$ROOT_DIR/.agent-work/logs/tunnel-latency-test.log"
JSON_FILE="$ROOT_DIR/.agent-work/outputs/tunnel-latency-test.json"
mkdir -p "$(dirname -- "$LOG_FILE")" "$(dirname -- "$JSON_FILE")"

python3 -m py_compile scripts/tunnel-latency.py

python3 scripts/tunnel-latency.py \
    --endpoint mikailamin \
    --samples 3 \
    --timeout 5 \
    --label test \
    --json-out "$JSON_FILE" | tee "$LOG_FILE"

python3 - "$JSON_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)

for key in ("watch", "edge", "public"):
    if key not in report["targets"]:
        raise SystemExit(f"missing target {key}")
    target = report["targets"][key]
    for field in ("host", "port", "dns", "tcp"):
        if field not in target:
            raise SystemExit(f"{key} missing {field}")

watch = report["targets"]["watch"]
if watch["tcp"].get("attempts") != 3:
    raise SystemExit("watch target did not record all samples")
if "tls" not in watch:
    raise SystemExit("watch target is missing the TLS handshake measurement")
if "minecraft" not in report["targets"]["public"]:
    raise SystemExit("public target is missing the Minecraft status measurement")
if not report.get("recommendations"):
    raise SystemExit("report has no recommendations")
print("tunnel latency report schema checks passed.")
PY

echo "tunnel latency checks passed."
