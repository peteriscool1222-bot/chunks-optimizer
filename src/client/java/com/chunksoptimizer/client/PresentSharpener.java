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
 * Optional upscaling + sharpening pass applied to the final image.
 *
 * <p>Two independent features are implemented here:
 *
 * <ul>
 * <li><b>EASU</b> (AMD FSR 1 Edge Adaptive Spatial Upsampling): an edge-aware 12-tap upscaler. It
 *     is only compiled and used on OpenGL 4.0+ because it needs {@code textureGather}. On Linux
 *     Mesa's GL 4.6 this is the intended path; on Windows' Intel HD 4600 GL 3.3 it is silently
 *     unavailable and the stable bilinear blit is used instead.</li>
 * <li><b>RCAS</b> (Robust Contrast Adaptive Sharpening): a 5-tap cross filter whose strength is
 *     derived from local contrast, so flat areas stay clean and edges gain acutance without the
 *     halos of a plain unsharp mask. It works on GL 3.3 and is the only pass used on Windows.</li>
 * </ul>
 *
 * <p>Without EASU, RCAS samples the reduced-size frame directly with bilinear filtering at output
 * resolution, so upscale and sharpen happen in one draw. With EASU, the reduced frame is first
 * rendered into a full-resolution texture, then RCAS (if enabled) samples that texture. If the
 * present source is not a sampleable texture the old blit-into-scratch fallback is used.
 *
 * <p>Only used on the OpenGL backend.
 */
public final class PresentSharpener {
	private static final Logger LOGGER = ChunksOptimizerClient.LOGGER;

