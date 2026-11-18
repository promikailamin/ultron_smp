package com.ultron.network;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class UltronNetworkOptimizer implements ModInitializer {
    public static final String MOD_ID = "ultron_network_optimizer";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile OptimizerConfig config = new OptimizerConfig();

    @Override
    public void onInitialize() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("ultron-network.properties");
        config = OptimizerConfig.load(path);
        LOGGER.info("Ultron Network Optimizer enabled: {}", config.summary());
    }

    public static OptimizerConfig config() {
        return config;
    }

    public static Logger logger() {
        return LOGGER;
    }
}
