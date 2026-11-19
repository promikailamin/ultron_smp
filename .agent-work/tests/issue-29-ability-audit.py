#!/usr/bin/env python3
"""Static audit for the issue-29 head ability changes.

Run from the repository root:
    python3 .agent-work/tests/issue-29-ability-audit.py
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
ENUM = ROOT / "paper-plugin/src/main/java/minecraft/heads/HeadAbility.java"
ABILITIES = ROOT / "paper-plugin/src/main/java/minecraft/heads/HeadAbilities.java"
POWERS = ROOT / "paper-plugin/src/main/java/minecraft/heads/HeadPowers.java"
CONFIGS = [
    ROOT / "paper-plugin/src/main/resources/config.yml",
    ROOT / "plugins/Heads/config.yml",
]

entry_re = re.compile(
    r'^\s*([A-Z_]+)\("([^"]+)",\s*"[^"]+",\s*([0-9.]+),\s*(true|false)\)',
    re.MULTILINE,
)
text = ENUM.read_text()
entries = entry_re.findall(text)
if not entries:
    sys.exit("could not parse HeadAbility.java")

by_name = {name: {"id": ability_id, "cooldown": float(cooldown), "active": active == "true"}
           for name, ability_id, cooldown, active in entries}

errors = []


def expect(condition, message):
    if not condition:
        errors.append(message)


for name, info in by_name.items():
    if info["active"]:
        expect(info["cooldown"] == 0.0,
               f"active ability {name} has a non-zero default cooldown")

for config in CONFIGS:
    body = config.read_text()
    expect(re.search(r'^\s*cooldowns-enabled:\s*false\s*$', body, re.MULTILINE) is not None,
           f"{config.relative_to(ROOT)} does not disable cooldowns")
    for name, info in by_name.items():
        if not info["active"]:
            continue
        match = re.search(rf'^\s*{re.escape(info["id"])}-cooldown:\s*([0-9.]+)\s*$',
                          body, re.MULTILINE)
        if match is None:
            errors.append(f'{config.relative_to(ROOT)} is missing powers.{info["id"]}-cooldown')
        elif float(match.group(1)) != 0.0:
            errors.append(f'{config.relative_to(ROOT)} has nonzero {info["id"]}-cooldown')

abilities_text = ABILITIES.read_text()

flight_sets = re.search(r'FLIGHT_MOBS\s*=\s*Set\.of\((.*?)\);', abilities_text, re.DOTALL)
expect(flight_sets is not None and '"BLAZE"' in flight_sets.group(1),
       "BLAZE is missing from FLIGHT_MOBS")

grants = {}
for ability_name, mobs in re.findall(
        r'grant\(abilities,\s*key,\s*HeadAbility\.([A-Z_]+),([^;]+)\);',
        abilities_text, re.DOTALL):
    grants.setdefault(ability_name, set()).update(re.findall(r'"([A-Z0-9_]+)"', mobs))


def has(ability, mob):
    return mob in grants.get(ability, set())


expect(has("DRAGON_BREATH", "ENDER_DRAGON"),
       "Ender dragon is missing DRAGON_BREATH")
expect(not has("FIRE_CHARGE", "ENDER_DRAGON"),
       "Ender dragon still uses FIRE_CHARGE")
expect(has("WIND_BURST", "BREEZE"),
       "Breeze is missing WIND_BURST")
expect(not has("FIRE_CHARGE", "BREEZE"),
       "Breeze still uses FIRE_CHARGE")
expect(has("PIG_GUARDS", "PIG"),
       "Pig is missing passive PIG_GUARDS")
expect(by_name.get("PIG_GUARDS", {}).get("active") is False,
       "PIG_GUARDS must be passive")
expect(has("FIRE_CHARGE", "BLAZE"),
       "Blaze should still throw fire charges")
expect(has("WITHER_HIT", "WITHER_SKELETON"),
       "Wither skeleton should wither targets on hit")

for mob in ("PIG",):
    for ability_name, mobs in grants.items():
        if mob in mobs:
            expect(by_name[ability_name]["active"] is False,
                   f"{mob} still has active ability {ability_name}")

powers_text = POWERS.read_text()
expect("if (now >= nextRecruit)" in powers_text and "commandPigs(owner);" in powers_text,
       "HeadPowers does not passively call pigs from the tick loop")
expect("interactionConsumesClick(player, event, action)" in powers_text,
       "HeadPowers is missing the interaction guard")
expect("isInteractiveBlock(block)" in powers_text,
       "HeadPowers is missing interactive-block detection")
expect("player.getTargetEntity(6, false)" in powers_text,
       "HeadPowers is missing entity crosshair detection")

print(f"abilities={len(entries)} active={sum(1 for e in entries if e[3] == 'true')} "
      f"configs={len(CONFIGS)}")
if errors:
    for error in errors:
        print(f"FAIL {error}")
    sys.exit(1)
print("ISSUE-29 ABILITY AUDIT PASS")
