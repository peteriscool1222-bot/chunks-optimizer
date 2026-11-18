package com.chunksoptimizer.client.mixin;

import com.chunksoptimizer.client.ChunkBudget;
import com.chunksoptimizer.client.FrameStats;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftFrameBudgetMixin {
	@Inject(method = "runTick", at = @At("HEAD"), require = 0)
	private void chunksoptimizer$beginTickBudget(boolean tickWorld, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		// The main menu and pause screen are intentionally limited to 10 FPS. Counting those
		// frames as spikes would keep the chunk throttle pinned on even before loading a world.
		ChunkBudget.setWorldActive(mc.level != null && !mc.isPaused());
		ChunkBudget.beginFrame();
		FrameStats.beginFrame();
	}

	@Inject(method = "runTick", at = @At("TAIL"), require = 0)
	private void chunksoptimizer$endFrameStats(boolean tickWorld, CallbackInfo ci) {
		FrameStats.endFrame();
	}
}
