# Issue 22 - clean rebuild plan

- Remove all tracked content except `.github/workflows/opencode.yml` and the
  persistent `.agent-work/` workspace required by the agent runner.
- Install the latest PaperMC build dynamically from the fill.papermc.io API.
- Install the latest Minekube Connect Paper plugin (`connect-spigot.jar`) and
  provide a config with endpoint `mikailamin`.
- Never commit the Minekube token. The workflow injects
  `MINEKUBE_CONNECT_TOKEN` from repository secrets at runtime and writes
  `plugins/connect/token.json` locally.
- Validate by booting Paper + Connect, confirming
  `mikailamin.play.minekube.net` registers, then shutting down cleanly.
