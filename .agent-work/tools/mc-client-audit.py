#!/usr/bin/env python3
"""Download a Minecraft client.jar, unpack it and compare its entity list with
the mob heads shipped by this plugin.

The client jar is cached under .agent-work/tmp and is never committed. The
small text report is written under .agent-work/outputs.
"""
from pathlib import Path
import hashlib
import json
import os
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".agent-work/tmp/mc-client"
OUTPUTS = ROOT / ".agent-work/outputs"
VERSION = os.environ.get("MC_VERSION", "26.3")


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "ultron-smp/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


manifest = fetch_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
version = next((entry for entry in manifest["versions"] if entry["id"] == VERSION), None)
if version is None:
    raise SystemExit(f"Minecraft version {VERSION} is not in the version manifest")

metadata = fetch_json(version["url"])
client = metadata["downloads"]["client"]
jar = CACHE / f"client-{VERSION}.jar"
jar.parent.mkdir(parents=True, exist_ok=True)
if not jar.exists() or hashlib.sha1(jar.read_bytes()).hexdigest() != client["sha1"]:
    request = urllib.request.Request(client["url"], headers={"User-Agent": "ultron-smp/1.0"})
    with urllib.request.urlopen(request, timeout=180) as response:
        jar.write_bytes(response.read())
if hashlib.sha1(jar.read_bytes()).hexdigest() != client["sha1"]:
    raise SystemExit("downloaded client jar failed its SHA-1 check")

with zipfile.ZipFile(jar) as archive:
    language = json.loads(archive.read("assets/minecraft/lang/en_us.json"))
    prefix = "data/minecraft/loot_table/entities/"
    mob_ids = sorted(
        name[len(prefix):-len(".json")]
        for name in archive.namelist()
        if name.startswith(prefix) and name.endswith(".json")
        and "/" not in name[len(prefix):]
    )

textures = ROOT / "paper-plugin/src/main/resources/heads-textures.tsv"
plugin_types = set()
for line in textures.read_text().splitlines():
    if not line or line.startswith("#"):
        continue
    plugin_types.add(line.split("\t", 1)[0])

vanilla_mobs = [name for name in mob_ids if name != "player"]
aliases = {
    "MOOSHROOM": {"MUSHROOM_COW", "MOOSHROOM"},
    "SNOW_GOLEM": {"SNOWMAN", "SNOW_GOLEM"},
    "ZOMBIFIED_PIGLIN": {"PIG_ZOMBIE", "ZOMBIFIED_PIGLIN"},
}


def covered(name):
    key = name.upper()
    return bool(aliases.get(key, {key}) & plugin_types)


missing = [name for name in vanilla_mobs if not covered(name)]
vanilla_upper = {name.upper() for name in vanilla_mobs}
extra = sorted(name for name in plugin_types if name not in vanilla_upper
               and not any(name in names for names in aliases.values()))

OUTPUTS.mkdir(parents=True, exist_ok=True)
report = OUTPUTS / f"mc-client-{VERSION}-entities.txt"
lines = [
    f"Minecraft client {VERSION}",
    f"client jar: {jar} (sha1 {client['sha1']})",
    f"vanilla living mob ids: {len(vanilla_mobs)}",
    f"plugin head types: {len(plugin_types)}",
    f"vanilla mobs without a plugin head: {len(missing)}",
    f"plugin heads not in this client version: {len(extra)}",
    "",
    "VANILLA LIVING MOB\tPLUGIN HEAD",
]
lines.extend(f"{name}\t{'yes' if covered(name) else 'no'}" for name in vanilla_mobs)
lines.extend(["", "MISSING VANILLA MOBS"])
lines.extend(missing or ["(none)"])
lines.extend(["", "EXTRA PLUGIN HEADS"])
lines.extend(extra or ["(none)"])
report.write_text("\n".join(lines) + "\n")

print(f"MC_CLIENT_AUDIT version={VERSION} mobs={len(vanilla_mobs)} "
      f"plugin_heads={len(plugin_types)} missing={len(missing)} extra={len(extra)}")
print(f"report={report.relative_to(ROOT)}")