	/**
	 * RCAS sharpness in stops. Strength 1.0 = the strongest setting RCAS allows (0 stops),
	 * strength 0.0 = a very mild 2 stops. The default 0.6 is deliberately gentle: without EASU,
	 * RCAS is fed a bilinear upscale rather than FSR's EASU, so strong settings amplify the
	 * upscaler's own ringing and make textured surfaces look grainy.
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

	private static final String EASU_VERTEX_SHADER = """
		#version 400 core
		void main() {
			vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
			gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	/**
	 * Port of the 32-bit EASU path from ffx_fsr1.h (MIT license, (c) 2021 Advanced Micro Devices).
	 *
	 * <p>The gather coordinates and component order are copied from AMD's reference: p0 yields
	 * {@code b c _ _}, p1 yields {@code i j f e}, p2 yields {@code k l h g}, p3 yields
	 * {@code _ _ o n}. Using real {@code textureGather} is what makes this path work; the earlier
	 * GL 3.3 substitutes based on {@code texture()} produced incorrect neighbourhoods.
	 *
	 * <p>{@code uScale}/{@code uOffset} map output pixels to source pixels, including a possible
	 * sub-rect. {@code uInvInput} converts the integer source position to a normalized gather
	 * coordinate.
	 */
	private static final String EASU_FRAGMENT_SHADER = """
		#version 400 core
		uniform sampler2D uSource;
		uniform vec2 uScale;     // source viewport size / output size
		uniform vec2 uOffset;    // source viewport origin, in source texels
		uniform vec2 uInvInput;  // 1.0 / source texture size
		out vec4 fragColor;

		// Luma times two, matching ffx_fsr1.h (cheap, no dot product needed).
		float luma2(vec3 c) {
			return c.b * 0.5 + (c.r * 0.5 + c.g);
		}

		void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len,
				float lob, float clp, vec3 c) {
			// Rotate the offset into the detected edge direction.
			vec2 v;
			v.x = off.x * dir.x + off.y * dir.y;
			v.y = off.x * (-dir.y) + off.y * dir.x;
			v *= len;
			float d2 = min(dot(v, v), clp);

			// Lanczos2 approximation without sin(), sqrt() or rcp().
			float wB = (2.0 / 5.0) * d2 - 1.0;
			float wA = lob * d2 - 1.0;
			wB *= wB;
			wA *= wA;
			wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
			float w = wB * wA;

			aC += c * w;
			aW += w;
		}

		void easuSet(inout vec2 dir, inout float len, vec2 pp,
				bool biS, bool biT, bool biU, bool biV,
				float lA, float lB, float lC, float lD, float lE) {
			float w = 0.0;
			if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
			if (biT) w = pp.x * (1.0 - pp.y);
			if (biU) w = (1.0 - pp.x) * pp.y;
			if (biV) w = pp.x * pp.y;

			float dc = lD - lC;
			float cb = lC - lB;
			float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1e-8);
			float dirX = lD - lB;
			dir.x += dirX * w;
			lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
			lenX *= lenX;
			len += lenX * w;

			float ec = lE - lC;
			float ca = lC - lA;
			float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1e-8);
			float dirY = lE - lA;
			dir.y += dirY * w;
			lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
			lenY *= lenY;
			len += lenY * w;
		}

		void main() {
			// gl_FragCoord is at a pixel centre; AMD's ip is an integer output pixel.
			vec2 pp = gl_FragCoord.xy * uScale + uOffset - 0.5;
			vec2 fp = floor(pp);
			pp -= fp;

			vec2 p0 = (fp + vec2(1.0, -1.0)) * uInvInput;
			vec2 p1 = (fp + vec2(0.0,  1.0)) * uInvInput;
			vec2 p2 = (fp + vec2(2.0,  1.0)) * uInvInput;
			vec2 p3 = (fp + vec2(1.0,  3.0)) * uInvInput;

			vec4 bczzR = textureGather(uSource, p0, 0);
			vec4 bczzG = textureGather(uSource, p0, 1);
			vec4 bczzB = textureGather(uSource, p0, 2);
			vec4 ijfeR = textureGather(uSource, p1, 0);
			vec4 ijfeG = textureGather(uSource, p1, 1);
			vec4 ijfeB = textureGather(uSource, p1, 2);
			vec4 klhgR = textureGather(uSource, p2, 0);
			vec4 klhgG = textureGather(uSource, p2, 1);
			vec4 klhgB = textureGather(uSource, p2, 2);
			vec4 zzonR = textureGather(uSource, p3, 0);
			vec4 zzonG = textureGather(uSource, p3, 1);
			vec4 zzonB = textureGather(uSource, p3, 2);

			vec4 bczzL = bczzB * 0.5 + (bczzR * 0.5 + bczzG);
			vec4 ijfeL = ijfeB * 0.5 + (ijfeR * 0.5 + ijfeG);
			vec4 klhgL = klhgB * 0.5 + (klhgR * 0.5 + klhgG);
			vec4 zzonL = zzonB * 0.5 + (zzonR * 0.5 + zzonG);

			float bL = bczzL.x;
			float cL = bczzL.y;
			float iL = ijfeL.x;
			float jL = ijfeL.y;
			float fL = ijfeL.z;
			float eL = ijfeL.w;
			float kL = klhgL.x;
			float lL = klhgL.y;
			float hL = klhgL.z;
			float gL = klhgL.w;
			float oL = zzonL.z;
			float nL = zzonL.w;

			vec2 dir = vec2(0.0);
			float len = 0.0;
			easuSet(dir, len, pp, true,  false, false, false, bL, eL, fL, gL, jL);
			easuSet(dir, len, pp, false, true,  false, false, cL, fL, gL, hL, kL);
			easuSet(dir, len, pp, false, false, true,  false, fL, iL, jL, kL, nL);
			easuSet(dir, len, pp, false, false, false, true,  gL, jL, kL, lL, oL);

			float dirR = dot(dir, dir);
			bool zero = dirR < (1.0 / 32768.0);
			dirR = zero ? 1.0 : inversesqrt(max(dirR, 1e-12));
			dir.x = zero ? 1.0 : dir.x;
			dir *= dirR;

			len = len * 0.5;
			len *= len;
			float stretch = dot(dir, dir) / max(max(abs(dir.x), abs(dir.y)), 1e-8);
			vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
			float lob = 0.5 + ((0.25 - 0.04) - 0.5) * len;
			float clp = 1.0 / max(lob, 1e-4);

			vec3 f = vec3(ijfeR.z, ijfeG.z, ijfeB.z);
			vec3 g = vec3(klhgR.w, klhgG.w, klhgB.w);
			vec3 j = vec3(ijfeR.y, ijfeG.y, ijfeB.y);
			vec3 k = vec3(klhgR.x, klhgG.x, klhgB.x);
			vec3 min4 = min(min(f, g), min(j, k));
			vec3 max4 = max(max(f, g), max(j, k));

			vec3 aC = vec3(0.0);
			float aW = 0.0;
			easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.x, bczzG.x, bczzB.x)); // b
			easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.y, bczzG.y, bczzB.y)); // c
			easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.x, ijfeG.x, ijfeB.x)); // i
			easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.y, ijfeG.y, ijfeB.y)); // j
			easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);                                 // f
			easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.w, ijfeG.w, ijfeB.w)); // e
			easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);                                 // k
			easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, vec3(klhgR.y, klhgG.y, klhgB.y)); // l
			easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, vec3(klhgR.z, klhgG.z, klhgB.z)); // h
			easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, vec3(klhgR.w, klhgG.w, klhgB.w)); // g
			easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, vec3(zzonR.z, zzonG.z, zzonB.z)); // o
			easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, vec3(zzonR.w, zzonG.w, zzonB.w)); // n

			// Normalize and clamp to the four nearest input texels to remove ringing.
			vec3 pix = aC / max(aW, 1e-5);
			fragColor = vec4(min(max4, max(min4, pix)), 1.0);
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
	 * <p>{@code uSrcRect} maps the output pixel grid onto the source texture (normalised), so when
	 * EASU is off the bilinear upscale happens inside the five taps. When EASU is on the source is
	 * already full-resolution and this pass is pure sharpening.
	 */
	private static final String RCAS_FRAGMENT_SHADER = """
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

