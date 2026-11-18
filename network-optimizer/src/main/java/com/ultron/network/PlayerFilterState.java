package com.ultron.network;

import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class PlayerFilterState {
    private static final int MAX_TRACKED_BLOCKS = 8192;
    private static final Map<Object, PlayerFilterState> STATES = new WeakHashMap<>();

    private final Map<Long, SentBlock> lastBlockUpdates = new HashMap<>();

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

    private record SentBlock(long tick, BlockState state) {
    }
}
