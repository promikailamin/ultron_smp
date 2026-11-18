package com.ultron.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundSource;

/**
 * Packet-level culling for game packets whose only purpose is visual or
 * positional feedback far away from the player.
 */
public final class PacketFilter {
    private PacketFilter() {
    }

    public static boolean shouldDrop(ServerCommonPacketListenerImpl listener, Packet<?> packet) {
        OptimizerConfig config = UltronNetworkOptimizer.config();
        if (!config.enabled) {
            return false;
        }
        if (!(listener instanceof ServerGamePacketListenerImpl gameListener)) {
            return false;
        }

        ServerPlayer player = gameListener.player;
        if (player == null || player.isRemoved()) {
            return false;
        }

        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            if (!config.cullParticles || particles.overrideLimiter() || particles.alwaysShow()) {
                return false;
            }
            if (outside(config.maxParticleDistance, player, particles.x(), particles.y(), particles.z())) {
                PacketStats.particle();
                return true;
            }
        } else if (packet instanceof ClientboundSoundPacket sound) {
            if (!config.cullSounds || keepSound(sound.getSource())) {
                return false;
            }
            if (outside(config.maxSoundDistance, player, sound.getX(), sound.getY(), sound.getZ())) {
                PacketStats.sound();
                return true;
            }
        } else if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
            if (!config.cullBlockEntities) {
                return false;
            }
            BlockPos pos = blockEntity.getPos();
            if (outside(config.maxBlockEntityDistance, player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                PacketStats.blockEntity();
                return true;
            }
        } else if (packet instanceof ClientboundLevelEventPacket levelEvent) {
            if (!config.cullLevelEvents || levelEvent.isGlobalEvent()) {
                return false;
            }
            BlockPos pos = levelEvent.getPos();
            if (outside(config.maxLevelEventDistance, player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                PacketStats.levelEvent();
                return true;
            }
        } else if (packet instanceof ClientboundBlockEventPacket blockEvent) {
            if (!config.cullBlockEvents) {
                return false;
            }
            BlockPos pos = blockEvent.getPos();
            if (outside(config.maxBlockEventDistance, player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                PacketStats.blockEvent();
                return true;
            }
        } else if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            if (!config.deduplicateBlockUpdates) {
                return false;
            }
            long tick = player.level().getServer().getTickCount();
            PlayerFilterState state = PlayerFilterState.of(listener);
            if (state.isDuplicateBlockUpdate(
                    tick,
                    blockUpdate.getPos().asLong(),
                    blockUpdate.getBlockState(),
                    config.blockUpdateDedupTicks
            )) {
                PacketStats.blockUpdate();
                return true;
            }
        } else if (packet instanceof ClientboundSetEntityDataPacket entityData) {
            if (config.dropEmptyEntityData && entityData.packedItems().isEmpty()) {
                PacketStats.entityData();
                return true;
            }
        }

        return false;
    }

    private static boolean keepSound(SoundSource source) {
        return source == SoundSource.MUSIC
                || source == SoundSource.UI
                || source == SoundSource.MASTER
                || source == SoundSource.VOICE;
    }

    private static boolean outside(double maxDistance, ServerPlayer player, double x, double y, double z) {
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return dx * dx + dy * dy + dz * dz > maxDistance * maxDistance;
    }
}
