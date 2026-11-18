package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hard distance clamp for block entity rendering (chests, signs, banners, beds, ...). Vanilla extracts
 * every block entity in visible sections regardless of distance; this skips the expensive state
 * extraction and model submission for distant ones. Returning null is the vanilla "nothing to render"
 * path and is already handled by the extractor.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDistanceMixin {
	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$limitBlockEntityDistance(BlockEntity blockEntity, float tickDelta,
			ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, boolean bl,
			CallbackInfoReturnable<BlockEntityRenderState> cir) {
		int limit = ChunkOptConfig.blockEntityRenderDistance;
		if (limit <= 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) return;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		double max = limit;
		if (blockEntity.getBlockPos().distToCenterSqr(camera.x(), camera.y(), camera.z()) > max * max) {
			GraphicsFeatures.announce("blockEntityDistance", "Block entity render distance clamp is active");
			cir.setReturnValue(null);
		}
	}
}
