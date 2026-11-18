package com.chunksoptimizer.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One-time activation logging for optional graphics hooks (proves in logs that a hook applied). */
public final class GraphicsFeatures {
	private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

	private GraphicsFeatures() {}

	/**
	 * Cheap check for hot paths: lets callers skip building a message string every frame.
	 * Without this, a per-frame {@code announce(key, "..." + value)} allocates a throwaway string
	 * on every frame even though only the first call ever logs.
	 */
	public static boolean isAnnounced(String key) {
		return ANNOUNCED.contains(key);
	}

	public static void announce(String key, String message) {
		if (ANNOUNCED.add(key)) {
			ChunksOptimizerClient.LOGGER.info("[Chunks Optimizer] {}", message);
		}
	}
}
