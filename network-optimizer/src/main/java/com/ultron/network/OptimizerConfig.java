package com.ultron.network;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small, dependency-free configuration for the network optimizer.
 *
 * <p>The file is written with comments the first time the server starts, so
 * the settings can be changed without rebuilding the mod.</p>
 */
public final class OptimizerConfig {
    public static final String DEFAULT_TEXT = """
            # Ultron SMP network optimizer
            #
            # Every value is read once at server startup. Disable individual
            # filters if a client-visible feature depends on that packet.
            enabled=true

            # Drop particle packets that are farther away than this many blocks.
            cull-particles=true
            max-particle-distance=32.0

            # Drop positional sound packets farther away than this many blocks.
            # Music and UI sounds are always kept.
            cull-sounds=true
            max-sound-distance=48.0

            # Drop sounds attached to an entity (mob noises, item pickup, ...)
            # farther away than this many blocks.
            cull-entity-sounds=true
            max-entity-sound-distance=48.0

            # Drop visual-only entity events, arm swings, hurt animations and
            # item-pickup animations farther away than this many blocks.
            cull-entity-visuals=true
            max-entity-visual-distance=48.0

            # Drop block-break crack animations farther away than this.
            cull-block-destruction=true
            max-block-destruction-distance=48.0

            # Explosions carry a large particle list. Drop the visual packet
            # for distant explosions that do not push the player.
            cull-explosions=true
            max-explosion-distance=64.0

            # Drop block-entity data (signs, chests, spawners, ...) farther than this.
            cull-block-entities=true
            max-block-entity-distance=64.0

            # Drop non-global level events (doors, chests, portals, ...) farther than this.
            cull-level-events=true
            max-level-event-distance=64.0

            # Drop block events (piston/note-block animation, ...) farther than this.
            cull-block-events=true
            max-block-event-distance=64.0

            # The server normally sends a block update whenever a block changes.
            # Some server-side mods send the same state again; skip a repeated
            # update for the same position and state within this many ticks.
            deduplicate-block-updates=true
            block-update-dedup-ticks=20

            # ClientboundSetEntityDataPacket with no values does nothing.
            drop-empty-entity-data=true

            # Skip an identical entity-metadata packet for the same entity
            # within this many ticks.
            deduplicate-entity-data=true
            entity-data-dedup-ticks=10

            # The client advances world time itself. Sending time less often
            # removes a small packet from almost every player tick.
            throttle-time-updates=true
            time-update-interval-ticks=100

            # Disable Nagle's algorithm and enable TCP keepalive on player
            # sockets. Nagle can add tens of milliseconds to small responses.
            tcp-no-delay=true
            tcp-keepalive=true

            # Log the number of suppressed packets every N seconds (0 = off).
            log-stats-seconds=300
            """;

    public boolean enabled = true;
    public boolean cullParticles = true;
    public double maxParticleDistance = 32.0;
    public boolean cullSounds = true;
    public double maxSoundDistance = 48.0;
    public boolean cullEntitySounds = true;
    public double maxEntitySoundDistance = 48.0;
    public boolean cullEntityVisuals = true;
    public double maxEntityVisualDistance = 48.0;
    public boolean cullBlockDestruction = true;
    public double maxBlockDestructionDistance = 48.0;
    public boolean cullExplosions = true;
    public double maxExplosionDistance = 64.0;
    public boolean cullBlockEntities = true;
    public double maxBlockEntityDistance = 64.0;
    public boolean cullLevelEvents = true;
    public double maxLevelEventDistance = 64.0;
    public boolean cullBlockEvents = true;
    public double maxBlockEventDistance = 64.0;
    public boolean deduplicateBlockUpdates = true;
    public int blockUpdateDedupTicks = 20;
    public boolean dropEmptyEntityData = true;
    public boolean deduplicateEntityData = true;
    public int entityDataDedupTicks = 10;
    public boolean throttleTimeUpdates = true;
    public int timeUpdateIntervalTicks = 100;
    public boolean tcpNoDelay = true;
    public boolean tcpKeepalive = true;
    public int logStatsSeconds = 300;

