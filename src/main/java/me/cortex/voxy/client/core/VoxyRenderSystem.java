package me.cortex.voxy.client.core;

import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.ColumnStreamedBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.GlobalCleaner;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final WorldEngine worldIn;

    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    private final Cleaner.Cleanable geoRef;


    private final RenderDistanceTracker renderDistanceTracker;
    private final BoundRenderer boundOutlineRenderer;
    // Fork: loaded-chunk depth mask used by the Metal path (fed by MixinRenderSectionManager).
    public final ChunkBoundRenderer chunkBoundRenderer;
    public final StreamedBoundStore visbleSectionStream;
    private @Nullable ColumnStreamedBoundStore columnStreamedBoundStore;//Only used when FREX is enabled

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    /** Diagnostic frame counter for the Metal LOD-ring log in {@link #renderOpaque}. */
    private int metalRingDiagFrame;

    // Bakery warmup burst (2026-07-03, Metal branch only). Root cause of the
    // "gigantic untextured LOD blocks for minutes after world join": mesh
    // builds throw IdNotYetComputedException for any unbaked block (the
    // 2026-05-25 log showed >1M throws vs 54k sections), the mesh queue
    // saturates past HierarchicalOcclusionTraverser's 4000-task request
    // throttle, and the bakery drains the whole backlog at ~5 bakes/tick
    // under the steady-state 0.9 ms budget. While the bake backlog is large
    // the frame is startup-degraded anyway, so spend real frame time
    // draining it: full burst above BAKE_BURST_HIGH pending bakes, half
    // burst above BAKE_BURST_LOW, steady 0.9 ms once warm.
    // VOXY_BAKE_WARMUP_MS tunes the burst budget in ms (default 8;
    // 0 disables the burst entirely — the pre-2026-07 behaviour).
    private static final long BAKE_BUDGET_STEADY_NS = 900_000L;
    private static final long BAKE_WARMUP_NS = parseBakeWarmupNs();
    // 2026-07-03 tier retune from the first BSL session: backlog peaked at 68
    // (full tier at 256 never engaged) and then hovered at 29-31 — just under
    // the old LOW=32 release — leaving a ~30-bake tail draining at 0.9 ms for
    // the whole session. Full burst from 128, and hold the burst until the
    // backlog is basically empty (8).
    private static final int BAKE_BURST_HIGH = 128;
    private static final int BAKE_BURST_LOW = 8;
    private boolean bakeWarmupActive;
    private int bakeWarmupTransitions;

    private static long parseBakeWarmupNs() {
        String v = System.getenv("VOXY_BAKE_WARMUP_MS");
        if (v == null || v.isBlank()) return 8_000_000L;
        try {
            return (long) (Float.parseFloat(v.trim()) * 1_000_000L);
        } catch (NumberFormatException e) {
            return 8_000_000L;
        }
    }

    private long computeBakeBudgetNs() {
        if (BAKE_WARMUP_NS <= 0) return BAKE_BUDGET_STEADY_NS;
        int backlog = this.modelService.getProcessingCount();
        long budget = BAKE_BUDGET_STEADY_NS;
        if (backlog > BAKE_BURST_HIGH) {
            budget = Math.max(BAKE_BUDGET_STEADY_NS, BAKE_WARMUP_NS);
        } else if (backlog > BAKE_BURST_LOW) {
            budget = Math.max(BAKE_BUDGET_STEADY_NS, BAKE_WARMUP_NS / 2);
        }
        boolean active = budget > BAKE_BUDGET_STEADY_NS;
        if (active != this.bakeWarmupActive) {
            this.bakeWarmupActive = active;
            // The low release threshold makes engage/release flap frame-to-
            // frame while bakes trickle in; log the first few transitions
            // (the interesting ones at world join) then sample.
            this.bakeWarmupTransitions++;
            if (this.bakeWarmupTransitions <= 4 || (this.bakeWarmupTransitions % 200) == 0) {
                me.cortex.voxy.common.Logger.info("[Metal-BAKE] warmup burst "
                        + (active ? ("ENGAGED (backlog=" + backlog + ", budget=" + (budget / 1_000_000L) + "ms)")
                                  : ("released (backlog=" + backlog + ", back to 0.9ms)"))
                        + " [transition " + this.bakeWarmupTransitions + "]");
            }
        }
        return budget;
    }

    /**
     * Iris pack-inject state the renderer (and its pipeline) was constructed
     * with. NormalRenderPipeline bakes {@code useEnvFog} from this at
     * construction (it's a compile-time shader define), so a pack
     * enable/disable at runtime needs a full renderer recreation — the same
     * shutdownRenderer()/createRenderer() path Sodium's
     * REQUIRES_RENDERER_RELOAD config flag (e.g. the env-fog toggle) drives.
     * {@link #renderOpaque} watches for the flip on Metal.
     */
    private final boolean constructedIrisGbufferInject;
    /** One-shot guard so the reload is scheduled exactly once per flip. */
    private boolean irisReloadScheduled;

    /** Accessor exposed for the Metal compositing mixin so it can read the IOSurface bridge. */
    public AbstractRenderPipeline getPipeline() {
        return this.pipeline;
    }

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal(msg), false);
        }

        this.constructedIrisGbufferInject = IrisUtil.irisGbufferInjectMode();

        // 2026-07-03 (Metal): migrate sub_division_size drift. The FPS-based
        // autoBalanceSubDivSize loop (call site commented out below in
        // renderOpaque) used to RAISE this value up to 256 whenever FPS<55
        // and persist it via VoxyConfig.save() — but nothing ever lowers it
        // again. A drifted value (user config had 229.18) makes LOD leaves
        // stop subdividing at a huge screen footprint: 16-block voxels at
        // only ~2000 blocks = the "gigantic shapeless distant blocks"
        // report. Values above 128 can only come from that disabled loop
        // (the config UI stays well below it), so reset them to the
        // default 64. Metal-only guard keeps GL behaviour untouched.
        if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL
                && VoxyConfig.CONFIG.subDivisionSize > 128f) {
            Logger.warn("[Metal] sub_division_size drifted to " + VoxyConfig.CONFIG.subDivisionSize
                    + " (residue of the disabled FPS auto-balancer) — resetting to 64 for full LOD detail");
            VoxyConfig.CONFIG.subDivisionSize = 64f;
            VoxyConfig.CONFIG.save();
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();
            glFinish();

            this.worldIn = world;

            this.properties = RenderProperties.getRenderProperties();
            // GL-only bound stores; the Metal path uses ChunkBoundRenderer instead.
            this.visbleSectionStream = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType() != me.cortex.voxy.client.core.gpu.BackendType.OPENGL ? null : new StreamedBoundStore();
            var backendFactory = getRenderBackendFactory();
            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));


                // Metal needs the geometry in a backend buffer; the reusable pool is GL-only.
                if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                        != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
                    this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getGeometryBufferSize());
                } else {
                    this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());
                }

                if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                    var buffer = ((BasicSectionGeometryData)this.geometryData).getGeometryBuffer();
                    this.geoRef = GlobalCleaner.CLEANER.register(this.geometryData,() -> RenderResourceReuse.giveBackGeometryBuffer((GlBuffer) buffer));
                } else {
                    this.geoRef = null;
                }

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service

            //Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);


            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;

                //Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {//TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.boundOutlineRenderer = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType() != me.cortex.voxy.client.core.gpu.BackendType.OPENGL ? null : new BoundRenderer(this.pipeline);
            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }


    /**
     * Temporal smoothing of the captured fog. MC's eye-in-fluid test is a
     * binary per-frame flip (no hysteresis), so bobbing across the water
     * surface alternates the captured FogParameters between water-fog (dark,
     * env end ~24-96) and air-fog (light, env end ~render distance) every
     * frame. Voxy paints the ENTIRE far field from this one record (bridge
     * clear + LOD fog mix), so the raw flip strobes the whole horizon.
     * Exponentially lerp all components toward the current value (~200 ms
     * time constant) so a crossing becomes a brief fade instead.
     * VOXY_FOG_SMOOTH_MS overrides the time constant; 0 disables.
     */
    private static final float FOG_SMOOTH_MS = parseFogSmoothMs();
    private static float parseFogSmoothMs() {
        String v = System.getenv("VOXY_FOG_SMOOTH_MS");
        if (v == null || v.isBlank()) return 200.0f;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return 200.0f;
        }
    }
    private FogParameters smoothedFog;
    private long lastFogSmoothNs;
    private boolean fogClassWater;
    private int fogClassStreak;
    private long fogClassStreakStartNs;
    private static boolean loggedViewportLeak;

    /** Metal-only kill switch: VOXY_LOD_ZOOM_REFINE=1 restores the upstream
     *  refine-on-zoom behaviour (spyglass demands finer LOD levels → ~3s
     *  pop-in) instead of the zoom-invariant minSSS compensation. */
    private static final boolean ZOOM_REFINE_ENABLED =
            "1".equals(System.getenv("VOXY_LOD_ZOOM_REFINE"));
    /** Number of LOD levels a zoom is allowed to refine (each level is 4x
     *  projected area / 2x linear). The compensation divides zoomComp by
     *  4^N before engaging, so the traversal only sees the zoom EXCESS
     *  beyond the budget. VOXY_LOD_ZOOM_REFINE_LEVELS tunes (default 2);
     *  0 restores full zoom invariance (effComp = zoomComp). */
    private static final int ZOOM_REFINE_LEVELS = parseZoomRefineLevels();
    private static final float ZOOM_REFINE_BUDGET_AREA =
            (float) Math.pow(4.0, ZOOM_REFINE_LEVELS);
    private static boolean zoomCompEngaged;

    /** Round-27 FOV-stability latch (see setupViewport): true while the frame's
     *  projection is FOV-unstable (scope lerp) or zoom compensation is engaged.
     *  Consumers pause hole-filling work for these frames. Volatile: read from
     *  the render thread only, but keep publication safe for diagnostics. */
    public static volatile boolean FOV_HOLD_ACTIVE;
    private static final boolean FOV_HOLD_ENABLED =
            !"0".equals(System.getenv("VOXY_VX_FOV_HOLD"));
    private static float lastFrameM00;

    private static int parseZoomRefineLevels() {
        String v = System.getenv("VOXY_LOD_ZOOM_REFINE_LEVELS");
        if (v == null || v.isBlank()) return 2;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /**
     * Submersion-type fog records (water/lava/powder-snow/blindness) carry a
     * short environmental end; atmospheric fog is hundreds of blocks. The
     * class of the RAW captured record tracks MC's binary eye-in-fluid
     * verdict without querying the camera.
     */
    private static boolean isSubmersionClassFog(FogParameters p) {
        return p.environmentalEnd() < 128.0f;
    }

    private FogParameters smoothFogParameters(FogParameters target) {
        if (FOG_SMOOTH_MS <= 0 || target == null) return target;
        long now = System.nanoTime();
        if (this.smoothedFog == null) {
            this.smoothedFog = target;
            this.lastFogSmoothNs = now;
            this.fogClassWater = isSubmersionClassFog(target);
            this.fogClassStreak = 0;
            return target;
        }
        // ASYMMETRIC DEBOUNCED SNAP on fog-class change. MC's eye-in-fluid
        // verdict is binary per frame and OSCILLATES while swimming at the
        // surface (flowing-water fractional fluid heights + swim bob), with
        // run lengths of 100-300 ms — long enough to defeat a symmetric
        // 4-frame filter (each bob produced two full-screen snaps). The
        // failure modes are asymmetric, so the filter is too:
        //  - AIR→WATER (densify) adopts after 2 agreeing frames — murk hides
        //    everything, divers get instant response, and a spurious densify
        //    is visually harmless.
        //  - WATER→AIR (thin/REVEAL) adopts only after 400 ms of consecutive
        //    air verdicts — bobbing never thins the fog, so the far field
        //    stays murky and stable through any splash pattern; a real
        //    surfacing pays 0.4 s of lingering haze.
        // While a flip is pending, hold the DISTANCE fields stable and keep
        // lerping the colour toward the target (no colour strobe either).
        boolean targetClass = isSubmersionClassFog(target);
        if (targetClass != this.fogClassWater) {
            if (this.fogClassStreak == 0) {
                this.fogClassStreakStartNs = now;
            }
            this.fogClassStreak++;
            boolean adopt = targetClass
                    ? this.fogClassStreak >= 2
                    : (now - this.fogClassStreakStartNs) >= 400_000_000L;
            if (adopt) {
                this.fogClassWater = targetClass;
                this.fogClassStreak = 0;
                this.smoothedFog = target;
                this.lastFogSmoothNs = now;
                return target;
            }
            float dtHold = (now - this.lastFogSmoothNs) / 1.0e9f;
            this.lastFogSmoothNs = now;
            float kHold = 1.0f - (float) Math.exp(-dtHold / (FOG_SMOOTH_MS / 1000.0f));
            FogParameters h = this.smoothedFog;
            this.smoothedFog = new FogParameters(
                    h.red()   + (target.red()   - h.red())   * kHold,
                    h.green() + (target.green() - h.green()) * kHold,
                    h.blue()  + (target.blue()  - h.blue())  * kHold,
                    h.alpha() + (target.alpha() - h.alpha()) * kHold,
                    h.environmentalStart(), h.environmentalEnd(),
                    h.renderStart(), h.renderEnd());
            return this.smoothedFog;
        }
        this.fogClassStreak = 0;
        float dt = (now - this.lastFogSmoothNs) / 1.0e9f;
        this.lastFogSmoothNs = now;
        // Colour uses the full time constant (kills the eye-crossing colour
        // strobe); the DISTANCE fields use a fast constant (<=250 ms) so fog
        // density tracks promptly within a fog type (e.g. waterVision ramp) —
        // slow distance smoothing dilutes underwater murk and reveals the far
        // field that vanilla hides.
        float kCol = 1.0f - (float) Math.exp(-dt / (FOG_SMOOTH_MS / 1000.0f));
        float kDist = 1.0f - (float) Math.exp(-dt / (Math.min(FOG_SMOOTH_MS, 250.0f) / 1000.0f));
        FogParameters p = this.smoothedFog;
        this.smoothedFog = new FogParameters(
                p.red()   + (target.red()   - p.red())   * kCol,
                p.green() + (target.green() - p.green()) * kCol,
                p.blue()  + (target.blue()  - p.blue())  * kCol,
                p.alpha() + (target.alpha() - p.alpha()) * kCol,
                p.environmentalStart() + (target.environmentalStart() - p.environmentalStart()) * kDist,
                p.environmentalEnd()   + (target.environmentalEnd()   - p.environmentalEnd())   * kDist,
                p.renderStart() + (target.renderStart() - p.renderStart()) * kDist,
                p.renderEnd()   + (target.renderEnd()   - p.renderEnd())   * kDist);
        return this.smoothedFog;
    }

    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }
        fogParameters = this.smoothFogParameters(fogParameters);

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        //cameraY += 100;
        float far = 3000 * 16;
        //dont like this hacky instanceof thing and how this is wired
        //TODO: fixme
        if (this.pipeline instanceof IrisVoxyRenderPipeline ivrp) {
            if (ivrp._getData().useDynamicFarPlane) {
                far  = (VoxyConfig.CONFIG.sectionRenderDistance * 32 + 2) * ((float) Math.sqrt(3)) * 16;
            }
        }

        float near = getVanillaRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection, near, far);

        /*
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        int width = dims[2];
        int height = dims[3];
        */
        if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            // GL_VIEWPORT is NOT trustworthy here: MC re-renders the 16x16
            // lightmap every game tick and blaze3d's createRenderPass sets the
            // GL viewport eagerly without restoring; above water the fullscreen
            // sky pass resets it before Sodium's terrain hook, but UNDERWATER
            // Sodium skips the sky pass — so GL_VIEWPORT reads 16x16 on every
            // tick frame (~20 Hz). That inflated minSSS 6400x (the octree walk
            // stopped at the top level: renderList collapsed to ~16) and
            // reallocated the IOSurface bridge to 16x16 (broken blit) — the
            // underwater strobe. MC's main RT is the authoritative frame size
            // (same source the compositor uses).
            var rt = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (rt != null && rt.width > 0 && rt.height > 0) {
                if ((width != rt.width || height != rt.height) && !loggedViewportLeak) {
                    loggedViewportLeak = true;
                    Logger.warn("[Metal-VIEWPORT] GL_VIEWPORT " + width + "x" + height
                            + " != mainRT " + rt.width + "x" + rt.height
                            + " (leaked pass viewport; using mainRT size)");
                }
                width = rt.width;
                height = rt.height;
            }
        }

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport.zoomCompensation = 1.0f;
        if (!ZOOM_REFINE_ENABLED
                && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                        != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            // The projection's FOV includes spyglass zoom (getFov(..., true) in
            // makeProjectionMatrix) but the HOT traversal's minSSS is uploaded
            // FOV-independent — zooming inflates every node's screenspace area
            // ~170x, so the walk demands 3-4 finer LOD levels that each need a
            // request→build→upload round trip (~3s of pop-in). Scale minSSS by
            // the projected-AREA zoom factor instead so the already-built level
            // stays selected. The REQUEST/cull frustum keeps the zoomed planes.
            float m00 = vanillaProjection.m00();
            float m11 = vanillaProjection.m11();
            float baseFov = Minecraft.getInstance().options.fov().get();
            float baseM11 = 1.0f / (float) Math.tan(Math.toRadians(baseFov) * 0.5);
            float aspect = m00 != 0.0f ? Math.abs(m11 / m00) : 0.0f;
            float baseM00 = aspect > 0.0f ? baseM11 / aspect : 0.0f;
            float zoomComp = (baseM00 != 0.0f && baseM11 != 0.0f)
                    ? (m00 / baseM00) * (m11 / baseM11)
                    : 1.0f;
            if (!Float.isFinite(zoomComp)) {
                zoomComp = 1.0f;
            }
            zoomComp = Math.min(400.0f, Math.max(1.0f, zoomComp));
            // Continuous refine budget instead of the old binary >16x engage:
            // divide out 4^N of the zoom area so the traversal refines exactly
            // N LOD levels (2^N linear) under the scope and the compensation
            // absorbs the rest. Full compensation was EXACT (projected-area
            // metric), so the scope showed the unzoomed level magnified ~13x —
            // giant amorphous blocks. The max(1,·) floor also structurally
            // neutralizes the world-join transient (zoomComp~4.1 on the first
            // frame with no spyglass → effComp=1 for N>=1) that the old 16x
            // threshold existed to guard against.
            float effComp = Math.min(400.0f,
                    Math.max(1.0f, zoomComp / ZOOM_REFINE_BUDGET_AREA));
            viewport.zoomCompensation = effComp;
            boolean engaged = effComp > 1.0f;
            if (engaged != zoomCompEngaged) {
                zoomCompEngaged = engaged;
                if (engaged) {
                    Logger.info("[Metal-LODTEST] LOD zoom compensation ON (zoomComp="
                            + zoomComp + ", refine budget N=" + ZOOM_REFINE_LEVELS
                            + " levels, effComp=" + effComp + "): minSSS scaled by the"
                            + " EXCESS projected-area zoom so the scope refines exactly"
                            + " N levels finer; VOXY_LOD_ZOOM_REFINE_LEVELS tunes N,"
                            + " VOXY_LOD_ZOOM_REFINE=1 restores full refine-on-zoom");
                } else {
                    Logger.info("[Metal-LODTEST] LOD zoom compensation off (zoomComp="
                            + zoomComp + " within the " + ZOOM_REFINE_LEVELS
                            + "-level refine budget; effComp=1)");
                }
            }
        }

        // 2026-07-17 round-27 FOV-stability latch (VOXY_VX_FOV_HOLD=0 reverts)
        // + [Metal-FRUSTUM-DELTA] probe. Root chain (bytecode-verified trace):
        // a spyglass scope (or any FOV transition — the 0.5/tick fovModifier
        // lerp) engages the zoom compensation, HOT re-selects finer LOD levels
        // whose sections are NOT yet built, the mid-distance opaque LOD floor
        // goes missing for the build round-trip, and missing-opaque +
        // present-trans-depth is exactly the abyss-fill trigger — its synthetic
        // dark seabed (or, with the fill off, the raw hole) paints the dark
        // section-shaped panes over mid-distance ocean until the zoom settles.
        // The latch marks the frame FOV-unstable while m00 moves >1%/frame or
        // the compensation is engaged; consumers (abyss fill + seafloor dim in
        // VxContractInjector, the HOT request boost) pause for those frames —
        // fail-open, render MVP untouched (the scope still draws zoomed).
        {
            float m00Now = vanillaProjection.m00();
            boolean unstable = false;
            if (lastFrameM00 != 0.0f && m00Now != 0.0f) {
                float rel = Math.abs(m00Now - lastFrameM00) / Math.abs(lastFrameM00);
                unstable = rel > 0.01f;
            }
            boolean hold = FOV_HOLD_ENABLED
                    && (unstable || viewport.zoomCompensation > 1.0f);
            if (hold != FOV_HOLD_ACTIVE) {
                Logger.info("[Metal-FRUSTUM-DELTA] fov " + (hold ? "UNSTABLE" : "stable")
                        + " (m00 " + lastFrameM00 + " -> " + m00Now
                        + ", zoomComp=" + viewport.zoomCompensation + ")"
                        + (hold ? " — abyss fill / seafloor dim / request boost PAUSED"
                                + " (VOXY_VX_FOV_HOLD=0 reverts)" : ""));
            }
            FOV_HOLD_ACTIVE = hold;
            lastFrameM00 = m00Now;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection, near, far)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }


    public void renderOpaque(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture) {
        if (viewport == null) {
            return;
        }

        if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            // Iris pack toggled since construction? The pipeline's env-fog
            // define (and the inject mode it pairs with) is baked at
            // construction, so recreate the renderer through the same
            // shutdown/create path the config screen's renderer-reload flag
            // uses. Deferred via execute(): the task runs on the render
            // thread BETWEEN frames — tearing this renderer down from inside
            // its own renderOpaque would free GPU objects mid-render.
            if (!this.irisReloadScheduled
                    && IrisUtil.irisGbufferInjectMode() != this.constructedIrisGbufferInject) {
                this.irisReloadScheduled = true;
                Logger.info("Iris pack state changed (gbufferInject "
                        + this.constructedIrisGbufferInject + " -> "
                        + IrisUtil.irisGbufferInjectMode()
                        + ") — scheduling Voxy renderer reload to rebake fog/inject mode");
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().levelRenderer instanceof IVoxyRenderSystemHolder holder
                            && holder.voxy$getRenderSystem() == this) {
                        holder.voxy$shutdownRenderer();
                        holder.voxy$createRenderer();
                    }
                });
            }
            // Metal path — skip all the GL state save/restore. Drive the
            // chunk-bound depth mask + the pipeline's Metal render. The
            // compositing mixin runs separately at renderLevel RETURN.
            this.pipeline.preSetup(viewport);
            // M13 chunk 3 — mirror the GL chunk-bound gate below (~:430):
            // rasterize the loaded-chunk AABB depth mask into
            // viewport.depthBoundingBuffer so quads.frag's depth-bound test
            // discards LOD fragments inside MC's loaded-chunk volume (the
            // LOD↔terrain ring fix + second line of defense for underwater
            // X-ray). Must run BEFORE runPipeline so the LOD pass samples
            // this frame's mask.
            if ((!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
                this.chunkBoundRenderer.drainReady();
                this.chunkBoundRenderer.renderMetal(viewport,
                        me.cortex.voxy.client.core.gpu.RenderBackendFactory.get());
            } else {
                this.chunkBoundRenderer.clearMetal(viewport);
            }
            this.pipeline.runPipeline(viewport, 0, viewport.width, viewport.height);

            // M13 chunk 2 follow-up: drive the per-frame dynamic-runtime
            // block on Metal too. Without these, the section tree never
            // advances with the player — `setCenterAndProcess` is what
            // adds/removes top-level LOD nodes as the player moves
            // (more than CHECK_DISTANCE_BLOCKS = 128), loading their
            // subtrees from LMDB into AsyncNodeManager. Without it, the
            // initial nodes are the ONLY LOD that ever renders, so the
            // distant horizon disappears once the player walks out of
            // the spawn ring. `UploadStream.tick` commits the geometry
            // upload buffer copies to the GPU and rotates fenced frames.
            // M13 chunk 1 follow-up: `modelService.tick` runs on Metal
            // too now. The bakery's resources are all raw GL bound to
            // MC's GL context (which is always current on the render
            // thread regardless of Voxy's backend) and the CPU readback
            // path in GlViewCapture works on Apple's GL 4.1 cap. Without
            // this tick the bakery queue stalls at 1 invocation and
            // every mesher call throws IdNotYetComputedException →
            // no LOD geometry ever materializes.
            UploadStream.INSTANCE.tick();
            boolean processedThisFrame = this.renderDistanceTracker.setCenterAndProcess(
                    viewport.cameraX, viewport.cameraZ);
            while (processedThisFrame && VoxyClient.isFrexActive()) {
                processedThisFrame = this.renderDistanceTracker.setCenterAndProcess(
                        viewport.cameraX, viewport.cameraZ);
            }
            // 2026-07-03: adaptive budget — burst through the startup bake
            // backlog instead of the flat 0.9 ms (see computeBakeBudgetNs).
            do { this.modelService.tick(this.computeBakeBudgetNs()); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            // Diagnostic: log every ~10s (600 frames) whether the LOD ring is
            // still adding/removing cells. After the ring converges this
            // should mostly read `processedThisFrame=false` until the player
            // moves >128 blocks.
            this.metalRingDiagFrame++;
            if (this.metalRingDiagFrame % 600 == 1) {
                me.cortex.voxy.common.Logger.info(String.format(
                        "[Metal-RING f=%d] processedThisFrame=%s  cam=(%.0f, %.0f)  cfgRD=%s",
                        this.metalRingDiagFrame, processedThisFrame,
                        viewport.cameraX, viewport.cameraZ,
                        me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance));
            }
            return;
        }

        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;//Only render on valid viewport
        }

        if (sourceDepthTexture == 0) {
            throw new IllegalStateException("Source depth texture cannot be 0");
        }

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();//Start marker
        TimingStatistics.main.start();

        //TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        GlStateManager._depthMask(true);
        GlStateManager._disablePolygonOffset();

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        //this.autoBalanceSubDivSize();


        glViewport(0, 0, viewport.width, viewport.height);

        int scrWidth  = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_WIDTH);
        int scrHeight = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_HEIGHT);

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        if (this.visbleSectionStream != null && (!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            if (VoxyClient.isFrexActive()!=(this.columnStreamedBoundStore!=null)) {
                if (this.columnStreamedBoundStore == null) {
                    this.columnStreamedBoundStore = new ColumnStreamedBoundStore();
                } else {
                    this.columnStreamedBoundStore.free();
                    this.columnStreamedBoundStore = null;
                }
            }
            //viewport.depthBoundingBuffer.framebuffer.bind(GL_COLOR_ATTACHMENT0, sourceColourTexture).verify();
            //If the bound renderer exists, it means we must be in FREX mode
            this.boundOutlineRenderer.render(viewport, this.columnStreamedBoundStore==null?this.visbleSectionStream:this.columnStreamedBoundStore);
        } else {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        }
        TimingStatistics.E.stop();


        GPUTiming.INSTANCE.marker();
        //The entire rendering pipeline (excluding the chunkbound thing)
        this.pipeline.runPipeline(viewport, sourceDepthTexture, sourceColourTexture, scrWidth, scrHeight);
        GPUTiming.INSTANCE.marker();


        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        //As much dynamic runtime stuff here
        {
            //Tick upload stream (this is ok to do here as upload ticking is just memory management)
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());//While FF is active, run until everything is processed
            TimingStatistics.H.start();
            //Done here as is allows less gl state resetup
            do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }





        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {//Reset state manager stuffs
            GlStateManager._glUseProgram(0);
            glUseProgram(0);
            GlStateManager._enableDepthTest();
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);//Clear binding
            glBindVertexArray(0);

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();//Thanks iris (sigh)

            //TODO: should/needto actually restore all of these, not just clear them
            //Clear all the bindings
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }
            GlStateManager._blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendEquation(GL_FUNC_ADD);
            GlStateManager._blendFuncSeparate(0,0, 0, 0);
            glBlendFunc(0, 0);
            GlStateManager._disableBlend(0);
            glDisable(GL_BLEND);
            GlStateManager._depthFunc(GL_LESS);
            glDepthFunc(GL_LESS);

            //((SodiumShader) Iris.getPipelineManager().getPipelineNullable().getSodiumPrograms().getProgram(DefaultTerrainRenderPasses.CUTOUT).getInterface()).setupState(DefaultTerrainRenderPasses.CUTOUT, fogParameters);
        }

        TimingStatistics.all.stop();

        //TimingStatistics.I.start();
        //glFlush();
        //TimingStatistics.I.stop();

        /*
        TimingStatistics.F.start();
        this.postProcessing.setup(viewport.width, viewport.height, boundFB);
        TimingStatistics.F.stop();

        this.renderer.renderFarAwayOpaque(viewport, this.chunkBoundRenderer.getDepthBoundTexture());


        TimingStatistics.F.start();
        //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or breaking _something_ am not sure what
        this.postProcessing.computeSSAO(viewport.MVP);
        TimingStatistics.F.stop();

        TimingStatistics.G.start();
        //We can render the translucent directly after as it is the furthest translucent objects
        this.renderer.renderFarAwayTranslucent(viewport, this.chunkBoundRenderer.getDepthBoundTexture());
        TimingStatistics.G.stop();


        TimingStatistics.F.start();
        this.postProcessing.renderPost(viewport, matrices.projection(), boundFB);
        TimingStatistics.F.stop();
         */
    }



    private void autoBalanceSubDivSize() {
        //only increase quality while there are very few mesh queues, this stops,
        // e.g. while flying and is rendering alot of low quality chunks
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        //Auto fps targeting
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    public static float getVanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    /*
    private static float getGameFoV() {
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;
        return gameRenderer.mainCamera().getFov();
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        //TODO: use the existing projection matrix use mulLocal by the inverse of the projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        projection.setPerspective(getGameFoV() * 0.01745329238474369f,
                (float) client.getWindow().getWidth() / (float)client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    //TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        //THis is a wild and insane problem to have
        // at short render distances the vanilla terrain doesnt end up covering the 16f near plane voxy uses
        // meaning that it explodes (due to near plane clipping).. _badly_ with the rastered culling being wrong in rare cases for the immediate
        // sections rendered after the vanilla render distance
        float nearVoxy = getVanillaRenderDistance()<=32.0f?8f:16f;
        nearVoxy = VoxyClient.disableSodiumChunkRender()?0.1f:nearVoxy;

        return base.mulLocal(
                Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix.invert(new Matrix4f()),
                new Matrix4f()
        ).mulLocal(makeProjectionMatrix(nearVoxy, 16*3000));
    }*/

    private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base, float nearPlane, float farPlane) {

        //this jank is to capture the extra crap they inject like viewbobbing
        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);


        float far = farPlane;
        float near = nearPlane;

        /* jank way of just modifying the base raw
        if (true) {
            return new Matrix4f(base)
                    .m22((far + near) / (near - far))
                    .m32((far+far) * near / (near - far));
        }*/

        //Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = nearPlane;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        //If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        //Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));//the +1 is to cover the outer ring of chunks when rendering a circle
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        var backend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get();
        debug.add("Buf/Tex [#/Mb]: [" + backend.getBufferCount() + "/" + (backend.getBufferTotalSize()/1_000_000) + "],[" + backend.getTextureCount() + "/" + (backend.getTextureEstimatedTotalSize()/1_000_000)+"]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        // World-rejoin fix: UploadStream is a process-lifetime singleton but
        // its queued copies target the world-lifetime buffers freed below.
        // Un-flushed session-N entries used to execute on session-N+1's first
        // commit against freed Metal handles (native use-after-free — prime
        // suspect for the white/untextured LODs on rejoin). Drain here while
        // every target is still alive; VOXY_UPLOAD_FLUSH_FIX=0 reverts.
        if (!"0".equals(System.getenv("VOXY_UPLOAD_FLUSH_FIX"))) {
            try {
                Logger.info("Flushing upload stream");
                me.cortex.voxy.client.core.rendering.util.UploadStream.INSTANCE.flushWaitClear();
            } catch (Exception e) {
                Logger.error("Error flushing upload stream", e);
            }
        }
        Logger.info("Shutting down rendering");
        try {
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            if (this.geoRef != null) {
                this.geoRef.clean();
            }

            if (this.boundOutlineRenderer != null) this.boundOutlineRenderer.free();
            if (this.chunkBoundRenderer != null) this.chunkBoundRenderer.free();
            if (this.visbleSectionStream != null) {
                this.visbleSectionStream.free();
            }
            if (this.columnStreamedBoundStore != null) {
                this.columnStreamedBoundStore.free();
                this.columnStreamedBoundStore = null;
            }

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}

        // The vx resolve pass caches its build in STATICS that outlive this
        // instance (GL programs compiled against ONE Iris pipeline generation +
        // a native UBO scratch sized for that generation's uniform layout).
        // Iris recreates its pipeline on every world rejoin, so free the stale
        // build here — on the render thread with the GL context current — and
        // let the next contract frame rebuild against the live pipeline. The
        // per-frame identity check in MetalVxResolvePass covers recreations
        // that don't pass through this shutdown (shader option toggles). No-op
        // on the GL backend (nothing is ever built there) and under
        // VOXY_VX_RESOLVE_REBUILD=0.
        try {
            me.cortex.voxy.client.core.util.MetalVxResolvePass.reset();
        } catch (Exception e) {
            Logger.error("Error resetting vx resolve pass", e);
        }



        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        // Anything queued into the upload stream DURING the teardown above
        // targets buffers that may already be freed — drop those entries
        // WITHOUT executing them (see UploadStream.discardClear).
        if (!"0".equals(System.getenv("VOXY_UPLOAD_FLUSH_FIX"))) {
            try {
                me.cortex.voxy.client.core.rendering.util.UploadStream.INSTANCE.discardClear();
            } catch (Exception e) {
                Logger.error("Error discarding upload stream", e);
            }
        }

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
