# Issue 23 - Slow GitHub runner -> tunnel path

## What was measured

The client -> tunnel leg and the server (GitHub runner) -> tunnel leg were
separated with `scripts/tunnel-latency.py`. On the live runner path:

| Target | TCP average | Loss | Notes |
| --- | ---: | ---: | --- |
| `watch-connect.minekube.net:443` | ~10 ms | 0% | Connect control/watch websocket |
| `connect.minekube.net:443` | ~35-50 ms | 0% | Connect edge/anycast endpoint |
| `mikailamin.play.minekube.net:25565` | ~30-40 ms | 0% | full edge -> connector -> Paper path |
| Minecraft status through the public tunnel | ~58-67 ms | - | measured from a GitHub runner |

The runner path itself is healthy in these runs. That means the user-visible
slowness is not a missing public port or a dead tunnel. The remaining causes are
the runner's variable egress route, bufferbloat/loss on the long tunnel path,
excess server traffic, and the fact that the Java plugin connector lives inside
Paper and cannot be restarted on its own. The changes below address each of
those.

## Implemented

### 1. Long-haul TCP tuning on the runner

`scripts/network-tune.sh`, run by the new `tune_runner_network` workflow step:

- BBR congestion control with the `fq` queue discipline.
- `tcp_slow_start_after_idle=0`, `tcp_mtu_probing=1`, TCP keepalives.
- `tcp_notsent_lowat=8192` to keep the send queue short and avoid bufferbloat.
- 32 MiB autotuning ceilings for TCP receive/send buffers.
- Larger SYN/accept/backlog limits for Netty and connection bursts.
- Best-effort: unavailable sysctls are logged and skipped.

Live check on the runner:

```text
OK net.core.default_qdisc=fq
OK net.ipv4.tcp_congestion_control=bbr
OK net.ipv4.tcp_notsent_lowat=8192
OK net.ipv4.tcp_mtu_probing=1
OK net.core.rmem_max=33554432
TCP tuning active: congestion_control=bbr default_qdisc=fq
```

### 2. Runner -> tunnel diagnostics and health history

`scripts/tunnel-latency.py` measures DNS, TCP connect, TLS handshake and a real
Minecraft status ping for:

- `watch-connect.minekube.net:443`
- `connect.minekube.net:443`
- `<endpoint>.play.minekube.net:25565`

The workflow runs it before Paper starts and again as soon as Paper/Connect is
ready, saves JSON/log artifacts, and appends the tunnel result to the job
summary. While Paper runs, a monitor samples the connector every 30 seconds:

- plugin mode: `CONNECT_WATCH_HEALTH_ADDR` (`/healthz`) status;
- Gate mode: Gate process state, with automatic Gate restart if it dies.

### 3. Less traffic over the tunnel

`server.properties` now uses tunnel-friendly values:

| Setting | Before | After |
| --- | ---: | ---: |
| `view-distance` | 8 | 6 |
| `simulation-distance` | 6 | 5 |
| `entity-broadcast-range-percentage` | 100 | 75 |
| `network-compression-threshold` | 256 | 128 |

This cuts chunk bursts, entity tracking and medium-packet bandwidth without
changing the base world.

### 4. Optional Gate connector (Minekube's recommended connector)

New `scripts/minekube-gate.sh` and a `connector` workflow input
(`plugin` default, `gate` optional). Gate:

- runs as a separate process next to Paper, so a stuck tunnel can be restarted
  without stopping the world;
- forwards only to `127.0.0.1:25565` and opens no inbound public port;
- uses compression threshold 128 and the same endpoint token secret;
- writes its token file with mode 600 and never logs the token.

The workflow auto-restarts Gate if its process disappears.

### 5. Advanced Connect routing knobs

`start.sh` now forwards these optional repository variables to the Connect
plugin without changing the default:

- `MINEKUBE_WATCH_URL` -> `CONNECT_WATCH_URL`
- `MINEKUBE_FORCE_TUNNEL_SERVICE_ADDR` -> `CONNECT_FORCE_TUNNEL_SERVICE_ADDR`
- `MINEKUBE_LIBP2P_EDGE_ADDR` -> `CONNECT_LIBP2P_EDGE_ADDR`
- `MINEKUBE_LIBP2P_RELAY_ADDRS` -> `CONNECT_LIBP2P_RELAY_ADDRS`
- `MINEKUBE_WATCH_HEALTH_ADDR` -> `CONNECT_WATCH_HEALTH_ADDR`

