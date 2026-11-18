package com.chunksoptimizer.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class ChunksOptimizerMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {}

	@Override
	public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.contains(".sodium.")) {
			try {
				return FabricLoader.getInstance().isModLoaded("sodium")
					&& classExists("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager");
			} catch (Throwable ignored) {
				// Let @Pseudo handle a missing/renamed Sodium target safely.
				return true;
			}
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() { return null; }

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	private static boolean classExists(String name) {
		String path = name.replace('.', '/') + ".class";
		return ChunksOptimizerMixinPlugin.class.getClassLoader().getResource(path) != null;
	}
}
