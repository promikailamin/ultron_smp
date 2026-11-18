# Issue 18 - GitHub runner bandwidth and Twingate stability

## Short answer

The runner itself does **not** show a bandwidth problem. In the live diagnostic
run it sustained roughly **712 Mbps download** and **111 Mbps upload** on the
direct runner path, with **0% packet loss** and low jitter. With the Twingate
Connector running, it measured about **635 Mbps download** and **256 Mbps
upload**, still with **0% loss**, and the Connector stayed **ALIVE/Online for
100% of the monitor samples**.

Minecraft does not need hundreds of Mbps. The remaining high in-game ping is
therefore not caused by the runner's internet capacity. It is the round-trip
path from the player, through their ISP and the Twingate relay, to the runner.
A slow client-to-connector session can still be limited by the player's ISP,
the relay, or that long path even while the runner speed test is healthy.

## What was added

- `scripts/network-diagnostic.py`
  - Measures runner facts, public IP, download/upload throughput, TCP connect
    latency/jitter/loss, and a soak test.
  - Optionally monitors the Twingate Connector state through the Admin API and
    Docker while the connector runs.
  - Never reads or prints Twingate token values.
- `.github/workflows/network-diagnostic.yml`
  - Manual **Network Diagnostic** workflow with size/duration inputs.
  - Runs a baseline test, starts the Twingate Connector, monitors it, runs the
    same test again, collects connector logs/stats, uploads an artifact and
    commits the results.
- `.agent-work/tests/test_network_diagnostic.sh`
  - Local-only test using an embedded HTTP server for download/upload, latency
    and soak measurements, plus a token-secrecy check.
- `README.md`
  - Documents the workflow, direct script usage, and the measurement limits.

## Live results

Workflow run:
<https://github.com/promikailamin/ultron_smp/actions/runs/44337668418>

Runner public IP: `20.98.155.184` (GitHub Actions Linux runner).
TCP tuning applied: `bbr` congestion control with `fq` qdisc.
NIC reported link speed: `100000 Mbps` (virtual interface, not the internet
limit). The measured internet throughput below is the useful number.

| Measurement | Baseline | Twingate Connector online |
| --- | ---: | ---: |
| Download average | 711.62 Mbps | 635.39 Mbps |
| Download range | 503.74 - 889.45 Mbps | 480.96 - 750.66 Mbps |
| Upload average | 110.97 Mbps | 256.12 Mbps |
| Upload range | 83.87 - 153.29 Mbps | 239.55 - 278.65 Mbps |
| Download rounds successful | 3/3 | 3/3 |
| Upload rounds successful | 3/3 | 3/3 |
| 60s soak success | 100% (12/12) | 100% (12/12) |
| Soak download average | 73.44 Mbps | 63.83 Mbps |
| Soak latency average | 7.12 ms | 6.29 ms |
| Connector monitor | not monitored | 100% ALIVE (6/6) |

TCP connect latency during the connector run:

| Target | Average | p95 | Jitter | Loss |
| --- | ---: | ---: | ---: | ---: |
| `1.1.1.1:443` | 6.33 ms | 6.77 ms | 0.21 ms | 0% |
| `8.8.8.8:443` | 19.32 ms | 31.95 ms | 5.67 ms | 0% |
| `github.com:443` | 27.18 ms | 33.85 ms | 4.45 ms | 0% |
| `api.github.com:443` | 29.79 ms | 35.36 ms | 3.81 ms | 0% |
| `speed.cloudflare.com:443` | 6.57 ms | 7.33 ms | 0.58 ms | 0% |
| `twingate.com:443` | 25.04 ms | 34.34 ms | 7.94 ms | 0% |
| `mikailamin.twingate.com:443` | 24.25 ms | 33.85 ms | 8.36 ms | 0% |

Connector evidence from the same run:

```text
State: Offline
State: Authentication
State: Authentication
State: Online
```

The Admin API showed `state=ALIVE` before and after the test, with the expected
Remote Network, Connector, `Everyone` group and the `minecraft` Resource:

```text
minecraft | address=127.0.0.1 | alias=minecraft.ultronsmp.internal | groups=Everyone
```

The connector container used about 15 MiB RAM and 0.16% CPU during the check.

Full logs and JSON reports are committed under:

- `.agent-work/logs/issue-18-*`
- `.agent-work/outputs/network-diagnostic-runner-baseline.json`
- `.agent-work/outputs/network-diagnostic-twingate-connector-online.json`

## Interpretation

1. **Runner egress is healthy.** The measured throughput is far above what a
   Minecraft server needs, and every round completed without loss.
2. **Twingate is not destabilising the connector host.** Starting the connector
   did not cause packet loss, and the connector remained online for every
   monitor sample.
3. **The runner speed test is not a player-path test.** The Twingate Connector
   serves remote Twingate clients; it does not route the runner's own speed
   test through Twingate. There are no Twingate client/service-key credentials
   in this repository, so a runner cannot impersonate a player and measure the
   final client-to-connector tunnel end to end.
4. **In-game ping is dominated by path RTT.** If a player in Bangladesh still
   sees high ping or low throughput, the next measurements should be taken on
   the player's device: Twingate client relay selected, RTT to the relay, and
   packet loss on the player's ISP. The runner data no longer points at GitHub
   runner bandwidth as the cause.

## Validation

Passed locally:

```text
python3 -m py_compile scripts/network-diagnostic.py          PASS
bash .agent-work/tests/test_network_diagnostic.sh            PASS
bash .agent-work/tests/test_twingate_connector.sh            PASS
python3 -c 'import yaml; ... network-diagnostic.yml ...'     PASS
```

The live workflow run `44337668418` completed all steps successfully, including
the connector start, resource check, connector monitor, result upload and
commit.

## How to rerun

From **Actions > Network Diagnostic > Run workflow**, or locally:

```bash
python3 scripts/network-diagnostic.py \
    --label my-runner \
    --download-bytes 25000000 \
    --upload-bytes 10000000 \
    --soak-seconds 60
```

## Remaining limitation

This diagnostic proves the runner and connector are fast and stable. It cannot
measure the player-to-Twingate-relay-to-runner path without Twingate client
credentials. If the complaint is specifically about a player's in-game speed,
the player-side Twingate client diagnostics are the next required data.
