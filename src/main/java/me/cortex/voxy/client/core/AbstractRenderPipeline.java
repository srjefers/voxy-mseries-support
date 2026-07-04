package me.cortex.voxy.client.core;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.GL_LEQUAL;
import static org.lwjgl.opengl.GL42.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL42.glDepthFunc;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static me.cortex.voxy.client.core.gl.GLCompat.bindTextureUnit;

public abstract class AbstractRenderPipeline extends TrackedObject {
    private final BooleanSupplier frexStillHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    private final FullscreenBlit depthMaskBlit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
    private final FullscreenBlit depthSetBlit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth0.frag");
    private final FullscreenBlit depthCopy = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth_copy.frag");

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    protected final boolean deferTranslucency;

    /**
     * Whether this pipeline wants environmental fog mixed into LOD terrain.
     * On GL the fog pass lives in {@link #finish(Viewport, int, int, int)};
     * on Metal it's applied per-fragment by quads.frag's USE_ENV_FOG branch
     * (M13 chunk 5) using fog params packed into the SceneUniform SSBO.
     * Subclasses that drive an env-fog config flag override this. Defaults
     * to false so unrelated pipelines (e.g. Iris) don't inject the define.
     */
    public boolean useEnvFog() {
        return false;
    }

    private static final int DEPTH_SAMPLER = glGenSamplers();
    static {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    }

