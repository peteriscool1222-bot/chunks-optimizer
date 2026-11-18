package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional first-person hand/item hiding: removes a full extra model pass and its overdraw. */
@Mixin(GameRenderer.class)
public class HideHandMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$hideHand(CameraRenderState cameraRenderState, PlayerRenderState playerRenderState,
			GpuTextureView textureView, CallbackInfo ci) {
		if (ChunkOptConfig.hideFirstPersonHand) {
			GraphicsFeatures.announce("hideHand", "First-person hand rendering is disabled");
			ci.cancel();
		}
	}
}
