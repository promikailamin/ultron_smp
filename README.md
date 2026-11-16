# Ultron SMP

Fabric Minecraft server backed up in this repository and started from the
**Run UltronSMP** GitHub Actions workflow.

## Connecting

After a server run starts, the workflow publishes two addresses:

| Address file | Relay | Notes |
| --- | --- | --- |
| `SERVER_ADDRESS.txt` | `bore.pub` (US East) | Stable while the run is alive. |
| `SERVER_ADDRESS_LOW_LATENCY.txt` | Pinggy (auto-selected relay) | Usually lower latency for players in Bangladesh/South Asia. The free relay address changes about once an hour. |

Try the low-latency address first if you are in South Asia. If it is slower
for you, use the stable `SERVER_ADDRESS.txt` address instead. Both files are
updated automatically and committed by the workflow.

## World saving and cancelled runs

The server now runs through `scripts/server-control.sh`, which gives the
workflow a console pipe. This lets the workflow send `save-all flush` and
`stop` to Minecraft instead of only copying files that are still in memory.

- The world is flushed and pushed to `main` every 90 seconds while the server
  is online (`AUTOSAVE_SECONDS` in `run.yml`).
- Cancelling a run triggers a trap that flushes the world, commits and pushes
  it, and then stops the server cleanly.
- The `restore_final_server` step runs even when the job fails or is
  cancelled, stops the server, and pushes a final backup.
- A new run re-syncs from `origin/main` before starting the server, so it
  cannot start from a stale world and overwrite the previous run's changes.
- `sync-chunk-writes` is disabled because the workflow now flushes explicitly
  every 90 seconds and on shutdown. This avoids disk-write stalls on the
  runner while still keeping the backed-up world fresh.
- `pause-when-empty-seconds=0` keeps the server ticking, so the first player
  to join does not wait for the server to resume from a pause.

The old behaviour (plain `nohup`, no `stop` command, saving only every five
minutes, and no re-sync at startup) is why a cancelled run could lose the
most recent changes even though the workflow "saved" the world.

## Why the ping is high

The Minecraft server runs on a GitHub Actions runner in the United States.
A player in Bangladesh is physically thousands of kilometres away, so the
round-trip time (RTT) has a hard lower bound of roughly 150-250 ms depending
on the ISP route. No server setting can remove that distance.

The workflow now makes the best of the available free infrastructure:

- `scripts/network-tune.sh` enables BBR and larger TCP buffers so long-haul
  connections recover better from packet loss.
- `scripts/low-latency-tunnel.sh` adds a Pinggy Asia-Pacific relay so traffic
  from Bangladesh does not first cross to the US East `bore.pub` relay.
- `scripts/tunnel.sh` keeps the stable `bore.pub` address as a fallback.

If the game is still unplayable, the only real fix is to host the server on
hardware with a better network path to South Asia (for example a VPS in
Singapore, India, or Bangladesh) instead of a GitHub Actions runner.

## Choosing a different relay region

By default the low-latency script measures the runner's latency to each
Pinggy relay and adds an approximate South Asian player-side cost, then picks
the lowest total. You can override it with the repository variable
`PINGGY_SERVER` under **Settings > Variables**:

- `auto` - pick the best relay automatically (default)
- `ap.free.pinggy.io` - Asia-Pacific / Singapore
- `us.free.pinggy.io` - US West
- `eu.free.pinggy.io` - Europe

Set `LOW_LATENCY_TUNNEL=false` to disable the Pinggy relay and use only the
stable `bore.pub` address.

## Tests

```bash
bash .agent-work/tests/test_run.sh
bash .agent-work/tests/test_server_control.sh
bash .agent-work/tests/test_tunnel.sh
bash .agent-work/tests/test_low_latency_tunnel.sh
bash .agent-work/tests/test_network_tune.sh
```
