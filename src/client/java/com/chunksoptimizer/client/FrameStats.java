package com.chunksoptimizer.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Optional frame-time breakdown logger (diagnostics only, no gameplay effect).
 *
 * <p>Every {@value #LOG_INTERVAL_SECONDS} seconds it logs average / p99 / max values for:
 * <ul>
 *   <li><b>frame</b> — wall time between two {@code Minecraft.runTick} calls;</li>
 *   <li><b>tick</b> — time spent in client ticks (entities, block entities, particles, level);</li>
 *   <li><b>render</b> — time spent in {@code GameRenderer.render} (world, entities, GUI);</li>
 *   <li><b>upscale</b> — final present-path blit / RCAS sharpen;</li>
 *   <li><b>present</b> — SDL swap-buffer time after rendering;</li>
 *   <li><b>chunk submit/apply</b> — Sodium's {@code updateChunks} (task submission) and
 *       {@code processChunkBuilds} (result application / GPU upload), when Sodium is installed;</li>
 *   <li><b>GC</b> — collection count and pause time reported by the JVM.</li>
 * </ul>
 *
 * <p>This makes the usual stutter suspects distinguishable: high {@code tick} = entity/particle
 * logic, high {@code render} with low GC = chunk uploads / shader compilation / overdraw, high GC =
 * heap pressure. The slowest frame also logs its own phase values plus a residual {@code other}
 * bucket, so the phase maxima from different frames cannot be mistaken for the cause of that frame.
 * Only enabled with {@code debugFrameStats=true}.
 */
public final class FrameStats {
	private static final int CAPACITY = 4096;
	private static final long LOG_INTERVAL_NS = 10_000_000_000L;
	private static final int LOG_INTERVAL_SECONDS = 10;

	private static final long[] FRAME_NS = new long[CAPACITY];
	private static final long[] TICK_NS = new long[CAPACITY];
	private static final long[] RENDER_NS = new long[CAPACITY];
	/** Final present-path blit/upscale (including RCAS) before the swap. */
	private static final long[] UPSCALE_NS = new long[CAPACITY];
	/** SDL swap-buffer / GPU present, after {@code GameRenderer.render}. */
	private static final long[] PRESENT_NS = new long[CAPACITY];
	/** Sodium chunk task submission (updateChunks), separate from the rest of render. */
	private static final long[] CHUNK_SUBMIT_NS = new long[CAPACITY];
	/** Sodium chunk result application / GPU uploads (processChunkBuilds). */
	private static final long[] CHUNK_APPLY_NS = new long[CAPACITY];
	private static final int[] TICKS = new int[CAPACITY];
	/** Whether a GC completed during that frame, used to attribute the worst frame. */
	private static final boolean[] GC_FRAME = new boolean[CAPACITY];

	/** Cached so the per-frame sampling does not re-resolve the MXBeans. */
	private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

	private static int index;
	private static int count;
	private static int gcFrames;

	/** Phase values from the single slowest frame in the current window. */
	private static long worstFrameNs;
	private static long worstTickNs;
	private static long worstRenderNs;
	private static long worstUpscaleNs;
	private static long worstPresentNs;
	private static long worstSubmitNs;
	private static long worstApplyNs;
	private static boolean worstGc;

	private static long frameStartNs;
	private static long tickStartNs;
	private static long tickAccumNs;
	private static int tickCount;
	private static long renderStartNs;
	private static long renderAccumNs;
	private static long upscaleStartNs;
	private static long upscaleAccumNs;
	private static long presentStartNs;
	private static long presentAccumNs;
	private static long chunkSubmitStartNs;
	private static long chunkSubmitAccumNs;
	private static long chunkApplyStartNs;
	private static long chunkApplyAccumNs;

	private static long windowStartNs;
	private static long lastGcCount = Long.MIN_VALUE;
	private static long lastGcTimeMs = Long.MIN_VALUE;
	/** GC counters sampled at the start of the current frame, for per-frame attribution. */
	private static long frameGcCount;
	private static long frameGcTimeMs;

	private FrameStats() {}

	public static void beginFrame() {
		if (!ChunkOptConfig.debugFrameStats) {
			if (windowStartNs != 0L) reset();
			return;
		}
		long now = System.nanoTime();
		if (windowStartNs == 0L) {
			windowStartNs = now;
			// Baseline the GC counters at the start of the window so the first summary also has a
			// delta instead of reporting 0 collections while attributing GCs to frames.
			frameGcCount = gcCount();
			frameGcTimeMs = gcTimeMs();
			lastGcCount = frameGcCount;
			lastGcTimeMs = frameGcTimeMs;
		}
		frameStartNs = now;
		tickAccumNs = 0L;
		tickCount = 0;
		renderAccumNs = 0L;
		renderStartNs = 0L;
		upscaleAccumNs = 0L;
		upscaleStartNs = 0L;
		presentAccumNs = 0L;
		presentStartNs = 0L;
		chunkSubmitAccumNs = 0L;
		chunkSubmitStartNs = 0L;
		chunkApplyAccumNs = 0L;
		chunkApplyStartNs = 0L;
	}

	public static void endFrame() {
		if (!ChunkOptConfig.debugFrameStats || frameStartNs == 0L) return;
		long now = System.nanoTime();
		long dt = now - frameStartNs;
		frameStartNs = 0L;
		// Ignore stalls caused by alt-tab, loading screens, breakpoints, ...
		if (dt <= 0L || dt > 1_000_000_000L) return;

		// One counter sample per frame: compare against the value seen at the end of the previous
		// frame, which is effectively this frame's start.
		long collections = gcCount();
		long pauseMs = gcTimeMs();
		boolean gcThisFrame = collections != frameGcCount || pauseMs != frameGcTimeMs;
		frameGcCount = collections;
		frameGcTimeMs = pauseMs;
		FRAME_NS[index] = dt;
		TICK_NS[index] = tickAccumNs;
		RENDER_NS[index] = renderAccumNs;
		UPSCALE_NS[index] = upscaleAccumNs;
		PRESENT_NS[index] = presentAccumNs;
		CHUNK_SUBMIT_NS[index] = chunkSubmitAccumNs;
		CHUNK_APPLY_NS[index] = chunkApplyAccumNs;
		TICKS[index] = tickCount;
		GC_FRAME[index] = gcThisFrame;
		if (gcThisFrame) gcFrames++;
		if (dt > worstFrameNs) {
			worstFrameNs = dt;
			worstTickNs = tickAccumNs;
			worstRenderNs = renderAccumNs;
			worstUpscaleNs = upscaleAccumNs;
			worstPresentNs = presentAccumNs;
			worstSubmitNs = chunkSubmitAccumNs;
			worstApplyNs = chunkApplyAccumNs;
			worstGc = gcThisFrame;
		}
		index = (index + 1) % CAPACITY;
		if (count < CAPACITY) count++;

		if (now - windowStartNs >= LOG_INTERVAL_NS) log(now);
	}

	public static void beginTick() {
		if (ChunkOptConfig.debugFrameStats) tickStartNs = System.nanoTime();
	}

	public static void endTick() {
		if (!ChunkOptConfig.debugFrameStats || tickStartNs == 0L) return;
		tickAccumNs += System.nanoTime() - tickStartNs;
		tickStartNs = 0L;
		tickCount++;
	}

	public static void beginRender() {
		if (ChunkOptConfig.debugFrameStats) renderStartNs = System.nanoTime();
	}

	public static void endRender() {
		if (!ChunkOptConfig.debugFrameStats || renderStartNs == 0L) return;
		renderAccumNs += System.nanoTime() - renderStartNs;
		renderStartNs = 0L;
	}

	public static void beginUpscale() {
		if (ChunkOptConfig.debugFrameStats) upscaleStartNs = System.nanoTime();
	}

	public static void endUpscale() {
		if (!ChunkOptConfig.debugFrameStats || upscaleStartNs == 0L) return;
		upscaleAccumNs += System.nanoTime() - upscaleStartNs;
		upscaleStartNs = 0L;
	}

	public static void beginPresent() {
		if (ChunkOptConfig.debugFrameStats) presentStartNs = System.nanoTime();
	}

	public static void endPresent() {
		if (!ChunkOptConfig.debugFrameStats || presentStartNs == 0L) return;
		presentAccumNs += System.nanoTime() - presentStartNs;
		presentStartNs = 0L;
	}

	public static void beginChunkSubmit() {
		if (ChunkOptConfig.debugFrameStats) chunkSubmitStartNs = System.nanoTime();
	}

	public static void endChunkSubmit() {
		if (!ChunkOptConfig.debugFrameStats || chunkSubmitStartNs == 0L) return;
		chunkSubmitAccumNs += System.nanoTime() - chunkSubmitStartNs;
		chunkSubmitStartNs = 0L;
	}

	public static void beginChunkApply() {
		if (ChunkOptConfig.debugFrameStats) chunkApplyStartNs = System.nanoTime();
	}

	public static void endChunkApply() {
		if (!ChunkOptConfig.debugFrameStats || chunkApplyStartNs == 0L) return;
		chunkApplyAccumNs += System.nanoTime() - chunkApplyStartNs;
		chunkApplyStartNs = 0L;
	}

	private static void log(long now) {
		int n = count;
		double seconds = (now - windowStartNs) / 1_000_000_000.0;
		long gcCount = gcCount();
		long gcTimeMs = gcTimeMs();
		long collections = lastGcCount == Long.MIN_VALUE ? 0L : gcCount - lastGcCount;
		long gcPauseMs = lastGcTimeMs == Long.MIN_VALUE ? 0L : gcTimeMs - lastGcTimeMs;
		lastGcCount = gcCount;
		lastGcTimeMs = gcTimeMs;
		// Carry the per-frame baseline across the window boundary, so the first frame of the next
		// window is not mis-attributed as a GC frame.
		frameGcCount = gcCount;
		frameGcTimeMs = gcTimeMs;
		// Tick, rendering work, upscale and present are sequential, but Sodium chunk work can run
		// inside or just outside GameRenderer.render. Use the largest of those overlapping buckets
		// so the residual is not overstated. What remains is idle, OS scheduling or log I/O.
		long renderWorkNs = Math.max(worstRenderNs, Math.max(worstSubmitNs, worstApplyNs));
		long otherNs = worstFrameNs - worstTickNs - renderWorkNs - worstUpscaleNs - worstPresentNs;
		if (otherNs < 0L) otherNs = 0L;

		if (n > 0) {
			String worstGcLabel = worstGc ? "GC" : "no GC";
			ChunksOptimizerClient.LOGGER.info(
				"[Chunks Optimizer] Frame stats ({}s, {} frames): frame avg {} / p99 {} / max {} ms"
					+ " | tick avg {} / p99 {} / max {} ms (max {} ticks/frame)"
					+ " | render avg {} / p99 {} / max {} ms"
					+ " | upscale avg {} / max {} ms, present avg {} / max {} ms"
					+ " | chunk submit avg {} / max {} ms, apply avg {} / max {} ms"
					+ " | budget {}/{} x{} (spike cd {})"
					+ " | GC {} collections, {} ms in {} frames"
					+ " | worst frame {} ms ({}) [tick {} / render {} / upscale {} / present {} / submit {} / apply {} / other {}]",
				String.format(Locale.ROOT, "%.1f", seconds), n,
				ms(average(FRAME_NS, n)), ms(percentile(FRAME_NS, n, 0.99)), ms(max(FRAME_NS, n)),
				ms(average(TICK_NS, n)), ms(percentile(TICK_NS, n, 0.99)), ms(max(TICK_NS, n)), maxInt(TICKS, n),
				ms(average(RENDER_NS, n)), ms(percentile(RENDER_NS, n, 0.99)), ms(max(RENDER_NS, n)),
				ms(average(UPSCALE_NS, n)), ms(max(UPSCALE_NS, n)),
				ms(average(PRESENT_NS, n)), ms(max(PRESENT_NS, n)),
				ms(average(CHUNK_SUBMIT_NS, n)), ms(max(CHUNK_SUBMIT_NS, n)),
				ms(average(CHUNK_APPLY_NS, n)), ms(max(CHUNK_APPLY_NS, n)),
				ChunkBudget.getEffectiveRebuildBudget(), ChunkBudget.getEffectiveUploadBudget(),
				String.format(Locale.ROOT, "%.2f", ChunkBudget.getUploadBudgetScale()), ChunkBudget.getSpikeCooldown(),
				collections, gcPauseMs, gcFrames,
				ms(worstFrameNs / 1_000_000.0), worstGcLabel,
				ms(worstTickNs / 1_000_000.0), ms(worstRenderNs / 1_000_000.0),
				ms(worstUpscaleNs / 1_000_000.0), ms(worstPresentNs / 1_000_000.0),
				ms(worstSubmitNs / 1_000_000.0),
				ms(worstApplyNs / 1_000_000.0), ms(otherNs / 1_000_000.0));
		}
		reset();
		windowStartNs = now;
	}

	/** Total collections reported by the JVM. Returns 0 if the MXBeans are unavailable. */
	private static long gcCount() {
		long total = 0L;
		try {
			for (int i = 0; i < GC_BEANS.size(); i++) {
				long collections = GC_BEANS.get(i).getCollectionCount();
				if (collections > 0L) total += collections;
			}
		} catch (Throwable ignored) {
			// GC beans are optional; the frame numbers are still useful without them.
		}
		return total;
	}

	/** Total collection time in milliseconds, or 0 if the MXBeans are unavailable. */
	private static long gcTimeMs() {
		long total = 0L;
		try {
			for (int i = 0; i < GC_BEANS.size(); i++) {
				long time = GC_BEANS.get(i).getCollectionTime();
				if (time > 0L) total += time;
			}
		} catch (Throwable ignored) {
			// GC beans are optional; the frame numbers are still useful without them.
		}
		return total;
	}

	private static void reset() {
		index = 0;
		count = 0;
		gcFrames = 0;
		worstFrameNs = 0L;
		worstTickNs = 0L;
		worstRenderNs = 0L;
		worstUpscaleNs = 0L;
		worstPresentNs = 0L;
		worstSubmitNs = 0L;
		worstApplyNs = 0L;
		worstGc = false;
		windowStartNs = 0L;
		frameStartNs = 0L;
		tickStartNs = 0L;
		tickAccumNs = 0L;
		tickCount = 0;
		renderStartNs = 0L;
		renderAccumNs = 0L;
		upscaleStartNs = 0L;
		upscaleAccumNs = 0L;
		presentStartNs = 0L;
		presentAccumNs = 0L;
		chunkSubmitStartNs = 0L;
		chunkSubmitAccumNs = 0L;
		chunkApplyStartNs = 0L;
		chunkApplyAccumNs = 0L;
	}

	private static double average(long[] values, int n) {
		long sum = 0L;
		for (int i = 0; i < n; i++) sum += values[i];
		return sum / (double) n / 1_000_000.0;
	}

	private static double percentile(long[] values, int n, double fraction) {
		long[] copy = Arrays.copyOf(values, n);
		Arrays.sort(copy);
		int i = (int) Math.min(n - 1, Math.max(0, Math.round((n - 1) * fraction)));
		return copy[i] / 1_000_000.0;
	}

	private static double max(long[] values, int n) {
		long max = 0L;
		for (int i = 0; i < n; i++) if (values[i] > max) max = values[i];
		return max / 1_000_000.0;
	}

	private static int maxInt(int[] values, int n) {
		int max = 0;
		for (int i = 0; i < n; i++) if (values[i] > max) max = values[i];
		return max;
	}

	private static String ms(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
