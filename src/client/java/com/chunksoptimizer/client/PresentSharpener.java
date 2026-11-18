package com.chunksoptimizer.client;

import com.mojang.renderpearl.backend.opengl.DirectStateAccess;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;

/**
 * Optional sharpening pass applied to the final image.
 *
 * <p>Uses AMD's FSR 1 RCAS (Robust Contrast Adaptive Sharpening): a 5-tap cross filter whose
 * strength is derived from the local contrast, so flat areas stay clean and edges gain acutance
 * without the halos of a plain unsharp mask. It is the cheapest "recover detail lost to upscaling"
 * filter that still looks good on integrated GPUs (five texture reads, no history buffers).
 *
 * <p>Single pass: the reduced-size frame is sampled directly with bilinear filtering at output
 * resolution, so the upscale and the sharpen happen in the same draw. An earlier version blitted
 * the frame into a full-size scratch texture first; that cost an extra 1600x900 write plus read
 * every frame and made Intel's driver stall on the framebuffer blit. If the present source is not
 * a sampleable texture (renderbuffer attachment, non-2D target) the old two-step path is used as a
 * fallback, and if anything fails the caller falls back to the plain stretched blit.
 *
 * <p>Only used on the OpenGL backend.
 */
public final class PresentSharpener {
	private static final Logger LOGGER = ChunksOptimizerClient.LOGGER;

	/**
	 * RCAS sharpness in stops. Strength 1.0 = the strongest setting RCAS allows (0 stops),
	 * strength 0.0 = a very mild 2 stops. The default 0.6 is deliberately gentle: RCAS is fed a
	 * bilinear upscale rather than FSR's EASU, so strong settings amplify the upscaler's own
	 * ringing and make textured surfaces look grainy.
	 */
	private static float sharpness() {
		double strength = Math.max(0.0, Math.min(1.0, ChunkOptConfig.sharpenStrength));
		return (float) Math.pow(2.0, -((1.0 - strength) * 2.0));
	}

	/** 0 = plain RCAS, 1 = full RCAS noise suppression (the {@code FSR_RCAS_DENOISE} path). */
	private static float denoise() {
		return (float) Math.max(0.0, Math.min(1.0, ChunkOptConfig.sharpenDenoise));
	}

	private static final String VERTEX_SHADER = """
		#version 330 core
		void main() {
			vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
			gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	/**
	 * Port of the 32-bit RCAS path from ffx_fsr1.h (MIT license, (c) 2021 Advanced Micro Devices),
	 * including the optional {@code FSR_RCAS_DENOISE} step.
	 *
	 * <p>RCAS derives its strength from local contrast, so flat surfaces are already left alone.
	 * What it cannot tell apart on its own is a real edge and pixel-to-pixel noise: upscaler
	 * ringing, texture grain and specular sparkle all look like "detail" and get amplified. The
	 * denoise step measures how far the centre pixel sits from the average of its four neighbours
	 * relative to the local range. Coherent edges and glyph strokes keep the full amount; isolated
	 * spikes are damped. {@code uDenoise} blends between off (1.0) and AMD's full suppression.
	 *
	 * <p>{@code uSrcRect} maps the output pixel grid onto the source texture (normalised), so the
	 * bilinear upscale happens inside the five taps.
	 */
	private static final String FRAGMENT_SHADER = """
		#version 330 core
		uniform sampler2D uSource;
		uniform vec4 uSrcRect;   // (u0, v0, u1, v1) of the source region, normalised
		uniform vec2 uTexel;     // one output pixel in source-texture units
		uniform vec2 uInvOut;    // 1.0 / output size
		uniform float uSharpness;
		uniform float uDenoise;
		out vec4 fragColor;

		const float RCAS_LIMIT = 0.1875; // 0.25 - 1.0 / 16.0

		// Luma times two, matching ffx_fsr1.h (cheap, no dot product needed).
		float luma2(vec3 c) {
			return c.b * 0.5 + (c.r * 0.5 + c.g);
		}

