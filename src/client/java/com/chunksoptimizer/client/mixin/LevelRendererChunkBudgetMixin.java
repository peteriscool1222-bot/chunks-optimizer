package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkBudget;
import com.chunksoptimizer.client.ChunksOptimizerClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla-renderer hooks (best-effort). When Sodium is installed it replaces most chunk rendering,
 * so these injections may not apply; they are marked optional to remain compatible.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererChunkBudgetMixin {

	/**
	 * Called when the renderer invalidates compiled geometry (world load, dimension change,
	 * render distance/resource reload). Re-arms the aggressive warmup budget window.
	 */
	@Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$onInvalidateCompiledGeometry(CallbackInfo ci) {
		ChunkBudget.triggerWarmup();
		if (ChunksOptimizerClient.SODIUM_PRESENT) {
			ChunksOptimizerClient.LOGGER.debug("[Chunks Optimizer] World/view reload: warmup throttling engaged (Sodium present).");
		}
	}
}
