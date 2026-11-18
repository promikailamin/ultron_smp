# Issue 14 - Configure Twingate

## What was found

`TWINGATE_API_TOKEN`, `TWINGATE_ACCESS_TOKEN`, `TWINGATE_REFRESH_TOKEN` and
`TWINGATE_NETWORK` are present as repository secrets. The Admin API token
worked, but the Twingate tenant had no Remote Network, Connector or Resource.
That is why the connector tokens could not produce a working private endpoint.

## What was provisioned

All objects were created with the Admin API through the new
**Twingate Admin** workflow. No token value was printed, written to the
repository or committed.

| Object | Value |
| --- | --- |
| Tenant | Resolved from `TWINGATE_NETWORK` (same tenant as the API token) |
| Remote Network | `Ultron SMP` - id `UmVtb3RlTmV0d29yazo0NjQ5` |
| Connector | `twingate-mccon` - id `Q29ubmVjdG9yOjEwNDIz` |
| Resource | `minecraft` - id `UmVzb3VyY2U6OTk1OA==` |
| Resource address | `127.0.0.1:25565`, TCP restricted to `25565`, UDP/ICMP disabled |
| Access | `Everyone` group assigned to the `minecraft` resource |

A fresh Connector access/refresh token pair was generated and written directly
to the `TWINGATE_ACCESS_TOKEN` / `TWINGATE_REFRESH_TOKEN` repository secrets
through `gh secret set`. The values exist only in GitHub Secrets and the
mode-600 temporary file that was deleted at the end of the workflow run.

## Verification runs

| Run | Result |
| --- | --- |
| Twingate Admin `44309254877` (status) | Confirmed the tenant was empty |
| Twingate Admin `44309354552` (provision + rotate tokens) | Created the Remote Network, Connector, token pair and resource |
| Verify Twingate Connector `44309522392` | Connector container healthy, log `State: Online`, API state `ALIVE` |
| Twingate Admin `44310470316` | Idempotent provisioning; assigned `Everyone` to the resource |
| Run UltronSMP `44310694698` | `start_twingate_connector` and `ensure_twingate_resource` passed; run cancelled after validation |
| Twingate Admin `44311505431` (final status) | Resource shows `groups=Everyone`; connector is `DEAD_HEARTBEAT_TOO_OLD` because the verification runner has stopped |

`Run UltronSMP` was deliberately cancelled after the Twingate steps so the
long-lived server loop did not stay online. The Twingate steps themselves
completed successfully before cancellation.

## Code changes

- `scripts/twingate-admin.py`
  - `status` lists Remote Networks, Connectors, Groups and Resources with
    their access groups.
  - `provision` creates a missing Remote Network/Connector, can generate a
    new Connector token pair into a mode-600 file, and is idempotent.
- `.github/workflows/twingate-admin.yml`
  - Manual status/provision workflow. `rotate_tokens=true` also updates the
    connector secrets with `gh secret set`.
- `.github/workflows/twingate-verify.yml`
  - Starts the connector on a runner, waits for health, prints the account
    state and connector log, then stops the connector.
- `scripts/twingate-resource.py`
  - Resolves `TWINGATE_RESOURCE_GROUP_NAMES` and assigns those groups when
    creating or updating the `minecraft` resource.
- `.github/workflows/run.yml`
  - Passes `TWINGATE_RESOURCE_GROUP_NAMES=Everyone` (overridable by a
    repository variable) so the resource keeps its access assignment.
- `.github/workflows/twingate-resource.yml`
  - Same group default for manual resource runs.
- `README.md` and `.agent-work/tests/`
  - Documented the admin/verify workflows and added admin/resource group tests.

## Local tests

Passed:

```bash
bash .agent-work/tests/test_twingate_connector.sh
bash .agent-work/tests/test_twingate_resource.sh
bash .agent-work/tests/test_twingate_admin.sh
bash .agent-work/tests/test_run.sh
python3 -m py_compile scripts/twingate-resource.py scripts/twingate-admin.py
```

## Remaining notes

- Twingate is a private-access service, not a public tunnel. The connector is
  online only while `run.yml` is running on the ephemeral GitHub runner. It
  therefore shows `DEAD_HEARTBEAT_TOO_OLD` whenever no run is active; start
  `run.yml` and it returns to `ALIVE` (verified in run `44309522392`).
- Each user who needs access must have the Twingate client installed and be a
  member of the `Everyone` group (or another group added to the resource).
- The public Minecraft tunnel is unchanged and remains the path for players
  who do not use Twingate.
