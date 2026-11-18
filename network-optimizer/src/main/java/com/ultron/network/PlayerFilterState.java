package com.ultron.network;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class PlayerFilterState {
    private static final int MAX_TRACKED_BLOCKS = 8192;
    private static final int MAX_TRACKED_ENTITIES = 1024;
    private static final Map<Object, PlayerFilterState> STATES = new WeakHashMap<>();

    private final Map<Long, SentBlock> lastBlockUpdates = new HashMap<>();
    private final Map<Integer, SentEntityData> lastEntityData = new HashMap<>();
    private long lastTimeUpdateTick = Long.MIN_VALUE;

    private PlayerFilterState() {
    }

    static PlayerFilterState of(Object listener) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(listener, ignored -> new PlayerFilterState());
        }
    }

    boolean isDuplicateBlockUpdate(long tick, long position, BlockState state, int dedupTicks) {
        SentBlock previous = lastBlockUpdates.get(position);
        if (previous != null
                && previous.state == state
                && tick - previous.tick <= dedupTicks) {
            return true;
        }

        if (lastBlockUpdates.size() >= MAX_TRACKED_BLOCKS) {
            lastBlockUpdates.clear();
        }
        lastBlockUpdates.put(position, new SentBlock(tick, state));
        return false;
    }

    boolean isDuplicateEntityData(long tick, int entityId, List<SynchedEntityData.DataValue<?>> values, int dedupTicks) {
        SentEntityData previous = lastEntityData.get(entityId);
        if (previous != null
                && previous.values.equals(values)
                && tick - previous.tick <= dedupTicks) {
            return true;
        }

        if (lastEntityData.size() >= MAX_TRACKED_ENTITIES) {
            lastEntityData.clear();
        }
        lastEntityData.put(entityId, new SentEntityData(tick, List.copyOf(values)));
        return false;
    }

    boolean shouldSkipTimeUpdate(long tick, int intervalTicks) {
        if (intervalTicks <= 0) {
            return false;
        }
        if (lastTimeUpdateTick == Long.MIN_VALUE) {
            lastTimeUpdateTick = tick;
            return false;
        }
        if (tick - lastTimeUpdateTick < intervalTicks) {
            return true;
        }
        lastTimeUpdateTick = tick;
        return false;
    }

    private record SentBlock(long tick, BlockState state) {
    }

    private record SentEntityData(long tick, List<SynchedEntityData.DataValue<?>> values) {
    }
}
