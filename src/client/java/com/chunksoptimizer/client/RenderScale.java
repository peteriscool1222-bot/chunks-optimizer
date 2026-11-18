package com.chunksoptimizer.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.Window.FramebufferSize;
import net.minecraft.client.Minecraft;

/**
 * Optional internal resolution scaling.
 *
 * <p>The window/swapchain stays at its real size; only the framebuffer size the game renders into is
 * reduced. The final present blit is patched (see {@code GlPresentScaleMixin}) to stretch that smaller
 * image across the whole window, because vanilla's present blit is 1:1 and would otherwise leave the
 * image sitting in a corner.
 *
 * <p>Side effects while enabled: GUI and screenshots are rendered at the reduced resolution (blurry
 * but proportionally correct and full-screen), and the mouse stays aligned because GUI layout and
 * input both derive from the scaled framebuffer size.
 */
public final class RenderScale {
	private static volatile double factor = 1.0;
	/** Real (unscaled) window pixel size, captured when the framebuffer is (re)created. */
	private static volatile int nativeWidth;
	private static volatile int nativeHeight;
	/**
	 * Set when the factor is changed from a settings screen. The resize itself is deferred to the
	 * start of the next frame, where it is safe to touch the window and render targets.
	 */
	private static volatile boolean pendingApply;

	private RenderScale() {}

	public static void setFactor(double value) {
		factor = Math.max(0.25, Math.min(1.0, value));
	}

	/**
	 * Changes the factor and schedules a live resize for the start of the next frame.
	 *
	 * <p>The resize cannot happen immediately from the settings screen because that runs in the
	 * middle of the frame, potentially while render commands are being encoded. Deferring one frame
	 * lets {@link Minecraft#renderFrame} run first, which in turn lets {@code GameRenderer.render()}
	 * recreate the main render target at the new size through its normal resize path.
	 */
	public static void requestFactor(double value) {
		setFactor(value);
		pendingApply = true;
	}

	/** Applies a scheduled factor change. Called once per frame from {@code Minecraft.renderFrame}. */
	public static void applyPending() {
		if (!pendingApply) return;
		pendingApply = false;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) return;
		applyLive(minecraft);
	}

	/**
	 * Resizes the framebuffer in place without recreating the window/swapchain.
	 *
	 * <p>Only the window's framebuffer dimensions and the GUI scale are changed here. The main and
	 * 3D HUD render targets are resized by {@code GameRenderer.render()} on the same frame, because
	 * it compares them against the extracted window size. The present blit then stretches the smaller
	 * image back to the real window size (see {@code GlPresentScaleMixin}).
	 */
	private static void applyLive(Minecraft minecraft) {
		Window window = minecraft.getWindow();
		if (window == null) return;
		FramebufferSize real = window.queryFramebufferSize();
		if (real.width() <= 0 || real.height() <= 0) return;

		nativeWidth = real.width();
		nativeHeight = real.height();

		int width = real.width();
		int height = real.height();
		if (isEnabled() && canScale()) {
			width = scale(real.width());
			height = scale(real.height());
			announceActive(real, width, height);
		}

		window.setWidth(width);
		window.setHeight(height);
		// GUI scale depends on the framebuffer size; this also resizes the open screen and
		// resyncs the mouse so the settings page stays usable at the new resolution.
		minecraft.resizeGui();
		PresentSharpener.invalidateSourceCache();
	}

	public static double getFactor() {
		return factor;
	}

	public static boolean isEnabled() {
		return factor < 0.999;
	}

	public static int scale(int value) {
		if (value <= 0 || factor >= 0.999) return value;
		return Math.max(1, (int) Math.round(value * factor));
	}

	/** Real window pixel width (before render scaling), or 0 if not captured yet. */
	public static int nativeWidth() {
		return nativeWidth;
	}

	/** Real window pixel height (before render scaling), or 0 if not captured yet. */
	public static int nativeHeight() {
		return nativeHeight;
	}

	/** Re-applies the current factor to an already created window. */
	public static void apply(Window window) {
		if (window == null) return;
		FramebufferSize real = window.queryFramebufferSize();
		if (real.width() <= 0 || real.height() <= 0) return;
		nativeWidth = real.width();
		nativeHeight = real.height();
		if (!isEnabled() || !canScale()) return;
		int width = scale(real.width());
		int height = scale(real.height());
		window.setWidth(width);
		window.setHeight(height);
		PresentSharpener.invalidateSourceCache();
		announceActive(real, width, height);
	}

	private static void announceActive(FramebufferSize real, int width, int height) {
		GraphicsFeatures.announce("renderScale", "Render scale is active (" + real.width() + "x" + real.height()
			+ " -> " + width + "x" + height + ", factor=" + factor + ")");
	}

	/**
	 * True when the current backend can upscale the present blit. The Vulkan present path also does a
	 * clamped 1:1 blit; without an upscale hook the reduced image would sit in a corner, so scaling
	 * stays off there.
	 */
	private static boolean canScale() {
		String backend = backendName();
		if (isOpenGlBackend(backend)) return true;
		GraphicsFeatures.announce("renderScaleUnsupported",
			"Render scale is disabled: upscale blit is OpenGL-only (current backend: " + backend + ")");
		return false;
	}

	private static String backendName() {
		try {
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			if (device == null) return "unknown";
			var info = device.getDeviceInfo();
			return info == null || info.backendName() == null ? "unknown" : info.backendName();
		} catch (Throwable ignored) {
			return "unknown";
		}
	}

	private static boolean isOpenGlBackend(String backend) {
		return backend != null && backend.toLowerCase(java.util.Locale.ROOT).contains("opengl");
	}
}