	/** GL 4.0+ only EASU program and its full-resolution target. */
	private static int easuProgram;
	private static int easuSourceUniform = -1;
	private static int easuScaleUniform = -1;
	private static int easuOffsetUniform = -1;
	private static int easuInvInputUniform = -1;
	private static int easuFbo;
	private static int easuTexture;
	private static int easuWidth;
	private static int easuHeight;

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
	private static boolean easuInitialized;
	private static boolean easuFailed;
	private static boolean glVersionChecked;
	private static int glMajor;

	private PresentSharpener() {}

	public static boolean isEnabled() {
		if (failed) return false;
		if (ChunkOptConfig.easu && !easuSupported()
				&& !GraphicsFeatures.isAnnounced("easuUnsupported")) {
			GraphicsFeatures.announce("easuUnsupported",
				"AMD FSR EASU requires OpenGL 4.0+; this context uses " + glVersionString()
				+ ". Keeping bilinear upscale + RCAS.");
		}
		return ChunkOptConfig.sharpen
			|| (ChunkOptConfig.easu && easuSupported() && !easuFailed);
	}

	/**
	 * Upscales and sharpens {@code srcFbo} into {@code dstFbo}.
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

			boolean useEasu = ChunkOptConfig.easu && !easuFailed && texture != 0
				&& easuSupported();
			if (useEasu) {
				try {
					if (ensureEasuInitialized() && ensureEasuTarget(outWidth, outHeight)) {
						renderEasu(texture, srcX0, srcY0, srcX1, srcY1, outWidth, outHeight);
						texture = easuTexture;
						u0 = v0 = 0.0f;
						u1 = v1 = 1.0f;
					} else {
						useEasu = false;
					}
				} catch (Throwable t) {
					easuFailed = true;
					LOGGER.warn("[Chunks Optimizer] EASU failed; falling back to bilinear upscale", t);
					GraphicsFeatures.announce("easuFallback",
						"AMD FSR EASU is unavailable; keeping bilinear upscale + RCAS.");
					useEasu = false;
				}
			}

			if (useEasu && !GraphicsFeatures.isAnnounced("easu")) {
				GraphicsFeatures.announce("easu", "AMD FSR EASU upscale active ("
					+ (srcX1 - srcX0) + "x" + (srcY1 - srcY0) + " -> "
					+ outWidth + "x" + outHeight + ", " + glVersionString() + ")");
			}

			if (!ChunkOptConfig.sharpen) {
				if (useEasu) {
					// EASU already produced a full-resolution image: a 1:1 blit is enough.
					dsa.blitFrameBuffers(easuFbo, dstFbo, 0, 0, outWidth, outHeight,
						0, 0, outWidth, outHeight, mask, GL11.GL_NEAREST);
					return true;
				}
				return false;
			}

			if (!GraphicsFeatures.isAnnounced("sharpen")) {
				GraphicsFeatures.announce("sharpen", "Sharpening is active (AMD FSR RCAS"
					+ (useEasu ? " after EASU" : "") + ", "
					+ outWidth + "x" + outHeight
					+ ", strength=" + Math.round(ChunkOptConfig.sharpenStrength * 100.0) + "%"
					+ ", denoise=" + Math.round(ChunkOptConfig.sharpenDenoise * 100.0) + "%)");
			}

			bindPassState(dstFbo, outWidth, outHeight);
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

	/** Renders the reduced-size source into {@link #easuFbo} at full output resolution. */
	private static void renderEasu(int texture, int srcX0, int srcY0, int srcX1, int srcY1,
			int outWidth, int outHeight) {
		bindPassState(easuFbo, outWidth, outHeight);
		GlStateManager._glUseProgram(easuProgram);
		GlStateManager._glBindVertexArray(vao);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(texture);
		GL33C.glBindSampler(0, sampler);

		GL20.glUniform1i(easuSourceUniform, 0);
		GL20.glUniform2f(easuScaleUniform,
			(srcX1 - srcX0) / (float) outWidth,
			(srcY1 - srcY0) / (float) outHeight);
		GL20.glUniform2f(easuOffsetUniform, srcX0, srcY0);
		GL20.glUniform2f(easuInvInputUniform, 1.0f / cachedWidth, 1.0f / cachedHeight);

		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

		GL33C.glBindSampler(0, 0);
		GlStateManager._glBindVertexArray(0);
		GlStateManager._glUseProgram(0);
	}

