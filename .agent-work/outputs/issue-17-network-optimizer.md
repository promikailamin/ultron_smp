# Issue 17 - Further network optimization for the Twingate tunnel

## Request

The Twingate route has low connection setup latency but Minecraft still shows
about 230 ms ping. The request is to optimise the server network further,
especially every packet handled by the network optimizer, and bring the ping
down towards 120-150 ms.

## What changed

### Network optimizer 1.1.0

`mods/ultron-network-optimizer-1.1.0.jar` (replaces 1.0.0) adds:

- Culling for entity-attached sounds (`ClientboundSoundEntityPacket`).
- Culling for visual-only entity packets: entity events, arm-swing
  animations, hurt animations and item-pickup animations.
- Culling for block-break crack animations (`ClientboundBlockDestructionPacket`).
- Culling for distant explosions that do not push the player. Explosions with
  player knockback are always kept.
- Deduplication of identical entity-metadata packets per player.
- Throttling of world-time updates from every 20 ticks to every 100 ticks.
- `TCP_NODELAY` and `SO_KEEPALIVE` on player sockets. Nagle's algorithm can add
  tens of milliseconds to small responses.
- All existing particle, sound, block-entity, level-event, block-event and
  duplicate-block-update filters remain enabled.
- All new filters are configurable in `config/ultron-network.properties`.

Source changes:

- `network-optimizer/src/main/java/com/ultron/network/OptimizerConfig.java`
- `network-optimizer/src/main/java/com/ultron/network/PacketFilter.java`
- `network-optimizer/src/main/java/com/ultron/network/PacketStats.java`
- `network-optimizer/src/main/java/com/ultron/network/PlayerFilterState.java`
- `network-optimizer/src/main/java/com/ultron/network/mixin/ConnectionMixin.java`
- `network-optimizer/src/main/resources/ultron-network-optimizer.mixins.json`

### TCP tuning

`scripts/network-tune.sh` now also sets:

- `tcp_notsent_lowat=8192` instead of 16384, which keeps the send queue short
  and reduces bufferbloat on long-haul tunnel paths.
- `tcp_low_latency=1`, SACK/DSACK and timestamps.
- `tcp_tw_reuse=1`, a larger SYN backlog and `somaxconn`.
- `net.core.optmem_max=65536` and `netdev_max_backlog=32768`.

### Vanilla settings

`server.properties` now uses:

- `view-distance=5` (was 6)
- `simulation-distance=5` (was 6)
- `entity-broadcast-range-percentage=50` (was 60)

This reduces chunk and entity-tracking traffic and the server work needed to
produce it.

## Validation

Passed locally on the runner:

```text
bash .agent-work/tests/test_network_optimizer.sh   PASS
bash .agent-work/tests/test_network_tune.sh        PASS
bash .agent-work/tests/test_run.sh                 PASS
```

The optimizer test rebuilt the mod, verified all mixin targets against
`versions/26.3/server-26.3.jar`, and started a disposable server. The server
logged:

```text
Ultron Network Optimizer enabled: particles=32.0m, sounds=48.0m,
entitySounds=48.0m, entityVisuals=48.0m, blockDestruction=48.0m,
explosions=64.0m, blockEntities=64.0m, levelEvents=64.0m,
blockEvents=64.0m, blockUpdateDedup=20t, entityDataDedup=10t,
timeUpdates=100t, tcpNoDelay=true, stats=300s
```

No mixin failures were reported.

Logs:

- `.agent-work/logs/issue-17-network-optimizer-test.log`
- `.agent-work/logs/issue-17-network-tune-test.log`
- `.agent-work/logs/issue-17-test-run.log`

## Important limitation

The ping shown by the Minecraft client is the keepalive round-trip time. It
contains the physical/network path through Twingate and cannot go below that
path's RTT. Packet culling, deduplication and TCP tuning reduce bandwidth,
burst size, bufferbloat and server work, which helps when the tunnel or server
is congested. They cannot guarantee a drop from 230 ms to 120-150 ms if the
Twingate relay or player ISP route itself takes 230 ms.

For a guaranteed 120-150 ms from Bangladesh, the remaining option is to host
the server on a VPS with a short route to the players (Singapore, India or
Bangladesh) instead of a GitHub Actions runner in the United States.
