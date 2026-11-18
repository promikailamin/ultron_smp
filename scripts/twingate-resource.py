#!/usr/bin/env python3
"""Create the Ultron SMP Twingate Resource through the Twingate Admin API.

The Twingate Connector tokens used by twingate-connector.sh cannot create
Resources. Resource automation therefore needs an Admin API token from
Settings > API in the Twingate Admin Console. For older installs the script
can fall back to TWINGATE_ACCESS_TOKEN, which only works if that value is
actually an Admin API token.

Credentials are read from the environment and are never printed.
"""

import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_NAME = "minecraft"
DEFAULT_ADDRESS = "127.0.0.1"
DEFAULT_PORT = 25565
PAGE_SIZE = 100


class TwingateError(Exception):
    pass


def env(name, default=""):
    value = os.environ.get(name, default)
    return value.strip() if isinstance(value, str) else value


def is_disabled(value):
    return value.lower() in {"false", "0", "no", "off", "disabled"}


def is_required(value):
    return value.lower() in {"true", "1", "yes", "on", "required"}


def api_token():
    token = env("TWINGATE_API_TOKEN")
    if token:
        return token

    fallback = env("TWINGATE_ACCESS_TOKEN")
    if fallback:
        print(
            "No TWINGATE_API_TOKEN configured; trying TWINGATE_ACCESS_TOKEN. "
            "Connector tokens cannot create Resources; use an Admin API token "
            "from Settings > API if this fails.",
            file=sys.stderr,
        )
        return fallback

    return ""


def network():
    value = env("TWINGATE_NETWORK")
    if not value:
        raise TwingateError(
            "TWINGATE_NETWORK is required to reach the Twingate Admin API."
        )
    if not all(ch.isalnum() or ch in ".-_" for ch in value):
        raise TwingateError("TWINGATE_NETWORK contains invalid characters.")
    return value


def resource_port():
    raw = env("TWINGATE_RESOURCE_PORT", str(DEFAULT_PORT))
    try:
        port = int(raw)
    except ValueError:
        raise TwingateError("TWINGATE_RESOURCE_PORT must be a number.")
    if not 1 <= port <= 65535:
        raise TwingateError("TWINGATE_RESOURCE_PORT must be between 1 and 65535.")
    return port


