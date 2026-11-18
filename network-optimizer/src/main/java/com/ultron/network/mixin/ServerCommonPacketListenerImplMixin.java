package com.ultron.network.mixin;

import com.ultron.network.PacketFilter;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    // Both send overloads end up here; filter only the two-argument method so
    // a packet is never seen twice (which would make duplicate detection drop
    // every block update).
    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void ultronNetworkOptimizer$filterGamePacket(
            Packet<?> packet,
            ChannelFutureListener listener,
            CallbackInfo callback
    ) {
        ServerCommonPacketListenerImpl commonListener = (ServerCommonPacketListenerImpl) (Object) this;
        if (PacketFilter.shouldDrop(commonListener, packet)) {
            callback.cancel();
        }
    }
}
