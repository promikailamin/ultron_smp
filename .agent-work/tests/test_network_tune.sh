#!/usr/bin/env bash
# Validate the TCP tuning script. Applies settings when run with root or
# passwordless sudo, otherwise only checks syntax and prints a skip notice.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_FILE="$ROOT_DIR/.agent-work/logs/network-tune-test.log"

mkdir -p "$(dirname -- "$LOG_FILE")"
bash -n "$ROOT_DIR/scripts/network-tune.sh"

if [ "$(id -u)" -eq 0 ]; then
    bash "$ROOT_DIR/scripts/network-tune.sh" 2>&1 | tee "$LOG_FILE"
elif sudo -n true 2>/dev/null; then
    sudo bash "$ROOT_DIR/scripts/network-tune.sh" 2>&1 | tee "$LOG_FILE"
else
    echo "SKIP: root or passwordless sudo is required to apply TCP tuning." | tee "$LOG_FILE"
fi

cc="$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo unknown)"
echo "TCP congestion control: ${cc}"
