#!/usr/bin/env bash
# Checks the tunnel TCP tuning profile without changing the host kernel.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

LOG_FILE="$ROOT_DIR/.agent-work/logs/network-tune-test.log"
mkdir -p "$(dirname -- "$LOG_FILE")"

bash -n scripts/network-tune.sh

output="$(NETWORK_TUNE_DRY_RUN=1 NETWORK_TUNE_LOG="$LOG_FILE" \
    bash scripts/network-tune.sh)"

for expected in \
    'net.ipv4.tcp_congestion_control=bbr' \
    'net.core.default_qdisc=fq' \
    'net.ipv4.tcp_notsent_lowat=8192' \
    'net.ipv4.tcp_autocorking=0' \
    'net.ipv4.tcp_mtu_probing=1' \
    'net.ipv4.tcp_slow_start_after_idle=0' \
    'net.ipv4.tcp_keepalive_time=60' \
    'net.ipv4.tcp_fin_timeout=15' \
    'net.ipv4.tcp_syn_retries=3' \
    'net.core.rmem_max=33554432' \
    'net.core.wmem_max=33554432' \
    'net.ipv4.tcp_rmem=65536 131072 33554432' \
    'net.ipv4.tcp_wmem=65536 131072 33554432'; do
    if ! grep -Fq "TEST ${expected}" <<<"$output"; then
        echo "FAIL: tuning profile does not set ${expected}" >&2
        exit 1
    fi
done

if ! grep -q 'TCP tuning active: congestion_control=dry-run default_qdisc=dry-run' <<<"$output"; then
    echo "FAIL: tuning report did not summarize the applied profile" >&2
    exit 1
fi

if [ ! -s "$LOG_FILE" ]; then
    echo "FAIL: NETWORK_TUNE_LOG was not written" >&2
    exit 1
fi

if ! grep -q '^TCP tuning active:' "$LOG_FILE"; then
    echo "FAIL: log file does not contain the tuning summary" >&2
    exit 1
fi

echo "network tune profile checks passed."
