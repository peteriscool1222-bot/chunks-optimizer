package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkBudget;
import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.ChunksOptimizerClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttles vanilla terrain buffer uploads per frame (Minecraft 26.3 renderer).
 *
 * Note: per-section rebuild throttling is intentionally NOT done here. In 26.x the extractor
 * clears each section's dirty flag before compile tasks run, so skipping a compile task would
 * leave that section permanently unbuilt. Upload deferral is safe: work is retried next frame.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher")
public class SectionRenderDispatcherBudgetMixin {
	private static boolean chunksoptimizer$announced;

	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$limitUploads(CallbackInfo ci) {
		if (!ChunkOptConfig.throttleVanilla || ChunksOptimizerClient.SODIUM_PRESENT) return;
		if (!chunksoptimizer$announced) {
			chunksoptimizer$announced = true;
			ChunksOptimizerClient.LOGGER.info("[Chunks Optimizer] Vanilla terrain upload throttle is active");
		}
		if (!ChunkBudget.tryConsumeUploadSlot()) ci.cancel();
	}
}
