package com.chunksoptimizer.client.mixin.sodium;

import com.chunksoptimizer.client.ChunkBudget;
import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.ChunksOptimizerClient;
import com.chunksoptimizer.client.FrameStats;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.JobDurationEstimator;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.JobEffort;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Throttles Sodium's chunk render task submission (Sodium 0.9.x / MC 26.x pipeline).
 *
 * Sodium already budgets uploads and worker time internally; this hook additionally caps
 * how many section build tasks are submitted per frame so the main thread/render thread
 * does not get hit by a large burst while exploring on slower CPUs.
 *
 * Optional: if Sodium changes the private method signature, the hook simply does not apply.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumRenderSectionManagerMixin {
	private static boolean chunksoptimizer$announced;
	private static boolean chunksoptimizer$uploadLimitAnnounced;
	/** True only during the per-frame upload path; destroy() also calls collectChunkBuildResults. */
	private static boolean chunksoptimizer$processingBuilds;
	/** Reused every frame: Sodium consumes this list inside processChunkBuilds and never retains it. */
	private static final ArrayList<BuilderTaskOutput> chunksoptimizer$resultBuffer = new ArrayList<>(4);

	@Accessor(value = "buildResults", remap = false)
	abstract ConcurrentLinkedDeque<ChunkJobResult<?>> getBuildResults();

	@Accessor(value = "jobDurationEstimator", remap = false)
	abstract JobDurationEstimator getJobDurationEstimator();

	@Inject(
		method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false
	)
	private void chunksoptimizer$limitTaskSubmission(CallbackInfo ci, @Local(argsOnly = true) int type) {
		if (!ChunkOptConfig.throttleSodium) return;

		// Sodium update type flags (ChunkUpdateTypes): SORT = 1, REBUILD = 2, IMPORTANT = 4, INITIAL_BUILD = 8.
		// Only throttle tasks that actually rebuild a section. Pure sorting tasks are cheap and
		// deferring them would keep retrying every frame, starving chunk builds while exploring.
		boolean pureSort = (type & 1) != 0 && (type & 2) == 0 && (type & 8) == 0;
		if (pureSort) return;

		if (!chunksoptimizer$announced) {
			chunksoptimizer$announced = true;
			ChunksOptimizerClient.LOGGER.info("[Chunks Optimizer] Sodium chunk task throttle is active");
		}
		if (!ChunkBudget.tryConsumeRebuildSlot()) {
			ci.cancel();
		}
	}

	/**
	 * Caps how many finished chunk meshes Sodium pulls off its result queue each frame.
	 *
	 * Sodium's duration/byte budget was not enough here: one Intel GL upload can take tens of ms,
	 * and the estimator's "typical" duration badly under-predicts it. Leaving completed results in
	 * the deque is safe — they have not been given to a RenderSection yet — so they are applied on
	 * later frames instead of all at once.
	 */
	@Inject(
		method = "collectChunkBuildResults()Ljava/util/ArrayList;",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false
	)
	private void chunksoptimizer$limitResultCollection(CallbackInfoReturnable<ArrayList<BuilderTaskOutput>> cir) {
		// destroy() drains this queue to free GPU buffers; never leave results behind there.
		if (!chunksoptimizer$processingBuilds || !ChunkOptConfig.throttleSodium) return;

		if (!chunksoptimizer$uploadLimitAnnounced) {
			chunksoptimizer$uploadLimitAnnounced = true;
			ChunksOptimizerClient.LOGGER.info("[Chunks Optimizer] Sodium chunk result upload cap is active");
		}

		int limit = Math.max(1, ChunkBudget.getEffectiveUploadBudget());
		ArrayList<BuilderTaskOutput> results = chunksoptimizer$resultBuffer;
		results.clear();
		JobDurationEstimator estimator = getJobDurationEstimator();
		ConcurrentLinkedDeque<ChunkJobResult<?>> queue = getBuildResults();

		for (int i = 0; i < limit; i++) {
			ChunkJobResult<?> result = queue.poll();
			if (result == null) break;

			Object output = result.unwrap();
			if (output instanceof BuilderTaskOutput) {
				results.add((BuilderTaskOutput) output);
			}
			result.clearJobFromSection();
			JobEffort effort = result.getJobEffort();
			if (effort != null) estimator.addData(effort);
		}
		estimator.updateModels();
		cir.setReturnValue(results);
	}

	/** Diagnostic phase timers: task submission and result application are different stall sources. */
	@Inject(
		method = "updateChunks(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V",
		at = @At("HEAD"),
		require = 0,
		remap = false
	)
	private void chunksoptimizer$beginChunkSubmit(CallbackInfo ci) {
		FrameStats.beginChunkSubmit();
	}

	@Inject(
		method = "updateChunks(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V",
		at = @At("TAIL"),
		require = 0,
		remap = false
	)
	private void chunksoptimizer$endChunkSubmit(CallbackInfo ci) {
		FrameStats.endChunkSubmit();
	}

	@Inject(
		method = "processChunkBuilds(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
		at = @At("HEAD"),
		require = 0,
		remap = false
	)
	private void chunksoptimizer$beginChunkApply(CallbackInfo ci) {
		chunksoptimizer$processingBuilds = true;
		FrameStats.beginChunkApply();
	}

	@Inject(
		method = "processChunkBuilds(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
		at = @At("TAIL"),
		require = 0,
		remap = false
	)
	private void chunksoptimizer$endChunkApply(CallbackInfo ci) {
		FrameStats.endChunkApply();
		chunksoptimizer$processingBuilds = false;
	}
}
