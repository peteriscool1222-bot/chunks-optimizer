package com.chunksoptimizer.client.mixin.sodium;

import com.chunksoptimizer.client.ChunkBudget;
import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.ChunksOptimizerClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales Sodium's per-frame chunk upload budget (GPU time + staging-buffer bytes) using the
 * frame times already tracked by ChunkBudget.
 *
 * - slow/heavy frames: upload less per frame, avoiding GPU/driver stalls while exploring
 * - fast frames with headroom: upload more per frame, so new chunks appear sooner
 *
 * Sodium still enforces its own upload limits; this only scales the per-frame allowance.
 * Optional and version-tolerant: if the constructor changes, the injection does not apply.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.LimitedResourceBudget", remap = false)
public class SodiumUploadBudgetMixin {
	private static boolean chunksoptimizer$announced;

	@ModifyVariable(
		method = "<init>(JJ)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0,
		remap = false
	)
	private static long chunksoptimizer$scaleDurationBudget(long duration) {
		if (!ChunkOptConfig.gpuUploadBudgetScaling) return duration;
		announce();
		// Keep at least 0.1 ms of estimated upload work allowed per frame.
		return Math.max(100_000L, (long) (duration * ChunkBudget.getUploadBudgetScale()));
	}

	@ModifyVariable(
		method = "<init>(JJ)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 1,
		require = 0,
		remap = false
	)
	private static long chunksoptimizer$scaleByteBudget(long bytes) {
		if (!ChunkOptConfig.gpuUploadBudgetScaling) return bytes;
		announce();
		// Sodium's byte limit is already 80% of the staging buffer capacity (a safety margin).
		// Only ever reduce it; increasing it could overwrite data before the GPU copies it.
		double scale = Math.min(1.0, ChunkBudget.getUploadBudgetScale());
		// Never starve uploads entirely: allow at least 256 KiB per frame.
		return Math.max(262_144L, (long) (bytes * scale));
	}

	private static void announce() {
		if (!chunksoptimizer$announced) {
			chunksoptimizer$announced = true;
			ChunksOptimizerClient.LOGGER.info("[Chunks Optimizer] Sodium GPU upload budget scaling is active");
		}
	}
}