These are for testing a pinned Minekube edge/tunnel service or libp2p endpoint
mode. Leave them unset unless a specific edge is being tested.

## Live verification

| Run | Mode | Result |
| --- | --- | --- |
| `44435450554` | `plugin` | Tuning and baseline diagnostics passed; Paper + plugin registered; public status 58-59 ms; run cancelled after verification. |
| `44436566925` | `gate` | Tuning and baseline diagnostics passed; Gate registered `mikailamin`; public status 21-48 ms; tunnel diagnostic 58 ms. Left running so the server stays online. |

Gate was also tested on a temporary random endpoint: it registered, answered a
Minecraft status ping in ~39 ms, and stopped cleanly.

Local test suite:

```text
bash .agent-work/tests/test_paper_setup.sh      PASS
bash .agent-work/tests/test_network_tune.sh     PASS
bash .agent-work/tests/test_minekube_gate.sh    PASS
bash .agent-work/tests/test_tunnel_latency.sh   PASS
shellcheck all changed shell files              PASS
python3 -m py_compile scripts/tunnel-latency.py PASS
YAML parse of .github/workflows/run.yml         PASS
```

## All possible solutions considered

1. **Tune the runner TCP stack (implemented).** BBR + fq + shallow send queue +
   MTU probing + buffers removes kernel-side stalls and bufferbloat on long,
   occasionally lossy routes.
2. **Measure and monitor both tunnel legs (implemented).** The new diagnostic
   distinguishes a bad runner -> watch path from a bad edge -> connector path
   and records 30-second health samples for the whole run.
3. **Reduce traffic (implemented).** Lower view/simulation/entity ranges and a
   lower compression threshold reduce bytes and burst size over the tunnel.
4. **Use Gate instead of the in-process plugin (implemented as an option).**
   Minekube documents Gate as its most capable/best-performing connector, and it
   can be restarted independently. Select `connector: gate` in the workflow.
5. **Pin a better edge/tunnel service (supported).** The new
   `MINEKUBE_FORCE_TUNNEL_SERVICE_ADDR`, `MINEKUBE_WATCH_URL` and libp2p
   variables let a specific route be tested. Use the diagnostic to compare
   candidates before pinning one.
6. **Use libp2p endpoint mode (supported experimentally).** Setting
   `MINEKUBE_LIBP2P_EDGE_ADDR`/`MINEKUBE_LIBP2P_RELAY_ADDRS` enables Connect's
   native endpoint path while keeping the watch path as fallback.
7. **Run multiple connectors for the same endpoint (not needed here).**
   Minekube supports load balancing multiple connectors, but two connectors on
   the same runner do not improve the runner's route. Multiple hosts would.
8. **Move the server to a VPS/self-hosted runner near the players (external).**
   This is the only guaranteed way to remove the GitHub runner's physical
   route and its ephemeral/preemption behaviour. A VPS in Singapore, India or
   Bangladesh would give the lowest stable latency for South Asian players.
9. **Use a GitHub larger runner or a runner in a chosen region (external).**
   Hosted public runners do not expose region selection; a larger/self-hosted
   runner can be placed closer to the Minekube edge or the players.
10. **Use a different tunnel provider (external/optional).** A named Cloudflare
    tunnel, playit.gg, Tailscale or a provider with a nearby dedicated relay can
    be used, but each has its own client requirements. The repository is
    currently standardised on Minekube Connect.
11. **Report the route to Minekube support (external).** If the diagnostic shows
    healthy runner -> watch but slow edge/connector legs, send the JSON report
    to Minekube with the runner region and timestamps so they can inspect their
    edge route.

## Remaining limitations

- TCP tuning cannot reduce the physical RTT of the route.
- The repository cannot choose the region of a free `ubuntu-latest` runner.
- Changing the tunnel provider or moving to a VPS is an infrastructure decision,
  not something a workflow file can guarantee.
- The plugin connector cannot be restarted independently; that is why Gate is
  now available as an option and is auto-restarted in Gate mode.
