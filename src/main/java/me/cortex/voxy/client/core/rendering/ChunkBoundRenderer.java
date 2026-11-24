package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlGraphicsPipeline;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.gpu.VertexLayout;
import me.cortex.voxy.client.core.rendering.util.MetalMvpUtil;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.JomlCompat;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_CCW;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_CW;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_GREATER;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glFrontFace;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

/**
 * Renders an AABB wireframe around loaded chunks. Pure debug visualisation —
 * one instanced indexed draw per batch of 32 chunks (each chunk emits 6×2×3
 * indices for the 6 faces of its bounding cube via {@link SharedIndexBuffer#INSTANCE_BB_BYTE}).
 *
 * M9 status: pipeline now created via
 * {@link me.cortex.voxy.client.core.gpu.RenderBackend#createGraphicsPipeline}
 * so the GLSL compiles cleanly on Metal/Vulkan. The GL path's bind + draw
 * stay raw GL (glUseProgram + glDrawElementsInstanced); per-call SSBO/UBO
 * binding lives in {@link #render}.
 *
 * M13 chunk 3: {@link #renderMetal} is the encoder-based Metal port — a
 * depth-only render pass into {@code viewport.depthBoundingBuffer} whose
 * result quads.frag's depth-bound test samples to discard LOD fragments
 * inside MC's loaded-chunk volume. {@link #clearMetal} mirrors the GL
 * gate's clear-to-0 branch.
 *
 * 2026-07-14 trans split (Metal only, VOXY_BOUND_TRANS_SPLIT=0 reverts):
 * TRANSLUCENT-ONLY built sections (open-ocean water surface — Sodium built
 * the water plane but the seafloor section below never builds under the
 * visibility graph) used to extend the mask to their AABB back face like any
 * other section, which discarded the LOD seafloor BEHIND their own water.
 * With no LOD floor injected, BSL shaded the real water against void/sky:
 * the flat pale near-water panes that survived every water-side fix. Split
 * them into a second instance set drawn with a COVERAGE EPSILON depth
 * (1e-5, see outline.fsh VOXY_BOUND_EPS): under the mask's GREATER
 * accumulate the epsilon still marks per-pixel coverage for the trans
 * near-cull ({@code bound > 0}: LOD water still culled, real water owns the
 * surface) but no LOD fragment has window-z below it, so the opaque
 * depth-bound test ({@code gl_FragCoord.z < bound}) never discards the
 * floor beneath — it injects, the ghost trans depth gives dT &lt; d, and the
 * seafloor water-column dim darkens it like a real BSL floor.
 */
public class ChunkBoundRenderer {
    private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;

    /** UBO binding for SceneUniform — matches `layout(binding=0)` in outline.vsh. */
    private static final int SCENE_UNIFORM_BINDING = 0;
    /** SSBO binding for the chunk-position array. */
    private static final int CHUNK_POS_BINDING = 1;

    /** Kill switch for the trans-only coverage-epsilon split (default ON on Metal). */
    private static final boolean TRANS_SPLIT =
            !"0".equals(System.getenv("VOXY_BOUND_TRANS_SPLIT"));

    /** VOXY_BOUND_TRANS_SPLIT=all: split even without the vx contract (the pre-2026-09-26 default, for A/B). */
    private static final boolean SPLIT_ALL = "all".equalsIgnoreCase(
            String.valueOf(System.getenv("VOXY_BOUND_TRANS_SPLIT")).trim());

    /**
     * The split only exists off-GL: the GL backend keeps the single-set path byte-identical.
     * 2026-09-26: and only where the translucent near-cull that relies on it is compiled, i.e. under the
     * vx contract with VOXY_TRANS_NEAR_CULL on (the same condition as MDICSectionRenderer). Without a
     * shader pack the epsilon made the depth-bound test discard nothing over trans-only and cutout-only
     * sections, so LOD water double-blended under MC water and far LOD plants showed through MC's cutout
     * gaps inside the vanilla ring. Read once per renderer; a pack change rebuilds the renderer.
     */
    private static boolean splitActive() {
        if (!TRANS_SPLIT || RenderBackendFactory.get().getType()
                == me.cortex.voxy.client.core.gpu.BackendType.OPENGL) return false;
        if (SPLIT_ALL) return true;
        String nc = System.getenv("VOXY_TRANS_NEAR_CULL");
        return (nc == null || !"0".equals(nc.trim()))
                && me.cortex.voxy.client.core.util.IrisUtil.vxContractActive();
    }

    /**
     * One heap-compact instance set: pos→idx map, idx→pos mirror, and the
     * GPU-side ivec2 position buffer the outline shader reads. The primary
     * set carries sections WITH opaque geometry (every section on GL / with
     * the split off); the epsilon set carries translucent-only sections.
     */
    private static final class InstanceSet {
        final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
        long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
        IGpuBuffer posBuffer = RenderBackendFactory.get().createBuffer(INIT_MAX_CHUNK_COUNT * 8); // ivec2 per entry

