# Issue 11 - Fix the Twingate connector startup failure

## Root cause

The `start_twingate_connector` step in `.github/workflows/run.yml` failed with:

```text
docker: Error response from daemon: failed to create task for container:
failed to create shim task: OCI runtime create failed: runc create failed:
sysctl "net.ipv4.ping_group_range" not allowed in host network namespace
```

`scripts/twingate-connector.sh` started the connector with both
`--network host` and `--sysctl net.ipv4.ping_group_range="0 2147483647"`.
Docker rejects that sysctl when the container shares the host network
namespace, so the connector container never started. Because the workflow
step uses `continue-on-error: true`, the failure appeared as a warning while
the rest of the server run continued.

## Changes

- `scripts/twingate-connector.sh`
  - Added `TWINGATE_DOCKER_NETWORK` (default `host`).
  - Applies the Twingate-recommended `net.ipv4.ping_group_range` sysctl only
    for non-host network modes.
  - Host networking is kept so the connector can reach the Minecraft server at
    `127.0.0.1:25565` on the runner.
- `.agent-work/tests/test_twingate_connector.sh`
  - Fake Docker now reproduces the real host-netns sysctl restriction.
  - Asserts that host mode does not pass the sysctl.
  - Adds a bridge-mode case that asserts the sysctl is still passed.
- `.agent-work/tests/test_twingate_docker_host.sh`
  - Real-Docker regression test. It reproduces the exact failure and verifies
    that `twingate/connector:1` starts with host networking when the sysctl is
    omitted. Uses generated fake credentials only.
- `README.md`
  - Documents the host-network behaviour and the optional
    `TWINGATE_DOCKER_NETWORK` override.

## Validation

Passed:

```bash
bash -n scripts/twingate-connector.sh
bash -n .agent-work/tests/test_twingate_connector.sh
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml"))'
bash .agent-work/tests/test_twingate_connector.sh
bash .agent-work/tests/test_twingate_docker_host.sh
```

The real-Docker test reproduced the original error and then showed the
connector image starting successfully without the sysctl. With fake
credentials the connector reports `State: Authentication` / `State: Error`,
as expected; a real token pair is still required for an end-to-end tunnel.

Logs:

- `.agent-work/logs/test-twingate-connector-*.log`
- `.agent-work/logs/test-twingate-docker-host-sysctl.log`
