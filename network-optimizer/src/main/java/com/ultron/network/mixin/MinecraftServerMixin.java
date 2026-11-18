package com.ultron.network.mixin;

import com.ultron.network.PacketStats;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void ultronNetworkOptimizer$logPacketStats(BooleanSupplier hasTimeLeft, CallbackInfo callback) {
        PacketStats.maybeLog((MinecraftServer) (Object) this);
    }
}
