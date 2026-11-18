package com.chunksoptimizer.client;

/** Per-frame budget counters and adaptive throttling to smooth chunk-related CPU spikes. */
public final class ChunkBudget {
	private static int scheduledRebuildsThisFrame;
	private static int uploadsThisFrame;
	private static long frameSeq;

	private static volatile int effectiveRebuildBudget;
	private static volatile int effectiveUploadBudget;
	/** Scales Sodium's GPU upload budget: tighter when frame time is high, looser when there is headroom. */
	private static volatile double uploadBudgetScale = 1.0;

	private static final long[] frameNs = new long[32];
	private static int frameNsIdx;
	private static int frameNsCount;
	private static long lastFrameStartNs;
	private static volatile int warmupRemaining;
	/** False in menus/pause screens, where Minecraft deliberately runs at 10 FPS. */
	private static volatile boolean worldActive;

	/**
	 * Frames left in the cooldown after one genuinely slow frame. A single 100 ms chunk-load spike
	 * barely moves the 32-frame average, so the old average-only logic immediately re-opened the
	 * budgets and let the next burst through. Reacting to the spike itself keeps the throttle tight
	 * while the backlog is being worked off.
	 */
	private static final int SPIKE_COOLDOWN_FRAMES = 45;
	private static int spikeCooldownFrames;

	static {
		effectiveRebuildBudget = 1;
		effectiveUploadBudget = 1;
	}

	private ChunkBudget() {}

	public static void beginFrame() {
		long now = System.nanoTime();
		if (lastFrameStartNs != 0L) {
			long dt = now - lastFrameStartNs;
			if (worldActive) {
				if (dt > 0 && dt < 1_000_000_000L) { // ignore pauses
					frameNs[frameNsIdx] = dt;
					frameNsIdx = (frameNsIdx + 1) & 31;
					if (frameNsCount < 32) frameNsCount++;
					// Only real in-world spikes matter. Menus are capped at 10 FPS by Minecraft,
					// so counting them would pin the throttle on permanently between sessions.
					if (dt > (long) ChunkOptConfig.targetFrameMs * 1_000_000L) {
						spikeCooldownFrames = SPIKE_COOLDOWN_FRAMES;
					}
				}
			} else {
				// Drop the rolling window so the first in-world frames are not dragged down by
				// menu frame times.
				frameNsCount = 0;
				frameNsIdx = 0;
			}
		}
		if (spikeCooldownFrames > 0) spikeCooldownFrames--;
		lastFrameStartNs = now;
		frameSeq++;
		scheduledRebuildsThisFrame = 0;
		uploadsThisFrame = 0;
		if ((frameSeq & 7) == 0) updateAdaptive();
	}

	public static void setWorldActive(boolean active) { worldActive = active; }

	public static void triggerWarmup() {
		warmupRemaining = Math.max(warmupRemaining, ChunkOptConfig.warmupTicks);
	}

	public static void tickWarmup() {
		if (warmupRemaining > 0) warmupRemaining--;
	}

	public static boolean isWarmup() { return warmupRemaining > 0; }

	private static void updateAdaptive() {
		// GPU upload scaling follows the same adaptive signal; without it, leave Sodium's budget untouched.
		boolean gpuScaling = ChunkOptConfig.adaptive && ChunkOptConfig.gpuUploadBudgetScaling;
		int rebuildCap = ChunkOptConfig.maxScheduledRebuildsPerFrame;
		int uploadCap = ChunkOptConfig.maxUploadsPerFrame;
		int rMin = ChunkOptConfig.minScheduledRebuildsPerFrame;
		int rMax = ChunkOptConfig.adaptiveRebuildCap;
		int uMin = ChunkOptConfig.minUploadsPerFrame;
		int uMax = ChunkOptConfig.adaptiveUploadCap;

		// A single slow frame is a better signal than the 32-frame average while exploring: one
		// 100 ms hitch leaves the average near 6 ms, so without this the budget reopens immediately.
		if (ChunkOptConfig.adaptive && spikeCooldownFrames > 0) {
			effectiveRebuildBudget = rMin;
			effectiveUploadBudget = Math.max(uMin, 1);
			uploadBudgetScale = gpuScaling ? 0.4 : 1.0;
			return;
		}

		if (!ChunkOptConfig.adaptive || frameNsCount < 4) {
			effectiveRebuildBudget = rebuildCap;
			effectiveUploadBudget = uploadCap;
			if (isWarmup()) {
				effectiveRebuildBudget = Math.min(effectiveRebuildBudget, rMin);
				effectiveUploadBudget = Math.min(effectiveUploadBudget, Math.max(1, uMin));
			}
			uploadBudgetScale = gpuScaling && isWarmup() ? 0.75 : 1.0;
			return;
		}
		long sum = 0;
		for (int i = 0; i < frameNsCount; i++) sum += frameNs[i];
		double avgMs = (sum / (double) frameNsCount) / 1_000_000.0;
		int target = ChunkOptConfig.targetFrameMs;

		if (isWarmup() || avgMs > target * 1.35) {
			effectiveRebuildBudget = rMin;
			effectiveUploadBudget = Math.max(uMin, 1);
			uploadBudgetScale = gpuScaling ? 0.6 : 1.0;
		} else if (avgMs > target) {
			effectiveRebuildBudget = Math.max(rMin, Math.min(rebuildCap, 1));
			effectiveUploadBudget = Math.max(uMin, Math.min(uploadCap, 1));
			uploadBudgetScale = gpuScaling ? 0.8 : 1.0;
		} else if (avgMs < target * 0.7) {
			effectiveRebuildBudget = Math.min(rMax, Math.max(rebuildCap, 2));
			effectiveUploadBudget = Math.min(uMax, Math.max(uploadCap, 2));
			uploadBudgetScale = gpuScaling ? 1.35 : 1.0;
		} else {
			effectiveRebuildBudget = rebuildCap;
			effectiveUploadBudget = uploadCap;
			uploadBudgetScale = 1.0;
		}
	}

	public static double getUploadBudgetScale() { return uploadBudgetScale; }

	public static boolean tryConsumeRebuildSlot() {
		int limit = effectiveRebuildBudget;
		if (limit <= 0) return false;
		if (scheduledRebuildsThisFrame >= limit) return false;
		scheduledRebuildsThisFrame++;
		return true;
	}

	public static boolean tryConsumeUploadSlot() {
		int limit = effectiveUploadBudget;
		if (limit <= 0) return false;
		if (uploadsThisFrame >= limit) return false;
		uploadsThisFrame++;
		return true;
	}

	public static int getEffectiveRebuildBudget() { return effectiveRebuildBudget; }
	public static int getEffectiveUploadBudget() { return effectiveUploadBudget; }
	/** Frames remaining in the post-spike throttle, for diagnostics. */
	public static int getSpikeCooldown() { return spikeCooldownFrames; }
}
