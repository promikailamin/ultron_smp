# Ultron SMP

Paper 26.3 Minecraft server backed up in this repository and started from the
**Run UltronSMP** GitHub Actions workflow.

## Paper server and Economy plugin

The server runs the latest Paper release (currently **26.3 build 255**, pinned
by SHA-256) instead of Fabric. The only Fabric mod, the Economy mod, has been
ported to a Paper plugin in `paper-plugin/`. It keeps the same commands
(`/bal`, `/pay`, `/fine`, `/shop`, `/buy`, `/sell`, `/ah`, `/eco`, `/ecosb`),
the seller axe, gear boxes, auctions and the existing `config/economy` data.

`paper/start.sh` downloads the pinned Paper jar on demand, builds the plugin
when `paper/plugins/Economy-1.0.0.jar` is missing, links the existing world and
economy data from the repository root, and starts the server on port `25565`.
The **Run UltronSMP** workflow uses this script through
`scripts/server-control.sh`, so autosaves, final backups and tunnels keep
working unchanged.

Useful commands:

```bash
# Build the Paper plugin (downloads/initialises Paper once)
bash paper-plugin/build.sh

# Build and run the in-server integration self-test against Paper
bash paper-plugin/test.sh

# Start the Paper server with the Economy plugin
bash paper/start.sh
```

The integration test runs a real Paper server, loads the plugin, and exercises
balances, prices, gear boxes, auctions, the seller axe, command registration,
all player GUIs/commands and the scoreboard. The
**Paper Economy Plugin Test** workflow runs the same test on every push that
changes the plugin, Paper files or economy data, and can also be started
manually.

## Connecting

After a server run starts, the workflow publishes the current address in
`SERVER_ADDRESS.txt`.

When starting the run with **Run workflow**, choose the **Public tunnel
provider** input. The **Free relay region** input selects Pinggy's free relay
for the `pinggy` and `tunnl` providers:

| Provider | Script | Notes |
| --- | --- | --- |
| `auto` | `scripts/tunnel.sh` | Default. Tries Minekube Connect, then LocalXpose, then `bore.pub`. |
| `minekube` | `scripts/minekube-tunnel.sh` | Minekube Connect through the Gate connector. Gives a free `<endpoint>.play.minekube.net` address without port forwarding. |
| `localxpose` | `scripts/tunnel.sh` | Uses the access token stored in the `LOCALXPOSE_ACCESS_TOKEN` repository secret. |
| `bore` | `scripts/bore-tunnel.sh` | Stable free relay at `bore.pub`. Tries a fixed port list, then a random port. |
| `serveo` | `scripts/serveo-tunnel.sh` | Free SSH relay at `serveo.net`. Reconnects automatically and updates `SERVER_ADDRESS.txt` when the random port changes. |
| `cloudflare` (or `cloudflared`) | `scripts/cloudflare-tunnel.sh` | Cloudflare Tunnel. A named tunnel (`CLOUDFLARE_TUNNEL_TOKEN` + `CLOUDFLARE_TUNNEL_HOSTNAME`) is required for Minecraft TCP. Without it, the game uses the Bangladesh-prioritised Pinggy TCP relay because Cloudflare Quick Tunnels are HTTP-only. Minecraft clients need Modflared or `cloudflared access tcp`. |
| `pinggy` | `scripts/low-latency-tunnel.sh` | Free TCP relay with an Asia-Pacific relay. Prioritises the route with the best latency for Bangladeshi players. |
| `tunnl` | `scripts/tunnel.sh` | Starts a Bangladesh-prioritised TCP relay for Minecraft and adds a free `tunnl.gg` HTTP status page. |

