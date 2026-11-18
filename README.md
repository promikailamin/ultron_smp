# Ultron SMP

Fabric Minecraft server backed up in this repository and started from the
**Run UltronSMP** GitHub Actions workflow.

## Connecting

After a server run starts, the workflow publishes the current address in
`SERVER_ADDRESS.txt`.

When starting the run with **Run workflow**, choose the **Public tunnel
provider** input:

| Provider | Script | Notes |
| --- | --- | --- |
| `auto` | `scripts/tunnel.sh` | Default. Tries Minekube Connect, then LocalXpose, then `bore.pub`. |
| `minekube` | `scripts/minekube-tunnel.sh` | Minekube Connect through the Gate connector. Gives a free `<endpoint>.play.minekube.net` address without port forwarding. |
| `localxpose` | `scripts/tunnel.sh` | Uses the access token stored in the `LOCALXPOSE_ACCESS_TOKEN` repository secret. |
| `bore` | `scripts/bore-tunnel.sh` | Stable free relay at `bore.pub`. Tries a fixed port list, then a random port. |
| `serveo` | `scripts/serveo-tunnel.sh` | Free SSH relay at `serveo.net`. Reconnects automatically and updates `SERVER_ADDRESS.txt` when the random port changes. |
| `pinggy` | `scripts/low-latency-tunnel.sh` | Free TCP relay with an Asia-Pacific relay. Prioritises the route with the best latency for Bangladeshi players. |
| `tunnl` | `scripts/tunnel.sh` | Starts a Bangladesh-prioritised TCP relay for Minecraft and adds a free `tunnl.gg` HTTP status page. |