        final LongOpenHashSet addQueue = new LongOpenHashSet();
        final LongOpenHashSet remQueue = new LongOpenHashSet();
        /**
         * Newly built sections Sodium has not put in a render list yet (Metal, see
         * {@link ChunkBoundRenderer#addSection(long, boolean, boolean)}). Never drained into
         * the mask directly: {@link #promote} / {@link #promoteAll} move them to addQueue.
         */
        final LongOpenHashSet deferQueue = new LongOpenHashSet();

        InstanceSet() {
            this.chunk2idx.defaultReturnValue(-1);
        }

        void add(long pos) {
            if (!this.remQueue.remove(pos)) {
                this.addQueue.add(pos);
            }
        }

        void addDeferred(long pos) {
            if (!this.remQueue.remove(pos)) {
                this.deferQueue.add(pos);
            }
        }

        boolean promote(long pos) {
            if (this.deferQueue.remove(pos)) {
                this.addQueue.add(pos);
                return true;
            }
            return false;
        }

        int promoteAll() {
            int n = this.deferQueue.size();
            if (n != 0) {
                this.addQueue.addAll(this.deferQueue);
                this.deferQueue.clear();
            }
            return n;
        }

        void remove(long pos) {
            if (!this.addQueue.remove(pos) && !this.deferQueue.remove(pos)) {
                this.remQueue.add(pos);
            }
        }

        /** Queued for add (now or deferred) or already resident — used to route removals/reclassifies. */
        boolean tracks(long pos) {
            return this.addQueue.contains(pos) || this.deferQueue.contains(pos) || this.chunk2idx.containsKey(pos);
        }

        void drainRemovals() {
            if (!this.remQueue.isEmpty()) {
                boolean wasEmpty = this.chunk2idx.isEmpty();
                this.remQueue.forEach(this::_remPos);
                this.remQueue.clear();
                if (!wasEmpty) UploadStream.INSTANCE.commit();
            }
        }

        void drainAdds() {
            if (!this.addQueue.isEmpty()) {
                this.addQueue.forEach(this::_addPos);
                this.addQueue.clear();
                UploadStream.INSTANCE.commit();
            }
        }

        private void _remPos(long pos) {
            int idx = this.chunk2idx.remove(pos);
            if (idx == -1) {
                Logger.warn("Chunk not in map: " + pos);
                return;
            }
            if (idx == this.chunk2idx.size()) {
                //Dont need to do anything as heap is already compact
                return;
            }
            if (this.idx2chunk[idx] != pos) {
                throw new IllegalStateException();
            }

            //Move last entry on heap to this index
            long ePos = this.idx2chunk[this.chunk2idx.size()];// since is already removed size is correct end idx
            if (this.chunk2idx.put(ePos, idx) == -1) {
                throw new IllegalStateException();
            }
            this.idx2chunk[idx] = ePos;

            //Put the end pos into the new idx
            this.put(idx, ePos);
        }

        private void _addPos(long pos) {
            if (this.chunk2idx.containsKey(pos)) {
                Logger.warn("Chunk already in map: " + pos);
                return;
            }
            this.ensureSize1();//Resize if needed

            int idx = this.chunk2idx.size();
            this.chunk2idx.put(pos, idx);
            this.idx2chunk[idx] = pos;

            this.put(idx, pos);
        }

        private void ensureSize1() {
            if (this.chunk2idx.size() < this.idx2chunk.length) return;
            //Commit any copies, ensures is synced to new buffer
            UploadStream.INSTANCE.commit();

            int size = (int) (this.idx2chunk.length * 1.5);
            Logger.info("Resizing chunk position buffer to: " + size);
            var old = this.posBuffer;
            this.posBuffer = RenderBackendFactory.get().createBuffer(size * 8L);
            // Cross-backend copy — the grow triggers on Metal too now that the
            // bound mask renders there. The GL implementation lowers to the same
            // glCopyNamedBufferSubData this used to call directly (DSA path).
            RenderBackendFactory.get().copyBufferSubData(old, this.posBuffer, 0, 0, old.size());
            old.free();
            var old2 = this.idx2chunk;
            this.idx2chunk = new long[size];
            System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
            // New buffer will be picked up by the next render()'s glBindBufferBase
            // call — no persistent shader-side binding to update anymore.
        }

        private void put(int idx, long pos) {
            long ptr2 = UploadStream.INSTANCE.upload(this.posBuffer, 8L * idx, 8);
            //Need to do it in 2 parts because ivec2 is 2 parts
            MemoryUtil.memPutInt(ptr2, (int) (pos & 0xFFFFFFFFL)); ptr2 += 4;
            MemoryUtil.memPutInt(ptr2, (int) ((pos >>> 32) & 0xFFFFFFFFL));
        }

        void free() {
            this.posBuffer.free();
        }
    }

    private final InstanceSet primary = new InstanceSet();
    /** Trans-only sections (coverage epsilon). Null when the split is off (GL / kill switch). */
    private final InstanceSet epsSet;