class Client:
    def __init__(self, token, tenant):
        self.endpoint = env("TWINGATE_API_URL") or "https://{}/api/graphql/".format(
            tenant
        )
        self.token = token

    def query(self, query, variables=None):
        payload = {"query": query}
        if variables:
            payload["variables"] = variables

        request = urllib.request.Request(
            self.endpoint,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "X-API-KEY": self.token,
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "ultron-smp-twingate-resource/1.0",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            message = "Twingate Admin API returned HTTP {}.".format(exc.code)
            if exc.code in (401, 403):
                message += (
                    " Authentication failed. Set TWINGATE_API_TOKEN to a "
                    "Read & Write Admin API token from Settings > API."
                )
            elif detail:
                message += " {}".format(redact(detail))
            raise TwingateError(message)
        except urllib.error.URLError as exc:
            raise TwingateError(
                "Could not reach the Twingate Admin API: {}.".format(exc.reason)
            )

        try:
            result = json.loads(body)
        except json.JSONDecodeError:
            raise TwingateError("Twingate Admin API returned invalid JSON.")

        if result.get("errors"):
            messages = []
            for error in result["errors"]:
                if isinstance(error, dict) and error.get("message"):
                    messages.append(error["message"])
            raise TwingateError(
                "Twingate Admin API error: {}.".format(
                    redact("; ".join(messages) or "unknown error")
                )
            )

        data = result.get("data")
        if not isinstance(data, dict):
            raise TwingateError("Twingate Admin API returned no data.")
        return data


def redact(text):
    for name in ("TWINGATE_API_TOKEN", "TWINGATE_ACCESS_TOKEN"):
        value = env(name)
        if value:
            text = text.replace(value, "<redacted>")
    return text


def paginate(client, field, selection):
    nodes = []
    after = None
    while True:
        query = """
        query($first: Int!, $after: String) {
          %s(first: $first, after: $after) {
            pageInfo { hasNextPage endCursor }
            edges { node { %s } }
          }
        }
        """ % (
            field,
            selection,
        )
        data = client.query(query, {"first": PAGE_SIZE, "after": after})
        connection = data.get(field) or {}
        for edge in connection.get("edges") or []:
            node = edge.get("node")
            if isinstance(node, dict):
                nodes.append(node)

        page_info = connection.get("pageInfo") or {}
        if not page_info.get("hasNextPage"):
            return nodes
        after = page_info.get("endCursor")
        if not after:
            raise TwingateError("Twingate Admin API pagination did not return a cursor.")


def select_remote_network(client):
    configured_id = env("TWINGATE_REMOTE_NETWORK_ID")
    if configured_id:
        return configured_id

    networks = paginate(client, "remoteNetworks", "id name")
    if not networks:
        raise TwingateError("No Twingate Remote Network exists in this account.")

    configured_name = env("TWINGATE_REMOTE_NETWORK")
    if configured_name:
        for item in networks:
            if item.get("name") == configured_name:
                return item.get("id")
        raise TwingateError(
            "No Twingate Remote Network named '{}' was found.".format(configured_name)
        )

    if len(networks) == 1:
        return networks[0].get("id")

    names = ", ".join(sorted(item.get("name", "?") for item in networks))
    raise TwingateError(
        "Multiple Twingate Remote Networks exist ({}). Set "
        "TWINGATE_REMOTE_NETWORK or TWINGATE_REMOTE_NETWORK_ID.".format(names)
    )


def find_resource(client, name):
    resources = paginate(
        client,
        "resources",
        "id name address { type value } remoteNetwork { id name }",
    )
    for item in resources:
        if item.get("name") == name:
            return item
    return None


def desired_protocols(port):
    return {
        "allowIcmp": False,
        "tcp": {
            "policy": "RESTRICTED",
            "ports": [{"start": port, "end": port}],
        },
        "udp": {
            "policy": "RESTRICTED",
            "ports": [],
        },
    }


def create_resource(client, name, address, network_id, port):
    query = """
    mutation($name: String!, $address: String!, $remoteNetworkId: ID!, $protocols: ProtocolsInput!) {
      resourceCreate(
        name: $name,
        address: $address,
        remoteNetworkId: $remoteNetworkId,
        protocols: $protocols
      ) {
        ok
        error
        entity { id name address { type value } }
      }
    }
    """
    data = client.query(
        query,
        {
            "name": name,
            "address": address,
            "remoteNetworkId": network_id,
            "protocols": desired_protocols(port),
        },
    )
    result = data.get("resourceCreate") or {}
    if not result.get("ok"):
        raise TwingateError(
            "Twingate could not create the resource: {}.".format(
                redact(result.get("error") or "unknown error")
            )
        )
    return result.get("entity") or {}


def update_resource(client, resource_id, address, network_id, port):
    query = """
    mutation($id: ID!, $address: String!, $remoteNetworkId: ID!, $protocols: ProtocolsInput!) {
      resourceUpdate(
        id: $id,
        address: $address,
        remoteNetworkId: $remoteNetworkId,
        protocols: $protocols
      ) {
        ok
        error
        entity { id name address { type value } }
      }
    }
    """
    data = client.query(
        query,
        {
            "id": resource_id,
            "address": address,
            "remoteNetworkId": network_id,
            "protocols": desired_protocols(port),
        },
    )
    result = data.get("resourceUpdate") or {}
    if not result.get("ok"):
        raise TwingateError(
            "Twingate could not update the resource: {}.".format(
                redact(result.get("error") or "unknown error")
            )
        )
    return result.get("entity") or {}


def ensure_resource():
    mode = env("TWINGATE_RESOURCE_ENABLED", env("TWINGATE_ENABLED", "auto"))
    token = api_token()

    if is_disabled(mode):
        print("Twingate resource automation is disabled.")
        return 0

    if not token:
        if is_required(mode):
            raise TwingateError(
                "Twingate resource automation is required, but no Admin API "
                "token is configured. Add TWINGATE_API_TOKEN from Settings > API."
            )
        print(
            "Twingate resource automation skipped: set TWINGATE_API_TOKEN to an "
            "Admin API token with Read & Write access (Settings > API)."
        )
        return 0

    name = env("TWINGATE_RESOURCE_NAME", DEFAULT_NAME)
    address = env("TWINGATE_RESOURCE_ADDRESS", DEFAULT_ADDRESS)
    port = resource_port()

    if not name:
        raise TwingateError("TWINGATE_RESOURCE_NAME must not be empty.")
    if not address:
        raise TwingateError("TWINGATE_RESOURCE_ADDRESS must not be empty.")

    client = Client(token, network())
    network_id = select_remote_network(client)
    existing = find_resource(client, name)

    if existing:
        entity = existing
        current_address = (existing.get("address") or {}).get("value")
        current_network = (existing.get("remoteNetwork") or {}).get("id")
        if current_address != address or current_network != network_id:
            entity = update_resource(
                client, existing.get("id"), address, network_id, port
            )
            print(
                "Twingate resource '{}' updated to {}:{}.".format(name, address, port)
            )
        else:
            print("Twingate resource '{}' already exists.".format(name))
    else:
        entity = create_resource(client, name, address, network_id, port)
        print("Twingate resource '{}' created at {}:{}.".format(name, address, port))

    print(
        "Resource id: {}. Assign users or groups to it in the Twingate Admin Console.".format(
            entity.get("id", "unknown")
        )
    )
    return 0


def main(argv):
    command = argv[1] if len(argv) > 1 else "ensure"
    if command not in ("ensure", "create"):
        print("Usage: twingate-resource.py [ensure]", file=sys.stderr)
        return 2

    try:
        return ensure_resource()
    except TwingateError as exc:
        print("ERROR: {}".format(redact(str(exc))), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