`tunnl.gg` only carries HTTP and WebSocket traffic, and its relay is in
Finland, so it cannot be the Minecraft TCP endpoint itself. The `tunnl`
provider therefore keeps the game on the lowest-latency TCP relay
(Pinggy's Asia-Pacific relay by default, `bore.pub` as a fallback) and uses
`tunnl.gg` to publish `SERVER_STATUS_URL.txt`, a small page showing the
current Minecraft address. That gives the project a `tunnl.gg` tunnel without
breaking gameplay or sending Bangladeshi players through a distant relay.

The provider selection is passed to the workflow as `TUNNEL_PROVIDER`. The
same provider is used for automatic tunnel restarts during the run. `pinggy`
and `tunnl` use the **Free relay region** input (`TUNNEL_REGION`) and the
repository variable `PLAYER_REGION` (default: `bangladesh`) so the
Asia-Pacific relay is preferred for South Asian players.

| Free relay region | Pinggy relay | Notes |
| --- | --- | --- |
| `auto` | measured | Default. Measures all free relays and applies the `PLAYER_REGION` preference. |
| `bangladesh` | `ap.free.pinggy.io` | Forces the Singapore (Asia-Pacific) relay for players in Bangladesh. |
| `asia-pacific` | `ap.free.pinggy.io` | Same relay as `bangladesh`, useful for other South/South-East Asian players. |
| `united-states` | `us.free.pinggy.io` | Forces the US relay. Often fastest when GitHub runs the server in the US. |
| `europe` | `eu.free.pinggy.io` | Forces the European relay. |

The free relays are shared by Pinggy's free tier and do not guarantee a
Bangladesh-specific location. Because the Minecraft server runs on a GitHub
Actions runner in the United States, `asia-pacific` is not always faster than
`united-states`: the player-to-relay hop is shorter, but the relay-to-runner
hop is longer. `auto` measures both legs and chooses the best total.

## Minekube Connect

Minekube's fast-start Connect plugin is a Paper/Spigot/Velocity/BungeeCord
plugin. This repository still uses Gate, Minekube's own Minecraft proxy and
platform-independent Connect connector, because it is already wired into the
tunnel workflow and keeps working independently of the server platform. Gate
runs next to the Paper server, forwards players to `127.0.0.1:25565` and keeps
an outbound tunnel to the Connect network. The public address is
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

## Cloudflare Tunnel

Select `cloudflare` (or `cloudflared`) as the **Public tunnel provider** to use
Cloudflare Tunnel. A **named tunnel** is required for Minecraft because Cloudflare
Quick Tunnels only proxy HTTP/HTTPS traffic.

Create a Cloudflare Tunnel in **Zero Trust > Networks > Tunnels**, add a public
hostname with the service `tcp://localhost:25565`, and configure:

| Name | Type | Purpose |
| --- | --- | --- |
| `CLOUDFLARE_TUNNEL_TOKEN` | repository secret | Connector token from the tunnel installation command. |
| `CLOUDFLARE_TUNNEL_HOSTNAME` | repository variable | Public hostname, for example `mc.example.com`. |

A named tunnel can also be described by `CLOUDFLARE_TUNNEL_CONFIG`
(repository variable), which points cloudflared at a config file. The
config's first `hostname:` entry is used when
`CLOUDFLARE_TUNNEL_HOSTNAME` is not set.

When no named tunnel is configured, `scripts/cloudflare-tunnel.sh` does **not**
publish a broken `*.trycloudflare.com` Minecraft address. It gives the game
port to a real TCP relay instead: the Bangladesh-prioritised Pinggy
Asia-Pacific relay by default, or `bore.pub` when
`CLOUDFLARE_TCP_FALLBACK=bore`. This is why players no longer see the tunnel
"stop and restart" after connecting to a quick-tunnel hostname that could
never carry Minecraft traffic. Set `CLOUDFLARE_TCP_FALLBACK=none` to make a
missing named tunnel fatal instead.

Cloudflare edge routing is anycast: players in Bangladesh connect to the
nearest Cloudflare location automatically, then travel over Cloudflare's
backbone to the tunnel connector. Cloudflare only supports forcing the `us`
edge region (`CLOUDFLARE_REGION=us`); `auto`/`bangladesh`/`asia-pacific`/`europe`
keep the global edge, which is the lowest-latency option Cloudflare offers.
The actual player-facing Bangladesh preference comes from the Pinggy
Asia-Pacific fallback when a named tunnel is not available.

Cloudflare TCP tunnels are **not plain public TCP ports**. Cloudflare only
gives end users a WebSocket route, so every player needs either the
[Modflared](https://modrinth.com/mod/modflared) Minecraft mod or the
`cloudflared` client on their own computer. With the client installed, players
run:

```bash
cloudflared access tcp --hostname <hostname> --url localhost:25565
```

and then join `localhost:25565` in Minecraft. Only the hostname part of
`SERVER_ADDRESS.txt` is used; do not enter `hostname:25565` directly in the
Minecraft server list. The workflow starts the same access proxy locally and
uses it for the public-route check, so a successful run confirms that the
tunnel itself works.

Named tunnels keep the same hostname when the workflow restarts them, so a
temporary process failure no longer changes the address players have saved.
The `cloudflared` binary is downloaded into `.agent-work/tools/` on demand and
is not committed.

## Twingate private access (optional)

Twingate is not a public tunnel and does not replace the addresses above. It
is a zero-trust access service, so the Minecraft server stays private and only
authorized Twingate users can reach it. Public players should keep using
Minekube, LocalXpose, bore, Serveo, Cloudflare, Pinggy or tunnl.

The server can run a Twingate Connector alongside the public tunnel:

1. In the Twingate Admin Console, add a Connector to the remote network and
   generate its **Access Token** and **Refresh Token**.
2. Add the two connector tokens as repository secrets
   `TWINGATE_ACCESS_TOKEN` and `TWINGATE_REFRESH_TOKEN`.
3. The connector network defaults to `mikailamin`. Override it with the
   repository variable or secret `TWINGATE_NETWORK` if your tenant uses a
   different slug. When the script is run outside the workflow it falls back
   to the access token's `aud` claim.
4. In the Twingate Admin Console, open **Settings > API**, generate an Admin
   API token with **Read & Write**, and add it as the repository secret
   `TWINGATE_API_TOKEN`. Connector access/refresh tokens cannot create
   Resources; the Admin API token is only used to ensure the `minecraft`
   Resource exists.
5. The Resource is assigned to the `Everyone` group by default so authorized
   users can reach it. Override this with the repository variable
   `TWINGATE_RESOURCE_GROUP_NAMES` (comma-separated group names). You can also
   assign users or groups manually in the Twingate Admin Console.

The connector starts automatically when both token secrets are present. Set
the repository variable `TWINGATE_ENABLED=false` to disable it, or `true` to
require both secrets. The default `auto` mode skips the connector when the
secrets are not configured.

`scripts/twingate-resource.py` runs after the connector and idempotently
creates the `minecraft` Resource through the Twingate Admin API. The Resource
address is `127.0.0.1:25565`, which is where the Connector reaches the server,
and restricts TCP to port `25565` with UDP and ICMP disabled. The script also
sets the Twingate alias `minecraft.ultronsmp.internal`, because `127.0.0.1`
on a player's device means that device, not the runner. To join through
Twingate, connect Minecraft to:

```text
minecraft.ultronsmp.internal:25565
```

The alias only resolves while the Twingate client is connected and the
Connector is online; it does not need a public DNS record. It is created in
the only Remote Network in the account; if there are several, set the
repository variable `TWINGATE_REMOTE_NETWORK` (name) or
`TWINGATE_REMOTE_NETWORK_ID`. Resource settings can be overridden with the
repository variables `TWINGATE_RESOURCE_NAME`, `TWINGATE_RESOURCE_ADDRESS`,
`TWINGATE_RESOURCE_ALIAS` and `TWINGATE_RESOURCE_PORT`. Set
`TWINGATE_RESOURCE_ALIAS=none` to create the Resource without an alias. The
script never prints token values. When neither `TWINGATE_API_TOKEN` nor
`TWINGATE_ACCESS_TOKEN` is set it skips cleanly; set
`TWINGATE_RESOURCE_ENABLED=true` to make a missing token fatal instead.

The standalone **Ensure Twingate Resource** workflow can also be run manually
from the Actions tab. This is useful after adding or rotating
`TWINGATE_API_TOKEN` without starting the Minecraft server. For backward
compatibility the script falls back to `TWINGATE_ACCESS_TOKEN` when
`TWINGATE_API_TOKEN` is absent, but that only works if the access token is
actually an Admin API token rather than a Connector token.

The **Twingate Admin** workflow inspects the account (`action=status`) or
provisions the missing Remote Network and Connector (`action=provision`). Set
`rotate_tokens=true` to generate a fresh Connector token pair and update the
`TWINGATE_ACCESS_TOKEN` / `TWINGATE_REFRESH_TOKEN` repository secrets without
printing the values. The **Verify Twingate Connector** workflow starts the
connector on a runner, waits for it to become healthy, and prints the account
and connector state. Run it after rotating tokens or changing the Twingate
configuration.

`scripts/twingate-connector.sh` runs the pinned
`twingate/connector:1` container with the settings Twingate prints in its
Admin Console: container name `twingate-mccon`, `--restart unless-stopped`,
`--pull always`, and the `TWINGATE_LABEL_HOSTNAME=ultronsmp.net` /
`TWINGATE_LABEL_DEPLOYED_BY=docker` labels. It reads the connector
credentials only from the environment and never writes token values to the
repository or logs. When no explicit network is supplied it reads
`TWINGATE_NETWORK` from the access token's `aud` claim. The connector is
optional and a connector failure does not stop the public tunnel.

Host networking allows the connector to reach the Minecraft server at
`127.0.0.1:25565`, but Docker rejects the Twingate-recommended
`net.ipv4.ping_group_range` sysctl in the host network namespace. The script
therefore applies that sysctl only for non-host modes. Set
`TWINGATE_DOCKER_NETWORK=bridge` if your resource is reachable through the
Docker bridge gateway. Use `TWINGATE_DOCKER_NETWORK=default` to omit
`--network` entirely and reproduce Twingate's default-bridge command; the
sysctl is applied in that mode.

The container name, image, pull policy and labels can be overridden with
`TWINGATE_CONNECTOR_NAME`, `TWINGATE_DOCKER_IMAGE`, `TWINGATE_DOCKER_PULL`,
`TWINGATE_LABEL_HOSTNAME` and `TWINGATE_LABEL_DEPLOYED_BY`. The workflow sets
the values from the matching repository variables when present.

Do not paste Twingate tokens into issues, pull requests, chat or commits. A
token that has been posted publicly must be revoked and regenerated in the
Twingate Admin Console. The value pasted into issue 9 was not used or stored
by this repository.

## Network diagnostics

The **Network Diagnostic** workflow and `scripts/network-diagnostic.py` measure
the runner's own network and, optionally, the Twingate Connector:

- download and upload throughput against Cloudflare's speed endpoint,
- TCP connect latency, jitter and packet loss to Cloudflare, GitHub and the
  Twingate tenant,
- a soak test that reports failed samples and speed/latency spikes,
- Twingate Connector state through the Admin API and Docker while it is
  running.

Run it from **Actions > Network Diagnostic > Run workflow**. The sizes,
soak duration and whether to start the Twingate Connector are inputs. Results
are written to `.agent-work/logs/issue-18-*` and
`.agent-work/outputs/network-diagnostic-*.json`, uploaded as the
`network-diagnostic` artifact, and committed to the branch when
`publish_results` is enabled.

The same measurements can be run locally:

```bash
python3 scripts/network-diagnostic.py \
    --label my-runner \
    --download-bytes 25000000 \
    --upload-bytes 10000000 \
    --soak-seconds 60
```

The throughput test uses the runner's normal internet egress. The Twingate
Connector serves remote Twingate clients; it does not route the runner's own
speed-test traffic. These numbers therefore show whether the GitHub runner
itself has a bandwidth, loss or jitter problem, while the connector monitor
shows whether the connector stayed online during the same period. A slow
client-to-connector game session can still be limited by the player's ISP,
the Twingate relay, or the long path to the runner even when the runner speed
test is healthy.

## Tunnel settings

- `LOCALXPOSE_REGION` (repository variable) - relay region: `ap`, `us`, `eu`,
  or `auto` to let LocalXpose choose. Default: `ap`.
- `LOCALXPOSE_BORE_FALLBACK` (repository variable) - set to `false` to make an
  `auto` LocalXpose failure fatal instead of falling back to `bore.pub`, or
  `true` to enable the fallback for an explicit `localxpose` run.
- `LOCALXPOSE_PORT_CANDIDATES` (optional environment variable) - remote TCP
  ports to try before asking LocalXpose for a random port. The default is
  `25565 25566 25567 25568 25569 25570`.

- `CLOUDFLARE_REGION` (repository variable, or the workflow **Free relay
  region** input) - `auto`/`global` (default) keeps Cloudflare's global anycast
  edge, `us` forces the US edge. Cloudflare does not expose an Asia-Pacific
  edge option; other values fall back to global and are logged.
- `CLOUDFLARE_TCP_FALLBACK` (repository variable) - TCP relay used when no
  named Cloudflare tunnel is configured: `pinggy` (default, Asia-Pacific and
  Bangladesh-prioritised), `bore`, or `none`.
- `CLOUDFLARE_ALLOW_QUICK_TCP` (repository variable) - allow the HTTP-only
  quick tunnel even for TCP. Default: `false`; it is only useful for tests or
  HTTP services.

- `SERVEO_SERVER` (optional environment variable) - Serveo relay host.
  Default: `serveo.net`.
- `SERVEO_PORT` (optional environment variable) - Serveo SSH port. Default:
  `22`.
- `SERVEO_RECONNECT_SECONDS` (optional environment variable) - reconnect
  after this many seconds; `0` only reconnects when the SSH session drops.
  Default: `0`.

- `TUNNEL_REGION` (workflow input **Free relay region**, or environment
  variable when running `scripts/low-latency-tunnel.sh` directly) - force the
  free Pinggy relay: `bangladesh`/`asia-pacific`, `united-states`, `europe`,
  or `auto` to measure all of them. Default: `auto`.
- `PLAYER_REGION` (repository variable) - set to `bangladesh` (or `bd`) to
  prefer Pinggy's Asia-Pacific relay for South Asian players when
  `TUNNEL_REGION=auto`, or `auto` to always pick the lowest measured total.
  Default: `bangladesh`.
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

Additional Cloudflare settings:

- `CLOUDFLARE_TUNNEL_TOKEN` (repository secret) - token for a named tunnel.
  Without it the script uses a quick `trycloudflare.com` tunnel.
- `CLOUDFLARE_TUNNEL_HOSTNAME` (repository variable) - public hostname of the
  named tunnel. Default: the first `hostname:` in `CLOUDFLARE_TUNNEL_CONFIG`.
- `CLOUDFLARE_TUNNEL_CONFIG` (repository variable) - optional cloudflared
  config file.
- `CLOUDFLARE_MODE` (repository variable) - `auto`, `named` or `quick`.
  Default: `auto`.
- `CLOUDFLARE_VERSION` (repository variable) - cloudflared release to
  download. Default: `2026.11.1`; use `latest` to follow new releases.
- `CLOUDFLARE_BORE_FALLBACK` (repository variable) - fall back to `bore.pub`
  when Cloudflare cannot start. Default: `true`.
- `CLOUDFLARE_ACCESS_PORT` (repository variable) - local port for the
  verification access proxy. Default: `25571`.
- `CLOUDFLARE_ACCESS_CLIENT_ID` and `CLOUDFLARE_ACCESS_CLIENT_SECRET`
  (repository secrets) - optional Cloudflare Access service token used by the
  local access proxy when the hostname is protected by Access.
- `CLOUDFLARE_DNS_WAIT_SECONDS` (optional environment variable) - how long to
  wait for a new quick-tunnel hostname to resolve before starting the access
  proxy. Default: `60`.

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

- `mods/ultron-network-optimizer-1.1.0.jar` culls particles, positional
  sounds, entity-attached sounds, block-entity data, non-global level events,
  block events, block-break animations, and distant visual-only explosions
  that are beyond a configurable distance from the player.
- It also skips repeated block updates for the same position/state, identical
  entity-metadata packets, empty entity-data packets, and excess world-time
  updates.
- It disables Nagle's algorithm (`TCP_NODELAY`) and enables TCP keepalive on
  player sockets, which avoids extra delay on small responses.
- Suppressed-packet totals are written to the server log every five minutes.

Source and a reproducible build script live in `network-optimizer/`:

```bash
bash network-optimizer/build.sh
```

Settings are read from `config/ultron-network.properties`. Disable an
individual filter there if a gameplay feature depends on that packet.

The vanilla settings are also tuned for lower packet volume:

- `view-distance=5` and `simulation-distance=5` instead of `10`.
- `entity-broadcast-range-percentage=50` so entities are tracked over a
  smaller area.
- `network-compression-threshold=128` so more medium-sized packets are
  compressed before they cross the tunnel.

These changes reduce bandwidth, burst size, and server packet work. They
cannot remove the physical round-trip time between the runner and South Asia.
In particular, the ping number shown by the client is measured from the
keepalive round trip, so a 230 ms route cannot become 120-150 ms just by
dropping packets. Lower packet volume does help when the tunnel is
congested or the server is behind on ticks.

## Why the ping is high

The Minecraft server runs on a GitHub Actions runner in the United States.
A player in Bangladesh is physically thousands of kilometres away, so the
round-trip time (RTT) has a hard lower bound of roughly 150-250 ms depending
on the ISP route. No server setting can remove that distance.

The workflow makes the best of the available infrastructure:

- `scripts/network-tune.sh` enables BBR, a lower `tcp_notsent_lowat`
  watermark to limit bufferbloat, SACK/timestamps, larger TCP buffers, and a
  larger network backlog so long-haul connections recover better from packet
  loss.
- `scripts/minekube-tunnel.sh` uses Minekube Connect through Gate, which gives
  players a nearby Connect edge and avoids exposing the runner directly.
- `scripts/tunnel.sh` selects the tunnel provider and uses a LocalXpose TCP
  tunnel, with the region selectable through `LOCALXPOSE_REGION`.
- `scripts/bore-tunnel.sh` keeps the stable `bore.pub` option and fallback
  available.
- `scripts/serveo-tunnel.sh` provides an alternative free SSH relay with
  automatic reconnects.
- `scripts/cloudflare-tunnel.sh` adds a named Cloudflare Tunnel and verifies
  the TCP route through a local `cloudflared access` proxy. Because Quick
  Tunnels are HTTP-only, a missing named tunnel falls back to the
  Bangladesh-prioritised Pinggy TCP relay instead of publishing a dead
  `trycloudflare.com` Minecraft address.
- `scripts/low-latency-tunnel.sh` selects the Pinggy free relay from
  `TUNNEL_REGION` (or measures all relays with `auto`), prefers the
  Asia-Pacific relay for Bangladeshi players, and reconnects when the free
  hourly tunnel expires.
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
bash .agent-work/tests/test_cloudflare_tunnel.sh
bash .agent-work/tests/test_tunnl_tunnel.sh
bash .agent-work/tests/test_tunnl_provider.sh
bash .agent-work/tests/test_low_latency_region.sh
bash .agent-work/tests/test_minekube_tunnel.sh
bash .agent-work/tests/test_twingate_connector.sh
bash .agent-work/tests/test_twingate_resource.sh
bash .agent-work/tests/test_twingate_admin.sh
bash .agent-work/tests/test_twingate_docker_host.sh
bash .agent-work/tests/test_network_diagnostic.sh
bash .agent-work/tests/test_network_tune.sh
bash .agent-work/tests/test_network_optimizer.sh
```

`test_tunnel.sh` uses a fake `loclx` client so it can validate the address
parsing, forwarding and process handling without spending LocalXpose quota.
`test_serveo_tunnel.sh` uses a fake `ssh` client for the same reason.
`test_cloudflare_tunnel.sh` uses a fake `cloudflared` client to exercise both
the quick and named tunnel modes plus the `cloudflared access tcp` proxy.
`test_tunnl_tunnel.sh` also uses a fake `ssh` client and checks that the
tunnl.gg URL serves the status page and its `/healthz` endpoint.
`test_tunnl_provider.sh` validates the full `TUNNEL_PROVIDER=tunnl` path:
a TCP game relay plus the tunnl.gg status page.
`test_low_latency_region.sh` uses a fake SSH client to check that the
`TUNNEL_REGION` workflow input maps to the correct Pinggy free relay without
contacting the public relays.
`test_minekube_tunnel.sh` uses a fake Gate connector to validate the
`TUNNEL_PROVIDER=minekube` path, generated Gate config, address file and
local forwarding without registering a real endpoint.
`test_network_diagnostic.sh` runs the diagnostic against a local HTTP server,
so it validates download/upload, latency, soak sampling, JSON output and token
secrecy without using external bandwidth.
`test_network_optimizer.sh` builds the mod, checks its mixin targets against
`server-26.3.jar`, and starts a disposable server to confirm the mod loads
without injection errors.
