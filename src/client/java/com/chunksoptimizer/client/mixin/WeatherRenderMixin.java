package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional weather (rain/snow) skipping. Skipping extraction and buffer preparation also removes the
 * CPU cost of rebuilding weather columns, not just the draw call.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherRenderMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$skipWeatherExtract(ClientLevel level, float tickDelta, Vec3 cameraPos,
			WeatherRenderState renderState, CallbackInfo ci) {
		if (ChunkOptConfig.disableWeather) {
			GraphicsFeatures.announce("weather", "Weather rendering is disabled");
			ci.cancel();
		}
	}

	@Inject(method = "prepare", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$skipWeatherPrepare(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {
		if (ChunkOptConfig.disableWeather) ci.cancel();
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$skipWeatherRender(WeatherRenderState renderState, RenderPass pass, CallbackInfo ci) {
		if (ChunkOptConfig.disableWeather) ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$skipWeatherRenderOit(OitStage stage, WeatherRenderState renderState, RenderPass pass,
			CallbackInfo ci) {
		if (ChunkOptConfig.disableWeather) ci.cancel();
	}
}
