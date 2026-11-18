#!/usr/bin/env bash
# Best-effort TCP tuning for high-latency / lossy long-distance links.
#
# Minecraft (Java) uses TCP. Players in South Asia connecting to a GitHub
# Actions runner in the US cross a long, sometimes lossy path, so BBR plus
# larger socket buffers can reduce stalls compared with the default CUBIC
# congestion control. This does not remove physical latency, but it makes
# the connection less likely to collapse under packet loss.
#
# The script is intentionally best-effort: if the runner does not expose a
# setting, it logs a warning and continues.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: network-tune.sh must run as root (use sudo)." >&2
    exit 1
fi

set_sysctl() {
    local key="$1"
    local value="$2"

    if sysctl -w "${key}=${value}" >/dev/null 2>&1; then
        echo "  ${key}=${value}"
    else
        echo "  SKIP ${key} (not available)"
    fi
}

echo "Applying low-latency TCP tuning..."

modprobe tcp_bbr 2>/dev/null || true

set_sysctl net.core.default_qdisc fq
set_sysctl net.ipv4.tcp_congestion_control bbr
set_sysctl net.ipv4.tcp_slow_start_after_idle 0
set_sysctl net.ipv4.tcp_mtu_probing 1
set_sysctl net.ipv4.tcp_fastopen 3
set_sysctl net.ipv4.tcp_keepalive_time 60
set_sysctl net.ipv4.tcp_keepalive_intvl 15
set_sysctl net.ipv4.tcp_keepalive_probes 4
set_sysctl net.ipv4.tcp_no_metrics_save 1
# Keep only a small amount of data queued in the kernel. On a long-haul
# tunnel a deep send queue turns bandwidth bursts into hundreds of
# milliseconds of extra latency (bufferbloat). 8 KiB is enough for
# Minecraft's packet stream while keeping the queue short.
set_sysctl net.ipv4.tcp_notsent_lowat 8192
set_sysctl net.ipv4.tcp_low_latency 1
set_sysctl net.ipv4.tcp_sack 1
set_sysctl net.ipv4.tcp_dsack 1
set_sysctl net.ipv4.tcp_timestamps 1
set_sysctl net.ipv4.tcp_tw_reuse 1
set_sysctl net.ipv4.tcp_max_syn_backlog 4096
set_sysctl net.core.somaxconn 1024
set_sysctl net.core.optmem_max 65536
set_sysctl net.core.netdev_max_backlog 32768
set_sysctl net.core.rmem_max 16777216
set_sysctl net.core.wmem_max 16777216
set_sysctl net.ipv4.tcp_rmem "4096 87380 16777216"
set_sysctl net.ipv4.tcp_wmem "4096 65536 16777216"

cc="$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo unknown)"
qdisc="$(sysctl -n net.core.default_qdisc 2>/dev/null || echo unknown)"
echo "TCP tuning active: congestion_control=${cc} default_qdisc=${qdisc}"
