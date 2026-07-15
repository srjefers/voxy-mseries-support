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

    /** The split only exists off-GL: the GL backend keeps the single-set path byte-identical. */
    private static boolean splitActive() {
        return TRANS_SPLIT && RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
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

        InstanceSet() {
            this.chunk2idx.defaultReturnValue(-1);
        }

        void add(long pos) {
            if (!this.remQueue.remove(pos)) {
                this.addQueue.add(pos);
            }
        }

        void remove(long pos) {
            if (!this.addQueue.remove(pos)) {
                this.remQueue.add(pos);
            }
        }

        /** Queued for add or already resident — used to route removals/reclassifies. */
        boolean tracks(long pos) {
            return this.addQueue.contains(pos) || this.chunk2idx.containsKey(pos);
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

    /** Throttle for the split-counts diagnostic log (~10s at 60fps). */
    private int splitLogCountdown = 0;

    private final AbstractRenderPipeline pipeline;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.pipeline = pipeline;
        this.epsSet = splitActive() ? new InstanceSet() : null;
        this.seedFromMirror();

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
        if (this.epsSet != null && !hasOpaque) {
            this.epsSet.add(pos);
        } else {
            this.primary.add(pos);
        }
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

        this.uploadSceneUniform(this.uniformBuffer, viewport, false, this.primary.chunk2idx.size());


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
     */
    private void uploadSceneUniform(IGpuBuffer target, Viewport<?> viewport, boolean metalNdcRemap, int count) {
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
        negInnerSec.getToAddress(ptr + 80);
        float renderDistance = Math.max(Minecraft.getInstance().gameRenderer.getRenderDistance(), 20 * 16);
        MemoryUtil.memPutFloat(ptr + 92, renderDistance);

        var mvp = viewport.MVP.translate(negInnerSec.negate(), new Matrix4f());
        if (metalNdcRemap && MetalMvpUtil.METAL_NDC_REMAP) {
            MetalMvpUtil.applyNdcRemap(mvp);
        }
        mvp.getToAddress(ptr);

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
        this.primary.drainAdds();
        if (this.epsSet != null) this.epsSet.drainAdds();

        int count = this.primary.chunk2idx.size();
        int epsCount = this.epsSet != null ? this.epsSet.chunk2idx.size() : 0;
        if (this.epsSet != null && this.splitLogCountdown-- <= 0) {
            this.splitLogCountdown = 600; // ~10s at 60fps
            Logger.info("[Metal-LODTEST] bound-mask split counts: opaque=" + count
                    + " transOnly=" + epsCount);
        }
        this.uploadSceneUniform(this.uniformBuffer, viewport, true, count);
        if (epsCount > 0) {
            this.uploadSceneUniform(this.epsUniformBuffer, viewport, true, epsCount);
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
