package com.ultron.network.mixin;

import com.ultron.network.OptimizerConfig;
import com.ultron.network.UltronNetworkOptimizer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelActive", at = @At("TAIL"))
    private void ultronNetworkOptimizer$configureSocket(ChannelHandlerContext context, CallbackInfo callback) {
        OptimizerConfig config = UltronNetworkOptimizer.config();
        if (!config.enabled) {
            return;
        }

        Channel channel = context.channel();
        if (config.tcpNoDelay) {
            setOption(channel, ChannelOption.TCP_NODELAY, Boolean.TRUE);
        }
        if (config.tcpKeepalive) {
            setOption(channel, ChannelOption.SO_KEEPALIVE, Boolean.TRUE);
        }
    }

    private static void setOption(Channel channel, ChannelOption<Boolean> option, Boolean value) {
        try {
            channel.config().setOption(option, value);
        } catch (RuntimeException exception) {
            UltronNetworkOptimizer.logger().debug(
                    "Could not set socket option {} ({})",
                    option.name(),
                    exception.toString()
            );
        }
    }
}
