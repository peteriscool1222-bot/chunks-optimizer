package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.FrameStats;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures SDL swap-buffer / present time, which is outside {@code GameRenderer.render}. */
@Mixin(GlSurface.class)
public class GlSurfacePresentStatsMixin {
	@Inject(method = "present", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$beginPresentStats(CallbackInfo ci) {
		FrameStats.beginPresent();
	}

	@Inject(method = "present", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$endPresentStats(CallbackInfo ci) {
		FrameStats.endPresent();
	}
}
