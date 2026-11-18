package com.ultron.network;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.LongAdder;

public final class PacketStats {
    private static final LongAdder PARTICLES = new LongAdder();
    private static final LongAdder SOUNDS = new LongAdder();
    private static final LongAdder ENTITY_SOUNDS = new LongAdder();
    private static final LongAdder ENTITY_VISUALS = new LongAdder();
    private static final LongAdder BLOCK_DESTRUCTION = new LongAdder();
    private static final LongAdder EXPLOSIONS = new LongAdder();
    private static final LongAdder BLOCK_ENTITIES = new LongAdder();
    private static final LongAdder LEVEL_EVENTS = new LongAdder();
    private static final LongAdder BLOCK_EVENTS = new LongAdder();
    private static final LongAdder BLOCK_UPDATES = new LongAdder();
    private static final LongAdder ENTITY_DATA = new LongAdder();
    private static final LongAdder TIME = new LongAdder();

    private static long lastLogTick;

    private PacketStats() {
    }

    public static void particle() {
        PARTICLES.increment();
    }

    public static void sound() {
        SOUNDS.increment();
    }

    public static void entitySound() {
        ENTITY_SOUNDS.increment();
    }

    public static void entityVisual() {
        ENTITY_VISUALS.increment();
    }

    public static void blockDestruction() {
        BLOCK_DESTRUCTION.increment();
    }

    public static void explosion() {
        EXPLOSIONS.increment();
    }

    public static void blockEntity() {
        BLOCK_ENTITIES.increment();
    }

    public static void levelEvent() {
        LEVEL_EVENTS.increment();
    }

    public static void blockEvent() {
        BLOCK_EVENTS.increment();
    }

    public static void blockUpdate() {
        BLOCK_UPDATES.increment();
    }

    public static void entityData() {
        ENTITY_DATA.increment();
    }

    public static void time() {
        TIME.increment();
    }

    public static void maybeLog(MinecraftServer server) {
        int seconds = UltronNetworkOptimizer.config().logStatsSeconds;
        if (seconds <= 0) {
            return;
        }

        long tick = server.getTickCount();
        long interval = seconds * 20L;
        if (lastLogTick == 0L) {
            lastLogTick = tick;
            return;
        }
        if (tick - lastLogTick < interval) {
            return;
        }
        lastLogTick = tick;

        long particles = PARTICLES.sumThenReset();
        long sounds = SOUNDS.sumThenReset();
        long entitySounds = ENTITY_SOUNDS.sumThenReset();
        long entityVisuals = ENTITY_VISUALS.sumThenReset();
        long blockDestruction = BLOCK_DESTRUCTION.sumThenReset();
        long explosions = EXPLOSIONS.sumThenReset();
        long blockEntities = BLOCK_ENTITIES.sumThenReset();
        long levelEvents = LEVEL_EVENTS.sumThenReset();
        long blockEvents = BLOCK_EVENTS.sumThenReset();
        long blockUpdates = BLOCK_UPDATES.sumThenReset();
        long entityData = ENTITY_DATA.sumThenReset();
        long time = TIME.sumThenReset();
        long total = particles + sounds + entitySounds + entityVisuals + blockDestruction + explosions
                + blockEntities + levelEvents + blockEvents + blockUpdates + entityData + time;

        if (total > 0L) {
            UltronNetworkOptimizer.logger().info(
                    "Suppressed {} packets in the last {}s (particles={}, sounds={}, entitySounds={}, "
                            + "entityVisuals={}, blockDestruction={}, explosions={}, blockEntities={}, "
                            + "levelEvents={}, blockEvents={}, duplicateBlocks={}, duplicateEntityData={}, "
                            + "timeUpdates={})",
                    total,
                    seconds,
                    particles,
                    sounds,
                    entitySounds,
                    entityVisuals,
                    blockDestruction,
                    explosions,
                    blockEntities,
                    levelEvents,
                    blockEvents,
                    blockUpdates,
                    entityData,
                    time
            );
        }
    }
}
