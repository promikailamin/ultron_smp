# Issue 9 - Optional Twingate connector

## Decision

Twingate is a zero-trust private-access service, not a public reverse tunnel.
It therefore cannot replace Minekube/LocalXpose/bore for public Minecraft
traffic and does not have a public `SERVER_ADDRESS.txt` endpoint of its own.
Twingate was added as an optional connector that runs alongside the chosen
public tunnel.

## Token handling

The value pasted into issue 9 is a short-lived Twingate JWT. It was not used,
printed, written to a file or committed. It is not a connector access/refresh
token pair and cannot configure a Connector by itself. Because it was posted
publicly, it should be revoked in the Twingate Admin Console.

Connector credentials are read only from:

- repository variable `TWINGATE_NETWORK`
- repository secret `TWINGATE_ACCESS_TOKEN`
- repository secret `TWINGATE_REFRESH_TOKEN`

The script writes those values to a mode-600 temporary env file under the
runner temp directory, passes it to Docker, and removes it on exit. Token
values are never echoed in logs.

## Implementation

- `scripts/twingate-connector.sh` starts, stops and checks a pinend
  pinned `twingate/connector:1` container with host networking.
- `scripts/twingate-connector.sh start` is gated by `TWINGATE_ENABLED=true`
  in the server workflow and uses `continue-on-error`, so a Twingate problem
  cannot take down the public tunnel.
- The final restore step stops the connector before the server is backed up.
- `README.md` documents the required Admin Console Resource and secret setup.
- `.agent-work/tests/test_twingate_connector.sh` uses a fake Docker binary to
  verify env-only token handling, cleanup, status/stop behaviour and failure
  when a token is missing.

## Limitations

A real end-to-end Twingate connection cannot be tested here because it needs
a valid Connector token pair, a tenant network slug and an Admin Console
Resource. The automated test validates the repository-side integration and
secret handling only.