    protected AbstractRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency) {
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;
    }

    //Allows pipelines to configure model baking system
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {//Stupid java ordering not allowing something pre super
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    //Called before the pipeline starts running, used to update uniforms etc
    public void preSetup(Viewport<?> viewport) {

    }

    protected abstract int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport);
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    /** IOSurface bridge for the Metal render path. Lazy-allocated on first non-GL frame. */
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalBridge;
    private int metalBridgeWidth;
    private int metalBridgeHeight;
    /**
     * BGRA8 IOSurface bridge carrying the LOD pass's depth (24-bit RGB-packed)
     * to GL for the Iris gbuffer injection (IrisGbufferInjector). Lazy —
     * allocated only on frames where {@code IrisUtil.irisGbufferInjectMode()}
     * is active, so pack-less runs never pay for the extra surface or the
     * export pass.
     */
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthBridge;
    private int metalDepthBridgeWidth;
    private int metalDepthBridgeHeight;
    /** Fullscreen depth→bridge export pass for {@link #metalDepthBridge}. Lazy like the bridge. */
    private me.cortex.voxy.client.core.interop.MetalDepthExport metalDepthExport;
    // --- Phase D translucent split (vx contract; all lazy) ---
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalTransBridge;
    private int metalTransBridgeWidth, metalTransBridgeHeight;
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthTransBridge;
    private int metalDepthTransBridgeWidth, metalDepthTransBridgeHeight;
    private me.cortex.voxy.client.core.gpu.IGpuTexture metalDepthTransTex;
    private me.cortex.voxy.client.core.gpu.IGpuBuffer metalTransReadBuffer;
    private me.cortex.voxy.client.core.interop.MetalDepthRestore metalDepthRestore;

    // --- Phase C material g-buffer (issue #11; vxMaterialMode; all lazy) ---
    // 3 BGRA8 planes per LOD layer: P0 albedo, P1 tint, P2 misc(light/face/customId).
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque0, metalVxOpaque1, metalVxOpaque2;
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans0, metalVxTrans1, metalVxTrans2;

    /** Lazy-(re)allocate a BGRA8 plane bridge sized to the framebuffer. */
    private static me.cortex.voxy.client.core.interop.IOSurfaceBridge ensurePlaneBridge(
            me.cortex.voxy.client.core.interop.IOSurfaceBridge cur,
            me.cortex.voxy.client.core.metal.MetalRenderBackend mrb, int fbw, int fbh) {
        if (cur == null || cur.width() != fbw || cur.height() != fbh) {
            if (cur != null) cur.close();
            return me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                    mrb.device(), fbw, fbh,
                    me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
        }
        return cur;
    }

    /** Lock + read a material plane IOSurface and log center pixels (BGRA8; the int
     *  prints as 0xAARRGGBB). Diagnostic for the dark-LOD investigation. */
    private static void dumpVxPlane(String label, me.cortex.voxy.client.core.interop.IOSurfaceBridge plane, int fbw, int fbh) {
        if (plane == null) { Logger.info("[Metal-VXPLANES] " + label + " = null"); return; }
        long surf = plane.ioSurfaceHandle();
        if (me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(surf) != 0) {
            Logger.info("[Metal-VXPLANES] " + label + " lock FAILED");
            return;
        }
        try {
            long sbase = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(surf);
            int bpr = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(surf);
            long rowAddr = sbase + (long) (fbh * 2 / 3) * bpr; // lower third = LOD terrain, below sky
            StringBuilder px = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                int x = fbw / 4 + i * (fbw / 16);
                px.append(String.format(" %08X", MemoryUtil.memGetInt(rowAddr + (long) x * 4)));
            }
            Logger.info("[Metal-VXPLANES] " + label + " (0xAARRGGBB):" + px);
        } finally {
            me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(surf);
        }
    }

    /**
     * Comprehensive one-shot statistical read-back of the three REAL material
     * planes (albedo/tint/misc) over a grid spanning the lower 2/3 of the frame
     * (below the sky). Decodes each pixel the same way MetalVxResolvePass does and
     * reports aggregates so a single run characterises the whole g-buffer instead
     * of 8 cherry-picked pixels: albedo coverage + mean RGB + grayscale fraction,
     * tint white-vs-biome split with sample non-white tints, and a block/sky light
     * histogram (the input BSL's GetLighting gates scene lighting on). VOXY_VX_DUMP_PLANES=1.
     */
    /**
     * Translucent-plane water-detection probe (VOXY_VX_DUMP_PLANES=1). Reads the TRANSLUCENT
     * misc plane's packed customId and reports, over covered water pixels, how many BSL would
     * detect as water (blockID = customId/100 == 200 or 204) vs not. If a meaningful fraction
     * read as NON-water, that's the flat-translucent "square artifact" cause: voxy_translucent
     * only runs its water branch when customId says water; otherwise it renders generic flat
     * translucent. Pairs with the opaque dumpVxStats.
     */
    private void dumpVxTransStats(int fbw, int fbh) {
        var a = this.metalVxTrans0; var m = this.metalVxTrans2;
        if (a == null || m == null) { Logger.info("[Metal-VXTRANS] trans planes null"); return; }
        long sa = a.ioSurfaceHandle(), sm = m.ioSurfaceHandle();
        if (me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(sa) != 0
                || me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(sm) != 0) {
            Logger.info("[Metal-VXTRANS] lock FAILED"); return;
        }
        try {
            long ba = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(sa);
            long bm = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(sm);
            int pra = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(sa);
            int prm = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(sm);
            int covered = 0, water = 0, notWater = 0;
            java.util.HashMap<Integer,Integer> idHist = new java.util.HashMap<>();
            int[] faceHist = new int[8]; // face = (misc.r>>1)&7 — the per-quad normal selector
            int[] skyHist = new int[16], blockHist = new int[16]; // light nibbles: block=misc.r>>4, sky=misc.g>>4
            for (int ry = 0; ry < 24; ry++) {
                int y = fbh / 4 + ry * (fbh * 3 / 4) / 24;
                long raA = ba + (long) y * pra, raM = bm + (long) y * prm;
                for (int rx = 0; rx < 48; rx++) {
                    int x = rx * fbw / 48;
                    int av = MemoryUtil.memGetInt(raA + (long) x * 4); // 0xAARRGGBB
                    if (((av >>> 24) & 0xFF) <= 1) continue; // trans coverage = albedo alpha
                    covered++;
                    int mv = MemoryUtil.memGetInt(raM + (long) x * 4);
                    int rCh = (mv >> 16) & 0xFF; // misc.r: (nib.x<<4)|((face&7)<<1)  [block nib in bits4-7]
                    int gCh = (mv >> 8) & 0xFF;   // misc.g: nib.y<<4  [sky nib in bits4-7]
                    int aCh = (mv >>> 24) & 0xFF, bCh = mv & 0xFF; // customId = b | (a<<8)
                    faceHist[(rCh >> 1) & 7]++;
                    blockHist[(rCh >> 4) & 0xF]++; skyHist[(gCh >> 4) & 0xF]++;
                    int customId = bCh | (aCh << 8);
                    int blockID = customId / 100;
                    if (blockID == 200 || blockID == 204) water++; else {
                        notWater++;
                        idHist.merge(customId, 1, Integer::sum);
                    }
                }
            }
            StringBuilder samp = new StringBuilder();
            idHist.entrySet().stream().sorted((p,q)->q.getValue()-p.getValue()).limit(6)
                    .forEach(e -> samp.append(String.format(" id=%d(blk=%d)x%d", e.getKey(), e.getKey()/100, e.getValue())));
            Logger.info(String.format("[Metal-VXTRANS] covered=%d waterDetected=%d notWater=%d  topNonWaterIds:%s",
                    covered, water, notWater, samp.length()==0?" none":samp.toString()));
            // face 0=DOWN 1=UP 2=NORTH 3=SOUTH 4=WEST 5=EAST (axis=face>>1, dir=face&1).
            // Flat water surface should be ~all UP(1); a spread => per-quad normals differ -> Fresnel squares.
            Logger.info("[Metal-VXTRANS] water face hist[0..7]=" + java.util.Arrays.toString(faceHist));
            // BSL scales the water SKY REFLECTION by sky-light (waterSkyOcclusion=lightmap.y^2,
            // voxy_translucent.glsl:357-362). A multi-modal/per-chunk sky histogram => per-chunk
            // reflection brightness => the lighter/darker chunk SQUARES; uniform => flat far water.
            Logger.info("[Metal-VXTRANS] water skyLight hist[0..15]=" + java.util.Arrays.toString(skyHist));
            Logger.info("[Metal-VXTRANS] water blockLight hist[0..15]=" + java.util.Arrays.toString(blockHist));
        } finally {
            me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(sa);
            me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(sm);
        }
    }

    private void dumpVxStats(int fbw, int fbh) {
        var a = this.metalVxOpaque0; var t = this.metalVxOpaque1; var m = this.metalVxOpaque2;
        if (a == null || t == null || m == null) { Logger.info("[Metal-VXSTATS] a plane is null"); return; }
        long sa = a.ioSurfaceHandle(), st = t.ioSurfaceHandle(), sm = m.ioSurfaceHandle();
        if (me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(sa) != 0
                || me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(st) != 0
                || me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(sm) != 0) {
            Logger.info("[Metal-VXSTATS] lock FAILED"); return;
        }
        try {
            long ba = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(sa);
            long bt = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(st);
            long bm = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(sm);
            int pra = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(sa);
            int prt = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(st);
            int prm = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(sm);
            int covered = 0, total = 0, gray = 0, tintWhite = 0, tintBiome = 0;
            long sumR = 0, sumG = 0, sumB = 0;
            int[] blockHist = new int[16], skyHist = new int[16];
            StringBuilder tintSamples = new StringBuilder();
            int tintSampleN = 0;
            // 24 rows across the lower 2/3, 48 cols across the full width.
            for (int ry = 0; ry < 24; ry++) {
                int y = fbh / 3 + ry * (fbh * 2 / 3) / 24;
                long raA = ba + (long) y * pra, raT = bt + (long) y * prt, raM = bm + (long) y * prm;
                for (int rx = 0; rx < 48; rx++) {
                    int x = rx * fbw / 48;
                    total++;
                    int av = MemoryUtil.memGetInt(raA + (long) x * 4); // 0xAARRGGBB
                    int alpha = (av >>> 24) & 0xFF;
                    if (alpha <= 1) continue; // matches resolve discard (albedo.a <= 0.001)
                    covered++;
                    int ar = (av >> 16) & 0xFF, ag = (av >> 8) & 0xFF, ab = av & 0xFF;
                    sumR += ar; sumG += ag; sumB += ab;
                    if (Math.abs(ar - ag) < 6 && Math.abs(ag - ab) < 6) gray++;
                    int tv = MemoryUtil.memGetInt(raT + (long) x * 4);
                    int tr = (tv >> 16) & 0xFF, tg = (tv >> 8) & 0xFF, tb = tv & 0xFF;
                    if (tr >= 250 && tg >= 250 && tb >= 250) tintWhite++;
                    else {
                        tintBiome++;
                        if (tintSampleN < 6) { tintSamples.append(String.format(" %02X%02X%02X", tr, tg, tb)); tintSampleN++; }
                    }
                    int mv = MemoryUtil.memGetInt(raM + (long) x * 4);
                    int mr = (mv >> 16) & 0xFF, mg = (mv >> 8) & 0xFF; // misc.r, misc.g
                    int block = (mr >> 4) & 0xF, sky = (mg >> 4) & 0xF;
                    blockHist[block]++; skyHist[sky]++;
                }
            }
            Logger.info(String.format("[Metal-VXSTATS] covered=%d/%d (%.0f%%) meanRGB=(%d,%d,%d) grayFrac=%.0f%%",
                    covered, total, 100.0 * covered / Math.max(1, total),
                    covered > 0 ? (int)(sumR / covered) : 0, covered > 0 ? (int)(sumG / covered) : 0,
                    covered > 0 ? (int)(sumB / covered) : 0, 100.0 * gray / Math.max(1, covered)));
            Logger.info(String.format("[Metal-VXSTATS] tint white=%d biome=%d  biomeSamples(RRGGBB):%s",
                    tintWhite, tintBiome, tintSamples.length() == 0 ? " none" : tintSamples.toString()));
            Logger.info("[Metal-VXSTATS] blockLight hist[0..15]=" + java.util.Arrays.toString(blockHist));
            Logger.info("[Metal-VXSTATS] skyLight   hist[0..15]=" + java.util.Arrays.toString(skyHist));
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                long dt = mc.level.getDayTime() % 24000L;
                Logger.info("[Metal-VXSTATS] worldDayTime=" + dt + " (0-12000=day, 13000-23000=night)");
            }
        } finally {
            me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(sa);
            me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(st);
            me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(sm);
        }
    }
    /**
     * Blit destination for {@link #metalDepthTex} (w×h raw D32F floats) and
     * read source of the export pass. Exists because Metal silently reads
     * zeros when a depth-format texture is sampled through the
     * texture2d&lt;float&gt; declaration SPIRV-Cross emits for sampler2D;
     * buffer reads are format-blind. Lazy like the bridge.
     */
    private me.cortex.voxy.client.core.gpu.IGpuBuffer metalDepthReadBuffer;
    /**
     * Depth texture for {@code runPipelineMetal}'s render pass. Lazy-allocated
     * to match the bridge size so depth-tested LOD terrain self-occludes correctly.
     * Lives in Metal-side memory (the bridge's color is shared with GL via
     * IOSurface; the depth has no GL consumer so it stays Metal-private).
     */
    private me.cortex.voxy.client.core.gpu.IGpuTexture metalDepthTex;
    private int metalDepthWidth;
    private int metalDepthHeight;
    /**
     * M13 chunk 3: Shared-storage mirror of MC's main-FBO depth, refreshed
     * per frame via {@code glGetTexImage}. Sourced by
     * {@code HiZBuffer.buildMipChain(IGpuTexture, ...)} so the HiZ pyramid
     * carries real occlusion data instead of the zero-init stub from M12.
     * Lazy — allocated on the first Metal frame that has a non-zero sized
     * source framebuffer.
     */
    private me.cortex.voxy.client.core.rendering.util.DepthMirror metalDepthMirror;
    /** Animation counter for the placeholder Metal render — replaced by real Voxy output incrementally. */
    private int metalFrame;
    /** VOXY_UNDERWATER_LOD=1 forces LOD draws even when submerged-fog saturates the far field. */
    private static final boolean UNDERWATER_LOD_FORCE = "1".equals(System.getenv("VOXY_UNDERWATER_LOD"));
    /**
     * 2026-07-03 round 3: submersionSkip used to skip the ENTIRE vx-contract
     * translucent block — including the material-plane clears — so going
     * underwater froze metalVxTrans0-2 (+ the trans depth bridge) at the last
     * above-water frame while the GL resolve kept re-compositing the stale
     * planes into the pack's colortex every frame: ghost water squares that
     * toggle with the skip at the waterline. Keep the clear-only maintenance
     * running every contract frame (cleared planes make the resolve discard
     * everything) and gate only the DRAWS on the skip.
     * VOXY_TRANS_SUBMERSION_CLEAR=0 restores the old skip-everything gating.
     */
    private static final boolean TRANS_SUBMERSION_CLEAR = !"0".equals(System.getenv("VOXY_TRANS_SUBMERSION_CLEAR"));

    // [Metal-FLICKER] diagnostic (2026-05-26): track whether the rendered
    // section set (renderList count) varies frame-to-frame. With a perfectly
    // static camera, a varying count proves NON-DETERMINISTIC section selection
    // (a GPU race in the HOT traversal) — vs a stable count meaning the flicker
    // is view-jitter at the frustum boundary. Logged every 600 frames.
    private int rlCountLast = -1;
    private int rlCountMin = Integer.MAX_VALUE;
    private int rlCountMax = 0;
    private int rlChanges = 0;

    public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            // Metal path — M12 chunk 6 step 1 runs the migrated compute side
            // of the pipeline (DownloadStream + AsyncNodeManager.tick +
            // NodeCleaner.tick + HOT.doTraversal + MDIC.buildDrawCalls) but
            // still clears the bridge for visual feedback instead of issuing
            // real LOD draws. Subsequent steps migrate renderTerrain /
            // renderTranslucent and replace the clear with encoder draws so
            // Voxy's actual LOD content reaches the IOSurface bridge.
            this.runPipelineMetal(viewport, sourceFrameBuffer);
            return;
        }
        int depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        rs.renderOpaque(viewport);
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            this.innerPrimaryWork(viewport, depthTexture);
        }
        if (occlusionDebug<=1) {
            rs.buildDrawCalls(viewport);
        }
        rs.renderTemporal(viewport);

        this.postOpaquePreTranslucent(viewport);

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }

        this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    /** Push-block binding for depth_copy.frag's scaleFactor (see Push struct in the shader). */
    private static final int DEPTH_COPY_PUSH_BINDING = 14;
    /** Push-block binding for blit_texture_depth_cutout.frag's PushMats (invProj + proj). */
    private static final int BLIT_DEPTH_MATS_PUSH_BINDING = 14;
    private static final int BLIT_DEPTH_MATS_PUSH_SIZE = 4 * 4 * 4 * 2; // two mat4s

    protected void initDepthStencil(int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight, int width, int height) {
        glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, 1.0f, 1);
        // using blit to copy depth from mismatched depth formats is not portable so instead a full screen pass is performed for a depth copy
        // the mismatched formats in this case is the d32 to d24s8
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFb);

        this.depthCopy.bind();
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        bindTextureUnit(0, depthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        // Push scaleFactor (vec2) into the Push UBO declared at DEPTH_COPY_PUSH_BINDING.
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long addr = stack.nmalloc(8);
            MemoryUtil.memPutFloat(addr,     ((float) width) / srcWidth);
            MemoryUtil.memPutFloat(addr + 4, ((float) height) / srcHeight);
            this.depthCopy.setBytes(DEPTH_COPY_PUSH_BINDING, addr, 8);
        }
        glColorMask(false,false,false,false);
        this.depthCopy.blit();

        /*
        if (Capabilities.INSTANCE.isMesa){
            glClearStencil(1);
            glClear(GL_STENCIL_BUFFER_BIT);
        }*/

        //This whole thing is hell, we basicly want to create a mask stenicel/depth mask specificiclly
        // in theory we could do this in a single pass by passing in the depth buffer from the sourceFrambuffer
        // but the current implmentation does a 2 pass system
        glEnable(GL_STENCIL_TEST);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilMask(0xFF);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_NOTEQUAL);//If != 1 pass
        //We do here
        this.depthMaskBlit.blit();
        glDisable(GL_DEPTH_TEST);

        //Blit depth 0 where stencil is 0
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 0, 0xFF);

        this.depthSetBlit.blit();

        glDepthFunc(GL_LEQUAL);
        glColorMask(true,true,true,true);

        //Make voxy terrain render only where there isnt mc terrain
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 1, 0xFF);
    }

    protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        // at this point the dst frame buffer doesn't have a stencil attachment so we don't need to keep the stencil test on for the blit
        // in the worst case the dstFB does have a stencil attachment causing this pass to become 'corrupted'
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFB);

        blitShader.bind();
        bindTextureUnit(0, srcDepthTex);

        // Push PushMats { mat4 invProjMat; mat4 projMat; } into the UBO at BLIT_DEPTH_MATS_PUSH_BINDING.
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long addr = stack.nmalloc(BLIT_DEPTH_MATS_PUSH_SIZE);
            new Matrix4f(viewport.MVP).invert().getToAddress(addr);                 // invProjMat
            targetTransform.getToAddress(addr + 4 * 4 * 4);                          // projMat
            blitShader.setBytes(BLIT_DEPTH_MATS_PUSH_BINDING, addr, BLIT_DEPTH_MATS_PUSH_SIZE);
        }

        glEnable(GL_DEPTH_TEST);
        blitShader.blit();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
    }

    protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {

        //Compute the mip chain
        viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            //Tick download stream
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            //glFlush();

            this.nodeCleaner.tick(this.traversal.getNodeBuffer());//Probably do this here??

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.frexStillHasWork.getAsBoolean());
    }

    @Override
    protected void free0() {
        this.fb.free();
        this.sectionRenderer.free();
        this.depthMaskBlit.delete();
        this.depthSetBlit.delete();
        this.depthCopy.delete();
        if (this.metalBridge != null) {
            this.metalBridge.close();
            this.metalBridge = null;
        }
        if (this.metalDepthBridge != null) {
            this.metalDepthBridge.close();
            this.metalDepthBridge = null;
        }
        if (this.metalDepthExport != null) {
            this.metalDepthExport.close();
            this.metalDepthExport = null;
        }
        if (this.metalDepthReadBuffer != null) {
            this.metalDepthReadBuffer.free();
            this.metalDepthReadBuffer = null;
        }
        if (this.metalTransBridge != null) { this.metalTransBridge.close(); this.metalTransBridge = null; }
        if (this.metalDepthTransBridge != null) { this.metalDepthTransBridge.close(); this.metalDepthTransBridge = null; }
        if (this.metalDepthTransTex != null) { this.metalDepthTransTex.free(); this.metalDepthTransTex = null; }
        if (this.metalTransReadBuffer != null) { this.metalTransReadBuffer.free(); this.metalTransReadBuffer = null; }
        if (this.metalDepthRestore != null) { this.metalDepthRestore.close(); this.metalDepthRestore = null; }
        if (this.metalVxOpaque0 != null) { this.metalVxOpaque0.close(); this.metalVxOpaque0 = null; }
        if (this.metalVxOpaque1 != null) { this.metalVxOpaque1.close(); this.metalVxOpaque1 = null; }
        if (this.metalVxOpaque2 != null) { this.metalVxOpaque2.close(); this.metalVxOpaque2 = null; }
        if (this.metalVxTrans0 != null) { this.metalVxTrans0.close(); this.metalVxTrans0 = null; }
        if (this.metalVxTrans1 != null) { this.metalVxTrans1.close(); this.metalVxTrans1 = null; }
        if (this.metalVxTrans2 != null) { this.metalVxTrans2.close(); this.metalVxTrans2 = null; }
        if (this.metalDepthTex != null) {
            this.metalDepthTex.free();
            this.metalDepthTex = null;
        }
        if (this.metalDepthMirror != null) {
            this.metalDepthMirror.free();
            this.metalDepthMirror = null;
        }
        super.free0();
    }

    /**
     * Metal-path runPipeline (M12 chunk 6, evolving). Today runs the migrated
     * compute side end-to-end on Metal — every stage in this method is either
     * already encoder-backed or skipped with a Metal-aware substitute — and
     * still clears the IOSurface bridge as visual confirmation that the
     * pipeline executed. Real LOD draws land in subsequent chunk 6 steps when
     * renderTerrain / renderTranslucent migrate to RenderEncoder.
     * <p>
     * Compared to the GL {@link #runPipeline}, the Metal path currently
     * skips: {@link #setup} (depth-stencil FBO copy uses raw GL),
     * {@link me.cortex.voxy.client.core.rendering.util.HiZBuffer#buildMipChain}
     * (partial GL — see chunk 6 step 2), the FrEx work loop wrapper, the
     * raw {@code glMemoryBarrier} inside {@link #innerPrimaryWork}, the
     * post-opaque SSAO compute, and {@link #finish}. The compute pipeline
     * (HOT traversal + buildDrawCalls' 5 prepasses) runs in full.
     */
    private void runPipelineMetal(Viewport<?> viewport, int sourceFrameBuffer) {
        int fbw = viewport.width;
        int fbh = viewport.height;
        if (fbw <= 0 || fbh <= 0) return;
        var backend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get();
        if (!(backend instanceof me.cortex.voxy.client.core.metal.MetalRenderBackend mrb)) return;

        // 1) Allocate the IOSurface bridge sized to MC's framebuffer. The
        //    bridge is the cross-context handle: Metal renders into the
        //    backing MTLTexture, IOSurfaceBridgeCompositor blits it into MC's
        //    main RT via a CGL-bound GL_TEXTURE_RECTANGLE source FBO.
        if (this.metalBridge == null || this.metalBridgeWidth != fbw || this.metalBridgeHeight != fbh) {
            if (this.metalBridge != null) this.metalBridge.close();
            this.metalBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                    mrb.device(), fbw, fbh,
                    me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
            this.metalBridgeWidth  = fbw;
            this.metalBridgeHeight = fbh;
        }

        // 2) Ensure the HiZ texture is allocated so HOT can bind it. The
        //    encoder-driven mip-chain build is wired up but parked: an
        //    initial integration test (2026-05-13) showed LOD chunks
        //    disappearing at the horizon when HOT samples the populated
        //    pyramid — likely a Depth32Float_Stencil8 sampling-as-sampler2D
        //    mismatch versus the GL path's pre-processed depth (see
        //    initDepthStencil's stencil-mask dance that zeros sky regions).
        //    Revert to M12's zero-init pyramid until that's debugged so
        //    HOT trivially passes every frustum-visible section. The
        //    DepthMirror class + MetalNative.mtlTextureNewSubresourceView
        //    JNI + IGpuTexture.createView(level, count) all stay committed
        //    for the follow-up. ensureAllocated zero-fills every mip on
        //    Metal at (re)allocation — MTLTexture contents are otherwise
        //    UNDEFINED and the screenspace.glsl "pointSample <= 0.0" guard
        //    needs real zeros, not luck.
        viewport.hiZBuffer.ensureAllocated(viewport.width, viewport.height);

        // 2b) Lazy-allocate the Metal-side depth texture for our render pass.
        //     PURE depth format (not D24S8): the packed Depth32Float_Stencil8
        //     cannot be reliably sampled as texture2d<float> on Metal — the
        //     Iris-inject depth export read zeros from it, so every injected
        //     LOD pixel discarded (d<=0) and LODs vanished under packs. The
        //     stencil aspect was never used by the LOD pass; pure D32F is the
        //     proven-sampleable format (same fix as HiZ + the chunk-bound
        //     mask) and depth-only attachment of it is validation-clean.
        //     The encoder pass clears it to 1.0 (far plane) each frame.
        if (this.metalDepthTex == null || this.metalDepthWidth != fbw || this.metalDepthHeight != fbh) {
            if (this.metalDepthTex != null) this.metalDepthTex.free();
            this.metalDepthTex = backend.createTexture()
                    .store(org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F, 1, fbw, fbh)
                    .name("VoxyMetalDepth");
            this.metalDepthWidth = fbw;
            this.metalDepthHeight = fbh;
        }

        // 3) Compute side — copy of innerPrimaryWork's body minus the GL bits
        //    (HiZBuffer.buildMipChain, raw glMemoryBarrier, FrEx loop). Each
        //    sub-stage is already encoder-backed (commits 0b963825, 68734b78,
        //    5dcbc645, 89b35814 for HOT's last raw-GL gaps).
        me.cortex.voxy.client.core.rendering.util.DownloadStream.INSTANCE.tick();
        this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
        this.nodeCleaner.tick(this.traversal.getNodeBuffer());
        this.traversal.doTraversal(viewport);

        // 4) Per-frame draw-command generation — all 5 MDIC compute prepasses
        //    (prep / cull-stub / commandGen / prefixSum / translucentGen) now
        //    flow through ComputeEncoder (chunks 1–5). Raw cast matches the
        //    GL path (line 119) — the renderer's viewport generic is set at
        //    construction by RenderPipelineFactory and we trust the pairing.
        @SuppressWarnings({"rawtypes", "unchecked"})
        AbstractSectionRenderer rs = (AbstractSectionRenderer) this.sectionRenderer;
        rs.buildDrawCalls(viewport);

        // M13 2026-05-14 baseInstance workaround: flush + wait so the compute
        // prepasses (commandGen writes drawCallBuffer's baseInstance field)
        // complete before the render pass starts. MetalRenderEncoder.
        // drawIndexedIndirect needs to CPU-read drawCallBuffer per draw to
        // push baseInstance via setVertexBytes (drawIndexedPrimitives:
        // indirectBuffer: doesn't propagate it natively). Without this
        // submit() the CPU sees stale data from the prior frame.
        backend.submit();

        // M13 2026-05-15 Layer B diagnostic — read back the renderList and
        // drawCountCallBuffer values so we can see exactly how many sections
        // HOT enqueued for rendering, and what dispatch parameters prep.comp
        // wrote for cmdgen. If sectionCount is small, the upstream traversal
        // is the bottleneck. If sectionCount is big but cmdGenDispatchX is
        // small, prep.comp's read of sectionCount is racing or stale.
        // (2026-05-26: logged every 600 frames ≈ 10s, offset from the other
        // 600-frame diag block below, so the translucent draw count — which
        // tells us how much water LOD is actually being drawn — is visible
        // regularly while diagnosing the "no colour" water.)
        if (this.metalFrame % 600 == 300
                && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mv) {
            int renderListSectionCount = -1;
            int cmdGenDispatchX = -1;
            int cmdGenDispatchY = -1;
            int cmdGenDispatchZ = -1;
            int opaqueDrawCount = -1;
            int translucentDrawCount = -1;
            int temporalOpaqueDrawCount = -1;
            if (mv.getRenderList() instanceof me.cortex.voxy.client.core.metal.MetalBuffer rl) {
                renderListSectionCount = org.lwjgl.system.MemoryUtil.memGetInt(rl.getContentsPtr());
            }
            if (mv.drawCountCallBuffer instanceof me.cortex.voxy.client.core.metal.MetalBuffer dc) {
                long p = dc.getContentsPtr();
                cmdGenDispatchX        = org.lwjgl.system.MemoryUtil.memGetInt(p +  0);
                cmdGenDispatchY        = org.lwjgl.system.MemoryUtil.memGetInt(p +  4);
                cmdGenDispatchZ        = org.lwjgl.system.MemoryUtil.memGetInt(p +  8);
                opaqueDrawCount        = org.lwjgl.system.MemoryUtil.memGetInt(p + 12);
                translucentDrawCount   = org.lwjgl.system.MemoryUtil.memGetInt(p + 16);
                temporalOpaqueDrawCount = org.lwjgl.system.MemoryUtil.memGetInt(p + 20);
            }
            int topNodeCount = this.traversal.getTopNodeCount();
            int firstDispatchSize = (topNodeCount + 127) >> 7;
            Logger.info(String.format(
                    "[Metal-LayerB f=%d] topNodeCount=%d firstDispatchSize=%d renderList.sectionCount=%d cmdGenDispatch=(%d,%d,%d) draws opaque=%d translucent=%d temporal=%d",
                    this.metalFrame, topNodeCount, firstDispatchSize,
                    renderListSectionCount,
                    cmdGenDispatchX, cmdGenDispatchY, cmdGenDispatchZ,
                    opaqueDrawCount, translucentDrawCount, temporalOpaqueDrawCount));
        }

        // 5) Render pass against bridge color + Voxy-owned depth. Clears both
        //    each frame (no MC-depth import on Metal yet, so we render every
        //    LOD chunk against a fresh depth buffer — they self-occlude but
        //    don't z-test against MC's foreground terrain). Inside the pass
        //    we call MDIC's Metal-aware renderOpaque equivalent to issue
        //    the actual LOD draws via the RenderEncoder API. 2026-05-14
        //    revert: alpha back to 1.0 (M12-stable) since the alpha-composite
        //    shader path made LOD invisible in-game. With the blit compositor
        //    the clear colour shows through in non-LOD areas (sky no longer
        //    visible through them); Sodium overdraws its near terrain on top.
        // 2026-05-14 diagnostic finding: with magenta clear, user reports
        // "Veo magenta en todo (excepto terreno MC cercano)" — confirming
        // the IOSurface bridge + blit-to-MC-mainRT path works end-to-end.
        // The reason LOD chunks are invisible is the MDIC opaque/temporal/
        // translucent draws are NOT producing visible pixels in the bridge.
        // Likely causes: drawIndexedIndirect counts are zero (HOT culling /
        // commandGen prepass), or vertex shader clips all geometry. Restored
        // dark clear so day-to-day play isn't magenta-flooded; the rendering
        // pipeline diagnosis continues in MDIC + buildDrawCalls.
        float clearR = 0.02f;
        float clearG = 0.02f;
        float clearB = 0.04f;
        if (viewport.fogParameters != null) {
            clearR = viewport.fogParameters.red();
            clearG = viewport.fogParameters.green();
            clearB = viewport.fogParameters.blue();
        }
        // DIAGNOSTIC (2026-05-25): VOXY_BRIDGE_SOLID_TEST=1 fills the bridge
        // with a static bright-green clear and SKIPS all LOD draws below. If
        // the green is rock-stable on screen, the IOSurface bridge + composite
        // + sync path is sound and the flicker lives in the LOD draws/content;
        // if the green itself flickers, the bridge/sync is the culprit.
        boolean bridgeSolidTest = "1".equals(System.getenv("VOXY_BRIDGE_SOLID_TEST"));
        if (bridgeSolidTest) {
            clearR = 0.0f; clearG = 1.0f; clearB = 0.0f;
            if ((this.metalFrame % 600) == 1) {
                Logger.info("[Metal-SOLID-TEST] VOXY_BRIDGE_SOLID_TEST active: bridge=green, LOD draws skipped");
            }
        }
        // Clear alpha 0.0: the alpha-discard composite drops undrawn bridge
        // pixels so MC's own sky/fog shows behind the LODs (kills the
        // whole-far-field fog flash when the eye crosses the water surface).
        // The blit fallback (VOXY_COMPOSITE_BLIT=1) copies raw pixels and
        // needs the M12-stable opaque clear; the solid test must stay visible.
        // Iris-pack mode injects the bridge into Iris's terrain gbuffer with
        // an alpha-discard + depth-write shader — undrawn pixels must carry
        // alpha 0 so only Voxy-drawn pixels write into the pack's colortex.
        // lodExport: TRUE for both LOD-export consumers — the legacy gbuffer
        // injection AND the native vx contract (issue #9); both need the
        // colour bridge with alpha-as-coverage plus the packed depth bridge.
        boolean irisGbufferInject = me.cortex.voxy.client.core.util.IrisUtil.vxContractActive()
                || me.cortex.voxy.client.core.util.IrisUtil.irisGbufferInjectMode();
        float clearA = (bridgeSolidTest || (IOSurfaceBridgeCompositor.USE_BLIT && !irisGbufferInject)) ? 1.0f : 0.0f;
        // Phase C material g-buffer (vxMaterialMode): the opaque LOD renders into 3
        // BGRA8 planes (P0 albedo, P1 tint, P2 misc) for the GL resolve to shade,
        // instead of the single composited bridge colour. Lazy-allocate all 6 planes
        // (opaque + translucent) up front so the translucent split below has targets.
        boolean vxMaterial = this.vxMaterialMode();
        boolean vxOpaqueMat = this.vxOpaqueMaterialMode();
        // Translucent (water) planes whenever the material contract is on; opaque planes
        // ONLY when opaque-material is opted in. Default (trans-only): opaque renders to the
        // base bridge → normal composite (untouched/mergeable), water → material resolve.
        if (vxOpaqueMat) {
            this.metalVxOpaque0 = ensurePlaneBridge(this.metalVxOpaque0, mrb, fbw, fbh);
            this.metalVxOpaque1 = ensurePlaneBridge(this.metalVxOpaque1, mrb, fbw, fbh);
            this.metalVxOpaque2 = ensurePlaneBridge(this.metalVxOpaque2, mrb, fbw, fbh);
        }
        if (vxMaterial) {
            this.metalVxTrans0 = ensurePlaneBridge(this.metalVxTrans0, mrb, fbw, fbh);
            this.metalVxTrans1 = ensurePlaneBridge(this.metalVxTrans1, mrb, fbw, fbh);
            this.metalVxTrans2 = ensurePlaneBridge(this.metalVxTrans2, mrb, fbw, fbh);
        }
        var passBuilder = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh);
        if (vxOpaqueMat) {
            passBuilder.clearColor(this.metalVxOpaque0.asGpuTexture(), 0f, 0f, 0f, 0f)
                       .clearColor(this.metalVxOpaque1.asGpuTexture(), 0f, 0f, 0f, 0f)
                       .clearColor(this.metalVxOpaque2.asGpuTexture(), 0f, 0f, 0f, 0f);
        } else {
            passBuilder.clearColor(this.metalBridge.asGpuTexture(), clearR, clearG, clearB, clearA);
        }
        var pass = passBuilder.clearDepth(this.metalDepthTex, 1.0f).build();
        // Submersion far-field skip: with the eye in water/lava the env fog
        // saturates at 24-96 blocks while every LOD fragment sits far beyond
        // it — the whole LOD field is 100% fog colour by construction. Drawing
        // it anyway only exposes artifacts: Sodium's fog-occlusion culling
        // de-renders near seafloor whose pixels then fall through the opaque
        // blit to the LOD field ("sand turns transparent", flooded caverns),
        // and any residual draw nondeterminism strobes. Skip the LOD draws and
        // let the fog-coloured clear stand — visually identical murk, stable
        // by construction. Guarded so tiny render distances (where LOD could
        // outrange the fog) keep drawing. VOXY_UNDERWATER_LOD=1 forces draws.
        boolean submersionSkip = false;
        // useEnvFog() gate: with Voxy fog disabled there is no murk to hide
        // behind — the skip only applies when the far field is provably
        // fog-saturated. (Previously fog-off avoided the skip only by the
        // accident of MixinFogRenderer inflating envEnd to 999999999.)
        // Round 23: inject mode forces useEnvFog() false, which made this
        // skip DEAD CODE under packs — the underwater far-field protection
        // (added specifically against "sand turns transparent / flooded
        // caverns") never engaged while swimming with a pack active. In
        // inject mode the pack's own underwater fog saturates the far field
        // (BSL composites it by depth), so the skip's premise holds there.
        boolean submersionEligible = this.useEnvFog()
                || me.cortex.voxy.client.core.util.IrisUtil.irisGbufferInjectMode();
        if (!UNDERWATER_LOD_FORCE && submersionEligible && viewport.fogParameters != null) {
            float envEnd = viewport.fogParameters.environmentalEnd();
            int rdBlocks = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
            submersionSkip = envEnd < 128.0f && rdBlocks > envEnd * 2.0f;
        }
        try (var enc = backend.beginRenderPass(pass)) {
            enc.setViewport(0, 0, fbw, fbh, 0.0f, 1.0f);
            // M12 close — invoke MDIC's Metal-aware draws in the same order
            // GL runPipeline uses (opaque → temporal → translucent). Iris is
            // GL-gated upstream so on non-GL the section renderer is always
            // an MDICSectionRenderer (and its viewport an MDICViewport —
            // typing follows from the RenderPipelineFactory pairing).
            // postOpaquePreTranslucent (SSAO) is skipped on Metal — SSAO
            // is M13 polish; the LOD result is intelligible without it.
            if (!bridgeSolidTest && !submersionSkip
                    && this.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdic
                    && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mv) {
                mdic.renderOpaqueMetal(enc, mv);
                mdic.renderTemporalMetal(enc, mv);
                // Phase D (issue #11): in vx-contract mode translucent LOD
                // renders in its OWN pass below — separate colour bridge
                // (premultiplied accumulation -> the pack's colortex16
                // layer) + separate depth (-> vxDepthTexTrans) so the
                // pack's deferred composites LOD water as WATER instead of
                // shading it as opaque land.
                if (!this.deferTranslucency
                        && !me.cortex.voxy.client.core.util.IrisUtil.vxContractActive()) {
                    mdic.renderTranslucentMetal(enc, mv);
                }
            }
        }
        // Iris gbuffer injection: export the LOD pass's depth into the R32F
        // depth bridge so the GL-side injector can unproject it back into
        // MC clip space and write pack-visible gl_FragDepth. Encoded into the
        // SAME command buffer as the LOD pass (encoder order = the barrier),
        // so the submit() below covers it — no extra waits. Bridge alloc
        // mirrors metalBridge's resize discipline above.
        if (irisGbufferInject) {
            if (this.metalDepthBridge == null || this.metalDepthBridgeWidth != fbw || this.metalDepthBridgeHeight != fbh) {
                if (this.metalDepthBridge != null) this.metalDepthBridge.close();
                this.metalDepthBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                        mrb.device(), fbw, fbh,
                        // BGRA8, not R32F: Apple GL's CGLTexImageIOSurface2D
                        // binds 'L00f' R32F surfaces without error but SAMPLES
                        // ZEROS (debug-mode verified: full-screen red = d<=0).
                        // The depth is 24-bit-packed into RGB instead — the
                        // BGRA8 path is the proven one (the color bridge).
                        me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                this.metalDepthBridgeWidth  = fbw;
                this.metalDepthBridgeHeight = fbh;
            }
            if (this.metalDepthExport == null) {
                this.metalDepthExport = new me.cortex.voxy.client.core.interop.MetalDepthExport(backend);
            }
            // Depth reaches the export pass via a plain buffer, not by
            // sampling metalDepthTex: depth-format textures bound to the
            // texture2d<float> slot SPIRV-Cross emits for sampler2D silently
            // read ZEROS on Metal. The blit encodes after the LOD pass and
            // the next beginRenderPass (inside render()) closes the blit
            // encoder, so encoder order gives LOD-pass → blit → export.
            long depthBufSize = (long) fbw * fbh * 4;
            if (this.metalDepthReadBuffer == null || this.metalDepthReadBuffer.size() != depthBufSize) {
                if (this.metalDepthReadBuffer != null) this.metalDepthReadBuffer.free();
                this.metalDepthReadBuffer = backend.createBuffer(depthBufSize);
            }
            mrb.copyTextureToBuffer(this.metalDepthTex, this.metalDepthReadBuffer, fbw, fbh);
            this.metalDepthExport.render(backend, this.metalDepthReadBuffer,
                    this.metalDepthBridge.asGpuTexture(), fbw, fbh);

            // Phase D translucent split (vx contract only): seed a second
            // depth target with the opaque depth (restore pass — the blit
            // buffer already holds it), render translucent LOD into its own
            // premultiplied colour bridge with depth WRITE so the water
            // SURFACE depth lands in vxDepthTexTrans, then blit+export that
            // depth through a second packed bridge. Encoder order keeps it
            // all in this frame's single submit.
            if (me.cortex.voxy.client.core.util.IrisUtil.vxContractActive()
                    && !bridgeSolidTest && (TRANS_SUBMERSION_CLEAR || !submersionSkip)
                    && this.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdicT
                    && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mvT
                    && !this.deferTranslucency) {
                // Material mode uses the 3 translucent planes (allocated above);
                // the single trans colour bridge is only for the Phase D-lite path.
                if (!vxMaterial && (this.metalTransBridge == null || this.metalTransBridgeWidth != fbw || this.metalTransBridgeHeight != fbh)) {
                    if (this.metalTransBridge != null) this.metalTransBridge.close();
                    this.metalTransBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                            mrb.device(), fbw, fbh,
                            me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                    this.metalTransBridgeWidth = fbw;
                    this.metalTransBridgeHeight = fbh;
                }
                if (this.metalDepthTransBridge == null || this.metalDepthTransBridgeWidth != fbw || this.metalDepthTransBridgeHeight != fbh) {
                    if (this.metalDepthTransBridge != null) this.metalDepthTransBridge.close();
                    this.metalDepthTransBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                            mrb.device(), fbw, fbh,
                            me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                    this.metalDepthTransBridgeWidth = fbw;
                    this.metalDepthTransBridgeHeight = fbh;
                }
                if (this.metalDepthTransTex == null || this.metalDepthTransTex.getWidth() != fbw || this.metalDepthTransTex.getHeight() != fbh) {
                    if (this.metalDepthTransTex != null) this.metalDepthTransTex.free();
                    this.metalDepthTransTex = backend.createTexture()
                            .store(org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F, 1, fbw, fbh);
                }
                if (this.metalTransReadBuffer == null || this.metalTransReadBuffer.size() != depthBufSize) {
                    if (this.metalTransReadBuffer != null) this.metalTransReadBuffer.free();
                    this.metalTransReadBuffer = backend.createBuffer(depthBufSize);
                }
                if (this.metalDepthRestore == null) {
                    this.metalDepthRestore = new me.cortex.voxy.client.core.interop.MetalDepthRestore(backend);
                }

                this.metalDepthRestore.render(backend, this.metalDepthReadBuffer, this.metalDepthTransTex, fbw, fbh);

                var transPassBuilder = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh);
                if (vxMaterial) {
                    transPassBuilder.clearColor(this.metalVxTrans0.asGpuTexture(), 0f, 0f, 0f, 0f)
                                    .clearColor(this.metalVxTrans1.asGpuTexture(), 0f, 0f, 0f, 0f)
                                    .clearColor(this.metalVxTrans2.asGpuTexture(), 0f, 0f, 0f, 0f);
                } else {
                    transPassBuilder.clearColor(this.metalTransBridge.asGpuTexture(), 0.0f, 0.0f, 0.0f, 0.0f);
                }
                var transPass = transPassBuilder
                        .depthAttachment(this.metalDepthTransTex, 0,
                                me.cortex.voxy.client.core.gpu.RenderPassDesc.LoadAction.LOAD,
                                me.cortex.voxy.client.core.gpu.RenderPassDesc.StoreAction.STORE, 1.0f)
                        .build();
                try (var encT = backend.beginRenderPass(transPass)) {
                    encT.setViewport(0, 0, fbw, fbh, 0.0f, 1.0f);
                    // Submerged: run the pass for its clears only (see
                    // TRANS_SUBMERSION_CLEAR) — the far field is fog-saturated,
                    // so skipping the draws over freshly-cleared planes keeps
                    // the resolve dark instead of compositing stale water.
                    if (!submersionSkip) {
                        mdicT.renderTranslucentMetal(encT, mvT);
                    }
                }

                mrb.copyTextureToBuffer(this.metalDepthTransTex, this.metalTransReadBuffer, fbw, fbh);
                this.metalDepthExport.render(backend, this.metalTransReadBuffer,
                        this.metalDepthTransBridge.asGpuTexture(), fbw, fbh);
            }
        }
        backend.submit();
        this.metalFrame++;

        // [Metal-VXPLANES] one-shot CPU read-back of the material g-buffer planes
        // (VOXY_VX_DUMP_PLANES=1). submit() waited, so the IOSurface holds the exact
        // rendered bytes — definitive (no tonemap / overdraw confound). Center-row px.
        if (vxMaterial && "1".equals(System.getenv("VOXY_VX_DUMP_PLANES"))
                && this.metalFrame % 600 == 200) {
            if ("1".equals(System.getenv("VOXY_VX_GBUFFER_DEBUG"))) {
                // Raw interData byte dump (debug-emit mode rewrites planes to raw attrs).
                dumpVxPlane("P0-albedo", this.metalVxOpaque0, fbw, fbh);
                dumpVxPlane("P1-tint",   this.metalVxOpaque1, fbw, fbh);
                dumpVxPlane("P2-misc",   this.metalVxOpaque2, fbw, fbh);
            } else {
                // Real material planes — statistical grid characterisation.
                dumpVxStats(fbw, fbh);
                dumpVxTransStats(fbw, fbh);
            }
        }

        // [Metal-DEPTHDIAG] chain-bisect instrumentation: the blit buffer is
        // Shared storage and submit() waited, so its contents are the exact
        // floats the export pass read. Histogram of the center rows tells
        // which chain segment is broken: all-zero = blit/LOD-depth broken
        // (Metal side), real spread = Metal side fine, break is in the
        // export pass or the IOSurface→GL hop. Periodic so world-load
        // progression is visible.
        if (irisGbufferInject && this.metalDepthReadBuffer != null
                && this.metalFrame % 600 == 240
                && this.metalDepthReadBuffer instanceof me.cortex.voxy.client.core.metal.MetalBuffer mdb) {
            long base = mdb.getContentsPtr();
            int n = fbw * fbh;
            int zeros = 0, ones = 0, mid = 0;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            int samples = 0;
            for (int i = n / 4; i < n; i += 997) {  // skip top quarter (mostly sky), stride prime
                float v = MemoryUtil.memGetFloat(base + (long) i * 4);
                if (v == 0.0f) zeros++;
                else if (v >= 1.0f) ones++;
                else mid++;
                if (v < min) min = v;
                if (v > max) max = v;
                samples++;
            }
            Logger.info(String.format(
                    "[Metal-DEPTHDIAG] frame=%d blitBuf %dx%d samples=%d zeros=%d ones=%d mid=%d min=%.6f max=%.6f",
                    this.metalFrame, fbw, fbh, samples, zeros, ones, mid, min, max));

            // Segment B: what did the export pass actually write into the
            // depth IOSurface? Lock read-only (forces GPU→CPU sync; diag
            // only) and dump a few center-row packed pixels. Real packed
            // depth = varied bytes; all-zero = export pass never wrote.
            long surf = this.metalDepthBridge.ioSurfaceHandle();
            if (me.cortex.voxy.client.core.metal.MetalNative.iosurfaceLockReadOnly(surf) == 0) {
                try {
                    long sbase = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBaseAddress(surf);
                    int bpr = me.cortex.voxy.client.core.metal.MetalNative.iosurfaceGetBytesPerRow(surf);
                    StringBuilder px = new StringBuilder();
                    long rowAddr = sbase + (long) (fbh / 2) * bpr;
                    for (int i = 0; i < 6; i++) {
                        int x = fbw / 2 + i * 37;
                        px.append(String.format(" %08X", MemoryUtil.memGetInt(rowAddr + (long) x * 4)));
                    }
                    Logger.info("[Metal-DEPTHDIAG] surface centerRow bpr=" + bpr + " px:" + px);
                } finally {
                    me.cortex.voxy.client.core.metal.MetalNative.iosurfaceUnlockReadOnly(surf);
                }
            } else {
                Logger.info("[Metal-DEPTHDIAG] surface lock FAILED");
            }
        }

        // [Metal-FLICKER] per-frame: read the renderList section count (shared
        // storage, valid after submit) and track its variance over the window.
        if (viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mvf
                && mvf.getRenderList() instanceof me.cortex.voxy.client.core.metal.MetalBuffer rlb) {
            int c = MemoryUtil.memGetInt(rlb.getContentsPtr());
            if (this.rlCountLast != -1 && c != this.rlCountLast) this.rlChanges++;
            this.rlCountLast = c;
            if (c < this.rlCountMin) this.rlCountMin = c;
            if (c > this.rlCountMax) this.rlCountMax = c;
        }

        // M13 diagnostic logging: every ~10s (600 frames at 60fps) report what
        // the Metal render path is actually doing — section count loaded into
        // the GPU geometry buffer, MB used, whether AsyncNodeManager has
        // pending work, and the camera position the LOD ring follows. This
        // is the equivalent of the F3 voxy panel for users who can't easily
        // capture it. Drops to silent once the data lines up cleanly.
        if (this.metalFrame % 600 == 1) {
            int sectionCount = -1;
            var geomData = this.sectionRenderer.getGeometryManager();
            if (geomData instanceof me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData bgd) {
                sectionCount = bgd.getSectionCount();
            }
            long usedMb = this.nodeManager.getUsedGeometryCapacity() / (1L << 20);
            long capMb  = this.nodeManager.getGeometryCapacity()    / (1L << 20);
            boolean hasWork = this.nodeManager.hasWork();
            Logger.info(String.format(
                    "[Metal-FLICKER f=%d] renderList count over last ~600 frames: min=%d max=%d changes=%d  (STATIC camera: changes>0 / min!=max => NON-DETERMINISTIC section selection = GPU traversal race; stable => flicker is frustum-edge view-jitter)",
                    this.metalFrame,
                    this.rlCountMin == Integer.MAX_VALUE ? -1 : this.rlCountMin,
                    this.rlCountMax, this.rlChanges));
            this.rlCountMin = Integer.MAX_VALUE; this.rlCountMax = 0; this.rlChanges = 0;
            Logger.info(String.format(
                    "[Metal-DIAG f=%d] sections=%d  geom=%d/%d MB  nodeMgr.hasWork=%s  cam=(%.0f, %.0f, %.0f)",
                    this.metalFrame, sectionCount, usedMb, capMb, hasWork,
                    viewport.cameraX, viewport.cameraY, viewport.cameraZ));
            Logger.info(String.format(
                    "[Metal-CHAIN f=%d] ingestCall=%d  ingestNoLight=%d  ingestQ=%d  ingestProc=%d  rawIngest=%d  worldEvt=%d  topLvlAdd=%d  geomResult=%d",
                    this.metalFrame,
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_ENQUEUE_CALL_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_ENQUEUE_NO_LIGHTING_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_ENQUEUE_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_PROCESS_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_RAW_INGEST_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_WORLD_EVENT_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_TOP_LEVEL_ADD_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_GEOMETRY_RESULT_COUNT.get()));
            Logger.info(String.format(
                    "[Metal-TICK  f=%d] tickWithResults=%d  tickWithUploads=%d  lastResultSectionCount=%d  basicSectionCount=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_TICK_WITH_RESULTS_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_TICK_WITH_UPLOADS_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_LAST_TICK_SECTION_COUNT.get(),
                    sectionCount));
            Logger.info(String.format(
                    "[Metal-PGR   f=%d] notInMap=%d  reqSingle=%d  reqChild=%d  innerLeaf=%d  notWatched=%d  uploadEmpty=%d  emptyKids=%d  emptyNoKids=%d  uploadReal=%d  topNoDataDeferred=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_NOT_IN_MAP.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_REQUEST_SINGLE.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_REQUEST_CHILD.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_INNER_LEAF.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_NOT_WATCHED.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_EMPTY.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_EMPTY_WITH_CHILDREN.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_EMPTY_NO_CHILDREN.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_REAL.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_TOP_LEVEL_NO_DATA_DEFER.get()));
            Logger.info(String.format(
                    "[Metal-GEN   f=%d] called=%d  prepThrow=%d  faceThrow=%d  zeroQ=%d  realQ=%d  lastQ=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_CALLED.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_PREPARE_THROW.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_FACE_THROW.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_ZERO_QUADS.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_REAL_QUADS.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_LAST_QUADCOUNT.get()));
            Logger.info(String.format(
                    "[Metal-BAKE  f=%d] invocations=%d  nonzeroPixels=%d  fullAlpha=%d  zeroAlpha=%d  dilateRuns=%d  dilateFilled=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_NONZERO_PIXEL_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_FULL_ALPHA_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_ZERO_ALPHA_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_DILATE_RUNS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_DILATE_PIXELS_FILLED.get()));
            Logger.info(String.format(
                    "[Metal-PIPE  f=%d] addEntry=%d  cpyBuf=%d  procModel=%d  atlasUpload=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_ADDENTRY_CALLS.get(),
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_CPYBUF_CALLBACKS.get(),
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_PROCESS_MODEL_RESULTS.get(),
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_ATLAS_UPLOADS.get()));
            Logger.info(String.format(
                    "[Metal-REQ   f=%d] last=%d  total=%d  directRead=%d  downloadRead=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_LAST_REQUEST_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_TOTAL_REQUEST_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_REQUEST_DIRECT_READ_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_REQUEST_DOWNLOAD_COUNT.get()));
        }
    }

    /** Accessor for the compositing mixin so it can grab the bridge's GL texture name. */
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalBridge() {
        return this.metalBridge;
    }

    /**
     * R32F depth bridge for the Iris gbuffer injection. Null until the first
     * Metal frame rendered with {@code IrisUtil.irisGbufferInjectMode()} on.
     */
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalTransBridge() {
        return this.metalTransBridge;
    }

    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthTransBridge() {
        return this.metalDepthTransBridge;
    }

    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthBridge() {
        return this.metalDepthBridge;
    }

    // Phase C material g-buffer planes (null unless vxMaterialMode rendered a frame).
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque0() { return this.metalVxOpaque0; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque1() { return this.metalVxOpaque1; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque2() { return this.metalVxOpaque2; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans0() { return this.metalVxTrans0; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans1() { return this.metalVxTrans1; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans2() { return this.metalVxTrans2; }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        RenderStatistics.addDebug(debug);
    }

    //Binds the framebuffer and any other bindings needed for rendering
    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);


    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    //null means no function, otherwise return the taa injection function
    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    /**
     * Phase C (issue #11): when true, the Metal LOD terrain pipelines render a
     * material g-buffer (quads.frag under PATCHED_SHADER + VOXY_VX_GBUFFER + the
     * {@link me.cortex.voxy.client.core.util.MetalVxGbufferEmitter} into 3 BGRA8
     * planes) instead of compositing a final colour, for the GL-side
     * {@link me.cortex.voxy.client.core.util.MetalVxResolvePass} to shade. Only
     * {@code MetalVxRenderPipeline} (Metal + Iris vx-contract) enables it.
     */
    public boolean vxMaterialMode() {
        return false;
    }

    /**
     * Whether the OPAQUE LOD layer also goes through the material g-buffer + BSL resolve.
     * Default FALSE: opaque LODs render on the proven base path (lit colour → bridge →
     * normal composite), which is what's stable on dev — the resolve runs ONLY on the
     * translucent (water) layer (issue #11). The full opaque-material path darkened far
     * terrain (BSL deferred shading of grazing LOD); kept behind VOXY_VX_MATERIAL_OPAQUE=1
     * for A/B only. Trans-only is the mergeable shape: water BSL-shaded, opaque untouched.
     */
    public static final boolean VX_MATERIAL_OPAQUE = "1".equals(System.getenv("VOXY_VX_MATERIAL_OPAQUE"));

    /** Opaque LOD uses the material g-buffer + resolve only when explicitly opted in. */
    public boolean vxOpaqueMaterialMode() {
        return vxMaterialMode() && VX_MATERIAL_OPAQUE;
    }

    //null means dont transform the shader
    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Returning null means apply the same patch as the opaque
    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Null means no scaling factor
    public float[] getRenderScalingFactor() {return null;}

}
