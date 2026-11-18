package com.ultron.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

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
        } else if (packet instanceof ClientboundSoundEntityPacket sound) {
            if (!config.cullEntitySounds || keepSound(sound.getSource())) {
                return false;
            }
            Entity entity = player.level().getEntity(sound.getId());
            if (entity != null
                    && outside(config.maxEntitySoundDistance, player, entity.getX(), entity.getY(), entity.getZ())) {
                PacketStats.entitySound();
                return true;
            }
        } else if (packet instanceof ClientboundEntityEventPacket entityEvent) {
            if (!config.cullEntityVisuals) {
                return false;
            }
            Entity entity = entityEvent.getEntity(player.level());
            if (entity != null
                    && outside(config.maxEntityVisualDistance, player, entity.getX(), entity.getY(), entity.getZ())) {
                PacketStats.entityVisual();
                return true;
            }
        } else if (packet instanceof ClientboundAnimatePacket animate) {
            if (!config.cullEntityVisuals) {
                return false;
            }
            Entity entity = player.level().getEntity(animate.getId());
            if (entity != null
                    && outside(config.maxEntityVisualDistance, player, entity.getX(), entity.getY(), entity.getZ())) {
                PacketStats.entityVisual();
                return true;
            }
        } else if (packet instanceof ClientboundHurtAnimationPacket hurt) {
            if (!config.cullEntityVisuals) {
                return false;
            }
            Entity entity = player.level().getEntity(hurt.id());
            if (entity != null
                    && outside(config.maxEntityVisualDistance, player, entity.getX(), entity.getY(), entity.getZ())) {
                PacketStats.entityVisual();
                return true;
            }
        } else if (packet instanceof ClientboundBlockDestructionPacket destruction) {
            if (!config.cullBlockDestruction) {
                return false;
            }
            BlockPos pos = destruction.getPos();
            if (outside(config.maxBlockDestructionDistance, player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                PacketStats.blockDestruction();
                return true;
            }
        } else if (packet instanceof ClientboundExplodePacket explosion) {
            if (!config.cullExplosions || explosion.playerKnockback().isPresent()) {
                return false;
            }
            if (outside(config.maxExplosionDistance, player, explosion.center().x(), explosion.center().y(), explosion.center().z())) {
                PacketStats.explosion();
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
            long tick = tick(player);
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
            if (config.deduplicateEntityData && !entityData.packedItems().isEmpty()) {
                long tick = tick(player);
                PlayerFilterState state = PlayerFilterState.of(listener);
                if (state.isDuplicateEntityData(
                        tick,
                        entityData.id(),
                        entityData.packedItems(),
                        config.entityDataDedupTicks
                )) {
                    PacketStats.entityData();
                    return true;
                }
            }
        } else if (packet instanceof ClientboundSetTimePacket) {
            if (!config.throttleTimeUpdates) {
                return false;
            }
            PlayerFilterState state = PlayerFilterState.of(listener);
            if (state.shouldSkipTimeUpdate(tick(player), config.timeUpdateIntervalTicks)) {
                PacketStats.time();
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

    private static long tick(ServerPlayer player) {
        return player.level().getServer().getTickCount();
    }

    private static boolean outside(double maxDistance, ServerPlayer player, double x, double y, double z) {
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return dx * dx + dy * dy + dz * dz > maxDistance * maxDistance;
    }
}
