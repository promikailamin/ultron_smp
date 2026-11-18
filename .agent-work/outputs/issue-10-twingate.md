# Issue 10 - Use the new Twingate secrets

## Request

Issue 10 says that `TWINGATE_ACCESS_TOKEN` and `TWINGATE_REFRESH_TOKEN` have
been added as GitHub Actions secrets and asks for Twingate to be used in
`run.yml`.

## Changes

- `.github/workflows/run.yml`
  - `TWINGATE_ENABLED` now defaults to `auto` instead of `false`.
  - `TWINGATE_NETWORK` can be supplied as a repository variable or secret.
  - The `start_twingate_connector` step no longer requires
    `TWINGATE_ENABLED=true`; it runs the connector script, which starts the
    connector whenever both token secrets are present.
  - The step remains `continue-on-error: true`, so Twingate problems cannot
    stop the Minecraft server or its public tunnel.
  - Connector output is saved to `.agent-work/logs/twingate-connector.log`.
- `scripts/twingate-connector.sh`
  - Added `auto`, `true`/`required`, and `false`/`disabled` modes.
  - `auto` skips cleanly when neither token is configured.
  - If `TWINGATE_NETWORK` is not supplied, the network slug is read from the
    connector access token's JWT `aud` claim. This lets the two new secrets
    work without adding another repository variable.
  - Partial or missing credentials still fail with a clear error, and token
    values are never printed.
- `README.md`
  - Updated setup instructions to require only the two token secrets.
  - Documented automatic startup, the disable/require overrides, and the
    optional network fallback/override.
- `.agent-work/tests/test_twingate_connector.sh`
  - Validates the workflow wiring.
  - Keeps the existing env-only token, cleanup and status checks.
  - Adds coverage for deriving the network from the access token.
  - Adds coverage for auto-skip when tokens are absent.
  - Adds coverage for explicit `true` mode failing without tokens.
  - Adds coverage that an explicit `TWINGATE_NETWORK` wins over the token.

## Security

The connector tokens are read only from the environment, written to a mode-600
temporary env file, passed to Docker with `--env-file`, and removed on exit.
No real token value is stored in the repository, tests or logs. The test suite
uses generated fake tokens.

## Validation

Run and passed:

```bash
bash -n scripts/twingate-connector.sh
bash -n .agent-work/tests/test_twingate_connector.sh
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/run.yml"))'
bash .agent-work/tests/test_twingate_connector.sh
```

Logs are in `.agent-work/logs/test-twingate-connector-*.log`.

## Limitation

A real Twingate end-to-end connection still needs a valid connector token
pair, an Admin Console Connector/Resource, and network access from the runner.
Those credentials are not available to this test run, so only the repository
integration and token handling were validated.
