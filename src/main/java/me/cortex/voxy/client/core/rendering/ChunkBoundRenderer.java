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
 */
public class ChunkBoundRenderer {
    private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;

    /** UBO binding for SceneUniform — matches `layout(binding=0)` in outline.vsh. */
    private static final int SCENE_UNIFORM_BINDING = 0;
    /** SSBO binding for the chunk-position array. */
    private static final int CHUNK_POS_BINDING = 1;

    private IGpuBuffer chunkPosBuffer = RenderBackendFactory.get().createBuffer(INIT_MAX_CHUNK_COUNT * 8); // ivec2 per entry
    private final IGpuBuffer uniformBuffer = RenderBackendFactory.get().createBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];

    private final IGpuPipeline rasterPipeline;
    /** Cached GL program id for the raw glUseProgram path; 0 on non-GL backends. */
    private final int glProgram;

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();

    private final AbstractRenderPipeline pipeline;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.chunk2idx.defaultReturnValue(-1);
        this.pipeline = pipeline;

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
    }

    public void addSection(long pos) {
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (!this.addQueue.remove(pos)) {
            this.remQueue.add(pos);
        }
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);
            this.remQueue.clear();
            if (!wasEmpty) UploadStream.INSTANCE.commit();
        }

        this.uploadSceneUniform(viewport, false);


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
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CHUNK_POS_BINDING, this.chunkPosBuffer.id());
        this.pipeline.bindUniforms();

        //Batch the draws into groups of size 32
        int count = this.chunk2idx.size();
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


        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
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
    private void uploadSceneUniform(Viewport<?> viewport, boolean metalNdcRemap) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        MemoryUtil.memSet(ptr, 0, 128);

        int sx = net.minecraft.util.Mth.floor(viewport.cameraX) & ~31;
        int sy = net.minecraft.util.Mth.floor(viewport.cameraY) & ~31;
        int sz = net.minecraft.util.Mth.floor(viewport.cameraZ) & ~31;
        MemoryUtil.memPutInt(ptr + 64, sx);
        MemoryUtil.memPutInt(ptr + 68, sy);
        MemoryUtil.memPutInt(ptr + 72, sz);
        MemoryUtil.memPutInt(ptr + 76, this.chunk2idx.size());

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
     */
    public void renderMetal(Viewport<?> viewport, RenderBackend backend) {
        if (viewport.width <= 0 || viewport.height <= 0) return; // mirrors runPipelineMetal's guard
        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);
            this.remQueue.clear();
            if (!wasEmpty) UploadStream.INSTANCE.commit();
        }

        this.uploadSceneUniform(viewport, true);

        int count = this.chunk2idx.size();
        try (RenderEncoder encoder = backend.beginRenderPass(boundDepthPass(viewport))) {
            if (count > 0) {
                encoder.setPipeline(this.rasterPipeline);
                encoder.setViewport(0, 0, viewport.width, viewport.height, 0, 1);
                encoder.setBuffer(SCENE_UNIFORM_BINDING, this.uniformBuffer, 0);
                encoder.setBuffer(CHUNK_POS_BINDING, this.chunkPosBuffer, 0);
                encoder.bindIndexBuffer(SharedIndexBuffer.INSTANCE_BB_SHORT.getBuffer(),
                        RenderEncoder.INDEX_TYPE_UINT16, 0);
                encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,
                        6 * 2 * 3 * 32, (count + 31) / 32, 0, 0, 0);
            }
        }

        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
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
    }

    private static RenderPassDesc boundDepthPass(Viewport<?> viewport) {
        return RenderPassDesc.builder(viewport.width, viewport.height)
                .depthAttachment(viewport.depthBoundingBuffer.getDepthTex(), 0,
                        RenderPassDesc.LoadAction.CLEAR,
                        RenderPassDesc.StoreAction.STORE, 0.0f)
                .build();
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
        var old = this.chunkPosBuffer;
        this.chunkPosBuffer = RenderBackendFactory.get().createBuffer(size * 8L);
        // Cross-backend copy — the grow triggers on Metal too now that the
        // bound mask renders there. The GL implementation lowers to the same
        // glCopyNamedBufferSubData this used to call directly (DSA path).
        RenderBackendFactory.get().copyBufferSubData(old, this.chunkPosBuffer, 0, 0, old.size());
        old.free();
        var old2 = this.idx2chunk;
        this.idx2chunk = new long[size];
        System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
        // New buffer will be picked up by the next render()'s glBindBufferBase
        // call — no persistent shader-side binding to update anymore.
    }

    private void put(int idx, long pos) {
        long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L * idx, 8);
        //Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr2, (int) (pos & 0xFFFFFFFFL)); ptr2 += 4;
        MemoryUtil.memPutInt(ptr2, (int) ((pos >>> 32) & 0xFFFFFFFFL));
    }

    public void reset() {
        this.chunk2idx.clear();
    }

    public void free() {
        this.rasterPipeline.close();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
