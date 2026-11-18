# Issue 7 - server.jar packet analysis and fix

## What was inspected

`versions/26.3/server-26.3.jar` was unpacked into
`.agent-work/tmp/serverjarall/` and the game-packet classes were examined with
`javap`. The reproducible summary is in
`.agent-work/outputs/issue-7-server-jar-analysis.txt`.

The jar contains 7,762 classes, including 166 clientbound and 69 serverbound
game-packet classes. The existing `mods/` directory already contains Krypton
(networking pipeline), ServerCore (server ticking), Lithium, FerriteCore and
ModernFix.

## Where the traffic comes from

- **Chunk data** - `ClientboundLevelChunkWithLightPacket` and
  `ClientboundLevelChunkPacketData` carry the bulk of join/movement traffic.
  They are unavoidable, but `view-distance=10` makes the server build and send
  roughly three times as many chunks as `view-distance=6`.
- **Entity tracking** - `ChunkMap$TrackedEntity` / `ServerEntity` broadcast
  movement and metadata at each entity's tracking range. The vanilla
  `entity-broadcast-range-percentage` setting scales all of these ranges.
- **Particles** - `ClientboundLevelParticlesPacket` is frequently sent for
  explosions, spawners, torches, and mob effects. Beyond the player's useful
  visual range these packets add bandwidth without gameplay value.
- **Sounds, block entities, level events, block events** - positional packets
  that are often sent to every tracking player even when the target is far
  away.
- **Duplicate block updates** - vanilla normally updates only changed blocks,
  but server-side mods can send the same state again. A repeated packet for the
  same position and state is redundant.
- **Empty entity metadata** - `ClientboundSetEntityDataPacket` with an empty
  value list has no effect.

## What was changed

1. Added the server-side mod `mods/ultron-network-optimizer-1.0.0.jar`.
   - Culls particles, positional sounds, block-entity data, non-global level
     events, and block events beyond configurable distances.
   - Keeps music/UI/voice sounds.
   - Keeps particles marked `overrideLimiter` or `alwaysShow`.
   - Deduplicates block updates for the same position/state within 20 ticks.
   - Drops empty entity-data packets.
   - Logs suppressed-packet totals every 300 seconds.
   - Source and build script: `network-optimizer/`.
   - Config: `config/ultron-network.properties`.
2. Reduced `view-distance` and `simulation-distance` from 10 to 6.
3. Reduced `entity-broadcast-range-percentage` from 100 to 60.
4. Reduced `network-compression-threshold` from 256 to 128 so more medium-sized
   packets are compressed before crossing the tunnel.
5. Extended `scripts/network-tune.sh` with `tcp_notsent_lowat` and a larger
   `netdev_max_backlog`.

## What this can and cannot fix

These changes cut bandwidth, burst size, and server-side packet work. They do
not remove the physical RTT between a GitHub Actions runner in the United
States and players in Bangladesh. If the connection is still unplayable, the
remaining fix is to host the server closer to the players.