`tunnl.gg` only carries HTTP and WebSocket traffic, and its relay is in
Finland, so it cannot be the Minecraft TCP endpoint itself. The `tunnl`
provider therefore keeps the game on the lowest-latency TCP relay
(Pinggy's Asia-Pacific relay by default, `bore.pub` as a fallback) and uses
`tunnl.gg` to publish `SERVER_STATUS_URL.txt`, a small page showing the
current Minecraft address. That gives the project a `tunnl.gg` tunnel without
breaking gameplay or sending Bangladeshi players through a distant relay.

The selection is passed to the workflow as `TUNNEL_PROVIDER`. The same
provider is used for automatic tunnel restarts during the run. `pinggy` and
`tunnl` also use the repository variable `PLAYER_REGION` (default:
`bangladesh`) so the Asia-Pacific relay is preferred for South Asian players.

## Minekube Connect

Minekube's fast-start Connect plugin is a Paper/Spigot/Velocity/BungeeCord
plugin, so a Fabric server cannot load it directly. Gate is Minekube's own
Minecraft proxy and the supported platform-independent Connect connector, so
this repository uses it instead of converting the Paper plugin. Gate runs
next to the Fabric server, forwards players to `127.0.0.1:25565` and keeps an
outbound tunnel to the Connect network. The public address is
`<endpoint>.play.minekube.net`.

No setup is required: `auto` tries Minekube first, and the `minekube`
provider forces it. If Minekube cannot be reached, `auto` falls back to
LocalXpose and then `bore.pub`. Set the repository variable
`MINEKUBE_AUTO=false` to keep the old `auto` behaviour.

The Gate binary is pinned to `0.81.1` and downloaded into `.agent-work/tools/`
on demand; it is not committed. `MINEKUBE_GATE_VERSION` (repository variable)
overrides the version.

By default a temporary endpoint name is generated for each run and stored in
`SERVER_ADDRESS.txt`. To keep a stable address:

1. Set the repository variable `MINEKUBE_ENDPOINT` to a name of 1-16
   lowercase letters, digits or hyphens (for example `ultron-smp-bd`).
2. On the first run, Gate creates `.agent-work/tmp/minekube/connect.json` and
   registers the endpoint. Copy its `token` value, or get the token from
   <https://app.minekube.com>, and add it as the repository secret
   `MINEKUBE_CONNECT_TOKEN`.

With both values set, every run reuses the same endpoint. If a configured
name is already owned by another connector and no token is provided,
`scripts/minekube-tunnel.sh` warns and temporarily uses a random endpoint so
the server stays reachable.

The connector allows offline-mode players by default because this server runs
with `online-mode=false`. `enforce-secure-profile` is also disabled so players
arriving through Connect without a Mojang profile key can still chat.

LocalXpose TCP tunnels require a **PRO** account. If the token is missing, is
invalid, or belongs to a FREE account (which only supports HTTP/HTTPS), the
`auto` provider falls back to the stable `bore.pub` tunnel so the server stays
reachable. An explicit `localxpose` choice stays on LocalXpose by default.
Set the repository variable `LOCALXPOSE_BORE_FALLBACK=true` to allow the
fallback for an explicit `localxpose` run, or `false` to disable it for
`auto`.

The token is never stored in the repository. Add or rotate it under
**Settings > Secrets and variables > Actions > Secrets** as
`LOCALXPOSE_ACCESS_TOKEN`.

## Twingate private access (optional)

Twingate is not a public tunnel and does not replace the addresses above. It
is a zero-trust access service, so the Minecraft server stays private and only
authorized Twingate users can reach it. Public players should keep using
Minekube, LocalXpose, bore, Serveo, Pinggy or tunnl.

The server can run a Twingate Connector alongside the public tunnel:

1. In the Twingate Admin Console, add a Connector to the remote network and
   generate its **Access Token** and **Refresh Token**.
2. Add the two connector tokens as repository secrets
   `TWINGATE_ACCESS_TOKEN` and `TWINGATE_REFRESH_TOKEN`.
3. Optional: if your access token does not contain the tenant name in its
   `aud` claim, add the network slug as the repository variable or secret
   `TWINGATE_NETWORK`.
4. In the Twingate Admin Console, open **Settings > API**, generate an Admin
   API token with **Read & Write**, and add it as the repository secret
   `TWINGATE_API_TOKEN`. Connector access/refresh tokens cannot create
   Resources; the Admin API token is only used to ensure the `minecraft`
   Resource exists.
5. Assign users or groups to the `minecraft` Resource in the Twingate Admin
   Console after it is created.

The connector starts automatically when both token secrets are present. Set
the repository variable `TWINGATE_ENABLED=false` to disable it, or `true` to
require both secrets. The default `auto` mode skips the connector when the
secrets are not configured.

`scripts/twingate-resource.py` runs after the connector and idempotently
creates the `minecraft` Resource through the Twingate Admin API. The default
Resource points at `127.0.0.1:25565` and restricts TCP to port `25565` with
UDP and ICMP disabled. It is created in the only Remote Network in the
account; if there are several, set the repository variable
`TWINGATE_REMOTE_NETWORK` (name) or `TWINGATE_REMOTE_NETWORK_ID`. Resource
settings can be overridden with the repository variables
`TWINGATE_RESOURCE_NAME`, `TWINGATE_RESOURCE_ADDRESS` and
`TWINGATE_RESOURCE_PORT`. The script never prints token values. If no API
token is configured it skips cleanly; set `TWINGATE_RESOURCE_ENABLED=true` to
make a missing Admin API token fatal instead.

The standalone **Ensure Twingate Resource** workflow can also be run manually
from the Actions tab. This is useful after adding or rotating
`TWINGATE_API_TOKEN` without starting the Minecraft server. For backward
compatibility the script falls back to `TWINGATE_ACCESS_TOKEN` when
`TWINGATE_API_TOKEN` is absent, but that only works if the access token is
actually an Admin API token rather than a Connector token.

`scripts/twingate-connector.sh` runs the pinned
`twingate/connector:1` container with host networking and reads the connector
credentials only from the environment. It reads `TWINGATE_NETWORK` from the
access token's `aud` claim when no explicit network is supplied. It never
writes token values to the repository or logs. The connector is optional and
a connector failure does not stop the public tunnel.

Host networking allows the connector to reach the Minecraft server at
`127.0.0.1:25565`, but Docker rejects the Twingate-recommended
`net.ipv4.ping_group_range` sysctl in the host network namespace. The script
therefore applies that sysctl only for non-host modes. Set
`TWINGATE_DOCKER_NETWORK=bridge` only if your resource is reachable through
the Docker bridge gateway instead of localhost.

Do not paste Twingate tokens into issues, pull requests, chat or commits. A
token that has been posted publicly must be revoked and regenerated in the
Twingate Admin Console. The value pasted into issue 9 was not used or stored
by this repository.

## Tunnel settings

- `LOCALXPOSE_REGION` (repository variable) - relay region: `ap`, `us`, `eu`,
  or `auto` to let LocalXpose choose. Default: `ap`.
- `LOCALXPOSE_BORE_FALLBACK` (repository variable) - set to `false` to make an
  `auto` LocalXpose failure fatal instead of falling back to `bore.pub`, or
  `true` to enable the fallback for an explicit `localxpose` run.
- `LOCALXPOSE_PORT_CANDIDATES` (optional environment variable) - remote TCP
  ports to try before asking LocalXpose for a random port. The default is
  `25565 25566 25567 25568 25569 25570`.

- `SERVEO_SERVER` (optional environment variable) - Serveo relay host.
  Default: `serveo.net`.
- `SERVEO_PORT` (optional environment variable) - Serveo SSH port. Default:
  `22`.
- `SERVEO_RECONNECT_SECONDS` (optional environment variable) - reconnect
  after this many seconds; `0` only reconnects when the SSH session drops.
  Default: `0`.

- `PLAYER_REGION` (repository variable) - set to `bangladesh` (or `bd`) to
  prefer Pinggy's Asia-Pacific relay for South Asian players, or `auto` to
  always pick the lowest measured total. Default: `bangladesh`.
- `BANGLADESH_RELAY_MARGIN_MS` (optional environment variable) - how many
  milliseconds worse the Asia-Pacific relay may be before another relay is
  chosen. Default: `75`.

- `TUNNL_SERVER` (optional environment variable) - tunnl.gg relay host.
  Default: `proxy.tunnl.gg`.
- `TUNNL_STATUS_PORT` (optional environment variable) - local port used for
  the status page that tunnl.gg exposes. Default: `8080`.
- `TUNNL_SSH_KEY` (optional environment variable) - SSH key for a stable
  tunnl.gg URL. If unset, a temporary key is generated in
  `.agent-work/tmp/`; the URL can then change between runs.
- `TUNNL_RECONNECT_SECONDS` (optional environment variable) - reconnect after
  this many seconds; `0` waits for the SSH session to end. Default: `0`.

The LocalXpose CLI (`loclx`) is downloaded automatically into
`.agent-work/tools/` and is not committed. Serveo only needs the system
`ssh` client.

Additional Minekube settings:

- `MINEKUBE_ENDPOINT` (repository variable) - stable endpoint name, 1-16
  lowercase letters, digits or hyphens.
- `MINEKUBE_CONNECT_TOKEN` (repository secret) - connector token for a stable
  endpoint. Never commit it.
- `MINEKUBE_GATE_VERSION` (repository variable) - Gate release to download.
  Default: `0.81.1`.
- `MINEKUBE_ALLOW_OFFLINE_PLAYERS` (optional environment variable) - allow
  offline/cracked players through Connect. Default: `true`.
- `MINEKUBE_BIND_PORT` (optional environment variable) - local port Gate
  listens on. Default: `25566`.

## World saving and cancelled runs

The server runs through `scripts/server-control.sh`, which gives the
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

## Server network optimizer

The server jar was unpacked and inspected (see
`.agent-work/outputs/issue-7-server-jar-analysis.txt`). The main sources of
network traffic are chunk data, entity tracking, particles, sounds, block
entities, and block/level events. Krypton and ServerCore already optimize the
pipeline and server ticking, so this repository adds a small server-side mod
for the remaining avoidable packets:

- `mods/ultron-network-optimizer-1.0.0.jar` culls particles, positional
  sounds, block-entity data, non-global level events, and block events that
  are beyond a configurable distance from the player.
- It also skips repeated block updates for the same position/state and empty
  entity-data packets.
- Suppressed-packet totals are written to the server log every five minutes.

Source and a reproducible build script live in `network-optimizer/`:

```bash
bash network-optimizer/build.sh
```

Settings are read from `config/ultron-network.properties`. Disable an
individual filter there if a gameplay feature depends on that packet.

The vanilla settings are also tuned for lower packet volume:

- `view-distance=6` and `simulation-distance=6` instead of `10`.
- `entity-broadcast-range-percentage=60` so entities are tracked over a
  smaller area.
- `network-compression-threshold=128` so more medium-sized packets are
  compressed before they cross the tunnel.

These changes reduce bandwidth and burst size. They cannot remove the
physical round-trip time between the runner and South Asia.

## Why the ping is high

The Minecraft server runs on a GitHub Actions runner in the United States.
A player in Bangladesh is physically thousands of kilometres away, so the
round-trip time (RTT) has a hard lower bound of roughly 150-250 ms depending
on the ISP route. No server setting can remove that distance.

The workflow makes the best of the available infrastructure:

- `scripts/network-tune.sh` enables BBR, larger TCP buffers, a lower
  unsent-byte watermark, and a larger network backlog so long-haul
  connections recover better from packet loss.
- `scripts/minekube-tunnel.sh` uses Minekube Connect through Gate, which gives
  players a nearby Connect edge and avoids exposing the runner directly.
- `scripts/tunnel.sh` selects the tunnel provider and uses a LocalXpose TCP
  tunnel, with the region selectable through `LOCALXPOSE_REGION`.
- `scripts/bore-tunnel.sh` keeps the stable `bore.pub` option and fallback
  available.
- `scripts/serveo-tunnel.sh` provides an alternative free SSH relay with
  automatic reconnects.
- `scripts/low-latency-tunnel.sh` prefers Pinggy's Asia-Pacific relay for
  Bangladeshi players and reconnects when the free hourly tunnel expires.
- `scripts/tunnl-tunnel.sh` adds a `tunnl.gg` HTTP status page. tunnl.gg does
  not carry raw TCP, so the game stays on a TCP relay.

If the game is still unplayable, the only real fix is to host the server on
hardware with a better network path to South Asia (for example a VPS in
Singapore, India, or Bangladesh) instead of a GitHub Actions runner.

## Tests

```bash
bash .agent-work/tests/test_run.sh
bash .agent-work/tests/test_server_control.sh
bash .agent-work/tests/test_tunnel.sh
bash .agent-work/tests/test_serveo_tunnel.sh
bash .agent-work/tests/test_tunnl_tunnel.sh
bash .agent-work/tests/test_tunnl_provider.sh
bash .agent-work/tests/test_minekube_tunnel.sh
bash .agent-work/tests/test_twingate_connector.sh
bash .agent-work/tests/test_twingate_docker_host.sh
bash .agent-work/tests/test_network_tune.sh
bash .agent-work/tests/test_network_optimizer.sh
```

`test_tunnel.sh` uses a fake `loclx` client so it can validate the address
parsing, forwarding and process handling without spending LocalXpose quota.
`test_serveo_tunnel.sh` uses a fake `ssh` client for the same reason.
`test_tunnl_tunnel.sh` also uses a fake `ssh` client and checks that the
tunnl.gg URL serves the status page and its `/healthz` endpoint.
`test_tunnl_provider.sh` validates the full `TUNNEL_PROVIDER=tunnl` path:
a TCP game relay plus the tunnl.gg status page.
`test_minekube_tunnel.sh` uses a fake Gate connector to validate the
`TUNNEL_PROVIDER=minekube` path, generated Gate config, address file and
local forwarding without registering a real endpoint.
`test_network_optimizer.sh` builds the mod, checks its mixin targets against
`server-26.3.jar`, and starts a disposable server to confirm the mod loads
without injection errors.
