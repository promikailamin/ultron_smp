#!/usr/bin/env python3
"""Audit mob-head abilities and their cooldown configuration.

This does not need a Paper server; it statically verifies that every active
HeadAbility has a matching `powers.<id>-cooldown` entry in both bundled configs.
Run from the repository root.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
ENUM = ROOT / "paper-plugin/src/main/java/minecraft/heads/HeadAbility.java"
CONFIGS = [
    ROOT / "paper-plugin/src/main/resources/config.yml",
    ROOT / "plugins/Heads/config.yml",
]

text = ENUM.read_text()
entries = re.findall(r'^\s*([A-Z_]+)\("([^"]+)",\s*"[^"]+",\s*([0-9.]+),\s*(true|false)\)',
                     text, re.MULTILINE)
if not entries:
    sys.exit("could not parse HeadAbility.java")

errors = []
active = [entry for entry in entries if entry[3] == "true"]
for config in CONFIGS:
    body = config.read_text()
    for _name, ability_id, _cooldown, _active in active:
        key = f"  {ability_id}-cooldown:"
        if key not in body:
            errors.append(f"{config.relative_to(ROOT)} is missing powers.{ability_id}-cooldown")

print(f"abilities={len(entries)} active={len(active)} configs={len(CONFIGS)}")
if errors:
    for error in errors:
        print(f"FAIL {error}")
    sys.exit(1)
print("ISSUE-28 ABILITY CONFIG AUDIT PASS")