    private final IGpuBuffer uniformBuffer = RenderBackendFactory.get().createBuffer(128);
    /** Separate 128-byte SceneUniform for the epsilon draw (section.w = its own count). */
    private final IGpuBuffer epsUniformBuffer;

    private final IGpuPipeline rasterPipeline;
    /** Coverage-epsilon variant (outline.fsh + VOXY_BOUND_EPS); null when the split is off. */
    private final IGpuPipeline epsPipeline;
    /** Cached GL program id for the raw glUseProgram path; 0 on non-GL backends. */
    private final int glProgram;

    /**
     * Round 23: static mirror of Sodium's built-section set, maintained by
     * MixinRenderSectionManager independent of renderer lifetime. A Voxy
     * renderer reload (pack toggle / config) constructs a FRESH
     * ChunkBoundRenderer, but Sodium's already-built sections never
     * re-transition, so the mask stayed empty until sections rebuilt —
     * during which the SOLID-head LOD depth inject stomped every real
     * terrain pixel. New instances seed their addQueue from this mirror.
     * Cleared when Sodium recreates its RenderSectionManager (level/render-
     * distance change) so stale entries can't mask-discard LODs over
     * chunks Sodium no longer renders.
     *
     * Trans split: kept as TWO sets so re-seeds preserve each section's
     * opaque/trans-only class.
     */
    private static final LongOpenHashSet MIRROR_OPAQUE = new LongOpenHashSet();
    private static final LongOpenHashSet MIRROR_TRANS_ONLY = new LongOpenHashSet();

    public static synchronized void mirrorAdd(long pos, boolean hasOpaque) {
        if (hasOpaque) {
            MIRROR_TRANS_ONLY.remove(pos);
            MIRROR_OPAQUE.add(pos);
        } else {
            MIRROR_OPAQUE.remove(pos);
            MIRROR_TRANS_ONLY.add(pos);
        }
    }

    public static synchronized void mirrorRemove(long pos) {
        MIRROR_OPAQUE.remove(pos);
        MIRROR_TRANS_ONLY.remove(pos);
    }

    public static synchronized void mirrorReset() {
        MIRROR_OPAQUE.clear();
        MIRROR_TRANS_ONLY.clear();
    }

    /**
     * A section REBUILT in place (built→built, no flag transition) may flip
     * between opaque and trans-only (sand pillar removed from a water
     * section, …). Updates the mirror and reports whether the class changed
     * so the caller can re-route the live instance sets.
     */
    public static synchronized boolean mirrorReclass(long pos, boolean hasOpaque) {
        boolean inOpaque = MIRROR_OPAQUE.contains(pos);
        boolean inTrans = MIRROR_TRANS_ONLY.contains(pos);
        if (!inOpaque && !inTrans) {
            return false; // not tracked (never transitioned to built through the mixin)
        }
        if (hasOpaque == inOpaque) {
            return false;
        }
        mirrorAdd(pos, hasOpaque);
        return true;
    }

    private void seedFromMirror() {
        synchronized (ChunkBoundRenderer.class) {
            this.primary.addQueue.addAll(MIRROR_OPAQUE);
            if (this.epsSet != null) {
                this.epsSet.addQueue.addAll(MIRROR_TRANS_ONLY);
            } else {
                this.primary.addQueue.addAll(MIRROR_TRANS_ONLY);
            }
        }
    }

    /**
     * Kill switch for the Sodium-matched mask radius (Metal). VOXY_BOUND_SODIUM_RADIUS=0 restores
     * max(render distance, 320 blocks), the radius M9 Phase 4 copied from upstream's LOD fog code.
     */
    private static final boolean SODIUM_RADIUS = !"0".equals(
            String.valueOf(System.getenv("VOXY_BOUND_SODIUM_RADIUS")).trim());

    /**
     * The distance Sodium culled its sections at in its last render-list build
     * (RenderSectionManager.getSearchDistance, captured by MixinRenderSectionManager); -1 until the
     * first build. Render thread only; volatile for safety.
     */
    private static volatile float sodiumSearchDistance = -1f;

    public static void noteSodiumSearchDistance(float blocks) {
        sodiumSearchDistance = blocks;
    }

    /**
     * Radius (blocks) of outline.vsh's shouldRender cull on the GL path, kept as it was: the GL
     * backend stays byte-identical.
     */
    private static float legacyMaskRadius() {
        return Math.max(Minecraft.getInstance().gameRenderer.getRenderDistance(), 20 * 16);
    }