	/** Sets the fixed-function state shared by the EASU and RCAS fullscreen draws. */
	private static void bindPassState(int fbo, int width, int height) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GlStateManager._viewport(0, 0, width, height);
		GlStateManager._disableScissorTest();
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._disableBlend(0);
		GlStateManager._disableCull();
		GlStateManager._disableColorLogicOp();
		GlStateManager._disablePolygonOffset();
		GlStateManager._colorMask(15);
		GL11.glDisable(GL11.GL_STENCIL_TEST);
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
			int fragment = compile(GL20.GL_FRAGMENT_SHADER, RCAS_FRAGMENT_SHADER);
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
				LOGGER.warn("[Chunks Optimizer] RCAS shader link failed: {}",
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
			LOGGER.warn("[Chunks Optimizer] RCAS shader setup failed", t);
			return false;
		}
	}

	/** Compiles the GL 4.0 EASU shader. Never called on the Windows GL 3.3 path. */
	private static boolean ensureEasuInitialized() {
		if (easuInitialized) return !easuFailed;
		if (easuFailed || !easuSupported()) return false;
		try {
			int vertex = compile(GL20.GL_VERTEX_SHADER, EASU_VERTEX_SHADER);
			int fragment = compile(GL20.GL_FRAGMENT_SHADER, EASU_FRAGMENT_SHADER);
			if (vertex == 0 || fragment == 0) {
				markEasuUnavailable();
				return false;
			}
			easuProgram = GlStateManager.glCreateProgram();
			GL20.glAttachShader(easuProgram, vertex);
			GL20.glAttachShader(easuProgram, fragment);
			GlStateManager.glLinkProgram(easuProgram);
			GL20.glDeleteShader(vertex);
			GL20.glDeleteShader(fragment);
			if (GlStateManager.glGetProgrami(easuProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				LOGGER.warn("[Chunks Optimizer] EASU shader link failed: {}",
					GlStateManager.glGetProgramInfoLog(easuProgram, 4096));
				markEasuUnavailable();
				return false;
			}
			easuSourceUniform = GL20.glGetUniformLocation(easuProgram, "uSource");
			easuScaleUniform = GL20.glGetUniformLocation(easuProgram, "uScale");
			easuOffsetUniform = GL20.glGetUniformLocation(easuProgram, "uOffset");
			easuInvInputUniform = GL20.glGetUniformLocation(easuProgram, "uInvInput");
			easuInitialized = true;
			return true;
		} catch (Throwable t) {
			easuFailed = true;
			LOGGER.warn("[Chunks Optimizer] EASU shader setup failed", t);
			GraphicsFeatures.announce("easuFallback",
				"AMD FSR EASU is unavailable; keeping bilinear upscale + RCAS.");
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

	/** Full-resolution, sampleable target for the EASU pass. */
	private static boolean ensureEasuTarget(int width, int height) {
		if (easuFbo != 0 && easuWidth == width && easuHeight == height) return true;
		releaseEasuTarget();
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
				LOGGER.warn("[Chunks Optimizer] EASU framebuffer incomplete (status {})", status);
				GlStateManager._deleteTexture(texture);
				GlStateManager._glDeleteFramebuffers(fbo);
				markEasuUnavailable();
				return false;
			}

			easuTexture = texture;
			easuFbo = fbo;
			easuWidth = width;
			easuHeight = height;
			return true;
		} catch (Throwable t) {
			easuFailed = true;
			LOGGER.warn("[Chunks Optimizer] EASU framebuffer setup failed", t);
			GraphicsFeatures.announce("easuFallback",
				"AMD FSR EASU is unavailable; keeping bilinear upscale + RCAS.");
			return false;
		}
	}

	private static void markEasuUnavailable() {
		easuFailed = true;
		GraphicsFeatures.announce("easuFallback",
			"AMD FSR EASU is unavailable; keeping bilinear upscale + RCAS.");
	}

	private static boolean easuSupported() {
		if (!glVersionChecked) {
			glVersionChecked = true;
			String version = GL11.glGetString(GL11.GL_VERSION);
			glMajor = parseMajorVersion(version);
		}
		return glMajor >= 4;
	}

	private static int parseMajorVersion(String version) {
		if (version == null) return 0;
		int i = 0;
		while (i < version.length() && !Character.isDigit(version.charAt(i))) i++;
		int start = i;
		while (i < version.length() && Character.isDigit(version.charAt(i))) i++;
		if (start == i) return 0;
		try {
			return Integer.parseInt(version.substring(start, i));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static String glVersionString() {
		String version = GL11.glGetString(GL11.GL_VERSION);
		return version == null ? "an unknown OpenGL version" : version;
	}

	private static int compile(int type, String source) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			LOGGER.warn("[Chunks Optimizer] Present shader compile failed: {}",
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

	private static void releaseEasuTarget() {
		if (easuFbo != 0) {
			GlStateManager._glDeleteFramebuffers(easuFbo);
			easuFbo = 0;
		}
		if (easuTexture != 0) {
			GlStateManager._deleteTexture(easuTexture);
			easuTexture = 0;
		}
		easuWidth = 0;
		easuHeight = 0;
	}
}
