package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.PresentSharpener;
import com.chunksoptimizer.client.RenderScale;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lowers the internal framebuffer resolution while leaving the real window/swapchain size untouched.
 *
 * <p>The game then renders world + GUI into a smaller target and vanilla's end-of-frame
 * {@code GpuSurface.blitFromTexture} stretches it to the window. The swapchain configuration itself
 * still uses {@code queryFramebufferSize()}, which is intentionally left unmodified.
 */
@Mixin(Window.class)
public class WindowRenderScaleMixin {
	@ModifyVariable(method = "onFramebufferResize(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
	private int chunksoptimizer$scaleFramebufferWidth(int width) {
		return RenderScale.scale(width);
	}

	@ModifyVariable(method = "onFramebufferResize(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
	private int chunksoptimizer$scaleFramebufferHeight(int height) {
		return RenderScale.scale(height);
	}

	@Inject(method = "refreshFramebufferSize()V", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$applyRenderScale(CallbackInfo ci) {
		RenderScale.apply((Window) (Object) this);
	}

	/**
	 * A window resize (F11, drag) recreates the main render target, so the sharpener's cached
	 * present source must be dropped before the next frame reads it.
	 */
	@Inject(method = "onFramebufferResize(II)V", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$invalidateSharpenerCache(int width, int height, CallbackInfo ci) {
		PresentSharpener.invalidateSourceCache();
	}
}
