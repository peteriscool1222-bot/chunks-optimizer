package com.chunksoptimizer.client;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Exposes this mod's settings as a page inside Sodium's options screen.
 *
 * <p>Sodium discovers this class through the {@code sodium:config_api_user} entry point (declared in
 * {@code fabric.mod.json}). The class is only ever loaded when Sodium is installed, so Sodium remains
 * an optional dependency; without it the mod keeps using {@code config/chunks-optimizer.properties}.
 *
 * <p>Options are bound directly to {@link ChunkOptConfig}'s static fields, so changes take effect
 * immediately (the mixins read the fields every frame). The render scale is a special case: the new
 * factor is scheduled and the framebuffer is resized at the start of the next frame.
 */
public final class SodiumConfigIntegration implements ConfigEntryPoint {
	private static final String NAMESPACE = ChunksOptimizerClient.MOD_ID;

	/** Shared handler: Sodium de-duplicates these, so the file is written once per apply. */
	private static final StorageEventHandler STORAGE = ChunkOptConfig::save;

	private static final ControlValueFormatter NUMBER = value -> Component.literal(Integer.toString(value));
	private static final ControlValueFormatter MILLIS = value -> Component.literal(value + " ms");
	private static final ControlValueFormatter TICKS = value -> Component.literal(value + " ticks");
	private static final ControlValueFormatter BLOCKS =
		value -> Component.literal(value == 0 ? "Vanilla" : value + " blocks");
	private static final ControlValueFormatter PERCENT = value -> Component.literal(value + "%");
	private static final ControlValueFormatter PARTICLE_COUNT =
		value -> Component.literal(value == 0 ? "Vanilla" : Integer.toString(value));

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		ModOptionsBuilder mod = builder.registerOwnModOptions()
			.setName("Chunks Optimizer");

		OptionPageBuilder page = builder.createOptionPage()
			.setName(Component.literal("Chunks Optimizer"));

		OptionGroupBuilder chunkLoading = builder.createOptionGroup()
			.setName(Component.literal("Chunk loading"));

		chunkLoading
			.addOption(bool(builder, "adaptive", "Adaptive budgeting",
				"Reduce chunk rebuild/upload work when frame time exceeds the target, and relax it again when frames are stable.",
				true, () -> ChunkOptConfig.adaptive, value -> ChunkOptConfig.adaptive = value))
			.addOption(integer(builder, "target_frame_ms", "Target frame time",
				"Frame time above this triggers tighter budgeting. Lower = smoother but slower chunk loading.",
				10, 200, 5, 40, MILLIS, () -> snap(ChunkOptConfig.targetFrameMs, 10, 200, 5),
				value -> ChunkOptConfig.targetFrameMs = value))
			.addOption(integer(builder, "max_rebuilds", "Max chunk rebuilds per frame",
				"Hard cap on chunk mesh rebuild tasks per frame. 1 is a good balance on older CPUs.",
				0, 8, 1, 1, NUMBER, () -> snap(ChunkOptConfig.maxScheduledRebuildsPerFrame, 0, 8, 1),
				value -> ChunkOptConfig.maxScheduledRebuildsPerFrame = value))
			.addOption(integer(builder, "max_uploads", "Max chunk uploads per frame",
				"Hard cap on chunk data uploaded to the GPU per frame.",
				0, 8, 1, 1, NUMBER, () -> snap(ChunkOptConfig.maxUploadsPerFrame, 0, 8, 1),
				value -> ChunkOptConfig.maxUploadsPerFrame = value))
			.addOption(integer(builder, "warmup_ticks", "Load warmup",
				"Extra-aggressive throttling window after world load or dimension change.",
				0, 1200, 20, 200, TICKS, () -> snap(ChunkOptConfig.warmupTicks, 0, 1200, 20),
				value -> ChunkOptConfig.warmupTicks = value))
			.addOption(bool(builder, "throttle_sodium", "Throttle Sodium chunk tasks",
				"Limit how many Sodium chunk build tasks are submitted per frame.",
				true, () -> ChunkOptConfig.throttleSodium, value -> ChunkOptConfig.throttleSodium = value))
			.addOption(bool(builder, "throttle_vanilla", "Throttle vanilla chunk uploads",
				"Limit vanilla chunk buffer uploads when Sodium is not handling them.",
				true, () -> ChunkOptConfig.throttleVanilla, value -> ChunkOptConfig.throttleVanilla = value))
			.addOption(bool(builder, "gpu_upload_budget", "Scale Sodium GPU upload budget",
				"Lower Sodium's per-frame GPU upload budget when frame time spikes, raise it when idle.",
				true, () -> ChunkOptConfig.gpuUploadBudgetScaling,
				value -> ChunkOptConfig.gpuUploadBudgetScaling = value));

