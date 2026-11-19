#!/usr/bin/env bash
# Best-effort TCP tuning for the connector <-> tunnel edge path.
#
# Minecraft (Java) uses TCP. The Paper server runs on an ephemeral GitHub
# Actions runner, while Minekube Connect forwards players through its nearest
# edge. The runner -> edge and edge -> runner legs cross long, sometimes lossy
# internet paths, so BBR plus larger socket buffers and a shallow send queue
# reduce stalls and bufferbloat compared with the runner's default CUBIC stack.
#
# This cannot remove physical distance, but it removes several avoidable
# kernel-side causes of slow tunnel communication. All settings are applied at
# runtime only; nothing is written outside the repository.
#
# Usage:
#   sudo bash scripts/network-tune.sh
#   NETWORK_TUNE_DRY_RUN=1 bash scripts/network-tune.sh
#
# Optional environment variables:
#   NETWORK_TUNE_DRY_RUN   Print the settings without changing the kernel.
#   NETWORK_TUNE_LOG       Also append the report to this file.
set -euo pipefail

DRY_RUN="${NETWORK_TUNE_DRY_RUN:-false}"
case "$DRY_RUN" in
    1 | yes | YES | true | TRUE) DRY_RUN=true ;;
    *) DRY_RUN=false ;;
esac
LOG_FILE="${NETWORK_TUNE_LOG:-}"

log() {
    printf '%s\n' "$*"
    if [ -n "$LOG_FILE" ]; then
        printf '%s\n' "$*" >>"$LOG_FILE"
    fi
}

if [ "$DRY_RUN" != "true" ] && [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: network-tune.sh must run as root (use sudo)." >&2
    exit 1
fi

if [ -n "$LOG_FILE" ]; then
    mkdir -p "$(dirname -- "$LOG_FILE")"
    printf '\n=== %s network tuning ===\n' "$(date -u '+%Y-%m-%d %H:%M:%S UTC')" >>"$LOG_FILE"
fi

apply() {
    local key="$1"
    local value="$2"

    if [ "$DRY_RUN" = "true" ]; then
        log "  TEST ${key}=${value}"
        return 0
    fi

    if sysctl -w "${key}=${value}" >/dev/null 2>&1; then
        log "  OK   ${key}=${value}"
    else
        log "  SKIP ${key}=${value} (not available on this kernel)"
    fi
}

read_setting() {
    local key="$1"
    if [ "$DRY_RUN" = "true" ]; then
        printf 'dry-run'
        return 0
    fi
    sysctl -n "$key" 2>/dev/null || printf 'unknown'
}

log "Applying long-haul TCP tuning for the tunnel path..."

if [ "$DRY_RUN" != "true" ]; then
    modprobe tcp_bbr 2>/dev/null || true
fi

# Congestion control and queueing. BBR tolerates the occasional loss on
# international routes much better than CUBIC, and fq paces its bursts.
apply net.core.default_qdisc fq
apply net.ipv4.tcp_congestion_control bbr

# Avoid re-entering TCP slow start after short idle periods. Minecraft
# connections can be idle between keepalives and player actions.
apply net.ipv4.tcp_slow_start_after_idle 0

# Recover from path-MTU black holes instead of stalling on large packets.
apply net.ipv4.tcp_mtu_probing 1

# Keepalives let the connector notice a silently dead edge route quickly.
apply net.ipv4.tcp_keepalive_time 60
apply net.ipv4.tcp_keepalive_intvl 15
apply net.ipv4.tcp_keepalive_probes 4

# Do not let stale route metrics pin a reconnected tunnel to a bad path.
apply net.ipv4.tcp_no_metrics_save 1

# A small not-yet-sent queue keeps interactive traffic responsive. A deep
# queue turns chunk bursts into hundreds of milliseconds of extra latency.
apply net.ipv4.tcp_notsent_lowat 8192

# Reliable, modern TCP behaviour.
apply net.ipv4.tcp_sack 1
apply net.ipv4.tcp_dsack 1
apply net.ipv4.tcp_timestamps 1
apply net.ipv4.tcp_tw_reuse 1
apply net.ipv4.tcp_fastopen 3

# Larger autotuning ceilings let a single long-haul flow use the available
# bandwidth (BDP) instead of being capped by the small default buffers.
apply net.core.rmem_max 33554432
apply net.core.wmem_max 33554432
apply net.ipv4.tcp_rmem "4096 131072 33554432"
apply net.ipv4.tcp_wmem "4096 65536 33554432"

# More room for connection bursts and Netty's event loops.
apply net.ipv4.tcp_max_syn_backlog 4096
apply net.core.somaxconn 4096
apply net.core.optmem_max 65536
apply net.core.netdev_max_backlog 32768

cc="$(read_setting net.ipv4.tcp_congestion_control)"
qdisc="$(read_setting net.core.default_qdisc)"
log "TCP tuning active: congestion_control=${cc} default_qdisc=${qdisc}"
