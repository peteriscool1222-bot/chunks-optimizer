package com.chunksoptimizer.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunksOptimizerClient implements ClientModInitializer {
	public static final String MOD_ID = "chunks_optimizer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static boolean SODIUM_PRESENT;

	@Override
	public void onInitializeClient() {
		SODIUM_PRESENT = FabricLoader.getInstance().isModLoaded("sodium");
		ChunkOptConfig.load();
		LOGGER.info("[Chunks Optimizer] Initializing (Sodium present: {}, throttleSodium={}, throttleVanilla={})",
			SODIUM_PRESENT, ChunkOptConfig.throttleSodium, ChunkOptConfig.throttleVanilla);
		LOGGER.info("[Chunks Optimizer] Adaptive max-out mode: targetFrameMs={}, warmupTicks={}, rebuild cap={}, upload cap={}",
			ChunkOptConfig.targetFrameMs, ChunkOptConfig.warmupTicks, ChunkOptConfig.adaptiveRebuildCap, ChunkOptConfig.adaptiveUploadCap);
		ClientTickEvents.END_CLIENT_TICK.register(client -> ChunkBudget.tickWarmup());
	}
}
