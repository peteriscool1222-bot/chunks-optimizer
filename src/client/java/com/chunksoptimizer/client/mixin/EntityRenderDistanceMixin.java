package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hard distance clamp for entity extraction/rendering. Vanilla's "Entity Distance" option only scales
 * the per-entity render distance; this enforces an absolute block radius, which is much cheaper on
 * large render distances and crowded worlds.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDistanceMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$limitEntityDistance(Entity entity, Frustum frustum, double x, double y, double z,
			float tickDelta, CallbackInfoReturnable<Boolean> cir) {
		int limit = ChunkOptConfig.entityRenderDistance;
		if (limit <= 0) return;
		double max = limit;
		if (entity.distanceToSqr(x, y, z) > max * max) {
			GraphicsFeatures.announce("entityDistance", "Entity render distance clamp is active");
			cir.setReturnValue(false);
		}
	}
}