    /**
     * Radius (blocks) of the mask's per-section distance cull on Metal. It has to be the distance
     * Sodium culls its own sections at: outline.vsh's shouldRender already mirrors Sodium's
     * OcclusionCuller.isWithinRenderDistance test (the same 1-block pad, the same cylinder), only the
     * radius differed.
     *
     * The old radius, max(render distance, 320), covered built sections Sodium does not draw whenever
     * the render distance is under 20 chunks: sections stay built after the camera moves away, until
     * their chunk unloads at about RD + 1 chunks. At RD 16 (the launcher setup) the ring from 256 to
     * ~290 blocks was masked, so the LODs were discarded and Sodium drew nothing: holes with no
     * texture on the ground at the vanilla/LOD border, behind the player and to the sides, and the
     * grey wall plus sky-coloured strip seen over the ocean (H9). Sodium also shortens its search
     * distance to the fog end when fog occlusion is on and the fog is opaque (under water, nether,
     * blindness); the captured value covers that too. Upstream's BoundRenderer uses
     * getEffectiveRenderDistance()*16, which is the fallback before Sodium's first build.
     */
    private float metalMaskRadius() {
        float rdBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
        float radius;
        String source;
        if (!SODIUM_RADIUS) {
            radius = legacyMaskRadius();
            source = "legacy max(RD, 320), VOXY_BOUND_SODIUM_RADIUS=0";
        } else if (sodiumSearchDistance > 0f) {
            radius = sodiumSearchDistance;
            source = "Sodium search distance";
        } else {
            radius = rdBlocks;
            source = "render distance (no Sodium build yet)";
        }
        this.radiusLogFrame++;
        if (Math.abs(radius - this.loggedRadius) > 1f && this.radiusLogFrame >= 120) {
            this.radiusLogFrame = 0;
            this.loggedRadius = radius;
            Logger.info("[Metal-LODTEST] bound-mask radius=" + radius + " blocks (" + source
                    + "; RD " + rdBlocks + ", old max(RD, 320)=" + legacyMaskRadius() + ")");
        }
        return radius;
    }

    /** Last radius logged by {@link #metalMaskRadius} and the frames since (throttle: one line per ~2s). */
    private float loggedRadius = -1f;
    private int radiusLogFrame = 120;

    /** H1 kill switch: VOXY_BOUND_RESET_QUEUES=0 leaves the add/removal queues alone in {@link #reset}. */
    private static final boolean RESET_QUEUES = !"0".equals(
            String.valueOf(System.getenv("VOXY_BOUND_RESET_QUEUES")).trim());
    /** H1 queue drop: Metal only, the GL backend keeps its reset. */
    private final boolean resetQueues = RESET_QUEUES && RenderBackendFactory.get().getType()
            != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;

    /** H2 kill switch: VOXY_BOUND_MIRROR_ALWAYS=0 stops mirror tracking while no Voxy renderer exists. */
    private static final boolean MIRROR_ALWAYS = !"0".equals(
            String.valueOf(System.getenv("VOXY_BOUND_MIRROR_ALWAYS")).trim());
    /**
     * H2: set once a Metal mask renderer has existed. From then on MixinRenderSectionManager keeps the
     * static mirror in step with Sodium even while no Voxy renderer exists ('Enable Voxy' OFF): the
     * mirror is only read to seed the next renderer, so it must not freeze half-built when the renderer
     * goes away. Never set on GL, where the old behaviour stays byte-identical.
     */
    private static volatile boolean mirrorTracksWithoutRenderer = false;

    public static boolean mirrorTracksWithoutRenderer() {
        return mirrorTracksWithoutRenderer;
    }

    /** Kill switch for the deferred add of newly uploaded sections (see addSection). */
    private static final boolean ADD_DEFER = !"0".equals(
            String.valueOf(System.getenv("VOXY_BOUND_ADD_DEFER")).trim());

    /** Defer uploads until Sodium lists them: Metal only, the GL backend keeps its order. */
    private final boolean deferAdds = ADD_DEFER && RenderBackendFactory.get().getType()
            != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;

    /** Deferred adds promoted by Sodium's immediate present / by its next list build, since the last log. */
    private int promotedNow, promotedAtBuild;
    private int deferLogCountdown = 0;

    /** Throttle for the split-counts diagnostic log (~10s at 60fps). */
    private int splitLogCountdown = 0;

