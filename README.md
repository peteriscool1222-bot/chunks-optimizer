# Chunks Optimizer (Fabric, Minecraft 26.3) — Max Optimization

Client-side mod aimed at **maximum reduction of chunk loading/exploration stutter** and FPS impact on older CPUs (e.g. Intel i5-4690) and GPUs (e.g. Intel HD 4600).  
Designed to **run alongside Sodium** (or without it).

## What it does

- **Sodium-aware compatibility**: detects Sodium at startup and applies a Sodium hook that caps rebuilt/re-uploaded chunk sections per frame (`RenderSectionManager`, Sodium 0.9.x). With no Sodium, a vanilla renderer upload throttle and warmup hooks are used instead.
- **Adaptive frame budgeting (max-out mode)**: tracks recent frame times and dynamically tightens chunk rebuild/upload budgets when frame time exceeds target (and during warmup/world-load windows), relaxing only when there is headroom.
- **Warmup throttling**: extra-aggressive budgeting for a configurable period after world/view reload to smooth initial chunk bursts (triggered by renderer geometry invalidation / world load).
- **Sort-aware**: Sodium translucency sort tasks are never throttled, so chunk builds are not starved while moving.
- **Adaptive GPU upload budget**: scales Sodium's per-frame chunk upload allowance (GPU time + staging-buffer bytes) with observed frame time — smaller when frames are heavy (fewer GPU stalls), larger when there is headroom (chunks appear sooner). Optional via `gpuUploadBudgetScaling`.
- **Result-upload cap**: also limits how many *finished* chunk meshes Sodium removes from its result queue per frame. Extra meshes stay queued and are uploaded on later frames, preventing one Intel GL driver call from stalling tens of milliseconds. Uses `maxUploadsPerFrame` / the adaptive upload budget; the world-unload path still drains the full queue.
- **In-world-only spike detection**: the 10 FPS main-menu/pause limiter no longer counts as a stutter, so the throttle does not stay pinned on while sitting in menus.
- **Extra graphics reduction (beyond Sodium/vanilla settings)**:
  - `renderScale` — internal resolution scaling (e.g. `0.75` = 44% fewer pixels). Renders the world + GUI smaller and stretches it to fill the window; the single biggest GPU win on integrated graphics.
  - `entityRenderDistance` / `blockEntityRenderDistance` — absolute block radius clamps for entity and block-entity rendering (vanilla only offers a percentage for entities).
  - `particleLimit` — hard cap on live particles (vanilla "Minimal" still allows thousands).
  - `hideFirstPersonHand` — skips the first-person hand/item pass and its overdraw.
  - `disableWeather` / `disableSky` — skip rain/snow (including CPU buffer rebuilds) and the sky disc/sun/moon/stars passes.
  - `forceClassicTransparency` — forces classic sorted translucency instead of 26.3's multi-target OIT pipeline (on by default; big saving on old GPUs).
- **Configurable**: auto-generates `config/chunks-optimizer.properties` on first run for tuning without rebuilding.
- **Sodium options page**: when Sodium is installed, a **Chunks Optimizer** page appears in Sodium's options screen with every setting (chunk budgets, adaptive options, graphics). Changes apply live and are written back to the properties file.
- **Safe/optional mixins**: injections use `require=0` and a mixin plugin so missing targets (version/renderer differences) do not prevent loading.

## Tuning (`config/chunks-optimizer.properties`)

With Sodium installed, the same settings are also available in-game: open Sodium's options screen and pick the **Chunks Optimizer** page. Changes apply immediately and are saved to `config/chunks-optimizer.properties`.

