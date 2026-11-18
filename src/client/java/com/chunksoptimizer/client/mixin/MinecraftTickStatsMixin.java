package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.FrameStats;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures client tick cost for {@link FrameStats}. A frame can run up to 10 ticks
 * (vanilla catch-up), so the times are accumulated across all ticks of the frame.
 */
@Mixin(Minecraft.class)
public class MinecraftTickStatsMixin {
	@Inject(method = "tick", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$beginTickStats(CallbackInfo ci) {
		FrameStats.beginTick();
	}

	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$endTickStats(CallbackInfo ci) {
		FrameStats.endTick();
	}
}