		OptionGroupBuilder graphics = builder.createOptionGroup()
			.setName(Component.literal("Graphics"));

		graphics
			.addOption(integer(builder, "render_scale", "Render scale",
				"Internal render resolution. 100% = native. Lower values cut GPU cost but blur the image. OpenGL only. Applies immediately.",
				25, 100, 5, 100, PERCENT,
				() -> snap((int) Math.round(ChunkOptConfig.renderScale * 100.0), 25, 100, 5),
				value -> {
					ChunkOptConfig.renderScale = value / 100.0;
					RenderScale.requestFactor(ChunkOptConfig.renderScale);
				}))
			.addOption(bool(builder, "sharpen", "Sharpen image",
				"Contrast-adaptive sharpening (AMD FSR RCAS) after upscaling. Recovers edge detail with very little GPU cost; useful below 100% render scale.",
				false, () -> ChunkOptConfig.sharpen, value -> ChunkOptConfig.sharpen = value))
			.addOption(bool(builder, "easu", "AMD FSR EASU upscale",
				"Edge-adaptive spatial upscaling (AMD FSR 1 EASU) before RCAS. Requires OpenGL 4.0+ (Linux Mesa 4.6); on Windows GL 3.3 it has no effect and the stable bilinear path is kept. Adds one full-resolution pass and needs a restart only if the driver does not support it.",
				false, () -> ChunkOptConfig.easu, value -> ChunkOptConfig.easu = value))
			.addOption(integer(builder, "sharpen_strength", "Sharpen strength",
				"How hard the sharpener works. Higher values restore more edge detail (including text) but amplify texture grain; lower values are subtler.",
				0, 100, 5, 60, PERCENT,
				() -> snap((int) Math.round(ChunkOptConfig.sharpenStrength * 100.0), 0, 100, 5),
				value -> ChunkOptConfig.sharpenStrength = value / 100.0))
			.addOption(integer(builder, "sharpen_denoise", "Sharpen noise suppression",
				"Damps sharpening where neighbouring pixels disagree randomly (upscaler ringing, texture grain, specular sparkle) while keeping coherent edges and text crisp. 0% = plain RCAS (noisiest), 100% = AMD's full denoise step.",
				0, 100, 10, 100, PERCENT,
				() -> snap((int) Math.round(ChunkOptConfig.sharpenDenoise * 100.0), 0, 100, 10),
				value -> ChunkOptConfig.sharpenDenoise = value / 100.0))
			.addOption(integer(builder, "entity_render_distance", "Entity render distance",
				"Absolute radius for entity rendering. 0 = vanilla behaviour.",
				0, 256, 8, 0, BLOCKS, () -> snap(ChunkOptConfig.entityRenderDistance, 0, 256, 8),
				value -> ChunkOptConfig.entityRenderDistance = value))
			.addOption(integer(builder, "block_entity_render_distance", "Block entity render distance",
				"Absolute radius for block entity rendering (chests, signs, ...). 0 = vanilla behaviour.",
				0, 256, 8, 0, BLOCKS, () -> snap(ChunkOptConfig.blockEntityRenderDistance, 0, 256, 8),
				value -> ChunkOptConfig.blockEntityRenderDistance = value))
			.addOption(integer(builder, "particle_limit", "Particle limit",
				"Hard cap on live particles. 0 = vanilla behaviour.",
				0, 16384, 100, 0, PARTICLE_COUNT, () -> snap(ChunkOptConfig.particleLimit, 0, 16384, 100),
				value -> ChunkOptConfig.particleLimit = value))
			.addOption(bool(builder, "hide_first_person_hand", "Hide first-person hand",
				"Skip first-person hand/item rendering.",
				false, () -> ChunkOptConfig.hideFirstPersonHand,
				value -> ChunkOptConfig.hideFirstPersonHand = value))
			.addOption(bool(builder, "disable_weather", "Disable weather rendering",
				"Skip rain/snow rendering and its CPU-side buffer rebuilds.",
				false, () -> ChunkOptConfig.disableWeather, value -> ChunkOptConfig.disableWeather = value))
			.addOption(bool(builder, "disable_sky", "Disable sky rendering",
				"Skip the sky disc, sun/moon and stars.",
				false, () -> ChunkOptConfig.disableSky, value -> ChunkOptConfig.disableSky = value))
			.addOption(bool(builder, "force_classic_transparency", "Force classic transparency",
				"Use the cheap classic translucency path instead of the expensive OIT pipeline.",
				true, () -> ChunkOptConfig.forceClassicTransparency,
				value -> ChunkOptConfig.forceClassicTransparency = value));

