# Issue 28 implementation summary

## Implemented
- Added `HeadAbility` enum with 23 non-effect abilities and per-ability cooldowns.
- Every one of the 92 living mob-head profiles now has at least one non-effect
  ability (43 distinct ability combinations, 135 total assignments).
- Iron golem hits apply a large configurable knockback (`powers.iron-golem-knockback`).
- Allay, bee, bat, parrot, phantom, vex, breeze, ghast, happy ghast, ender dragon
  and wither heads grant creative-style survival flight.
- Breeze (plus blaze/ghast/ender dragon) right-click throws a fire charge.
- Creeper right-click is now an entity-only blast: blocks are never broken and the
  wearer gets a short blast immunity window.
- Added active abilities for all other mobs: teleport, pig guards, arrows,
  freezing snowballs, wither skulls, sonic boom, poison, webs, invisibility,
  shields, lightning, fire aura, tidal burst, whirlpool pull, healing, dash,
  leap, and loyal minions.
- Added passive thorns knockback powers for pufferfish/armadillo/shulker/turtle
  and guardian heads.
- Active abilities share a generic cooldown system with action-bar recharge
  messages; every cooldown is configurable in `config.yml`.
- Head lore now lists the granted abilities.

## Verification
- `paper-plugin/build.sh`: passed.
- `paper-plugin/test.sh` on Paper 26.3 build 256: passed.
  - `HEADS SELFTEST PASS types=92 fallbacks=0`
  - `HEADS GUI SELFTEST PASS pages=3 heads=92`
  - `HEADS POWERS SELFTEST PASS profiles=92`
  - `HEADS ABILITIES SELFTEST PASS abilities=135 unique=23 combos=43`
- `.agent-work/tests/issue-28-ability-audit.py`: passed (all active abilities have
  config entries in both bundled configs).
- GitHub Actions Heads Plugin Test run 44531641740: success.

## Commit
- `0aca8a2` Add active cooldowns and unique head abilities
