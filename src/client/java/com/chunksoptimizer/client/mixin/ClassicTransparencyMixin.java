package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces classic (sorted) translucency instead of 26.3's improved-transparency OIT pipeline.
 *
 * <p>OIT allocates and resolves multiple extra full-resolution targets every frame, which is very
 * expensive on old integrated GPUs. Classic transparency is what older versions used and is visually
 * close for normal gameplay.
 */
@Mixin(GameRenderer.class)
public class ClassicTransparencyMixin {
	@Inject(method = "useImprovedTransparency", at = @At("RETURN"), cancellable = true, require = 0)
	private void chunksoptimizer$forceClassicTransparency(CallbackInfoReturnable<Boolean> cir) {
		if (ChunkOptConfig.forceClassicTransparency && Boolean.TRUE.equals(cir.getReturnValue())) {
			GraphicsFeatures.announce("classicTransparency", "Improved transparency (OIT) is forced off");
			cir.setReturnValue(false);
		}
	}
}