    private final AbstractRenderPipeline pipeline;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.pipeline = pipeline;
        this.epsSet = splitActive() ? new InstanceSet() : null;
        if (this.epsSet == null && TRANS_SPLIT && RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            Logger.info("[Metal-LODTEST] bound-mask trans split OFF: no vx-contract shader pack (or"
                    + " VOXY_TRANS_NEAR_CULL=0), so the translucent near-cull it relies on is not compiled;"
                    + " every built section writes real depth. VOXY_BOUND_TRANS_SPLIT=all forces it on");
        }
        this.seedFromMirror();
        if (RenderBackendFactory.get().getType() != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            if (MIRROR_ALWAYS && !mirrorTracksWithoutRenderer) {
                mirrorTracksWithoutRenderer = true;
                Logger.info("[Metal-LODTEST] bound-mask mirror tracks Sodium even with Voxy off (H2);"
                        + " VOXY_BOUND_MIRROR_ALWAYS=0 reverts");
            }
            Logger.info("[Metal-LODTEST] bound-mask new sections join " + (this.deferAdds
                    ? "when Sodium lists them (at once if it presents them in their upload frame, else at its"
                            + " next render-list build); VOXY_BOUND_ADD_DEFER=0 reverts"
                    : "in their upload frame (Round 23 order, VOXY_BOUND_ADD_DEFER=0)"));
        }

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            vert = vert + "\n\n\n" + taa;
        }
        String frag = ShaderLoader.parse("voxy:chunkoutline/outline.fsh");

        Map<String, String> defines = new LinkedHashMap<>();
        if (taa != null) defines.put("TAA", "");

        // Baked pipeline state is Metal-effective only — the GL render() path
        // sets raw GL state around its draws and GlGraphicsPipeline ignores
        // the desc state entirely. Depth GREATER + write against the
        // 0.0-cleared bound target keeps the FARTHEST chunk-AABB face per
        // pixel (the GL path's "reverse depth buffer" GL_GREATER setup).
        // NO_CULL instead of the GL path's CW-flip+cull-back: the transpile
        // pipeline does NOT y-flip gl_Position (the IOSurface compositor
        // flips at blit time), which inverts window-space winding parity on
        // Metal vs GL — a fixed cull direction would keep the wrong face
        // set. With depth GREATER the no-cull result is identical to
        // back-face-only (max depth per pixel IS the back face), at the cost
        // of rasterizing both faces of each 16³ box.
        PipelineState metalState = new PipelineState(
                new PipelineState.DepthState(true, true, PipelineState.CompareOp.GREATER),
                PipelineState.BlendState.OPAQUE,
                PipelineState.RasterState.NO_CULL);
        this.rasterPipeline = RenderBackendFactory.get().createGraphicsPipeline(new GraphicsPipelineDesc(
                vert, frag, defines,
                null, null,           // no MSL — runtime compiler produces on Metal
                null, null,           // no SPIRV — runtime compiler produces on Vulkan
                0,                    // no color format — depth-only pass (precedent: HiZBuffer.blit)
                VertexLayout.EMPTY,   // gl_VertexID + gl_InstanceID + gl_BaseInstance drive the math
                metalState,           // GL ignores this; render() manages raw GL state itself
                "ChunkBoundRenderer.raster"));
        this.glProgram = (this.rasterPipeline instanceof GlGraphicsPipeline gp) ? gp.program() : 0;

        if (this.epsSet != null) {
            Map<String, String> epsDefines = new LinkedHashMap<>(defines);
            epsDefines.put("VOXY_BOUND_EPS", "");
            this.epsPipeline = RenderBackendFactory.get().createGraphicsPipeline(new GraphicsPipelineDesc(
                    vert, frag, epsDefines,
                    null, null,
                    null, null,
                    0,
                    VertexLayout.EMPTY,
                    metalState,
                    "ChunkBoundRenderer.rasterEps"));
            this.epsUniformBuffer = RenderBackendFactory.get().createBuffer(128);
            Logger.info("[Metal-LODTEST] bound-mask trans split ON (translucent-only built"
                    + " sections write coverage epsilon 1e-5: the trans near-cull still sees"
                    + " them as covered, but the opaque depth-bound test no longer discards"
                    + " the LOD seafloor behind their water — the near pale panes);"
                    + " VOXY_BOUND_TRANS_SPLIT=0 reverts");
        } else {
            this.epsPipeline = null;
            this.epsUniformBuffer = null;
        }
    }

    public void addSection(long pos, boolean hasOpaque) {
        this.addSection(pos, hasOpaque, false);
    }

    /**
     * @param fromUpload the section became built in Sodium's uploadChunks
     *        (processChunkBuildResults). Sodium 0.8.1 builds each frame's render
     *        list BEFORE that upload, and its collector only lists sections that
     *        are already built, so such a section is drawn in its upload frame only
     *        if Sodium presents it at once (blocking job, or within 64 blocks and
     *        visible: isSectionImmediatePresentationCandidate). Any other one is
     *        drawn from Sodium's next render-list build. On Metal it waits in the
     *        defer queue until {@link #promoteSection} (Sodium presented it now) or
     *        {@link #promoteAllSections} (Sodium is building a list), so the mask
     *        never covers a section in a frame Sodium does not draw it. Round 23
     *        added every new section in its upload frame: far ones were masked one
     *        frame early, a hole showing what lies below (grey squares on water and
     *        ice along the loading front, ahead of a moving player).
     *        VOXY_BOUND_ADD_DEFER=0 restores that order; GL never defers.
     */
    public void addSection(long pos, boolean hasOpaque, boolean fromUpload) {
        InstanceSet set = (this.epsSet != null && !hasOpaque) ? this.epsSet : this.primary;
        if (fromUpload && this.deferAdds) {
            set.addDeferred(pos);
        } else {
            set.add(pos);
        }
    }

    /** Sodium presented this freshly uploaded section in the current frame's list. */
    public void promoteSection(long pos) {
        if (!this.deferAdds) return;
        if (this.primary.promote(pos) || (this.epsSet != null && this.epsSet.promote(pos))) {
            this.promotedNow++;
        }
    }

    /** Sodium is building a render list: every deferred section is in it from now on. */
    public void promoteAllSections() {
        if (!this.deferAdds) return;
        this.promotedAtBuild += this.primary.promoteAll();
        if (this.epsSet != null) this.promotedAtBuild += this.epsSet.promoteAll();
    }

    public void removeSection(long pos) {
        if (this.epsSet != null && this.epsSet.tracks(pos)) {
            this.epsSet.remove(pos);
        } else {
            this.primary.remove(pos);
        }
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        this.primary.drainRemovals();

        this.uploadSceneUniform(this.uniformBuffer, viewport, false, this.primary.chunk2idx.size(), legacyMaskRadius());


        {
            //need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        }

        glBindVertexArray(RenderBackendFactory.get().getStaticVAO());
        viewport.depthBoundingBuffer.bind();
        // M9 transitional: bind/draw stay raw GL because the surrounding
        // runPipeline path is GL-only until IOSurface bridge lands. The shader
        // pipeline itself is now backend-agnostic via createGraphicsPipeline.
        if (this.glProgram != 0) glUseProgram(this.glProgram);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        glBindBufferBase(GL_UNIFORM_BUFFER, SCENE_UNIFORM_BINDING, this.uniformBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CHUNK_POS_BINDING, this.primary.posBuffer.id());
        this.pipeline.bindUniforms();

        //Batch the draws into groups of size 32
        int count = this.primary.chunk2idx.size();
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count / 32);
        }
        if (count % 32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count % 32), GL_UNSIGNED_BYTE, 0, 1, (count / 32) * 32);
        }

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(GL_LEQUAL);

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }


        this.primary.drainAdds();
    }

    /**
     * Packs outline.vsh's std140 SceneUniform with EXPLICIT offsets:
     * mat4 MVP @0, ivec4 section @64 (xyz = camera section origin in blocks,
     * w = live chunk count for the shader's tail guard), vec4 negInnerSec @80
     * (xyz = camera offset inside the section, w = cull render distance);
     * bytes 96..128 zeroed. The previous chained-pointer packing started
     * negInnerSec at offset 76 and never wrote offset 92 — the shader's
     * negInnerSec read (y, z, renderDistance, stale-ring-memory), so the
     * shouldRender cull radius was garbage. Latent upstream bug; the fix
     * applies to GL too (render() shares this buffer).
     *
     * @param metalNdcRemap apply the shared GL→Metal NDC-z remap to the MVP —
     *        pass true ONLY from the Metal path so this pass and the LOD
     *        terrain pass keep the same depth convention (both gate on
     *        {@link MetalMvpUtil#METAL_NDC_REMAP}).
     * @param maskRadius shouldRender's cull radius in blocks (negInnerSec.w):
     *        {@link #legacyMaskRadius} on GL, {@link #metalMaskRadius} on Metal.
     */
    private void uploadSceneUniform(IGpuBuffer target, Viewport<?> viewport, boolean metalNdcRemap, int count,
                                    float maskRadius) {
        long ptr = UploadStream.INSTANCE.upload(target, 0, 128);
        MemoryUtil.memSet(ptr, 0, 128);

        int sx = net.minecraft.util.Mth.floor(viewport.cameraX) & ~31;
        int sy = net.minecraft.util.Mth.floor(viewport.cameraY) & ~31;
        int sz = net.minecraft.util.Mth.floor(viewport.cameraZ) & ~31;
        MemoryUtil.memPutInt(ptr + 64, sx);
        MemoryUtil.memPutInt(ptr + 68, sy);
        MemoryUtil.memPutInt(ptr + 72, sz);
        MemoryUtil.memPutInt(ptr + 76, count);

        var negInnerSec = new Vector3f(
                (float) (viewport.cameraX - sx),
                (float) (viewport.cameraY - sy),
                (float) (viewport.cameraZ - sz));
        JomlCompat.put(negInnerSec, ptr + 80);
        MemoryUtil.memPutFloat(ptr + 92, maskRadius);

        var mvp = viewport.MVP.translate(negInnerSec.negate(), new Matrix4f());
        if (metalNdcRemap && MetalMvpUtil.METAL_NDC_REMAP) {
            MetalMvpUtil.applyNdcRemap(mvp);
        }
        JomlCompat.put(mvp, ptr);

        UploadStream.INSTANCE.commit();
    }

    /**
     * Metal port of {@link #render} — same remove-queue drain, same
     * SceneUniform upload (plus the shared NDC remap so this pass and the
     * LOD terrain pass agree on depth convention), then a depth-only render
     * pass rasterizing the loaded-chunk AABBs into
     * {@code viewport.depthBoundingBuffer}. quads.frag's depth-bound test
     * (binding 2) discards LOD fragments nearer than this mask, so LOD never
     * renders inside MC's loaded-chunk volume.
     *
     * One instanced draw of ceil(count/32) batches over the uint16 cube
     * index buffer; the shader-side section.w guard collapses the last
     * batch's over-draw slots (no baseInstance tail draw — Metal's
     * base_instance propagation is unreliable, see VOXY_METAL_BI_FIX).
     * Depth/cull state is baked into the pipeline (GREATER + write against
     * the 0.0 clear keeps the farthest AABB face per pixel).
     *
     * Trans split: a second draw over the trans-only set with the
     * coverage-epsilon pipeline, into the SAME pass/attachment — GREATER
     * keeps any real (opaque-section) depth over the epsilon.
     */
    public void renderMetal(Viewport<?> viewport, RenderBackend backend) {
        if (viewport.width <= 0 || viewport.height <= 0) return; // mirrors runPipelineMetal's guard
        this.primary.drainRemovals();
        if (this.epsSet != null) this.epsSet.drainRemovals();
        // Round 23: drain the ADD queue BEFORE the mask draw, not after.
        // Adds are enqueued during Sodium's setupTerrain (section upload),
        // which runs earlier in the same frame — draining after the draw
        // meant every freshly built section was rendered by Sodium for >=1
        // frame while ABSENT from the mask, so the SOLID-head LOD depth
        // inject stomped its pixels (real-terrain flicker during camera
        // movement; the dominant underwater x-ray trigger).
        // H14 follow-up: on Metal a section built during uploadChunks reaches
        // addQueue only once Sodium lists it (addSection / promote*), so
        // draining here is right for every add: immediate presents and the
        // sections Sodium's list build of this frame took in.
        this.primary.drainAdds();
        if (this.epsSet != null) this.epsSet.drainAdds();

        int count = this.primary.chunk2idx.size();
        int epsCount = this.epsSet != null ? this.epsSet.chunk2idx.size() : 0;
        if (this.deferAdds && this.deferLogCountdown-- <= 0) {
            this.deferLogCountdown = 600; // ~10s at 60fps
            if (this.promotedNow + this.promotedAtBuild > 0) {
                Logger.info("[Metal-LODTEST] bound-mask deferred adds: presented at once=" + this.promotedNow
                        + " joined at Sodium's next list build=" + this.promotedAtBuild
                        + " still waiting=" + (this.primary.deferQueue.size()
                        + (this.epsSet != null ? this.epsSet.deferQueue.size() : 0)));
            }
            this.promotedNow = 0;
            this.promotedAtBuild = 0;
        }
        // Also without the trans split (no pack): transOnly=0. vx_mascara and the H1/H2
        // checks read the mask size from this line.
        if (this.splitLogCountdown-- <= 0) {
            this.splitLogCountdown = 600; // ~10s at 60fps
            Logger.info("[Metal-LODTEST] bound-mask split counts: opaque=" + count
                    + " transOnly=" + epsCount);
        }
        float maskRadius = this.metalMaskRadius();
        this.uploadSceneUniform(this.uniformBuffer, viewport, true, count, maskRadius);
        if (epsCount > 0) {
            this.uploadSceneUniform(this.epsUniformBuffer, viewport, true, epsCount, maskRadius);
        }

        try (RenderEncoder encoder = backend.beginRenderPass(boundDepthPass(viewport))) {
            if (count > 0) {
                encoder.setPipeline(this.rasterPipeline);
                encoder.setViewport(0, 0, viewport.width, viewport.height, 0, 1);
                encoder.setBuffer(SCENE_UNIFORM_BINDING, this.uniformBuffer, 0);
                encoder.setBuffer(CHUNK_POS_BINDING, this.primary.posBuffer, 0);
                encoder.bindIndexBuffer(SharedIndexBuffer.INSTANCE_BB_SHORT.getBuffer(),
                        RenderEncoder.INDEX_TYPE_UINT16, 0);
                encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,
                        6 * 2 * 3 * 32, (count + 31) / 32, 0, 0, 0);
            }
            if (epsCount > 0) {
                encoder.setPipeline(this.epsPipeline);
                encoder.setViewport(0, 0, viewport.width, viewport.height, 0, 1);
                encoder.setBuffer(SCENE_UNIFORM_BINDING, this.epsUniformBuffer, 0);
                encoder.setBuffer(CHUNK_POS_BINDING, this.epsSet.posBuffer, 0);
                encoder.bindIndexBuffer(SharedIndexBuffer.INSTANCE_BB_SHORT.getBuffer(),
                        RenderEncoder.INDEX_TYPE_UINT16, 0);
                encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,
                        6 * 2 * 3 * 32, (epsCount + 31) / 32, 0, 0, 0);
            }
        }

        exportBoundMaskMetal(viewport, backend);
    }

    /**
     * Metal equivalent of the GL gate's {@code depthBoundingBuffer.clear(0)}
     * branch — a load-action-only pass clearing the bound mask to 0.0
     * ("no bound; never discard"), no draws. Pattern:
     * HiZBuffer.zeroFillPyramid.
     */
    public void clearMetal(Viewport<?> viewport) {
        if (viewport.width <= 0 || viewport.height <= 0) return; // mirrors runPipelineMetal's guard
        try (RenderEncoder ignored = RenderBackendFactory.get().beginRenderPass(boundDepthPass(viewport))) {
            // no draws — the CLEAR load action does the fill
        }
        exportBoundMaskMetal(viewport, RenderBackendFactory.get());
    }

    /**
     * Round 20: blit the bound mask's depth into a plain buffer for
     * quads.frag's VOXY_METAL_BOUND_SSBO read. Sampling the depth texture
     * directly silently reads ZEROS on Metal (texture2d&lt;float&gt; vs
     * depth-format mismatch — same bug class as the round-18 Iris depth
     * export), which left the bound test inert since M13 chunk 3: LODs drew
     * inside the loaded-chunk volume, and once the Iris inject started
     * writing real depth they stomped the pack's terrain depth test
     * ("only a few blocks textured", underwater cave X-ray under BSL).
     * Encoder order on the active command buffer = bound pass → this blit →
     * LOD pass, so the LOD fragments read this frame's mask. Layout: uint
     * width + 12 pad bytes, floats at offset 16.
     */
    private static void exportBoundMaskMetal(Viewport<?> viewport, RenderBackend backend) {
        if (!(backend instanceof me.cortex.voxy.client.core.metal.MetalRenderBackend mrb)) {
            return;
        }
        long size = 16L + (long) viewport.width * viewport.height * 4L;
        var buf = viewport.metalBoundReadBuffer;
        if (buf == null || buf.size() != size) {
            if (buf != null) buf.free();
            buf = backend.createBuffer(size);
            viewport.metalBoundReadBuffer = buf;
            // Width header, written once per (re)alloc — Shared storage is
            // CPU-visible and the GPU only ever writes from offset 16 on.
            org.lwjgl.system.MemoryUtil.memPutInt(
                    ((me.cortex.voxy.client.core.metal.MetalBuffer) buf).getContentsPtr(),
                    viewport.width);
        }
        mrb.copyTextureToBuffer(viewport.depthBoundingBuffer.getDepthTex(), buf,
                viewport.width, viewport.height, 16);
    }

    private static RenderPassDesc boundDepthPass(Viewport<?> viewport) {
        return RenderPassDesc.builder(viewport.width, viewport.height)
                .depthAttachment(viewport.depthBoundingBuffer.getDepthTex(), 0,
                        RenderPassDesc.LoadAction.CLEAR,
                        RenderPassDesc.StoreAction.STORE, 0.0f)
                .build();
    }

    public void reset() {
        this.primary.chunk2idx.clear();
        if (this.epsSet != null) this.epsSet.chunk2idx.clear();
        if (this.resetQueues) {
            // H1 (Metal): reset() runs when Sodium builds a NEW RenderSectionManager
            // (renderer reload: Apply with REQUIRES_RENDERER_RELOAD, F3+A, shader
            // toggle, RD change, world join), which then re-announces every section
            // it builds. LevelRenderer.allChanged runs Voxy's reload (order 900)
            // BEFORE Sodium's (1000), so the fresh Voxy renderer had already queued
            // its mirror seed, which survived this reset: every re-announced section
            // hit "Chunk already in map" (~10 000 warnings per Apply), and after an RD
            // drop the seed kept the old, wider ring in the mask over terrain Sodium
            // no longer draws. Drop every queue with the resident set: Sodium's own
            // announcements rebuild the mask. Without a Sodium reload (/voxy reload,
            // 'Enable Voxy') reset() is not called and the seed stays, which is what
            // Round 23 built it for. VOXY_BOUND_RESET_QUEUES=0 reverts.
            int dropped = this.primary.addQueue.size() + this.primary.deferQueue.size();
            int droppedRem = this.primary.remQueue.size();
            this.primary.addQueue.clear();
            this.primary.deferQueue.clear();
            this.primary.remQueue.clear();
            if (this.epsSet != null) {
                dropped += this.epsSet.addQueue.size() + this.epsSet.deferQueue.size();
                droppedRem += this.epsSet.remQueue.size();
                this.epsSet.addQueue.clear();
                this.epsSet.deferQueue.clear();
                this.epsSet.remQueue.clear();
            }
            if (dropped + droppedRem > 0) {
                Logger.info("[Metal-LODTEST] bound-mask reset by a new Sodium section manager: dropped " + dropped
                        + " queued adds (mirror seed) and " + droppedRem + " removals; Sodium re-announces its"
                        + " sections (H1); VOXY_BOUND_RESET_QUEUES=0 reverts");
            }
        }
    }

    public void free() {
        this.rasterPipeline.close();
        this.uniformBuffer.free();
        this.primary.free();
        if (this.epsSet != null) {
            this.epsPipeline.close();
            this.epsUniformBuffer.free();
            this.epsSet.free();
        }
    }
}