Key options (defaults tuned for max smoothness on old 4-core CPUs):
- `maxScheduledRebuildsPerFrame` / `minScheduledRebuildsPerFrame` (default `1` / `1`)
- `maxUploadsPerFrame` / `minUploadsPerFrame` (default `1` / `1`)
- `adaptive` (default `true`)
- `targetFrameMs` (default `40`) — lower = more aggressive throttling
- `adaptiveRebuildCap` / `adaptiveUploadCap` (defaults `2` / `2`) — upper bounds when frames are stable
- `warmupTicks` (default `200`) — aggressive period after load
- `throttleSodium` / `throttleVanilla` (default `true` / `true`)
- `gpuUploadBudgetScaling` (default `true`) — adapt Sodium's GPU upload budget to frame time
- `renderScale` (default `1.0`, range `0.25–1.0`) — internal resolution multiplier; `1.0` = off
- `sharpen` (default `false`) — contrast-adaptive sharpening (AMD FSR RCAS) after upscaling; off = no extra pass
- `sharpenStrength` (default `0.6`, range `0.0–1.0`) — how hard the sharpener works; `1.0` = RCAS maximum, lower = subtler
- `sharpenDenoise` (default `1.0`, range `0.0–1.0`) — RCAS noise suppression; `0` = plain RCAS (grainiest), `1` = AMD's full denoise step
- `entityRenderDistance` / `blockEntityRenderDistance` (default `0` = vanilla, max `512`) — absolute block radius
- `particleLimit` (default `0` = vanilla, max `16384`)
- `hideFirstPersonHand` (default `false`)
- `disableWeather` / `disableSky` (default `false` / `false`)
- `forceClassicTransparency` (default `true`)
- `debugFrameStats` (default `false`) — log a 10-second frame/tick/render/GC breakdown (diagnostics only)

### Aggressive graphics profile (paste into the config for maximum FPS)

```properties
renderScale=0.7
sharpen=true
sharpenStrength=0.6
sharpenDenoise=1.0
entityRenderDistance=64
blockEntityRenderDistance=64
particleLimit=3000
hideFirstPersonHand=true
disableWeather=true
disableSky=true
forceClassicTransparency=true
```

