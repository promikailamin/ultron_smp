# Issue 15 - Minecraft "Connection refused" through Twingate

## Root cause

The Twingate Resource was configured with the address `127.0.0.1:25565`.
`127.0.0.1` is always the loopback address of the device that opens the
connection. On the player's PC, Minecraft therefore tried to connect to the
player's own PC, not to the GitHub Actions runner behind the Twingate
Connector, and the local port was closed.

`127.0.0.1` is the correct target for the Connector (the Minecraft server runs
on the same runner), but it is the wrong address for Twingate clients. The
client-facing address must be different, which is what Twingate Resource
aliases are for.

## Fix

`scripts/twingate-resource.py` now creates or updates the `minecraft`
Resource with both:

- Address `127.0.0.1` - reachable from the Connector on the runner.
- Alias `minecraft.ultronsmp.internal` - the address Twingate clients use.

Players should connect Minecraft to:

```text
minecraft.ultronsmp.internal:25565
```

The alias is intercepted by the Twingate Client and forwarded through the
Connector to `127.0.0.1:25565`. It only resolves while the Twingate Client is
connected and the Connector is online; no public DNS record is needed.

The alias is idempotently added to an existing Resource, so the Resource that
was already provisioned does not need to be deleted.

## Configuration

New optional repository variable:

- `TWINGATE_RESOURCE_ALIAS` - defaults to
  `minecraft.ultronsmp.internal`. Set it to `none` to create the Resource
  without an alias, or to another DNS-valid name to customise it.

Existing settings are unchanged:

- `TWINGATE_RESOURCE_ADDRESS=127.0.0.1`
- `TWINGATE_RESOURCE_PORT=25565`
- `TWINGATE_RESOURCE_GROUP_NAMES=Everyone`

All four Twingate workflows pass the alias setting. The admin status output
also shows each Resource's alias.

## Validation

Passed locally:

```bash
python3 -m py_compile scripts/twingate-resource.py scripts/twingate-admin.py
bash -n .agent-work/tests/test_twingate_resource.sh
python3 -c 'import yaml; [yaml.safe_load(open(p)) for p in [".github/workflows/run.yml",".github/workflows/twingate-resource.yml",".github/workflows/twingate-admin.yml",".github/workflows/twingate-verify.yml"]]'
bash .agent-work/tests/test_twingate_resource.sh
bash .agent-work/tests/test_twingate_admin.sh
bash .agent-work/tests/test_twingate_connector.sh
bash .agent-work/tests/test_run.sh
```

The resource test covers resource creation with the alias, idempotent reruns,
adding the alias to a pre-existing Resource that has none, group assignment,
network selection and token secrecy.

## How to apply

1. Run the **Ensure Twingate Resource** workflow once (or restart **Run
   UltronSMP**, which runs the same step).
2. In the Twingate Client, make sure the `minecraft` Resource is allowed for
   your user.
3. Connect Minecraft to `minecraft.ultronsmp.internal:25565`, not
   `127.0.0.1:25565`.

If the alias still does not resolve, update the Twingate Client and Connector
to versions that support Resource aliases (Connector 1.50.0 or newer).
