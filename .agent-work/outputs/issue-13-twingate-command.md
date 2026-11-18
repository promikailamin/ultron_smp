# Issue 13 - Use the Twingate connector command

## Request

Issue 13 supplies the Docker command Twingate prints for its connector and asks
for it to be used. The command includes:

- `twingate/connector:1`
- `--sysctl net.ipv4.ping_group_range="0 2147483647"`
- `TWINGATE_NETWORK=mikailamin`
- `TWINGATE_LABEL_HOSTNAME=ultronsmp.net`
- `TWINGATE_LABEL_DEPLOYED_BY=docker`
- container name `twingate-mccon`
- `--restart=unless-stopped`
- `--pull=always`

The token values in the issue body are empty, so the implementation continues
to read them from `TWINGATE_ACCESS_TOKEN` / `TWINGATE_REFRESH_TOKEN` only. No
token was pasted into the repository or logs. The backticks around
`ultronsmp.net` in the issue body were treated as a copy/paste artifact; the
label is the literal value `ultronsmp.net`.

## Changes

- `scripts/twingate-connector.sh`
  - Defaults now match the issue: container `twingate-mccon`, network
    `mikailamin` fallback from the workflow, `--pull always`, hostname/deployed
    labels, and `--restart unless-stopped`.
  - Added `TWINGATE_DOCKER_PULL`, `TWINGATE_LABEL_HOSTNAME` and
    `TWINGATE_LABEL_DEPLOYED_BY` overrides.
  - Added `TWINGATE_DOCKER_NETWORK=default`, which omits `--network` so the
    connector uses Docker's default bridge network exactly like the issue
    command, while still adding the recommended sysctl.
  - Kept host networking as the repository default because the `minecraft`
    Twingate Resource points at `127.0.0.1:25565` on the runner. Docker rejects
    the ping_group_range sysctl in the host namespace, so it is still omitted
    for host mode.
- `.github/workflows/run.yml`
  - Uses `TWINGATE_NETWORK=mikailamin` as the fallback after repository
    variable/secret overrides.
  - Sets `twingate-mccon`, `--pull always`, hostname `ultronsmp.net` and
    deployed-by `docker` defaults, all overridable with repository variables.
- `.github/workflows/twingate-resource.yml`
  - Uses the same `mikailamin` network fallback so the Resource and Connector
    target the same tenant.
- `README.md`
  - Documents the new defaults, overrides and `default` network mode.
- `.agent-work/tests/test_twingate_connector.sh`
  - Fake Docker now records pull policy and labels and asserts the new
    defaults.
  - Added coverage for `TWINGATE_DOCKER_NETWORK=default` (no `--network`,
    sysctl present).

## Validation

Commands run successfully:

```bash
bash -n scripts/twingate-connector.sh
bash -n .agent-work/tests/test_twingate_connector.sh
shellcheck scripts/twingate-connector.sh
shellcheck .agent-work/tests/test_twingate_connector.sh
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml")); yaml.safe_load(open(".github/workflows/twingate-resource.yml"))'
bash .agent-work/tests/test_twingate_connector.sh
bash .agent-work/tests/test_twingate_docker_host.sh
```

The real-Docker test confirmed the host-network sysctl conflict still exists
and that `twingate/connector:1` starts under host networking without the
sysctl. It used fake credentials only and reported `Authentication` / `Error`,
which is expected without real tokens.

## Limitation

An end-to-end Twingate connection still requires the real connector token
pair and the Admin Console Resource assignment. Those secrets were not
available to this run and were not required to validate the command wiring.
