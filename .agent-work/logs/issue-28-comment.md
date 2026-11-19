Implemented the issue-28 head-power overhaul.

### What changed
- Added a new `HeadAbility` system with **23 non-effect abilities** and configurable cooldowns for every active power. Action-bar messages show remaining recharge time.
- **All 92 living mob heads now have at least one non-effect ability** (135 ability assignments, 43 distinct combinations), in addition to their passive potion effects.
- **Iron Golem**: melee hits now apply a large knockback (`powers.iron-golem-knockback`, default `3.5`). Other heavy mobs also knock targets back.
- **Allay / Bee / Bat / Parrot / Phantom / Vex / Breeze / Ghast / Happy Ghast / Ender Dragon / Wither** heads grant creative-style flight in survival. Flight is revoked safely when the head is removed or the player quits.
- **Breeze** (also blaze/ghast/ender dragon) right-click throws a fire charge projectile. Damage is applied on impact without block griefing.
- **Creeper** right-click is now an entity-only blast: blocks are never broken regardless of config, and the wearer gets short blast immunity.
- Added many further abilities, including:
  - enderman/creaking teleport
  - pig guard summoning
  - skeleton/pillager arrows
  - snow golem/frostbite freezing snowballs
  - wither skulls and warden sonic boom
  - poison clouds, temporary webs, invisibility, shields
  - evoker lightning, fire auras, tidal bursts and whirlpool pulls
  - healing, dashes, leaps, and friendly summoned minions
  - passive thorns knockback for pufferfish/armadillo/shulker/turtle/guardian heads
- Head item lore now lists the granted abilities, and both bundled config files document every cooldown/tuning option.

### Verification
- `paper-plugin/build.sh` passed.
- `paper-plugin/test.sh` passed on Paper 26.3 build 256:
  - `HEADS SELFTEST PASS types=92 fallbacks=0`
  - `HEADS GUI SELFTEST PASS pages=3 heads=92`
  - `HEADS POWERS SELFTEST PASS profiles=92`
  - `HEADS ABILITIES SELFTEST PASS abilities=135 unique=23 combos=43`
- `.agent-work/tests/issue-28-ability-audit.py` passed: every active ability has matching config entries.
- GitHub Actions **Heads Plugin Test** run `44531641740`: **success**.

### Commits
- `0aca8a2` Add active cooldowns and unique head abilities
- `86d47fc` Record issue 28 verification and CI logs

Pushed to `main`. No known blockers. Cooldowns and strengths can be tuned in `plugins/Heads/config.yml`; active abilities still intentionally require an empty main hand so they do not clash with block/item interactions.
