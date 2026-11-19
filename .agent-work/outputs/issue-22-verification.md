# Issue 22 verification

Environment: GitHub Actions runner, Temurin Java 25.

1. Bootstrapped Paper 26.3 build 255 (latest stable from
   `fill.papermc.io`) with Minekube Connect 0.15.18 (latest release).
2. The first boot created `plugins/connect/config.yml`; the server was stopped
   cleanly and the endpoint was set to `mikailamin`.
3. The token was applied from the repository secret into the git-ignored
   `plugins/connect/token.json`; it was not committed.
4. Local boot log: `.agent-work/logs/issue-22-boot-test.log`
   - `Endpoint name: mikailamin`
   - `Your public address: mikailamin.play.minekube.net`
5. Live workflow run `44431611680`:
   - `verify_connect_secret`: success
   - `run_server`: Minecraft ready and Minekube registration confirmed
   - `mikailamin.play.minekube.net:25565` answered a Minecraft status ping
   - The run was intentionally cancelled after verification to stop the server.
   - Full log: `.agent-work/logs/issue-22-workflow-live.log`
6. Static checks: `bash .agent-work/tests/test_paper_setup.sh` passed.
