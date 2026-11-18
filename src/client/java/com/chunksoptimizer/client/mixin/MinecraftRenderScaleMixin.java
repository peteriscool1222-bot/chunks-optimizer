package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.RenderScale;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies a pending render-scale change at the very start of a frame.
 *
 * <p>Settings screens run in the middle of the frame, so resizing there could happen while render
 * commands are still being encoded. At the head of {@code renderFrame} the previous frame is fully
 * submitted, and the game has not extracted its window state yet, so the new framebuffer size is
 * picked up by {@code GameRenderer} on this same frame.
 */
@Mixin(Minecraft.class)
public class MinecraftRenderScaleMixin {
	@Inject(method = "renderFrame(Z)V", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$applyPendingRenderScale(boolean renderLevel, CallbackInfo ci) {
		RenderScale.applyPending();
	}
}