    public static OptimizerConfig load(Path path) {
        OptimizerConfig config = new OptimizerConfig();

        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_TEXT, StandardCharsets.UTF_8);
            }

            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            config.enabled = bool(properties, "enabled", config.enabled);
            config.cullParticles = bool(properties, "cull-particles", config.cullParticles);
            config.maxParticleDistance = positiveDouble(properties, "max-particle-distance", config.maxParticleDistance);
            config.cullSounds = bool(properties, "cull-sounds", config.cullSounds);
            config.maxSoundDistance = positiveDouble(properties, "max-sound-distance", config.maxSoundDistance);
            config.cullEntitySounds = bool(properties, "cull-entity-sounds", config.cullEntitySounds);
            config.maxEntitySoundDistance = positiveDouble(properties, "max-entity-sound-distance", config.maxEntitySoundDistance);
            config.cullEntityVisuals = bool(properties, "cull-entity-visuals", config.cullEntityVisuals);
            config.maxEntityVisualDistance = positiveDouble(properties, "max-entity-visual-distance", config.maxEntityVisualDistance);
            config.cullBlockDestruction = bool(properties, "cull-block-destruction", config.cullBlockDestruction);
            config.maxBlockDestructionDistance = positiveDouble(properties, "max-block-destruction-distance", config.maxBlockDestructionDistance);
            config.cullExplosions = bool(properties, "cull-explosions", config.cullExplosions);
            config.maxExplosionDistance = positiveDouble(properties, "max-explosion-distance", config.maxExplosionDistance);
            config.cullBlockEntities = bool(properties, "cull-block-entities", config.cullBlockEntities);
            config.maxBlockEntityDistance = positiveDouble(properties, "max-block-entity-distance", config.maxBlockEntityDistance);
            config.cullLevelEvents = bool(properties, "cull-level-events", config.cullLevelEvents);
            config.maxLevelEventDistance = positiveDouble(properties, "max-level-event-distance", config.maxLevelEventDistance);
            config.cullBlockEvents = bool(properties, "cull-block-events", config.cullBlockEvents);
            config.maxBlockEventDistance = positiveDouble(properties, "max-block-event-distance", config.maxBlockEventDistance);
            config.deduplicateBlockUpdates = bool(properties, "deduplicate-block-updates", config.deduplicateBlockUpdates);
            config.blockUpdateDedupTicks = nonNegativeInt(properties, "block-update-dedup-ticks", config.blockUpdateDedupTicks);
            config.dropEmptyEntityData = bool(properties, "drop-empty-entity-data", config.dropEmptyEntityData);
            config.deduplicateEntityData = bool(properties, "deduplicate-entity-data", config.deduplicateEntityData);
            config.entityDataDedupTicks = nonNegativeInt(properties, "entity-data-dedup-ticks", config.entityDataDedupTicks);
            config.throttleTimeUpdates = bool(properties, "throttle-time-updates", config.throttleTimeUpdates);
            config.timeUpdateIntervalTicks = nonNegativeInt(properties, "time-update-interval-ticks", config.timeUpdateIntervalTicks);
            config.tcpNoDelay = bool(properties, "tcp-no-delay", config.tcpNoDelay);
            config.tcpKeepalive = bool(properties, "tcp-keepalive", config.tcpKeepalive);
            config.logStatsSeconds = nonNegativeInt(properties, "log-stats-seconds", config.logStatsSeconds);
        } catch (IOException | RuntimeException exception) {
            UltronNetworkOptimizer.logger().warn(
                    "Could not read {}; using built-in defaults ({})",
                    path,
                    exception.toString()
            );
        }

        return config;
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static double positiveDouble(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0.0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int nonNegativeInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public String summary() {
        return "particles=" + (cullParticles ? maxParticleDistance + "m" : "off")
                + ", sounds=" + (cullSounds ? maxSoundDistance + "m" : "off")
                + ", entitySounds=" + (cullEntitySounds ? maxEntitySoundDistance + "m" : "off")
                + ", entityVisuals=" + (cullEntityVisuals ? maxEntityVisualDistance + "m" : "off")
                + ", blockDestruction=" + (cullBlockDestruction ? maxBlockDestructionDistance + "m" : "off")
                + ", explosions=" + (cullExplosions ? maxExplosionDistance + "m" : "off")
                + ", blockEntities=" + (cullBlockEntities ? maxBlockEntityDistance + "m" : "off")
                + ", levelEvents=" + (cullLevelEvents ? maxLevelEventDistance + "m" : "off")
                + ", blockEvents=" + (cullBlockEvents ? maxBlockEventDistance + "m" : "off")
                + ", blockUpdateDedup=" + (deduplicateBlockUpdates ? blockUpdateDedupTicks + "t" : "off")
                + ", entityDataDedup=" + (deduplicateEntityData ? entityDataDedupTicks + "t" : "off")
                + ", timeUpdates=" + (throttleTimeUpdates ? timeUpdateIntervalTicks + "t" : "off")
                + ", tcpNoDelay=" + tcpNoDelay
                + ", stats=" + (logStatsSeconds > 0 ? logStatsSeconds + "s" : "off");
    }
}
