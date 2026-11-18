package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkOptConfig;
import com.chunksoptimizer.client.GraphicsFeatures;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Absolute particle cap. Vanilla's "Minimal" setting still allows thousands of particles; on weak GPUs
 * particle overdraw is a large, spiky cost. Dropping new particles beyond the cap keeps the world
 * readable while bounding the cost.
 */
@Mixin(ParticleEngine.class)
public class ParticleLimitMixin {
	@Shadow
	@Final
	private Map<ParticleRenderType, ParticleGroup<?>> particles;

	@Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
	private void chunksoptimizer$capParticles(Particle particle, CallbackInfo ci) {
		int limit = ChunkOptConfig.particleLimit;
		if (limit <= 0) return;
		int total = 0;
		for (ParticleGroup<?> group : this.particles.values()) {
			total += group.size();
			if (total >= limit) break;
		}
		if (total >= limit) {
			if (!GraphicsFeatures.isAnnounced("particleLimit")) {
				GraphicsFeatures.announce("particleLimit", "Particle cap is active (limit=" + limit + ")");
			}
			ci.cancel();
		}
	}
}
