# Issue 16 - Change the free tunnel region for Bangladesh

## Request

Issue 16 asks whether the free-tier tunnel region can be changed for
Bangladesh to improve ping.

## Answer

Yes. The **Run UltronSMP** workflow now has a **Free relay region** input.
Choose `bangladesh` (or `asia-pacific`) with the `pinggy` or `tunnl` provider
to force Pinggy's Singapore relay. `auto` keeps the previous behaviour and
measures every free relay before choosing.

Pinggy's free tier has no relay located inside Bangladesh. `bangladesh` maps
to the Asia-Pacific/Singapore relay (`ap.free.pinggy.io`); the other choices
are `us.free.pinggy.io` and `eu.free.pinggy.io`.

Important: the Minecraft server itself still runs on a GitHub Actions runner
in the United States. Forcing the Singapore relay shortens the
player-to-relay hop but lengthens the relay-to-runner hop, so it is not
always faster than `united-states`. `auto` remains the safest default because
it measures both legs and applies the existing `PLAYER_REGION=bangladesh`
preference margin.

## Changes

- `.github/workflows/run.yml`
  - Added the `tunnel_region` workflow input with `auto`, `bangladesh`,
    `asia-pacific`, `united-states` and `europe`.
  - Passes it to the scripts as `TUNNEL_REGION`.
- `scripts/low-latency-tunnel.sh`
  - Added `TUNNEL_REGION` support with friendly aliases for Bangladesh/South
    Asia (`bangladesh`, `bd`, `asia-pacific`, `ap`, `singapore`, `india`, ...),
    the US and Europe.
  - `PINGGY_SERVER` still overrides region selection for advanced use.
  - The generated `SERVER_ADDRESS.txt` records the selected free relay.
- `scripts/tunnel.sh`
  - Documents that `TUNNEL_REGION` is forwarded to the Pinggy/tunnl path.
- `README.md`
  - Documents the new input, the relay mapping, and the US-runner trade-off.
- `.agent-work/tests/test_low_latency_region.sh`
  - Uses a fake SSH client to verify the Bangladesh/AP, US and EU mappings and
    the workflow input wiring without contacting public relays.

## How to use

1. Open **Actions > Run UltronSMP > Run workflow**.
2. Set **Public tunnel provider** to `pinggy` or `tunnl`.
3. Set **Free relay region** to `bangladesh` (or `auto` to measure).
4. Start the run and use the address written to `SERVER_ADDRESS.txt`.

## Validation

Passed locally:

```bash
bash -n scripts/low-latency-tunnel.sh
bash -n scripts/tunnel.sh
bash -n .agent-work/tests/test_low_latency_region.sh
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml"))'
bash .agent-work/tests/test_low_latency_region.sh
bash .agent-work/tests/test_tunnel.sh
bash .agent-work/tests/test_serveo_tunnel.sh
bash .agent-work/tests/test_tunnl_provider.sh
```

`test_low_latency_region.sh` confirmed:

```text
TUNNEL_REGION=bangladesh -> ap.free.pinggy.io
TUNNEL_REGION=asia-pacific -> ap.free.pinggy.io
TUNNEL_REGION=united-states -> us.free.pinggy.io
TUNNEL_REGION=europe -> eu.free.pinggy.io
```

Logs are in `.agent-work/logs/test-low-latency-region.log` and
`.agent-work/logs/test-region-tunnel-suite.log`.

## Limitation

No free Pinggy relay is hosted in Bangladesh, and the server runner location
cannot be changed from this repository. A real Bangladesh host (or a VPS in
Singapore/India/Bangladesh) is the only way to remove the long
relay-to-runner distance.