		OptionGroupBuilder diagnostics = builder.createOptionGroup()
			.setName(Component.literal("Diagnostics"));

		diagnostics.addOption(bool(builder, "debug_frame_stats", "Log frame stats",
			"Write a 10-second frame-time breakdown (frame, tick, render, GC) to the game log. Diagnostic only; no effect on performance.",
			false, () -> ChunkOptConfig.debugFrameStats, value -> ChunkOptConfig.debugFrameStats = value));

		page.addOptionGroup(chunkLoading);
		page.addOptionGroup(graphics);
		page.addOptionGroup(diagnostics);
		mod.addPage(page);

		ChunksOptimizerClient.LOGGER.info("[Chunks Optimizer] Registered Sodium config page (21 options)");
	}

	private static BooleanOptionBuilder bool(ConfigBuilder builder, String path, String name, String tooltip,
			boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return builder.createBooleanOption(id(path))
			.setName(Component.literal(name))
			.setTooltip(Component.literal(tooltip))
			.setDefaultValue(defaultValue)
			.setBinding(setter, getter)
			.setStorageHandler(STORAGE);
	}

	private static IntegerOptionBuilder integer(ConfigBuilder builder, String path, String name, String tooltip,
			int min, int max, int step, int defaultValue, ControlValueFormatter formatter,
			Supplier<Integer> getter, Consumer<Integer> setter) {
		return builder.createIntegerOption(id(path))
			.setName(Component.literal(name))
			.setTooltip(Component.literal(tooltip))
			.setRange(min, max, step)
			.setDefaultValue(defaultValue)
			.setValueFormatter(formatter)
			.setBinding(setter, getter)
			.setStorageHandler(STORAGE);
	}

	/**
	 * Sodium rejects values that are not on the configured step grid and replaces them with the
	 * default. Values coming from the properties file may be arbitrary, so snap them to the grid
	 * before handing them to Sodium (the file itself is left untouched until the user applies).
	 */
	private static int snap(int value, int min, int max, int step) {
		int clamped = Math.max(min, Math.min(max, value));
		return min + Math.round((clamped - min) / (float) step) * step;
	}

	private static Identifier id(String path) {
		return Identifier.parse(NAMESPACE + ":" + path);
	}
}
