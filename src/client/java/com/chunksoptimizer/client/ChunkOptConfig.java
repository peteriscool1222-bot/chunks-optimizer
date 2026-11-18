package com.chunksoptimizer.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tunable settings aimed at older CPUs (e.g. i5-4690: 4 cores / 4 threads).
 * Loaded from config/chunks-optimizer.properties if present.
 */
public final class ChunkOptConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(ChunksOptimizerClient.MOD_ID);

	public static volatile int maxScheduledRebuildsPerFrame = 1;
	public static volatile int maxUploadsPerFrame = 1;
	public static volatile int minScheduledRebuildsPerFrame = 1;
	public static volatile int minUploadsPerFrame = 1;
	public static volatile boolean adaptive = true;
	/** Target frame time in ms; when exceeded, budgets are reduced toward minimums. */
	public static volatile int targetFrameMs = 40;
	/** When frame time is comfortably below target, budgets may increase up to these caps. */
	public static volatile int adaptiveRebuildCap = 2;
	public static volatile int adaptiveUploadCap = 2;
	/** Extra-aggressive throttling window after world load/dimension change (ticks). */
	public static volatile int warmupTicks = 200;
	public static volatile boolean throttleSodium = true;
	public static volatile boolean throttleVanilla = true;
	/** Scale Sodium's per-frame GPU upload budget with observed frame time (less GPU stall, faster loads when idle). */
	public static volatile boolean gpuUploadBudgetScaling = true;

	// ---- Extra graphics reduction (all opt-in except classic transparency) ----

	/** Internal render resolution multiplier, 0.25-1.0. 1.0 = native resolution (off). */
	public static volatile double renderScale = 1.0;
	/** Apply contrast-adaptive sharpening (AMD FSR RCAS) to the final image. */
	public static volatile boolean sharpen = false;
	/** Sharpening amount, 0.0-1.0. Higher = crisper edges but more visible grain on textures. */
	public static volatile double sharpenStrength = 0.6;
	/**
	 * RCAS noise suppression, 0.0-1.0. 0 = plain RCAS, 1 = AMD's full {@code FSR_RCAS_DENOISE}
	 * step. Keeps coherent edges and text crisp while damping the amplification of upscaler
	 * ringing and texture grain, which is what makes sharpening look "noisy".
	 */
	public static volatile double sharpenDenoise = 1.0;
	/** Absolute entity render radius in blocks. 0 = vanilla behaviour. */
	public static volatile int entityRenderDistance = 0;
	/** Absolute block entity render radius in blocks. 0 = vanilla behaviour. */
	public static volatile int blockEntityRenderDistance = 0;
	/** Hard cap on live particles. 0 = vanilla behaviour. */
	public static volatile int particleLimit = 0;
	/** Skip first-person hand/item rendering. */
	public static volatile boolean hideFirstPersonHand = false;
	/** Skip rain/snow rendering (and its CPU-side buffer rebuilds). */
	public static volatile boolean disableWeather = false;
	/** Skip sky rendering (sky disc, sun/moon, stars). */
	public static volatile boolean disableSky = false;
	/** Force classic translucency instead of the expensive OIT pipeline. */
	public static volatile boolean forceClassicTransparency = true;

	// ---- Diagnostics (opt-in) ----

	/** Log a 10-second frame-time breakdown (frame/tick/render/GC). Diagnostic only. */
	public static volatile boolean debugFrameStats = false;

	private ChunkOptConfig() {}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("chunks-optimizer.properties");
		Properties p = new Properties();
		if (Files.exists(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				p.load(in);
			} catch (IOException e) {
				LOGGER.warn("[Chunks Optimizer] Failed to read config, using defaults", e);
			}
		}

		maxScheduledRebuildsPerFrame = getInt(p, "maxScheduledRebuildsPerFrame", maxScheduledRebuildsPerFrame);
		maxUploadsPerFrame = getInt(p, "maxUploadsPerFrame", maxUploadsPerFrame);
		minScheduledRebuildsPerFrame = getInt(p, "minScheduledRebuildsPerFrame", minScheduledRebuildsPerFrame);
		minUploadsPerFrame = getInt(p, "minUploadsPerFrame", minUploadsPerFrame);
		adaptive = getBool(p, "adaptive", adaptive);
		targetFrameMs = getInt(p, "targetFrameMs", targetFrameMs);
		adaptiveRebuildCap = getInt(p, "adaptiveRebuildCap", adaptiveRebuildCap);
		adaptiveUploadCap = getInt(p, "adaptiveUploadCap", adaptiveUploadCap);
		warmupTicks = getInt(p, "warmupTicks", warmupTicks);
		throttleSodium = getBool(p, "throttleSodium", throttleSodium);
		throttleVanilla = getBool(p, "throttleVanilla", throttleVanilla);
		gpuUploadBudgetScaling = getBool(p, "gpuUploadBudgetScaling", gpuUploadBudgetScaling);
		renderScale = getDouble(p, "renderScale", renderScale);
		sharpen = getBool(p, "sharpen", sharpen);
		// Migration: configs written before the denoise path existed were tuned against plain RCAS,
		// where 0.8-0.9 looked grainy. Fall back to the new, gentler default once.
		boolean legacySharpenStrength = !p.containsKey("sharpenDenoise") && p.containsKey("sharpenStrength");
		sharpenStrength = getDouble(p, "sharpenStrength", sharpenStrength);
		sharpenDenoise = getDouble(p, "sharpenDenoise", sharpenDenoise);
		if (legacySharpenStrength) {
			sharpenStrength = 0.6;
			p.setProperty("sharpenStrength", Double.toString(sharpenStrength));
			LOGGER.info("[Chunks Optimizer] Reset sharpenStrength to {} (RCAS denoise is now enabled)", sharpenStrength);
		}
		entityRenderDistance = getInt(p, "entityRenderDistance", entityRenderDistance);
		blockEntityRenderDistance = getInt(p, "blockEntityRenderDistance", blockEntityRenderDistance);
		particleLimit = getInt(p, "particleLimit", particleLimit);
		hideFirstPersonHand = getBool(p, "hideFirstPersonHand", hideFirstPersonHand);
		disableWeather = getBool(p, "disableWeather", disableWeather);
		disableSky = getBool(p, "disableSky", disableSky);
		forceClassicTransparency = getBool(p, "forceClassicTransparency", forceClassicTransparency);
		debugFrameStats = getBool(p, "debugFrameStats", debugFrameStats);

		clamp();
		RenderScale.setFactor(renderScale);
		save(path, p);
		LOGGER.info("[Chunks Optimizer] Config: rebuilds={}-{}/{}, uploads={}-{}/{}, adaptive={}, targetFrameMs={}, warmupTicks={}",
			minScheduledRebuildsPerFrame, maxScheduledRebuildsPerFrame, adaptiveRebuildCap,
			minUploadsPerFrame, maxUploadsPerFrame, adaptiveUploadCap, adaptive, targetFrameMs, warmupTicks);
		LOGGER.info("[Chunks Optimizer] Graphics: renderScale={}, sharpen={}, sharpenStrength={}, sharpenDenoise={}, entityDistance={}, blockEntityDistance={}, particleLimit={}, hideHand={}, weatherEnabled={}, skyEnabled={}, classicTransparency={}",
			renderScale, sharpen, sharpenStrength, sharpenDenoise, entityRenderDistance, blockEntityRenderDistance, particleLimit,
			hideFirstPersonHand, !disableWeather, !disableSky, forceClassicTransparency);
		if (debugFrameStats) {
			LOGGER.info("[Chunks Optimizer] Frame stats logging is enabled (10-second summaries)");
		}
	}

	/** Writes the current in-memory settings back to config/chunks-optimizer.properties. */
	public static void save() {
		clamp();
		// The render scale is applied by whoever changed it (the Sodium page calls
		// RenderScale.requestFactor so the resize happens at the start of the next frame). This method
		// only persists the value.
		Path path = FabricLoader.getInstance().getConfigDir().resolve("chunks-optimizer.properties");
		Properties p = new Properties();
		p.setProperty("maxScheduledRebuildsPerFrame", Integer.toString(maxScheduledRebuildsPerFrame));
		p.setProperty("maxUploadsPerFrame", Integer.toString(maxUploadsPerFrame));
		p.setProperty("minScheduledRebuildsPerFrame", Integer.toString(minScheduledRebuildsPerFrame));
		p.setProperty("minUploadsPerFrame", Integer.toString(minUploadsPerFrame));
		p.setProperty("adaptive", Boolean.toString(adaptive));
		p.setProperty("targetFrameMs", Integer.toString(targetFrameMs));
		p.setProperty("adaptiveRebuildCap", Integer.toString(adaptiveRebuildCap));
		p.setProperty("adaptiveUploadCap", Integer.toString(adaptiveUploadCap));
		p.setProperty("warmupTicks", Integer.toString(warmupTicks));
		p.setProperty("throttleSodium", Boolean.toString(throttleSodium));
		p.setProperty("throttleVanilla", Boolean.toString(throttleVanilla));
		p.setProperty("gpuUploadBudgetScaling", Boolean.toString(gpuUploadBudgetScaling));
		p.setProperty("renderScale", Double.toString(renderScale));
		p.setProperty("sharpen", Boolean.toString(sharpen));
		p.setProperty("sharpenStrength", Double.toString(sharpenStrength));
		p.setProperty("sharpenDenoise", Double.toString(sharpenDenoise));
		p.setProperty("entityRenderDistance", Integer.toString(entityRenderDistance));
		p.setProperty("blockEntityRenderDistance", Integer.toString(blockEntityRenderDistance));
		p.setProperty("particleLimit", Integer.toString(particleLimit));
		p.setProperty("hideFirstPersonHand", Boolean.toString(hideFirstPersonHand));
		p.setProperty("disableWeather", Boolean.toString(disableWeather));
		p.setProperty("disableSky", Boolean.toString(disableSky));
		p.setProperty("forceClassicTransparency", Boolean.toString(forceClassicTransparency));
		p.setProperty("debugFrameStats", Boolean.toString(debugFrameStats));
		save(path, p);
	}

	private static void clamp() {
		maxScheduledRebuildsPerFrame = clampInt(maxScheduledRebuildsPerFrame, 0, 64);
		maxUploadsPerFrame = clampInt(maxUploadsPerFrame, 0, 64);
		minScheduledRebuildsPerFrame = clampInt(minScheduledRebuildsPerFrame, 0, maxScheduledRebuildsPerFrame);
		minUploadsPerFrame = clampInt(minUploadsPerFrame, 0, maxUploadsPerFrame);
		adaptiveRebuildCap = clampInt(adaptiveRebuildCap, maxScheduledRebuildsPerFrame, 64);
		adaptiveUploadCap = clampInt(adaptiveUploadCap, maxUploadsPerFrame, 64);
		targetFrameMs = clampInt(targetFrameMs, 10, 200);
		warmupTicks = clampInt(warmupTicks, 0, 20 * 120);
		renderScale = Math.max(0.25, Math.min(1.0, renderScale));
		sharpenStrength = Math.max(0.0, Math.min(1.0, sharpenStrength));
		sharpenDenoise = Math.max(0.0, Math.min(1.0, sharpenDenoise));
		entityRenderDistance = clampInt(entityRenderDistance, 0, 512);
		blockEntityRenderDistance = clampInt(blockEntityRenderDistance, 0, 512);
		particleLimit = clampInt(particleLimit, 0, 16384);
	}

	private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

	private static double getDouble(Properties p, String k, double def) {
		if (p.containsKey(k)) {
			try { return Double.parseDouble(p.getProperty(k).trim()); } catch (NumberFormatException ignored) {}
		}
		p.setProperty(k, Double.toString(def));
		return def;
	}

	private static int getInt(Properties p, String k, int def) {
		if (p.containsKey(k)) {
			try { return Integer.parseInt(p.getProperty(k).trim()); } catch (NumberFormatException ignored) {}
		}
		p.setProperty(k, Integer.toString(def));
		return def;
	}

	private static boolean getBool(Properties p, String k, boolean def) {
		if (p.containsKey(k)) {
			return Boolean.parseBoolean(p.getProperty(k).trim());
		}
		p.setProperty(k, Boolean.toString(def));
		return def;
	}

	private static void save(Path path, Properties p) {
		try {
			Files.createDirectories(path.getParent());
			try (OutputStream out = Files.newOutputStream(path)) {
				p.store(out, "Chunks Optimizer config (tuned for max chunk-load smoothness on older CPUs)");
			}
		} catch (IOException e) {
			LOGGER.warn("[Chunks Optimizer] Failed to save config", e);
		}
	}
}
