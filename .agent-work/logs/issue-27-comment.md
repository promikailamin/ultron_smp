Implemented mob-head powers for the Heads plugin.

Summary of changes:
- Added a power profile for every living mob head (92 total), with unique passive effect combinations.
- Slime head: refreshed Jump Boost while worn.
- Horse head: Speed.
- Chicken head: Slow Falling.
- Cow head: standing on a grass block for 5 seconds starts healing every 2 seconds.
- Pig head: right-click to recruit nearby pigs as guards that follow within a 4-block radius, attack nearby hostiles, and retaliate against entities that hit the player.
- Creeper head: right-click with an empty hand to create a safe blast at the player's position; the wearer is immune to that blast. Block breaking is configurable and off by default.
- Iron Golem head: Strength + extra hearts.
- Enderman head: right-click to teleport to a safe nearby location.
- Added head power metadata to items so abilities survive naming and work for vanilla skull heads.
- Added power cooldown/radius/config options under `powers` in the Heads config.
- Added power coverage logging and self-tests.

Testing:
- Built the plugin successfully with `paper-plugin/build.sh`.
- Ran `paper-plugin/test.sh` against a real Paper server. Result: pass.
  - `HEADS SELFTEST PASS types=92 fallbacks=0`
  - `HEADS GUI SELFTEST PASS pages=3 heads=92`
  - `HEADS POWERS SELFTEST PASS profiles=92`
- Fixed the existing Heads test workflow startup failure (invalid job-level `runner.temp` context usage). The workflow now runs successfully.

Server run:
- Cancelled the old `run.yml` run (44521823320).
- Pushed the updated plugin jar and source.
- Started a fresh `run.yml` run: https://github.com/promikailamin/ultron_smp/actions/runs/44526548555
- Server is reachable at `mikailamin.play.minekube.net:25565` (Minecraft status check succeeded).

Commits:
- `ec27856` Add unique powers for every mob head
- `c297a99` Fix Heads plugin test workflow startup
- `bf3d282` Record issue 27 test and live run logs

No remaining known issues. The live `run.yml` server is running with the updated Heads jar.
