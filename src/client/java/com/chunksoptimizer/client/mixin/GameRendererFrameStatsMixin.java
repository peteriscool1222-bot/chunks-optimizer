package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.FrameStats;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures the render pass for {@link FrameStats} (world, entities, GUI and present). */
@Mixin(GameRenderer.class)
public class GameRendererFrameStatsMixin {
	@Inject(method = "render", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$beginRenderStats(CallbackInfo ci) {
		FrameStats.beginRender();
	}

	@Inject(method = "render", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$endRenderStats(CallbackInfo ci) {
		FrameStats.endRender();
	}
}