Notes on `renderScale`:
- The smaller image is **stretched to fill the window** by the mod (vanilla's present blit is 1:1, which is why an unpatched render scale only fills a corner).
- The GUI is rendered at the reduced resolution too, so it is slightly blurry and appears roughly `1/renderScale` larger (e.g. `0.7` → ~1.43x). If that bothers you, lower the game's **GUI Scale** one step.
- `0.85` is a mild, hard-to-notice reduction; `0.7` is a large GPU win; below `0.6` gets visibly soft.
- OpenGL backend only: on the Vulkan backend render scaling stays off (the Vulkan present path cannot be upscaled safely by this mod yet).
- Changing it from the Sodium page applies on the next frame: the framebuffer is resized in place and the main render target is recreated by the game's normal resize path, so no restart is needed.
- `sharpen=true` runs AMD FSR 1 **RCAS** after the upscale: a 5-tap contrast-adaptive filter that restores edge detail without halos. It is the closest cheap analogue to FSR/DLSS sharpening, costs one extra fullscreen pass, and only runs on the OpenGL backend.
- `sharpenStrength` controls how hard RCAS works (`0` = barely visible, `1` = RCAS maximum). Because the GUI/text is rasterised at the reduced resolution, sharpening can restore *perceived* text crispness but not the lost detail. Truly pixel-perfect text needs the GUI rendered at native resolution, which this mod does not do (the GUI layout is tied to the framebuffer size).
- `sharpenDenoise` is the fix for "sharpening makes everything look grainy". Plain RCAS cannot tell a real edge from pixel-to-pixel noise, so it also amplifies upscaler ringing, texture grain and specular sparkle. The denoise step measures how far the centre pixel sits from the average of its four neighbours relative to the local contrast: coherent edges and glyph strokes keep the full amount, isolated spikes are damped. At `1.0` textured surfaces stay calm while text and block edges stay crisp. If you prefer the raw look, set it to `0` and lower `sharpenStrength` instead.
- Suggested pairings: `0.6` / `1.0` (default — clean and still visibly sharper), `0.4` / `1.0` (very subtle, best for 0.85+ render scale), `0.9` / `1.0` (maximum text crispness at 0.7 or below).

**Max smoothness tip:** keep rebuild/upload mins low and `targetFrameMs` around 40–50 for i5-4690-class CPUs (trade: slower chunk pop-in for fewer spikes). If chunks feel too slow once stable, raise `adaptiveRebuildCap`/`adaptiveUploadCap` slightly.

### Diagnosing micro-stutter

Set `debugFrameStats=true` (or enable **Log frame stats** under *Diagnostics* in the Sodium page) and the game log gets one summary every 10 seconds:

```
[Chunks Optimizer] Frame stats (10.0s, 2093 frames): frame avg 4.70 / p99 11.32 / max 53.65 ms
  | tick avg 0.14 / p99 1.81 / max 2.31 ms (max 1 ticks/frame)
  | render avg 1.06 / p99 1.87 / max 4.19 ms
  | upscale avg 0.20 / max 0.60 ms, present avg 0.80 / max 1.10 ms
  | chunk submit avg 0.02 / max 0.68 ms, apply avg 0.03 / max 0.63 ms
  | budget 2/2 x1.35 (spike cd 0)
  | GC 1 collections, 24 ms in 1 frames
  | worst frame 53.65 ms (no GC) [tick 0.00 / render 0.87 / upscale 0.40 / present 1.10 / submit 0.10 / apply 0.20 / other 51.28]
```

How to read it:
- **`tick` high** (p99 above ~3–4 ms, or several ticks per frame) — entity/block-entity/particle logic and vanilla's catch-up ticks. Reduce `particleLimit`, `entityRenderDistance`, or lower the simulation distance.
- **`chunk apply` max high** — result application / GPU uploads. This is the classic exploring hitch; the result-upload cap, post-spike cooldown, and Sodium's upload scaling target it. A lower Sodium **Chunk Builder Threads** value (1–2) can also help on 4-core CPUs.
- **`chunk submit` max high** — Sodium is cloning section data / submitting rebuild tasks on the main thread. The task throttle targets this; reduce render distance if it persists.
- **`render` high with low `chunk` time** — world/entity/GUI rendering or shader compilation, not chunk loading.
- **`GC` collections/pause time high** — heap pressure, not the mod. `in N frames` is how many frames a collection actually landed in (one pause usually equals one stalled frame), and `worst frame … (GC)` / `(no GC)` says whether the single worst frame was caused by a collection. Use the JVM flags in [JVM / launcher tips](#jvm--launcher-tips); on 6 GB of RAM, do **not** use a 4 GB heap.
- **`worst frame … [tick … / render … / upscale … / present … / submit … / apply … / other …]`** — the exact phase timings of that one slow frame, not separate per-window maxima. Tick-dominated = integrated-server catch-up/world generation; render-dominated = Intel GL/driver or shader work; upscale/present-dominated = final blit, RCAS or SDL swap; apply-dominated = GPU uploads (still targetable by the cap). `other` is the residual after sequential phases — idle, OS scheduling or log I/O. Sodium chunk submit/apply can overlap `render`, so the residual subtracts the largest of those buckets.
- **`budget … (spike cd N)`** — a slow *in-world* frame re-armed a short throttle window, so rebuilds/uploads are being held back while the backlog clears. `N` counts down to 0; menu frames are ignored.
- **`frame` max spikes with normal averages** — one-off stalls (world load, resource reload, alt-tab). Use the vanilla profiler (F3+L) for the sub-pass breakdown.

Note: Sodium chunk work can run inside or just outside `GameRenderer.render`, so the `other` residual uses the largest of render/submit/apply rather than assuming they are strict subsets. Tick time is measured across all ticks in a frame, so it also reveals catch-up amplification.

On the i5-4690 / HD 4600 test machine, a clean 3.5-minute in-world sample after the result-upload cap classified 20 worst frames as **14 residual/outside, 4 render, 2 tick, 0 chunk-apply**. A second run with `upscale`/`present` timing confirmed those buckets were also small; the residuals land almost every 10 seconds, matching this logger's summary interval. For final feel tests, set `debugFrameStats=false` (or turn **Log frame stats** off) — on older CPUs or slow log disks the summary itself can cost a noticeable periodic spike.

### Verified compatibility

- Minecraft **26.3**, Fabric Loader **0.20.1**, Java **25** (Temurin 25.0.5).
- Without Sodium: loads, joins a world, and logs
  `[Chunks Optimizer] Vanilla terrain upload throttle is active` (vanilla upload/warmup hooks).
- With **Sodium 0.9.3-beta.5+mc26.3** (Fabric): loads, joins a world, and logs
  `[Chunks Optimizer] Sodium chunk task throttle is active`, confirming the Sodium rebuild hook is applied.
- Graphics extras are logged on first use (e.g. `Entity render distance clamp is active`,
  `Improved transparency (OIT) is forced off`, `Weather rendering is disabled`), so you can confirm each hook applied.

### Recommended Sodium settings for i5-4690-class CPUs

These are not changed automatically, but pair well with this mod:
- **Chunk Update Threads**: `1` or `2` (avoid 4+ on 4-thread CPUs)
- **CPU Render-Ahead Limit**: `1` or `2`
- **Always Defer Chunk Updates**: `ON`
- **Render Distance**: `8–12` while exploring; raise only if stable
- **Simulation Distance**: `5–8`
- **Graphics**: Fast/Fancy rather than Fabulous on very old GPUs

If available in your Sodium build, set **Chunk Builder Threads** to `2` (or `1`) to reduce thread contention with the render thread.

### JVM / launcher tips (big impact on stutter)

Use these as a starting point for an i5-4690-class CPU. The mod itself allocates nothing in the frame loop, so if `GC … in N frames` is high, the allocations are coming from the game, Sodium, or another mod — fix it at the JVM level:

```text
-Xms2G -Xmx2G
-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:MaxGCPauseMillis=50
-XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -XX:+DisableExplicitGC
-XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M
-XX:G1ReservePercent=15 -XX:SurvivorRatio=32 -XX:MaxTenuringThreshold=1
```

Those are the exact flags now applied to `gradlew runClient` in `build.gradle`. GC file logging was removed after tuning: on this machine the periodic `gc.log` writes were another avoidable disk-I/O source. Re-add `-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags` only while investigating a suspected GC problem.

Heap sizing matters more than the tuning flags. This machine has **6 GB RAM**, and the dev client was originally started with no `-Xmx` at all — the JVM default is 1/4 of RAM (~1.5 GB), which left G1 permanently near full and produced 5–15 collections per 10 seconds. A fixed 2 GB heap cut that to **1–3 collections per 10 s**. Don't jump to 4 GB on 6 GB total: Windows will page the IDE and browser out, and the page faults cost far more than a short G1 pause.

The flags above are the **low-frequency** variant that tested best here: a 20% young-generation minimum and a 50 ms pause target gave **1–3 collections per 10 s** with 15–40 ms pauses. The short-pause alternative (`MaxGCPauseMillis=15`, `G1NewSizePercent=10`) was tested in 1.4.5: it lowered individual pauses to ~10–20 ms but raised collection frequency to 4–5 per 10 s and did not feel better, so it was reverted. If you temporarily re-enable the GC log, look for `Pause Young … Nms` and `Pause Remark … Nms` to compare variants. Hitches that only happen while exploring are chunk work, not GC; use the phase timers above instead.

Also consider stacking with Sodium, Lithium, FerriteCore, Krypton, ImmediatelyFast, EntityCulling, and ModernFix for maximum smoothness.

## Requirements

- Minecraft **26.3**
- **Fabric Loader** `>= 0.20.1`
- **Java 25+** (matching MC 26.3)
- Fabric API (declared dependency in build)
- Optional: **Sodium** (supported alongside)

## Build

From this folder (Java 25 required):

```powershell
# Uses the included Gradle wrapper (downloads Gradle 9.8.2 once):
.\gradlew.bat build

# Or with a local Gradle 9.8.2 installation:
gradle build
```

Output jar: `build/libs/chunks-optimizer-1.4.0.jar`

## Install

1. Put `chunks-optimizer-1.4.0.jar` in your Fabric `mods` folder (Minecraft 26.3).
2. Optionally add Sodium `0.9.3-beta.5+mc26.3` for the chunk rebuild hook.
3. Launch once; edit `config/chunks-optimizer.properties` and restart to tune.

## Notes

- **Sodium is where the big wins are**: the per-frame rebuild cap directly limits Sodium's chunk task submission, smoothing exploration bursts. Vanilla 26.3 only exposes a safe upload throttle (its rebuild path clears dirty flags before tasks run, so skipping a task would leave holes).
- **Effectiveness varies by version**: Sodium internals/method names can differ, so its hook is marked optional (`require=0`, pseudo target). If a future Sodium update changes the target, the mod simply runs without that hook instead of crashing.
- **Tradeoff**: aggressive budgeting maximizes frame stability but can delay chunk appearance; adjust caps/target in config to balance for your setup (render distance, shaders, Sodium settings).

## Project layout

- `src/client/java` - client-only mod code, config, adaptive budget, mixins (+ Sodium mixins)
- `src/client/resources/chunks_optimizer.client.mixins.json` - mixin config with plugin
- `src/main/resources/fabric.mod.json` - mod metadata