		void main() {
			vec2 st = gl_FragCoord.xy * uInvOut;
			vec2 uv = vec2(uSrcRect.x + (uSrcRect.z - uSrcRect.x) * st.x,
			               uSrcRect.y + (uSrcRect.w - uSrcRect.y) * st.y);

			vec3 b = texture(uSource, uv + vec2(0.0, -uTexel.y)).rgb;
			vec3 d = texture(uSource, uv + vec2(-uTexel.x, 0.0)).rgb;
			vec4 center = texture(uSource, uv);
			vec3 e = center.rgb;
			vec3 f = texture(uSource, uv + vec2(uTexel.x, 0.0)).rgb;
			vec3 h = texture(uSource, uv + vec2(0.0, uTexel.y)).rgb;

			// --- RCAS noise detection (FSR_RCAS_DENOISE) ---
			float bL = luma2(b);
			float dL = luma2(d);
			float eL = luma2(e);
			float fL = luma2(f);
			float hL = luma2(h);

			float mnL = min(min(min(bL, dL), eL), min(fL, hL));
			float mxL = max(max(max(bL, dL), eL), max(fL, hL));
			float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
			// Guard the range: a perfectly flat neighbourhood would divide by zero.
			nz = clamp(abs(nz) / max(mxL - mnL, 1e-5), 0.0, 1.0);
			nz = -0.5 * nz + 1.0;
			// uDenoise = 0 -> factor 1.0 (no change), 1 -> full AMD suppression.
			nz = mix(1.0, nz, uDenoise);

			// --- RCAS limiters ---
			vec3 mn4 = min(min(min(b, d), f), h);
			vec3 mx4 = max(max(max(b, d), f), h);

			vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-5));
			vec3 hitMax = (vec3(1.0) - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));

			vec3 lobe = clamp(max(-hitMin, hitMax), -RCAS_LIMIT, 0.0);

			// --- Apply noise removal, then resolve ---
			float amount = max(max(lobe.r, lobe.g), lobe.b) * uSharpness * nz;
			float rcpWeight = 1.0 / (4.0 * amount + 1.0);
			vec3 color = (amount * (b + d + h + f) + e) * rcpWeight;
			fragColor = vec4(color, center.a);
		}
		""";

	private static int program;
	private static int vao;
	private static int sampler;
	private static int sourceUniform = -1;
	private static int rectUniform = -1;
	private static int texelUniform = -1;
	private static int invOutUniform = -1;
	private static int sharpnessUniform = -1;
	private static int denoiseUniform = -1;

	/** Fallback-only intermediates, used when the present source cannot be sampled directly. */
	private static int scratchFbo;
	private static int scratchTexture;
	private static int scratchWidth;
	private static int scratchHeight;

	/**
	 * Cached present source: framebuffer name, attached texture and its size. Lets the hot path run
	 * with no GL queries at all (see {@link #resolveSource(int)}).
	 */
	private static int cachedFbo = -1;
	private static int cachedTexture;
	private static int cachedWidth;
	private static int cachedHeight;

	private static boolean initialized;
	private static boolean failed;

	private PresentSharpener() {}

	public static boolean isEnabled() {
		return ChunkOptConfig.sharpen && !failed;
	}

	/**
	 * Upscales and sharpens {@code srcFbo} into {@code dstFbo} in a single draw.
	 *
	 * @return {@code true} if the image was drawn, {@code false} if the caller should use the
	 *         normal blit instead.
	 */
	public static boolean present(DirectStateAccess dsa, int srcFbo, int dstFbo,
			int srcX0, int srcY0, int srcX1, int srcY1,
			int dstX0, int dstY0, int dstX1, int dstY1,
			int mask, int filter, int outWidth, int outHeight) {
		if (!isEnabled() || mask != GL11.GL_COLOR_BUFFER_BIT) return false;
		if (outWidth <= 0 || outHeight <= 0) return false;
		if (!ensureInitialized()) return false;

		try {
			int texture = resolveSource(srcFbo);
			float u0 = 0.0f;
			float v0 = 0.0f;
			float u1 = 1.0f;
			float v1 = 1.0f;

			if (texture != 0) {
				u0 = srcX0 / (float) cachedWidth;
				v0 = srcY0 / (float) cachedHeight;
				u1 = srcX1 / (float) cachedWidth;
				v1 = srcY1 / (float) cachedHeight;
			} else {
				// Fallback: resolve/upscale into the scratch texture first. Covers renderbuffer
				// attachments and anything else we cannot sample; the blit already fills it.
				if (!ensureScratch(outWidth, outHeight)) return false;
				dsa.blitFrameBuffers(srcFbo, scratchFbo, srcX0, srcY0, srcX1, srcY1,
					0, 0, outWidth, outHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
				texture = scratchTexture;
			}

			if (!GraphicsFeatures.isAnnounced("sharpen")) {
				GraphicsFeatures.announce("sharpen", "Sharpening is active (AMD FSR RCAS, "
					+ outWidth + "x" + outHeight
					+ ", strength=" + Math.round(ChunkOptConfig.sharpenStrength * 100.0) + "%"
					+ ", denoise=" + Math.round(ChunkOptConfig.sharpenDenoise * 100.0) + "%)");
			}

			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFbo);
			GlStateManager._viewport(0, 0, outWidth, outHeight);
			GlStateManager._disableScissorTest();
			GlStateManager._disableDepthTest();
			GlStateManager._depthMask(false);
			GlStateManager._disableBlend(0);
			GlStateManager._disableCull();
			GlStateManager._disableColorLogicOp();
			GlStateManager._disablePolygonOffset();
			GlStateManager._colorMask(15);
			GL11.glDisable(GL11.GL_STENCIL_TEST);

			GlStateManager._glUseProgram(program);
			GlStateManager._glBindVertexArray(vao);
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);
			GlStateManager._bindTexture(texture);
			// Use our own sampler so the upscale is bilinear and clamped regardless of the
			// filtering state the presented texture happens to carry (mipmaps, nearest, ...).
			GL33C.glBindSampler(0, sampler);

			GL20.glUniform1i(sourceUniform, 0);
			GL20.glUniform4f(rectUniform, u0, v0, u1, v1);
			GL20.glUniform2f(texelUniform, (u1 - u0) / outWidth, (v1 - v0) / outHeight);
			GL20.glUniform2f(invOutUniform, 1.0f / outWidth, 1.0f / outHeight);
			GL20.glUniform1f(sharpnessUniform, sharpness());
			GL20.glUniform1f(denoiseUniform, denoise());

			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

			GL33C.glBindSampler(0, 0);
			GlStateManager._glBindVertexArray(0);
			GlStateManager._glUseProgram(0);
			return true;
		} catch (Throwable t) {
			failed = true;
			LOGGER.warn("[Chunks Optimizer] Sharpening disabled after an error", t);
			return false;
		}
	}

	/**
	 * Resolves the colour-attachment texture of {@code fbo} and caches it, together with the
	 * texture size.
	 *
	 * <p>This runs once per frame in the present path, so it deliberately avoids GL queries after
	 * the first frame: {@code glGetTexLevelParameteri} can force the Intel driver to flush pending
	 * texture work, and a per-frame flush is exactly the kind of hidden cost that turns into
	 * hitching. The cache is keyed on the framebuffer name; the game creates a new framebuffer when
	 * the render target is resized, and {@link #invalidateSourceCache()} covers in-place resizes.
	 *
	 * @return the attached texture name, or 0 if the attachment is not a sampleable texture.
	 */
	private static int resolveSource(int fbo) {
		if (fbo == cachedFbo) return cachedTexture;

		int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int texture = 0;
		int width = 0;
		int height = 0;
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
			GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
		if (type == GL11.GL_TEXTURE) {
			texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
			if (texture != 0) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0);
				GlStateManager._bindTexture(texture);
				width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
				height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
				if (width <= 0 || height <= 0) texture = 0;
			}
		}
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);

		cachedFbo = fbo;
		cachedTexture = texture;
		cachedWidth = width;
		cachedHeight = height;
		return texture;
	}

	/** Drops the cached source attachment. Call after anything that can resize in place. */
	public static void invalidateSourceCache() {
		cachedFbo = -1;
		cachedTexture = 0;
		cachedWidth = 0;
		cachedHeight = 0;
	}

	private static boolean ensureInitialized() {
		if (initialized) return true;
		if (failed) return false;
		try {
			int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
			int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
			if (vertex == 0 || fragment == 0) {
				failed = true;
				return false;
			}
			program = GlStateManager.glCreateProgram();
			GL20.glAttachShader(program, vertex);
			GL20.glAttachShader(program, fragment);
			GlStateManager.glLinkProgram(program);
			GL20.glDeleteShader(vertex);
			GL20.glDeleteShader(fragment);
			if (GlStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				LOGGER.warn("[Chunks Optimizer] Sharpen shader link failed: {}",
					GlStateManager.glGetProgramInfoLog(program, 4096));
				failed = true;
				return false;
			}
			sourceUniform = GL20.glGetUniformLocation(program, "uSource");
			rectUniform = GL20.glGetUniformLocation(program, "uSrcRect");
			texelUniform = GL20.glGetUniformLocation(program, "uTexel");
			invOutUniform = GL20.glGetUniformLocation(program, "uInvOut");
			sharpnessUniform = GL20.glGetUniformLocation(program, "uSharpness");
			denoiseUniform = GL20.glGetUniformLocation(program, "uDenoise");
			vao = GlStateManager._glGenVertexArrays();

			sampler = GL33C.glGenSamplers();
			GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

			initialized = true;
			return true;
		} catch (Throwable t) {
			failed = true;
			LOGGER.warn("[Chunks Optimizer] Sharpen shader setup failed", t);
			return false;
		}
	}

	private static boolean ensureScratch(int width, int height) {
		if (scratchFbo != 0 && scratchWidth == width && scratchHeight == height) return true;
		releaseScratch();
		try {
			int texture = GlStateManager._genTexture();
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);
			GlStateManager._bindTexture(texture);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

			int fbo = GlStateManager.glGenFramebuffers();
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
			GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, texture, 0);
			int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
			if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
				LOGGER.warn("[Chunks Optimizer] Sharpen framebuffer incomplete (status {})", status);
				GlStateManager._deleteTexture(texture);
				GlStateManager._glDeleteFramebuffers(fbo);
				failed = true;
				return false;
			}

			scratchTexture = texture;
			scratchFbo = fbo;
			scratchWidth = width;
			scratchHeight = height;
			return true;
		} catch (Throwable t) {
			failed = true;
			LOGGER.warn("[Chunks Optimizer] Sharpen framebuffer setup failed", t);
			return false;
		}
	}

	private static int compile(int type, String source) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			LOGGER.warn("[Chunks Optimizer] Sharpen shader compile failed: {}",
				GL20.glGetShaderInfoLog(shader, 4096));
			GL20.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	private static void releaseScratch() {
		if (scratchFbo != 0) {
			GlStateManager._glDeleteFramebuffers(scratchFbo);
			scratchFbo = 0;
		}
		if (scratchTexture != 0) {
			GlStateManager._deleteTexture(scratchTexture);
			scratchTexture = 0;
		}
		scratchWidth = 0;
		scratchHeight = 0;
	}
}
