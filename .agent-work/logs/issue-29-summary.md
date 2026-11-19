# Issue 29 implementation summary

## Implemented
- Right-click abilities now trigger from air, nothing, or solid non-interactive
  blocks (for example dirt), even while holding normal items.
- Interactive blocks and entities (chests, doors, villagers, boats, etc.) keep
  their vanilla interaction and do not fire head abilities. Items that have a
  real right-click use (food, bows, buckets, block placement, tools on blocks)
  also keep priority.
- Cooldowns are disabled (`powers.cooldowns-enabled: false`) and every active
  ability defaults to `0.0` seconds.
- Pig is now passive: no right-click ability. It automatically calls nearby
  pigs to follow and protect the wearer.
- Blaze now has creative-style flight.
- Breeze now throws a wind charge instead of a fire charge.
- Ender dragon now throws a dragon fireball (dragon's breath) instead of a
  fire charge.
- Added passive on-hit powers: fire hit (blaze/magma/sulfur), poison hit
  (cave spider/bee/pufferfish), wither hit (wither/wither skeleton).
- Adjusted mappings toward vanilla behavior: piglins shoot arrows, wither
  skeletons wither on hit, blaze/ghast keep fire charges, breeze has wind burst.
- Head lore marks abilities as active or passive.

## Verification
- `paper-plugin/build.sh`: passed.
- `paper-plugin/test.sh` on Paper 26.3 build 256: passed.
  - `HEADS SELFTEST PASS types=92 fallbacks=0`
  - `HEADS GUI SELFTEST PASS pages=3 heads=92`
  - `HEADS POWERS SELFTEST PASS profiles=92`
  - `HEADS ABILITIES SELFTEST PASS abilities=143 unique=28 combos=45`
- `.agent-work/tests/issue-29-ability-audit.py`: passed.
- `.agent-work/tests/issue-28-ability-audit.py`: passed.
- `.agent-work/tools/mc-client-audit.py`: downloaded and unpacked Minecraft
  26.3 client.jar, compared its 92 living mob ids with the plugin heads.
  91/92 have heads; only the new `mannequin` entity has no texture in the
  bundled DropHeads data. Report: `.agent-work/outputs/mc-client-26.3-entities.txt`

## Commit
- `73688c6` Fix head ability interactions and mob-specific powers

Pushed to `main`.
