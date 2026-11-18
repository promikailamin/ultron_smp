# Issue 8 - Minekube Connect on the Fabric server

## Why Gate instead of converting the Paper plugin

Minekube's fast-start Connect plugin targets the Bukkit/Paper, Velocity and
BungeeCord APIs (`connect-spigot.jar`, `connect-velocity.jar`,
`connect-bungee.jar`). Ultron SMP runs Fabric, which does not load Bukkit or
Paper plugins, so dropping the Paper jar into `mods/` cannot work.

Minekube's own supported connector for other server types is Gate, a
standalone Minecraft proxy. It connects out to the Connect watch service,
receives player sessions and forwards them to a local backend. Running Gate
next to the Fabric server therefore provides the same
`<endpoint>.play.minekube.net` address that the Paper plugin would provide,
without a plugin port or server-side Fabric mod.

## Implementation

- `scripts/minekube-tunnel.sh` downloads the pinned Gate release into
  `.agent-work/tools/` (not committed), generates a Gate config in
  `.agent-work/tmp/minekube/`, registers an endpoint and writes the public
  address to `SERVER_ADDRESS.txt`.
- `scripts/tunnel.sh` dispatches `TUNNEL_PROVIDER=minekube`; `auto` tries
  Minekube first and keeps LocalXpose/bore as fallbacks.
- The generated Gate config uses `onlineMode: false`,
  `forwarding.mode: none`, `allowOfflineModePlayers: true`, compression
  threshold 128 and a local bind of `127.0.0.1:25566` to match the existing
  offline-mode Fabric server.
- `.github/workflows/run.yml` exposes the `minekube` choice and passes
  `MINEKUBE_ENDPOINT`, `MINEKUBE_CONNECT_TOKEN`, `MINEKUBE_GATE_VERSION` and
  `MINEKUBE_AUTO`.
- `server.properties` now has `enforce-secure-profile=false` so Connect
  players without a Mojang profile key can use chat.

## Endpoint names

The Connect watch service currently accepts endpoint names of 1-16 lowercase
letters, digits or hyphens. Gate 0.81.1's generated three-word names can be
longer than 16 characters, which the service rejects with
`400 invalid endpoint name format`. The script therefore validates names and
generates its own `ultron-<8 hex>` fallback (15 characters).

## Verification

Real connector test on 2026-11-18:

```text
$ bash scripts/minekube-tunnel.sh
Downloading Minekube Gate 0.81.1 for linux/amd64...
Tunnel is live at ultron-36e5b8d5.play.minekube.net:25565

$ python3 .agent-work/tests/mc_status.py ultron-36e5b8d5.play.minekube.net 25565
connect=48ms version=Gate 1.7.2-1.7.5-26.3 motd=... players=0/100
```

The temporary endpoint was stopped after the test. No connector token is
stored in the repository.

Automated tests:

```text
bash .agent-work/tests/test_minekube_tunnel.sh   PASS
bash .agent-work/tests/test_tunnel.sh            PASS
bash .agent-work/tests/test_serveo_tunnel.sh     PASS
bash .agent-work/tests/test_tunnl_provider.sh    PASS
```
