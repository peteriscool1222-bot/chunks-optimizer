package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.FrameStats;
import com.chunksoptimizer.client.GraphicsFeatures;
import com.chunksoptimizer.client.PresentSharpener;
import com.chunksoptimizer.client.RenderScale;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.DirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stretches the final swapchain blit when the framebuffer is smaller than the window.
 *
 * <p>Vanilla's present path is a 1:1 blit that is clamped to the smaller of the window and the source
 * texture, bottom-left aligned. With a reduced framebuffer that leaves the image in a corner of the
 * window instead of filling it. Here the same blit is issued with the source rect set to the whole
 * (smaller) texture and the destination rect set to the whole real window, which upscales it with
 * bilinear filtering.
 *
 * <p>The decision is based on the actual blit sizes rather than the configured factor, so the image
 * still fills the window if the factor is changed while the game is running.
 *
 * <p>When sharpening is enabled the blit is replaced by {@link PresentSharpener}, which upscales into
 * an offscreen texture and then applies AMD FSR RCAS while drawing to the swapchain image.
 *
 * <p>The target class is package-private, so it is referenced by name; the hook is optional
 * ({@code require = 0}) in case a future version moves the blit.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public class GlPresentScaleMixin {
	@Inject(method = "presentTexture", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$beginUpscaleStats(long texture, GpuTextureView source, int width,
			int height, CallbackInfo ci) {
		FrameStats.beginUpscale();
	}

	@Inject(method = "presentTexture", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$endUpscaleStats(long texture, GpuTextureView source, int width,
			int height, CallbackInfo ci) {
		FrameStats.endUpscale();
	}

	@Redirect(
		method = "presentTexture",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"
		),
		require = 0
	)
	private void chunksoptimizer$stretchPresent(DirectStateAccess dsa, int srcFbo, int dstFbo,
			int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1,
			int mask, int filter) {
		int windowWidth = RenderScale.nativeWidth();
		int windowHeight = RenderScale.nativeHeight();
		int sourceWidth = srcX1 - srcX0;
		int sourceHeight = srcY1 - srcY0;

		if (PresentSharpener.isEnabled() && windowWidth > 0 && windowHeight > 0
				&& PresentSharpener.present(dsa, srcFbo, dstFbo,
					srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1,
					mask, filter, windowWidth, windowHeight)) {
			return;
		}

		boolean needsStretch = sourceWidth != windowWidth || sourceHeight != windowHeight;
		if (needsStretch && windowWidth > 0 && windowHeight > 0
				&& sourceWidth > 0 && sourceHeight > 0) {
			dsa.blitFrameBuffers(srcFbo, dstFbo, srcX0, srcY0, srcX1, srcY1,
				0, 0, windowWidth, windowHeight, mask, GL11.GL_LINEAR);
			if (!GraphicsFeatures.isAnnounced("presentStretch")) {
				GraphicsFeatures.announce("presentStretch", "Render scale upscale blit is active ("
					+ sourceWidth + "x" + sourceHeight + " -> " + windowWidth + "x" + windowHeight + ")");
			}
			return;
		}
		dsa.blitFrameBuffers(srcFbo, dstFbo, srcX0, srcY0, srcX1, srcY1,
			dstX0, dstY0, dstX1, dstY1, mask, filter);
	}
}
