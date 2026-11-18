# Issue 20 - Fix the Cloudflare tunnel that "stops and restarts"

## Root cause

The Cloudflare provider added for issue 19 started a **Quick Tunnel**
(`cloudflared tunnel --url tcp://127.0.0.1:25565`) when no named tunnel was
configured. Cloudflare Quick Tunnels only proxy HTTP/HTTPS, so Minecraft's raw
TCP traffic can never connect through them. The workflow still published the
random `*.trycloudflare.com` hostname, and the keep-alive loop restarted the
process whenever it dropped, producing the loop players saw:

> tunnel stopped and restarting

A Cloudflare **named tunnel** does support Minecraft TCP, but only when the
client also uses Modflared or `cloudflared access tcp`.

## Changes

### `scripts/cloudflare-tunnel.sh`

- A named tunnel (`CLOUDFLARE_TUNNEL_TOKEN` + `CLOUDFLARE_TUNNEL_HOSTNAME`) is
  now the only Cloudflare mode used for Minecraft TCP.
- Quick tunnels are disabled for TCP by default. They are still available for
  tests/HTTP with `CLOUDFLARE_ALLOW_QUICK_TCP=true`.
- When Cloudflare cannot carry the game, the script uses a real TCP relay
  instead of publishing a dead address:
  - `CLOUDFLARE_TCP_FALLBACK=pinggy` (default): Bangladesh-prioritised Pinggy
    Asia-Pacific relay.
  - `CLOUDFLARE_TCP_FALLBACK=bore`: stable `bore.pub`.
  - `CLOUDFLARE_TCP_FALLBACK=none`: fail instead of falling back.
- Added `CLOUDFLARE_REGION`. Cloudflare only documents forcing the `us` edge,
  so `auto`/`bangladesh`/`asia-pacific`/`europe` keep the global anycast edge.
  Cloudflare then automatically connects each player to the nearest edge
  (including Bangladesh), while the named-tunnel connector stays stable.
- Background `cloudflared` and `access` processes are started with `nohup`, so
  they survive the end of the workflow step that launched them.
- Named tunnel failures no longer silently fall through to a broken quick
  tunnel; they log the reason and use the TCP fallback.

### `.github/workflows/run.yml`

- Added `CLOUDFLARE_REGION`, `CLOUDFLARE_TCP_FALLBACK` and
  `CLOUDFLARE_ALLOW_QUICK_TCP` environment variables.
- The keep-alive loop now treats a dead Cloudflare access proxy as a failed
  tunnel, so the tunnel and proxy are restarted together.
- Restarted addresses are committed and pushed immediately, not only at the
  next world autosave. Named tunnels keep the same hostname across restarts.
- The **Free relay region** input also controls `CLOUDFLARE_REGION`.

### Documentation

- `README.md` and `scripts/tunnel.sh` explain the HTTP-only Quick Tunnel
  limitation, the named-tunnel setup, the new fallback/region settings, and
  the Bangladesh-priority behaviour.

## Tests

```text
bash -n scripts/*.sh .agent-work/tests/*.sh                        PASS
shellcheck scripts/cloudflare-tunnel.sh scripts/tunnel.sh \
    .agent-work/tests/test_cloudflare_tunnel.sh                    PASS
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml"))' PASS
bash .agent-work/tests/test_cloudflare_tunnel.sh                   PASS
bash .agent-work/tests/test_tunnel.sh                              PASS
bash .agent-work/tests/test_low_latency_region.sh                  PASS
bash .agent-work/tests/test_serveo_tunnel.sh                       PASS
```

`test_cloudflare_tunnel.sh` covers:

- quick tunnel parsing with `CLOUDFLARE_ALLOW_QUICK_TCP=true`;
- named tunnel startup and `--region us` forwarding;
- the default TCP guard that refuses to publish an HTTP-only quick tunnel;
- the Bangladesh fallback to `ap.free.pinggy.io`.

Log: `.agent-work/logs/issue-20-cloudflare-test.log`

## How to get a real Cloudflare address

1. In **Cloudflare Zero Trust > Networks > Tunnels**, create a tunnel.
2. Add a public hostname whose service is `tcp://localhost:25565`.
3. Add the connector token as repository secret
   `CLOUDFLARE_TUNNEL_TOKEN`.
4. Add the hostname as repository variable
   `CLOUDFLARE_TUNNEL_HOSTNAME`.
5. Run **Run UltronSMP** with **Public tunnel provider** = `cloudflare`.
6. Players install [Modflared](https://modrinth.com/mod/modflared), or run
   `cloudflared access tcp --hostname <hostname> --url localhost:25565` and
   join `localhost:25565`.

Without steps 1-4 the workflow now keeps the server playable through the
Bangladesh-prioritised Pinggy Asia-Pacific relay instead of handing players an
address that cannot work.
