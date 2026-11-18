# Issue 19 - Add a Cloudflare Tunnel option to run.yml

## Request

Issue 19 asks to add a Cloudflare (`cloudflared`) tunnel as an option of the
**Run UltronSMP** workflow tunnels.

## What changed

### Workflow

`.github/workflows/run.yml`:

- Added `cloudflare` and `cloudflared` choices to the **Public tunnel
  provider** input.
- Added `CLOUDFLARE_TUNNEL_TOKEN`, `CLOUDFLARE_TUNNEL_HOSTNAME`,
  `CLOUDFLARE_TUNNEL_CONFIG`, `CLOUDFLARE_MODE`, `CLOUDFLARE_VERSION`,
  `CLOUDFLARE_BORE_FALLBACK`, `CLOUDFLARE_ACCESS_PORT`,
  `CLOUDFLARE_DNS_WAIT_SECONDS` and Cloudflare Access service-token
  environment variables.
- The expose step now reads the local access-proxy endpoint written by the
  Cloudflare script and exports it as `SERVER_VERIFY_ADDRESS`.
- `verify_public_tunnel` checks that proxy for Cloudflare runs, so the tunnel
  is verified end-to-end even though Cloudflare TCP is not a plain public TCP
  port and cannot be reached without cloudflared on the client.
- Tunnel failure logs now include the Cloudflare logs.

### Tunnel script

New `scripts/cloudflare-tunnel.sh`:

- Downloads the pinned `cloudflared` release (`2026.11.1` by default) into
  `.agent-work/tools/cloudflared-linux-<arch>`; nothing is committed.
- **Named tunnel:** uses `CLOUDFLARE_TUNNEL_TOKEN` plus
  `CLOUDFLARE_TUNNEL_HOSTNAME` (or the first `hostname:` in
  `CLOUDFLARE_TUNNEL_CONFIG`).
- **Quick tunnel:** when no token is configured, starts an account-less
  `trycloudflare.com` tunnel and parses its random hostname.
- Starts a local `cloudflared access tcp` proxy and writes its address to
  `${RUNNER_TEMP}/ultron-tunnel-verify-address`.
- Waits for a new quick-tunnel hostname to resolve before starting the access
  proxy.
- Writes `SERVER_ADDRESS.txt` with the Cloudflare hostname and instructions
  for Modflared/cloudflared clients.
- Falls back to a quick tunnel when a named tunnel fails, then to `bore.pub`
  when `CLOUDFLARE_BORE_FALLBACK` is not `false`.

`scripts/tunnel.sh` dispatches `cloudflare`, `cloudflared`,
`cloudflare-tunnel` and `cf` to the new script.

The `.gitignore` file now ignores the downloaded cloudflared binary and its
runtime logs.

## Player instructions

Cloudflare TCP tunnels require a client-side proxy. Players must use the
Modflared Minecraft mod or run:

```bash
cloudflared access tcp --hostname <hostname> --url localhost:25565
```

and then join `localhost:25565`. Entering `hostname:25565` directly in the
Minecraft server list does not work for Cloudflare TCP tunnels.

## Named tunnel setup

1. Create a tunnel in **Cloudflare Zero Trust > Networks > Tunnels**.
2. Add a public hostname with service `tcp://localhost:25565`.
3. Add the connector token as repository secret `CLOUDFLARE_TUNNEL_TOKEN`.
4. Add the public hostname as repository variable
   `CLOUDFLARE_TUNNEL_HOSTNAME`.
5. Run the workflow with **Public tunnel provider** = `cloudflare`.

Without these settings the `cloudflare` provider still works using a quick
tunnel, but its hostname changes on every run.

## Validation

Passed locally:

```text
bash -n scripts/cloudflare-tunnel.sh                              PASS
bash -n scripts/tunnel.sh                                         PASS
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml"))' PASS
shellcheck scripts/cloudflare-tunnel.sh scripts/tunnel.sh \
    .agent-work/tests/test_cloudflare_tunnel.sh                   PASS
bash .agent-work/tests/test_cloudflare_tunnel.sh                  PASS
```

The fake-client test covers both quick and named modes and forwards a real
HTTP request through the fake tunnel and access proxy.

A live test against the real Cloudflare quick tunnel also passed:

```text
Tunnel is live at <random>.trycloudflare.com:25565
HTTP/1.0 200 OK
```

Logs:

- `.agent-work/logs/test-cloudflare-tunnel-run.log`
- `.agent-work/logs/issue-19-cloudflare-real-test.log`

## Remaining limitation

A Cloudflare TCP tunnel cannot be a normal public `host:port` for vanilla
Minecraft clients: every player needs Modflared or the cloudflared client.
If a no-client public address is required, keep using Minekube, LocalXpose,
bore, Serveo or Pinggy, or use Cloudflare Spectrum (paid).
