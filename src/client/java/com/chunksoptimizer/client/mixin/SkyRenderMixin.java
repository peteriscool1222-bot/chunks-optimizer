package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional sky skipping (sky disc, sun/moon, stars, sunrise/sunset, end sky). The frame still gets a
 * clear color, so this looks like a plain flat sky while removing several full-screen/overdraw passes.
 */
@Mixin(SkyRenderer.class)
public class SkyRenderMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$skipSky(GpuBufferSlice bufferSlice, SkyRenderState renderState, CallbackInfo ci) {
		if (ChunkOptConfig.disableSky) {
			GraphicsFeatures.announce("sky", "Sky rendering is disabled");
			ci.cancel();
		}
	}
}
