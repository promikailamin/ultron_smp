#!/usr/bin/env python3
"""Inspect and provision the Twingate account used by Ultron SMP.

The script uses the Twingate Admin GraphQL API with an Admin API token from
Settings > API. It never prints token values. Use it to discover the account's
Remote Networks, Connectors and Resources, and optionally to create the missing
Remote Network/Connector and generate Connector tokens.

Commands:
  status     Show Remote Networks, Connectors and Resources.
  provision  Create a Remote Network and Connector when they are missing.
             Set TWINGATE_GENERATE_TOKENS=true to generate a new Connector
             token pair (requires a Read, Write & Provision API token) and
             write it to TWINGATE_TOKEN_ENV_FILE with mode 600.

Credentials are read only from the environment.
"""

import importlib.util
import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESOURCE_SCRIPT = os.path.join(SCRIPT_DIR, "twingate-resource.py")


def load_resource_module():
    spec = importlib.util.spec_from_file_location("twingate_resource", RESOURCE_SCRIPT)
    if spec is None or spec.loader is None:
        raise SystemExit("could not load scripts/twingate-resource.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


resource = load_resource_module()

TwingateError = resource.TwingateError
env = resource.env
redact = resource.redact


def truthy(value):
    return value.strip().lower() in {"1", "true", "yes", "on"}


def token_tenant(token):
    """Return the tenant/network slug embedded in a token, if it is a JWT."""
    return resource.derive_network_from_token(token)


def make_client():
    token = env("TWINGATE_API_TOKEN")
    if not token:
        raise TwingateError(
            "TWINGATE_API_TOKEN is required. Add a Read, Write & Provision "
            "Admin API token from Settings > API."
        )

    candidates = resource.network_candidates(token)
    last_error = None
    for tenant in candidates:
        client = resource.Client(token, tenant)
        try:
            # Validate the tenant and token before returning the client.
            client.query("query { remoteNetworks(first: 1) { edges { node { id } } } }")
            return client, tenant
        except TwingateError as exc:
            last_error = exc

    if last_error:
        raise last_error
    raise TwingateError("Could not reach the Twingate Admin API.")


def list_remote_networks(client):
    return resource.paginate(
        client,
        "remoteNetworks",
        "id name networkType location isActive",
    )


def list_connectors(client):
    return resource.paginate(
        client,
        "connectors",
        "id name hostname state remoteNetwork { id name }",
    )


def list_resources(client):
    return resource.paginate(
        client,
        "resources",
        "id name address { type value } remoteNetwork { id name }",
    )


def format_resource_address(item):
    address = item.get("address") or {}
    value = address.get("value")
    return value if isinstance(value, str) and value else "?"


def print_status(client, tenant):
    networks = list_remote_networks(client)
    connectors = list_connectors(client)
    resources = list_resources(client)

    print("Twingate tenant: {}".format(tenant))
    print("Remote Networks ({}):".format(len(networks)))
    for item in networks:
        print(
            "  - {} | id={} | type={} | location={} | active={}".format(
                item.get("name", "?"),
                item.get("id", "?"),
                item.get("networkType", "?"),
                item.get("location", "?"),
                item.get("isActive", "?"),
            )
        )

    print("Connectors ({}):".format(len(connectors)))
    for item in connectors:
        network = item.get("remoteNetwork") or {}
        print(
            "  - {} | id={} | hostname={} | state={} | network={} ({})".format(
                item.get("name", "?"),
                item.get("id", "?"),
                item.get("hostname", "?"),
                item.get("state", "?"),
                network.get("name", "?"),
                network.get("id", "?"),
            )
        )

    print("Resources ({}):".format(len(resources)))
    for item in resources:
        network = item.get("remoteNetwork") or {}
        print(
            "  - {} | id={} | address={} | network={} ({})".format(
                item.get("name", "?"),
                item.get("id", "?"),
                format_resource_address(item),
                network.get("name", "?"),
                network.get("id", "?"),
            )
        )

    return networks, connectors, resources


def select_or_create_remote_network(client, networks):
    configured_id = env("TWINGATE_REMOTE_NETWORK_ID")
    if configured_id:
        for item in networks:
            if item.get("id") == configured_id:
                return item, False
        raise TwingateError(
            "No Remote Network with id '{}' exists.".format(configured_id)
        )

    configured_name = env("TWINGATE_REMOTE_NETWORK")
    if configured_name:
        for item in networks:
            if item.get("name") == configured_name:
                return item, False
        if networks:
            names = ", ".join(sorted(item.get("name", "?") for item in networks))
            raise TwingateError(
                "No Remote Network named '{}' exists (found: {}).".format(
                    configured_name, names
                )
            )
        desired_name = configured_name
    elif len(networks) == 1:
        return networks[0], False
    elif networks:
        names = ", ".join(sorted(item.get("name", "?") for item in networks))
        raise TwingateError(
            "Multiple Remote Networks exist ({}). Set TWINGATE_REMOTE_NETWORK "
            "or TWINGATE_REMOTE_NETWORK_ID.".format(names)
        )
    else:
        desired_name = env("TWINGATE_NEW_REMOTE_NETWORK", "Ultron SMP")

    query = """
    mutation($name: String!) {
      remoteNetworkCreate(name: $name) {
        ok
        error
        entity { id name networkType location isActive }
      }
    }
    """
    data = client.query(query, {"name": desired_name})
    result = data.get("remoteNetworkCreate") or {}
    if not result.get("ok"):
        raise TwingateError(
            "Twingate could not create the Remote Network: {}.".format(
                redact(str(result.get("error") or "unknown error"))
            )
        )
    entity = result.get("entity") or {}
    print("Created Remote Network '{}' ({}).".format(entity.get("name"), entity.get("id")))
    return entity, True


def select_or_create_connector(client, connectors, network_id):
    name = env("TWINGATE_CONNECTOR_NAME", "twingate-mccon")
    for item in connectors:
        item_network = item.get("remoteNetwork") or {}
        if item.get("name") == name and item_network.get("id") == network_id:
            return item, False

    query = """
    mutation($name: String!, $remoteNetworkId: ID!) {
      connectorCreate(name: $name, remoteNetworkId: $remoteNetworkId) {
        ok
        error
        entity { id name hostname state remoteNetwork { id name } }
      }
    }
    """
    data = client.query(query, {"name": name, "remoteNetworkId": network_id})
    result = data.get("connectorCreate") or {}
    if not result.get("ok"):
        raise TwingateError(
            "Twingate could not create the Connector: {}.".format(
                redact(str(result.get("error") or "unknown error"))
            )
        )
    entity = result.get("entity") or {}
    print("Created Connector '{}' ({}).".format(entity.get("name"), entity.get("id")))
    return entity, True


def generate_connector_tokens(client, connector_id):
    query = """
    mutation($connectorId: ID!) {
      connectorGenerateTokens(connectorId: $connectorId) {
        ok
        error
        connectorTokens { accessToken refreshToken }
      }
    }
    """
    data = client.query(query, {"connectorId": connector_id})
    result = data.get("connectorGenerateTokens") or {}
    if not result.get("ok"):
        raise TwingateError(
            "Twingate could not generate Connector tokens: {}. The API token "
            "must have Read, Write & Provision permission.".format(
                redact(str(result.get("error") or "unknown error"))
            )
        )
    tokens = result.get("connectorTokens") or {}
    access = tokens.get("accessToken")
    refresh = tokens.get("refreshToken")
    if not access or not refresh:
        raise TwingateError("Twingate returned incomplete Connector tokens.")
    return access, refresh


def write_token_env(path, network, access, refresh):
    if not path:
        raise TwingateError(
            "Set TWINGATE_TOKEN_ENV_FILE so generated tokens are not printed."
        )
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write("TWINGATE_NETWORK={}\n".format(network))
        handle.write("TWINGATE_ACCESS_TOKEN={}\n".format(access))
        handle.write("TWINGATE_REFRESH_TOKEN={}\n".format(refresh))
    print("Connector tokens written to a mode-600 temporary file.")


def provision(client, tenant):
    networks = list_remote_networks(client)
    network, _ = select_or_create_remote_network(client, networks)
    network_id = network.get("id")
    if not network_id:
        raise TwingateError("The Remote Network has no id.")

    connectors = list_connectors(client)
    connector, _ = select_or_create_connector(client, connectors, network_id)
    connector_id = connector.get("id")
    if not connector_id:
        raise TwingateError("The Connector has no id.")

    if truthy(env("TWINGATE_GENERATE_TOKENS")):
        access, refresh = generate_connector_tokens(client, connector_id)
        write_token_env(
            env("TWINGATE_TOKEN_ENV_FILE"), tenant, access, refresh
        )
    else:
        print(
            "Connector token generation skipped. Set TWINGATE_GENERATE_TOKENS=true "
            "to rotate/generate a token pair."
        )

    print("Provisioned Remote Network '{}' and Connector '{}'.".format(
        network.get("name", "?"), connector.get("name", "?")
    ))


def main(argv):
    command = argv[1] if len(argv) > 1 else "status"
    if command not in ("status", "provision"):
        print("Usage: twingate-admin.py [status|provision]", file=sys.stderr)
        return 2

    try:
        client, tenant = make_client()
        if command == "status":
            print_status(client, tenant)
        else:
            provision(client, tenant)
        return 0
    except TwingateError as exc:
        print("ERROR: {}".format(redact(str(exc))), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
