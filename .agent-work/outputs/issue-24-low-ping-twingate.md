# Issue 24 - Lower ping and restore Twingate

## Short answer

The repository had been reset to a Paper + Minekube base, which removed the
Twingate scripts and workflows that were already working in issues 9-18. That
explains why Twingate was no longer available. The Twingate integration is now
restored and runs automatically from `Run Paper Server`.

The 270 ms in-game ping is mostly the physical path: the free GitHub runner is
in the United States, so every player packet crosses the ocean. No TCP setting
can remove that distance. The changes below remove avoidable latency on top of
that path and add the best low-ping route available without a server in Asia.

## What changed

### 1. Twingate restored and wired into the server workflow

Restored:

- `scripts/twingate-connector.sh`
- `scripts/twingate-resource.py`
- `scripts/twingate-admin.py`
- `.github/workflows/twingate-admin.yml`
- `.github/workflows/twingate-resource.yml`
- `.github/workflows/twingate-verify.yml`

`Run Paper Server` now:

- starts the Twingate Connector before Paper;
- creates/updates the `minecraft` Resource with address `127.0.0.1:25565` and
  alias `minecraft.ultronsmp.internal`;
- keeps the Connector running for the whole server session;
- stops the Connector on normal exit, cancellation, or failure.

Twingate connects directly to Paper, bypassing the Minekube edge. It therefore
removes one public-tunnel hop and is the recommended private route for players
who install the Twingate client.

The existing repository secrets are reused and never printed:

- `TWINGATE_ACCESS_TOKEN`
- `TWINGATE_REFRESH_TOKEN`
- `TWINGATE_API_TOKEN`

If those secrets are missing, the Twingate steps skip cleanly instead of
breaking the public Minecraft server.

### 2. Faster connector default

`Run Paper Server` now defaults to `connector: gate`. Gate is Minekube's
recommended connector, runs separately from Paper, and measured faster than the
in-process plugin in issue 23. The `plugin` option is still available.

### 3. Lower-latency TCP profile

`scripts/network-tune.sh` now also sets:

- `net.ipv4.tcp_autocorking=0` - stops TCP from deliberately delaying small
  keepalive/entity packets.
- `net.ipv4.tcp_fin_timeout=15`, `tcp_syn_retries=3`,
  `tcp_synack_retries=2`, `tcp_thin_linear_timeouts=1` - fail over from dead
  connections faster.
- `tcp_rmem`/`tcp_wmem` minimum raised from 4 KiB to 64 KiB so a fresh flow can
  ramp up immediately on a 200+ ms route.
- The existing BBR + `fq`, shallow send queue, MTU probing and 32 MiB buffer
  ceilings are retained.

### 4. Lower tick and packet load

`server.properties` now uses an even lighter profile:

| Setting | Before | Now |
| --- | ---: | ---: |
| `view-distance` | 6 | 5 |
| `simulation-distance` | 5 | 4 |
| `entity-broadcast-range-percentage` | 75 | 50 |
| `network-compression-threshold` | 128 | 128 |

Paper now runs with Aikar-style G1 flags (`-XX:MaxGCPauseMillis=30`,
parallel reference processing, short tenuring, etc.). Shorter GC pauses mean
keepalive and movement packets are not held up by heap collections.

### 5. A real route to sub-200 ms

`Run Paper Server` now supports the `RUNNER_LABELS` repository variable:

```text
["self-hosted","linux","asia"]
```

Setting it runs Paper on a VPS/self-hosted runner near the players. That is the
only guaranteed way to remove the US-runner RTT. The default remains
`["ubuntu-latest"]` so existing runs are unchanged.

## How to use Twingate

1. Install the Twingate client and sign in to the `mikailamin` tenant.
2. Make sure your user/group has access to the `minecraft` Resource. The
   workflow keeps the `Everyone` group assigned by default.
3. Start **Actions > Run Paper Server**.
4. In Minecraft, connect to:

```text
minecraft.ultronsmp.internal:25565
```

Do **not** connect to `127.0.0.1:25565`: on a player's PC that means the player's
own computer. `127.0.0.1` is only the target used by the Connector on the runner.

The public Minekube address is still written to `SERVER_ADDRESS.txt` for players
who do not use Twingate.

## Validation

Local tests passed:

```text
test_paper_setup.sh          PASS
test_network_tune.sh         PASS
test_minekube_gate.sh        PASS
test_twingate_connector.sh   PASS
test_twingate_resource.sh    PASS
test_twingate_admin.sh       PASS
```

Additional checks:

- YAML parsing of all four workflows: PASS
- Python compilation of the Twingate helpers: PASS
- Real Paper boot with the new JVM flags and `server.properties`: PASS
  (`Done (18.662s)!`), then stopped cleanly.
- Real Docker `twingate/connector:1` host-network regression test: PASS
  (connector container runs; fake credentials reach `State: Authentication` as
  expected).

Live tunnel diagnostic from this runner (3 samples):

| Target | Result |
| --- | ---: |
| Runner -> `watch-connect.minekube.net` TCP | ~9 ms |
| Runner -> `connect.minekube.net` TCP | ~23-54 ms |
| Public endpoint TCP | ~11-18 ms |
| Public Minecraft status | ~96-244 ms (highly variable) |

The public status variability is the same long path the player uses; it
includes the location of the runner hosting the live connector. It confirms
that the remaining delay is route distance, not runner bandwidth or DNS.

Logs are committed under `.agent-work/logs/issue-24-*`.

## Remaining limitation

- A real end-to-end Twingate client session could not be tested from this
  runner because player/client credentials are not available here. The
  repository integration, Docker connector, Resource automation and token
  secrecy were validated instead.
- With a free GitHub-hosted runner in the US, 250-270 ms from South Asia is
  close to the physical floor. Twingate removes the public tunnel hop and
  usually gives the lowest available route, but it cannot move the runner.
- For a guaranteed 120-150 ms, set `RUNNER_LABELS` to a self-hosted runner/VPS
  in Singapore, India or Bangladesh. The workflow now supports that directly.

## Commit

- `229deaf` - Restore Twingate and tighten low-ping tuning
- Pushed successfully to `main` before this report was written.
