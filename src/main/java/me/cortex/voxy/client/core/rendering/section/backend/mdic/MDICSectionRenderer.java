package me.cortex.voxy.client.core.rendering.section.backend.mdic;


import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.ComputeEncoder;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.MetalMvpUtil;
import me.cortex.voxy.client.core.rendering.util.NativeUniformWriter;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glGetBufferSubData;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL31C.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect;
import static me.cortex.voxy.client.core.gl.GLCompat.bindTextureUnit;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;

//Uses MDIC to render the sections
public class MDICSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> {
    public static final Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(MDICSectionRenderer.class);

    private static final int TRANSLUCENT_OFFSET = 400_000;//in draw calls
    private static final int TEMPORAL_OFFSET = 500_000;//in draw calls
    private static final int STATISTICS_BUFFER_BINDING = 8;
    /**
     * Terrain shaders. Two paths:
     *   - Iris-patched (legacy {@link Shader.Builder}, GL-only by definition since
     *     the Iris pipeline is GL-gated in RenderPipelineFactory).
     *   - Unpatched ({@link me.cortex.voxy.client.core.gpu.IGpuPipeline} via
     *     createGraphicsPipeline). On Metal this is the only path that ever
     *     runs; on GL it's used when no Iris pack is active.
     * Exactly one of each pair is non-null per opaque/translucent slot.
     */
    private final Shader terrainShader;
    private final Shader translucentTerrainShader;
    private final me.cortex.voxy.client.core.gpu.IGpuPipeline terrainPipeline;
    private final me.cortex.voxy.client.core.gpu.IGpuPipeline translucentTerrainPipeline;
    private final int terrainProgram;
    private final int translucentTerrainProgram;
    /**
     * NEAREST/CLAMP sampler for the chunk-bound depth mask at texture
     * binding 2 (quads.frag's {@code depthTex}; pattern: HiZBuffer's blit
     * sampler). Metal-only — the GL path binds the raw texture unit in
     * {@link #bindRenderingBuffers}; null on OpenGL.
     */
    private final me.cortex.voxy.client.core.gpu.IGpuSampler boundDepthSampler;

    // M9 migration: MDIC's 5 non-Iris-patched shaders (4 compute + 1 graphics)
    // now flow through RenderBackend.create*Pipeline so they compile cleanly on
    // Metal/Vulkan. terrainShader + translucentTerrainShader stay on the legacy
    // Shader.Builder path because they thread Iris's patchOpaqueShader /
    // patchTranslucentShader callbacks; that path is GL-only after the
    // RenderPipelineFactory gate (commit 1e2a1190). Bind/draw stays raw GL —
    // MDIC operates inside AbstractRenderPipeline's FBO context, not a
    // RenderEncoder.

    private final me.cortex.voxy.common.util.ResourceScope resources = new me.cortex.voxy.common.util.ResourceScope();
    private final me.cortex.voxy.client.core.gpu.RenderBackend backend = RenderBackendFactory.get();

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline commandGenPipeline;
    // M12 chunk 3: commandGen prepass is dispatched via ComputeEncoder; no
    // cached glProgram id needed.

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline prepPipeline;
    // M12 chunk 2: prep prepass is dispatched via ComputeEncoder; no cached
    // glProgram id needed (encoder pulls it from GlComputePipeline on GL,
    // MTLComputePipelineState on Metal).

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline cullPipeline;
    private final int cullProgram;

    /**
     * M12 chunk 5 Metal stub: substitutes for the depth-test-based cull pass
     * on backends that can't currently open a depth-only render pass against
     * MC's depth buffer. Writes `visibilityData[sid] = frameId | (1<<31)` for
     * every section in `indirectLookup`, so cmdgen queues all frustum-visible
     * sections for rendering (slower than real depth occlusion but
     * functionally correct). Allocated unconditionally — only dispatched
     * when the backend isn't OpenGL. Negligible memory cost; the alternative
     * (gating allocation behind a backend check) makes the class harder to
     * read for no real benefit.
     */
    private final me.cortex.voxy.client.core.gpu.IGpuPipeline forceAllVisiblePipeline;

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline prefixSumPipeline;
    // M12 chunk 1: prefixSum prepass is dispatched via ComputeEncoder, so it
    // does not need a cached glProgram id (the encoder pulls it from the
    // GlComputePipeline directly on GL; Metal uses the MTLComputePipelineState).

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline translucentGenPipeline;
    // M12 chunk 4: translucentGen prepass is dispatched via ComputeEncoder;
    // no cached glProgram id needed.

    /** {@code VOXY_CMDGEN_NOCLAMP=1} removes the cmdgen draw-command bound checks
     *  (caps injected as INT_MAX) so the pre-fix overflow can be A/B-reproduced
     *  on-device. Default: clamps ON. */
    private static boolean cmdgenNoClamp() {
        return "1".equals(System.getenv("VOXY_CMDGEN_NOCLAMP"));
    }

    private static java.util.Map<String, String> cmdgenDefines() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("TRANSLUCENT_WRITE_BASE", "1024");
        m.put("TEMPORAL_OFFSET", Integer.toString(TEMPORAL_OFFSET));
        m.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7");
        // Capacities of the opaque and temporal draw-command regions, mirroring
        // MDICViewport.drawCallBuffer = 5*4*(400_000 + 100_000 + 100_000):
        //   opaque    [0, TRANSLUCENT_OFFSET)               -> 400_000 cmds
        //   temporal  [TEMPORAL_OFFSET, TEMPORAL_OFFSET+100k) -> 100_000 cmds (the tail)
        // cmdgen.comp clamps its unbounded atomic write cursors to these so a
        // large visible-section render list can never scatter draw commands past
        // a region into the next slice or off the end of the buffer. Past-the-end
        // SSBO writes are undefined on Metal (device fault / cmd-buffer abort) and
        // corrupt the adjacent slices on GL; the CPU read side already saturates
        // its draw counts at the same caps (maxDrawCount / metalDrawCount).
        boolean noClamp = cmdgenNoClamp();
        m.put("MAX_OPAQUE_DRAWS",   Integer.toString(noClamp ? Integer.MAX_VALUE : TRANSLUCENT_OFFSET));
        m.put("MAX_TEMPORAL_DRAWS", Integer.toString(noClamp ? Integer.MAX_VALUE : (TEMPORAL_OFFSET - TRANSLUCENT_OFFSET)));
        if (RenderStatistics.enabled) {
            m.put("HAS_STATISTICS", "");
            m.put("STATISTICS_BUFFER_BINDING", Integer.toString(STATISTICS_BUFFER_BINDING));
        }
        return m;
    }

    private static int mdicProgramId(me.cortex.voxy.client.core.gpu.IGpuPipeline p) {
        if (p instanceof me.cortex.voxy.client.core.gl.GlGraphicsPipeline gg) return gg.program();
        if (p instanceof me.cortex.voxy.client.core.gl.GlComputePipeline gc) return gc.program();
        return 0;
    }

    private final IGpuBuffer uniform;//TODO move to viewport?

    private static boolean loggedNearCullRuntime;

    //TODO: needs to be in the viewport, since it contains the compute indirect call/values
    private final IGpuBuffer distanceCountBuffer;//TODO move to viewport?

    //Statistics
    private final IGpuBuffer statisticsBuffer;

    private final AbstractRenderPipeline pipeline;
    public MDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(modelStore, geometryData);
        this.pipeline = pipeline;
        try {
            this.commandGenPipeline = this.resources.own(this.backend.createComputePipeline(
                new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                        ShaderLoader.parse("voxy:lod/gl46/cmdgen.comp"),
                        cmdgenDefines(),
                        null, null,
                        128, 1, 1, // matches cmdgen.comp's local_size_x=128 (and prep.comp's /128 dispatch math)
                        "MDICSectionRenderer.cmdgen")), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
            this.prepPipeline = this.resources.own(this.backend.createComputePipeline(
                new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                        ShaderLoader.parse("voxy:lod/gl46/prep.comp"),
                        java.util.Map.of(),
                        null, null,
                        1, 1, 1,
                        "MDICSectionRenderer.prep")), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
            this.cullPipeline = this.resources.own(this.backend.createGraphicsPipeline(
                new me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc(
                        ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert"),
                        ShaderLoader.parse("voxy:lod/gl46/cull/raster.frag"),
                        java.util.Map.of(),
                        null, null, null, null,
                        GL_RGBA8,
                        me.cortex.voxy.client.core.gpu.VertexLayout.EMPTY,
                        me.cortex.voxy.client.core.gpu.PipelineState.DEFAULT,
                        "MDICSectionRenderer.cull")), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
            this.forceAllVisiblePipeline = this.resources.own(this.backend.createComputePipeline(
                new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                        ShaderLoader.parse("voxy:lod/gl46/force_all_visible.comp"),
                        java.util.Map.of(),
                        null, null,
                        128, 1, 1,
                        "MDICSectionRenderer.forceAllVisible")), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
            this.prefixSumPipeline = this.resources.own(this.backend.createComputePipeline(
                new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                        ShaderLoader.parse(Capabilities.INSTANCE.subgroup ? "voxy:util/prefixsum/inital3.comp" : "voxy:util/prefixsum/simple.comp"),
                        java.util.Map.of("IO_BUFFER", "0"),
                        null, null,
                        256, 1, 1, // matches WORK_SIZE 256 declared in both prefixsum variants
                        "MDICSectionRenderer.prefixSum")), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
            this.translucentGenPipeline = this.resources.own(this.backend.createComputePipeline(
                new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                        ShaderLoader.parse("voxy:lod/gl46/buildtranslucents.comp"),
                        java.util.Map.of(
                                "TRANSLUCENT_WRITE_BASE", "1024",
                                "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5",
                                "TRANSLUCENT_OFFSET", Integer.toString(TRANSLUCENT_OFFSET),
                                // Exclusive upper bound of the translucent region
                                // [TRANSLUCENT_OFFSET, TEMPORAL_OFFSET): buildtranslucents.comp
                                // drops any command whose prefix-sum cursor would land in the
                                // temporal slice or off the buffer end. INT_MAX under the
                                // VOXY_CMDGEN_NOCLAMP A/B switch.
                                "MAX_TRANSLUCENT_DRAW_END",
                                Integer.toString(cmdgenNoClamp() ? Integer.MAX_VALUE : TEMPORAL_OFFSET)),
                        null, null,
                        128, 1, 1, // matches buildtranslucents.comp's local_size_x=128
                        "MDICSectionRenderer.translucentGen")), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
            this.uniform = this.resources.own(RenderBackendFactory.get().createBuffer(1024), IGpuBuffer::free).zero();
            this.distanceCountBuffer = this.resources.own(RenderBackendFactory.get().createBuffer(1024*4+100_000*4), IGpuBuffer::free).zero();
            this.statisticsBuffer = this.resources.own(RenderBackendFactory.get().createBuffer(1024), IGpuBuffer::free).zero();
            this.cullProgram = mdicProgramId(this.cullPipeline);
            //The pipeline can be used to transform the renderer in abstract ways

            // M13 chunk 3: sampler for the bound-depth texture at binding 2 —
            // quads.frag only texelFetches it, but the MSL signature still wants
            // a sampler bound alongside the texture.
            this.boundDepthSampler = this.backend.getType() != BackendType.OPENGL
                    ? this.resources.own(this.backend.createSampler(me.cortex.voxy.client.core.gpu.SamplerDesc.builder()
                            .filter(me.cortex.voxy.client.core.gpu.SamplerDesc.Filter.NEAREST,
                                    me.cortex.voxy.client.core.gpu.SamplerDesc.Filter.NEAREST)
                            .mipFilter(me.cortex.voxy.client.core.gpu.SamplerDesc.MipFilter.NEAREST)
                            .wrap(me.cortex.voxy.client.core.gpu.SamplerDesc.Wrap.CLAMP_TO_EDGE,
                                    me.cortex.voxy.client.core.gpu.SamplerDesc.Wrap.CLAMP_TO_EDGE)
                            .label("MDIC.boundDepthSampler")
                            .build()), me.cortex.voxy.client.core.gpu.IGpuSampler::close)
                    : null;

            String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
            String taa = pipeline.taaFunction("taaShift");
            if (taa != null) {
                vertex += "\n"+taa;//inject it at the end
            }
            var builder = Shader.make()
                    .defineIf("TAA_PATCH", taa != null)
                    .defineIf("DEBUG_RENDER", false)

                    //.defineIf("USE_NV_BARRY", Capabilities.INSTANCE.nvBarryCoords)

                    .addSource(ShaderType.VERTEX, vertex);

            //Apply per face tinting
            addDirectionalFaceTint(builder, Minecraft.getInstance().level);

            String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");

            String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
            boolean opaquePatched = opaqueFrag != null;
            if (!opaquePatched) opaqueFrag = frag;

            String translucentFrag = pipeline.patchTranslucentShader(this, frag);
            boolean translucentPatched = translucentFrag != null;
            if (!translucentPatched) translucentFrag = frag;

            if (opaquePatched || translucentPatched) {
                // Iris-patched path stays on the legacy Shader.Builder. It's GL-only
                // because the Iris pipeline itself is now gated to OpenGL in
                // RenderPipelineFactory (commit 1e2a1190).
                this.terrainShader = this.resources.own(tryCompilePatchedOrNormal(builder, opaqueFrag, frag), Shader::free);
                this.translucentTerrainShader = this.resources.own(tryCompilePatchedOrNormal(
                        builder.define("TRANSLUCENT"), translucentFrag, frag), Shader::free);
                this.terrainPipeline = null;
                this.translucentTerrainPipeline = null;
                this.terrainProgram = 0;
                this.translucentTerrainProgram = 0;
            } else {
                // Unpatched path — runs on every backend including Metal. Build the
                // two pipelines via the cross-backend abstraction. Defines mirror
                // what Shader.Builder collected above (face-tint floats from
                // addDirectionalFaceTint + TAA_PATCH if a TAA function exists).
                this.terrainShader = null;
                this.translucentTerrainShader = null;
                var configuration = TerrainShaderConfiguration.load(pipeline, this.backend.getType());
                this.terrainPipeline = this.resources.own(this.backend.createGraphicsPipeline(configuration.opaque()), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
                this.translucentTerrainPipeline = this.resources.own(this.backend.createGraphicsPipeline(configuration.translucent()), me.cortex.voxy.client.core.gpu.IGpuPipeline::close);
                this.terrainProgram = mdicProgramId(this.terrainPipeline);
                this.translucentTerrainProgram = mdicProgramId(this.translucentTerrainPipeline);
            }
        } catch (RuntimeException | Error failure) {
            this.resources.rollback(failure);
            throw failure;
        }
    }

    private void uploadUniformBuffer(MDICViewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);
        long base = ptr;

        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        // Experiment (2026-05-26, VOXY_LOD_METAL_NDC=1): GL→Metal NDC-z remap.
        // Extracted to MetalMvpUtil so the chunk-bound depth-mask pass
        // (ChunkBoundRenderer.renderMetal) shares the exact same depth
        // convention — quads.frag compares its gl_FragCoord.z against the
        // mask's depths, so the two MVPs must remap identically.
        if (MetalMvpUtil.METAL_NDC_REMAP && this.backend.getType() != BackendType.OPENGL) {
            MetalMvpUtil.applyNdcRemap(mat);
        }
        NativeUniformWriter.putMatrix4f(ptr, mat); ptr += 4*4*4;

        NativeUniformWriter.putVector3i(ptr, viewport.section); ptr += 4*3;

        if (viewport.frameId<0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId&0x7fffffff); ptr += 4;
        NativeUniformWriter.putVector3f(ptr, viewport.innerTranslation); ptr += 4*3;

        // std140 padding: cameraSubPos (vec3 at offset 80) consumes 12B; the
        // next vec4 must be 16B-aligned, so skip the 4B trailing pad before
        // writing voxyFogEndParams + voxyFogColour. Using offsets from `base`
        // rather than chained `ptr +=` reads more locally about the std140
        // layout and is robust to future intermediate fields.
        // M13 chunk 5: pack the env-fog parameters that quads.frag reads when
        // USE_ENV_FOG is defined. Mirrors NormalRenderPipeline.finish so the
        // Metal terrain-shader fog matches what the GL post-pass would do.
        // When the pipeline doesn't want fog the whole 32-byte tail is zeroed
        // (fogColour.a == 0 makes the shader's mix a no-op anyway).
        long fogBase = base + 96; // matches SceneUniform's std140 layout
        if (this.pipeline.useEnvFog() && viewport.fogParameters != null) {
            float start = viewport.fogParameters.environmentalStart();
            float end   = viewport.fogParameters.environmentalEnd();
            if (Math.abs(end - start) > 1) {
                float invEndFogDelta = 1f / (end - start);
                float endDistance = Math.max(
                        Minecraft.getInstance().gameRenderer.getRenderDistance(),
                        20 * 16);
                endDistance *= (float) Math.sqrt(3);
                float startDelta = -start * invEndFogDelta;
                MemoryUtil.memPutFloat(fogBase +  0, invEndFogDelta);
                MemoryUtil.memPutFloat(fogBase +  4, startDelta);
                MemoryUtil.memPutFloat(fogBase +  8,
                        Math.clamp(endDistance * invEndFogDelta + startDelta, 0f, 1f));
                MemoryUtil.memPutFloat(fogBase + 12, 0f);
                MemoryUtil.memPutFloat(fogBase + 16, viewport.fogParameters.red());
                MemoryUtil.memPutFloat(fogBase + 20, viewport.fogParameters.green());
                MemoryUtil.memPutFloat(fogBase + 24, viewport.fogParameters.blue());
                MemoryUtil.memPutFloat(fogBase + 28, viewport.fogParameters.alpha());
            } else {
                MemoryUtil.memSet(fogBase, 0, 32);
            }
        } else {
            MemoryUtil.memSet(fogBase, 0, 32);
        }

        // voxyLodParams (2026-07-03): distance-mip projection scale + the
        // far-water alpha ramp. Offset 128 = right after voxyFogColour in
        // SceneUniform's std140 layout (the buffer is 1024 B, so no growth).
        // Written on every backend — GL shaders declare the field but no GL
        // code path reads it (VOXY_LOD_DIST_MIP / VOXY_WATER_FAR_ALPHA are
        // Metal-only injections), so this is provably no-op on GL.
        {
            long lodBase = base + 128;
            // World units per pixel per unit view distance, from THIS frame's
            // projection: 2*tan(fovY/2)/viewportH == 2/(m11*viewportH). Tracks
            // spyglass zoom + window resizes. NaN/degenerate projection (the
            // known first-frames state, see HierarchicalOcclusionTraverser's
            // NaN guard) uploads 0, which quads.frag treats as "mip 0".
            float projK = 0.0f;
            float m11 = viewport.projection.m11();
            if (!Float.isNaN(m11) && m11 > 1e-6f && viewport.height > 0) {
                projK = 2.0f / (m11 * viewport.height);
            }
            // Ramp window: start past the LOD<->MC seam (seam parity keeps MC's
            // exact 0.706), reach the target alpha a few render distances out.
            float rdBlocks = Math.max(Minecraft.getInstance().gameRenderer.getRenderDistance(), 32f);
            float rampStart = TerrainShaderConfiguration.WATER_FAR_ALPHA_START > 0f
                    ? TerrainShaderConfiguration.WATER_FAR_ALPHA_START
                    : Math.max(rdBlocks * 1.5f, 384f);
            float rampEnd = TerrainShaderConfiguration.WATER_FAR_ALPHA_END > rampStart
                    ? TerrainShaderConfiguration.WATER_FAR_ALPHA_END
                    : Math.max(rdBlocks * 4f, rampStart + 768f);
            MemoryUtil.memPutFloat(lodBase,      projK);
            MemoryUtil.memPutFloat(lodBase +  4, rampStart);
            MemoryUtil.memPutFloat(lodBase +  8, 1.0f / (rampEnd - rampStart));
            MemoryUtil.memPutFloat(lodBase + 12, TerrainShaderConfiguration.WATER_FAR_ALPHA);
            // voxyLodParams2.x: translucent near-cull distance (vx contract —
            // see VOXY_TRANS_NEAR_CULL). GL and no-pack sessions read 0.
            float nearCull = 0.0f;
            if (this.pipeline.materialPolicy().legacyWater()) {
                // FULLRING (masked mode): radius = the full vanilla border so the
                // mask gate is reachable in the border overlap ring — see the
                // The configured full-ring mode. MetalVxResolvePass.ringCullNow()
                // mirrors this formula (intentionally identical).
                nearCull = TerrainShaderConfiguration.TRANS_NEAR_CULL_FULLRING
                        ? Math.max(rdBlocks, 64f)
                        : Math.max(rdBlocks - TerrainShaderConfiguration.TRANS_NEAR_CULL_MARGIN, 64f);
            }
            if (!loggedNearCullRuntime) {
                loggedNearCullRuntime = true;
                // One-shot: getRenderDistance() units (blocks vs chunks) decide
                // whether the cull radius is ~RD or degenerate ~64.
                Logger.info("[Metal-LODTEST] trans near-cull runtime: rdBlocks=" + rdBlocks
                        + " cullDist=" + nearCull + " fullRing=" + TerrainShaderConfiguration.TRANS_NEAR_CULL_FULLRING
                        + " (vxContract="
                        + me.cortex.voxy.client.core.util.IrisUtil.vxContractActive()
                        + "); VOXY_TRANS_NEAR_CULL_FULLRING=0 reverts to the -margin radius");
            }
            MemoryUtil.memPutFloat(lodBase + 16, nearCull);
            MemoryUtil.memPutFloat(lodBase + 20, 0f);
            MemoryUtil.memPutFloat(lodBase + 24, 0f);
            MemoryUtil.memPutFloat(lodBase + 28, 0f);
        }

        UploadStream.INSTANCE.commit();
    }


    private void bindRenderingBuffers(MDICViewport viewport) {
        // SceneUniform is now an SSBO (see bindings.glsl); bind it to the
        // GL_SHADER_STORAGE_BUFFER target so the in-shader binding=0 matches.
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.uniform.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getGeometryBuffer().id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.geometryManager.getMetadataBuffer().id());
        this.modelStore.bind(3, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.positionScratchBuffer.id());
        LightMapHelper.bind(1);
        bindTextureUnit(2, GL_TEXTURE_2D, viewport.depthBoundingBuffer.getDepthTex().id());

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCallBuffer.id());
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id());
    }

    private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount) {
        //RenderLayer.getCutoutMipped().startDrawing();


        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        if (this.terrainShader != null) {
            this.terrainShader.bind();
        } else if (this.terrainProgram != 0) {
            org.lwjgl.opengl.GL20C.glUseProgram(this.terrainProgram);
        }
        glBindVertexArray(RenderBackendFactory.get().getStaticVAO());//Needs to be before binding
        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);//Barrier everything is needed
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        if (Capabilities.INSTANCE.indirectCount) {
            glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCountOffset, maxDrawCount, 0);
        } else {
            int drawCount = Math.min(readDrawCount(viewport.drawCountCallBuffer.id(), drawCountOffset), maxDrawCount);
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCount, 0);
        }
        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        bindTextureUnit(0, GL_TEXTURE_2D, 0);
        glBindSampler(1, 0);
        bindTextureUnit(1, GL_TEXTURE_2D, 0);

        //RenderLayer.getCutoutMipped().endDrawing();
    }

    @Override
    public void renderOpaque(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        this.uploadUniformBuffer(viewport);

        this.renderTerrain(viewport, 0, 4*3, Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), 400_000));
    }

    /**
     * M12 chunk 6 step 3 — Metal-only opaque draw via {@link RenderEncoder}.
     * Called from {@code AbstractRenderPipeline.runPipelineMetal} inside a
     * render pass that targets the IOSurface bridge color + Voxy's
     * Metal-side depth texture.
     *
     * Differences from the GL {@link #renderTerrain}:
     * <ul>
     *   <li>No raw {@code glUseProgram} / {@code glBindBufferBase} / vertex
     *       array binding — all flows through {@link RenderEncoder.setPipeline}
     *       / {@code setBuffer}.</li>
     *   <li>No {@code setupAndBindOpaque} — the render pass already targets
     *       the bridge; there's no separate FBO bind step.</li>
     *   <li>Lightmap (binding 1 sampler) is bound via
     *       {@code LightMapHelper.bindMetal} — Voxy keeps a Shared-storage
     *       mirror of MC's 16×16 lightmap and CPU-uploads it once per frame
     *       (M13 chunk 2). Depth-bounding texture (binding 2 sampler) stays
     *       unbound — it depends on M13 chunk 3's MC depth import.</li>
     *   <li>Model atlas texture + sampler (binding 0) also skipped —
     *       {@code ModelTextureBakery} is GL-only so the atlas is blank on
     *       Metal anyway; only the model + colour SSBOs feed shape data.</li>
     *   <li>{@code drawIndexedIndirect} with CPU-side {@code maxDrawCount}
     *       instead of {@code drawIndexedIndirectCount} — Metal has no
     *       count-aware MDI compatible with quads.frag (see gotcha #16);
     *       the bound is clamped to the GPU-written count via
     *       {@link #metalDrawCount} (CPU-coherent after runPipelineMetal's
     *       post-buildDrawCalls submit), so stale slots are never drawn.</li>
     * </ul>
     */
    public void renderOpaqueMetal(me.cortex.voxy.client.core.gpu.RenderEncoder encoder, MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        // SceneUniform was already uploaded by buildDrawCalls this frame
        // (runPipelineMetal always pairs them); no re-upload.
        if (this.terrainPipeline == null) {
            // Iris-patched path — GL-only by construction (see 1e2a1190). Should
            // never hit on Metal because RenderPipelineFactory gates Iris pipeline.
            return;
        }
        int maxDrawCount = Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), 400_000);
        maxDrawCount = metalDrawCount(viewport, OPAQUE_DRAW_COUNT_OFFSET, maxDrawCount);
        if (maxDrawCount == 0) return;
        this.renderTerrainMetal(encoder, this.terrainPipeline, viewport, 0L, maxDrawCount);
    }

    /**
     * M12 Metal-side temporal render — reuses the opaque terrain pipeline but
     * draws from the temporal slice of {@code drawCallBuffer}
     * ({@code TEMPORAL_OFFSET}+ slots, populated by commandGen.comp for sections
     * that were visible-this-frame-but-not-last). On GL the equivalent path
     * is {@link #renderTemporal}, which forwards to {@link #renderTerrain}
     * with the temporal offsets.
     */
    public void renderTemporalMetal(me.cortex.voxy.client.core.gpu.RenderEncoder encoder, MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        if (this.terrainPipeline == null) return;
        int maxDrawCount = Math.min(this.geometryManager.getSectionCount(), 100_000);
        maxDrawCount = metalDrawCount(viewport, TEMPORAL_DRAW_COUNT_OFFSET, maxDrawCount);
        if (maxDrawCount == 0) return;
        this.renderTerrainMetal(encoder, this.terrainPipeline, viewport,
                /*indirectOffset bytes*/ (long) TEMPORAL_OFFSET * 5L * 4L,
                maxDrawCount);
    }

    /**
     * M12 Metal-side translucent render — uses the dedicated translucent
     * pipeline (Metal surface depth-test-and-write state
     * + mode-specific blending, both baked in at pipeline creation) and
     * draws from the translucent slice of {@code drawCallBuffer}
     * ({@code TRANSLUCENT_OFFSET}+ slots, populated by buildtranslucents.comp).
     * Same SSBO bindings as opaque since both share quads3.vert + quads.frag;
     * blend + depth state come from the pipeline state, no per-draw GL state
     * changes needed.
     */
    public void renderTranslucentMetal(me.cortex.voxy.client.core.gpu.RenderEncoder encoder, MDICViewport viewport) {
        renderTranslucentMetal(encoder, viewport, this.translucentTerrainPipeline);
    }

    /** Shares geometry bindings and draw bounds with the shaders-off layer captures. */
    public void renderTranslucentMetal(me.cortex.voxy.client.core.gpu.RenderEncoder encoder, MDICViewport viewport, me.cortex.voxy.client.core.gpu.IGpuPipeline layerPipeline) {
        if (this.geometryManager.getSectionCount() == 0 || layerPipeline == null) return;
        int translucentMax = Math.min(this.geometryManager.getSectionCount(), 100_000);
        translucentMax = metalDrawCount(viewport, TRANSLUCENT_DRAW_COUNT_OFFSET, translucentMax);
        if (translucentMax == 0) return;
        this.renderTerrainMetal(encoder, layerPipeline, viewport,
                /*indirectOffset bytes*/ (long) TRANSLUCENT_OFFSET * 5L * 4L,
                translucentMax);
    }

    /**
     * Byte offsets of the GPU-written draw counts in {@code drawCountCallBuffer}
     * — fields 4/5/6 of bindings.glsl's DrawCommandCountBuffer (after the three
     * cmdGenDispatch uints). Same offsets the GL path feeds to
     * glMultiDrawElementsIndirectCountARB (4*3 / 4*4 / 4*5).
     */
    private static final long OPAQUE_DRAW_COUNT_OFFSET = 4 * 3;
    private static final long TRANSLUCENT_DRAW_COUNT_OFFSET = 4 * 4;
    private static final long TEMPORAL_DRAW_COUNT_OFFSET = 4 * 5;

    /**
     * Metal-only clamp of the CPU-side maxDrawCount to the count cmdgen.comp /
     * buildtranslucents.comp actually wrote. All three drawCallBuffer slices are
     * written compactly from their slice base (opaque: atomicAdd from slot 0;
     * temporal: atomicAdd from TEMPORAL_OFFSET; translucent: prefix-sum scatter
     * covering [TRANSLUCENT_OFFSET, +count) exactly once), so clamping skips
     * only never-written slots. Valid because runPipelineMetal submit()s (and
     * waits) between buildDrawCalls and the render pass — the same coherency
     * the baseInstance CPU-read in MetalRenderEncoder.drawIndexedIndirect
     * already requires. Cuts the per-draw JNI loop from the 150k-450k upper
     * bound to the real count. No-op on GL (drawCountCallBuffer is not a
     * MetalBuffer there).
     */
    private static int metalDrawCount(MDICViewport viewport, long countOffset, int upperBound) {
        if (viewport.drawCountCallBuffer instanceof me.cortex.voxy.client.core.metal.MetalBuffer mb) {
            long p = mb.getContentsPtr();
            if (p != 0) {
                int actual = MemoryUtil.memGetInt(p + countOffset);
                // unsigned compare: garbage >= 2^31 must not wrap negative past min()
                if (Integer.compareUnsigned(actual, upperBound) < 0) return actual;
            }
        }
        return upperBound;
    }

    private void renderTerrainMetal(me.cortex.voxy.client.core.gpu.RenderEncoder encoder,
                                    me.cortex.voxy.client.core.gpu.IGpuPipeline pipeline,
                                    MDICViewport viewport, long indirectOffset, int maxDrawCount) {
        encoder.setPipeline(pipeline);
        // SSBO bindings 0..5 — mirror bindRenderingBuffers; SceneUniform is an
        // SSBO post-chunk-3 SceneUniform flip.
        encoder.setBuffer(0, this.uniform, 0);
        encoder.setBuffer(1, this.geometryManager.getGeometryBuffer(), 0);
        encoder.setBuffer(2, this.geometryManager.getMetadataBuffer(), 0);
        // M13 chunk 1: bindBuffers now also wires up the model atlas texture +
        // cross-backend sampler at binding 0 (blockModelAtlas in quads.frag).
        this.modelStore.bindBuffers(encoder, 3, 4, 0);
        encoder.setBuffer(5, viewport.positionScratchBuffer, 0);
        this.pipeline.bindUniforms(encoder);
        // Texture / sampler binding 1 — MC's 16×16 RGBA8 lightmap, mirrored
        // into a Shared-storage Metal texture each frame (M13 chunk 2).
        LightMapHelper.bindMetal(encoder, 1, viewport.frameId);
        // Texture / sampler binding 2 — the chunk-bound depth mask
        // (ChunkBoundRenderer.renderMetal rasterized it into
        // viewport.depthBoundingBuffer earlier this frame; same command
        // queue, so ordering is guaranteed). quads.frag's depth-bound test
        // texelFetches it to discard LOD fragments inside MC's loaded-chunk
        // volume (M13 chunk 3). Bound for all three Metal draws (opaque /
        // temporal / translucent share this method). Harmlessly unused when
        // the VOXY_NO_DEPTH_BOUND kill switch removed the sample.
        if (this.boundDepthSampler != null) {
            encoder.setTexture(2, viewport.depthBoundingBuffer.getDepthTex());
            encoder.setSampler(2, this.boundDepthSampler);
        }
        // Buffer binding 9 — the bound mask as raw floats (round 20,
        // VOXY_METAL_BOUND_SSBO; 6 is quads3.vert's per-draw UBO slot, 7/8
        // are cmdgen compute bindings). The texture binding above is kept
        // for compatibility but the shader no longer samples it (depth-
        // format textures read zeros through texture2d<float> declarations).
        if (viewport.metalBoundReadBuffer != null) {
            encoder.setBuffer(9, viewport.metalBoundReadBuffer, 0);
        }

        encoder.bindIndexBuffer(me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer.INSTANCE.getBuffer(),
                me.cortex.voxy.client.core.gpu.RenderEncoder.INDEX_TYPE_UINT16, 0);
        encoder.drawIndexedIndirect(
                me.cortex.voxy.client.core.gpu.RenderEncoder.PRIMITIVE_TRIANGLES,
                viewport.drawCallBuffer, indirectOffset,
                maxDrawCount,
                /*stride*/ 5 * 4); // DrawElementsIndirectCommand = 5 uint32
    }

    @Override
    public void renderTranslucent(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        if (this.translucentTerrainShader != null) {
            this.translucentTerrainShader.bind();
        } else if (this.translucentTerrainProgram != 0) {
            org.lwjgl.opengl.GL20C.glUseProgram(this.translucentTerrainProgram);
        }
        glBindVertexArray(RenderBackendFactory.get().getStaticVAO());//Needs to be before binding
        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);//Barrier everything is needed
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
        int translucentMax = Math.min(this.geometryManager.getSectionCount(), 100_000);
        if (Capabilities.INSTANCE.indirectCount) {
            glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, 4*4, translucentMax, 0);
        } else {
            int drawCount = Math.min(readDrawCount(viewport.drawCountCallBuffer.id(), 4*4L), translucentMax);
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, drawCount, 0);
        }

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        bindTextureUnit(0, GL_TEXTURE_2D, 0);
        glBindSampler(1, 0);
        bindTextureUnit(1, GL_TEXTURE_2D, 0);

        glDisable(GL_BLEND);
    }

    private static boolean COMPUTE_SERIALIZE_LOGGED = false;

    /** Fallback: re-enable the per-frame drawCallBuffer zero on Metal. */
    private static final boolean METAL_ZERO_DRAWBUF =
            "1".equals(System.getenv("VOXY_LOD_ZERO_DRAWBUF"));

    @Override
    public void buildDrawCalls(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);

        // DIAGNOSTIC (2026-05-25): VOXY_COMPUTE_SERIALIZE=1 forces submit()+wait
        // after each Metal compute prepass (prep, cull, commandGen) so they run
        // strictly serially with memory coherency — the guarantee the GL path
        // gets from glMemoryBarrier. If the LOD flicker stops with this on, the
        // prepasses were racing across encoder boundaries and the real fix is
        // inter-encoder fences/barriers. Metal-only; GL unaffected.
        boolean computeSerialize = "1".equals(System.getenv("VOXY_COMPUTE_SERIALIZE"))
                && this.backend.getType() != BackendType.OPENGL;
        if (computeSerialize && !COMPUTE_SERIALIZE_LOGGED) {
            COMPUTE_SERIALIZE_LOGGED = true;
            Logger.info("[Metal-SERIALIZE] VOXY_COMPUTE_SERIALIZE active: submit()+wait after each compute prepass");
        }

        // On non-GL backends the renderer issues `drawIndexedIndirect` against
        // viewport.drawCallBuffer with a CPU-side `maxDrawCount` upper bound
        // (Metal has no count-aware MDI without an ICB, and MDIC's terrain
        // pipeline opts out of ICB — see gotcha #16). The render*Metal calls
        // clamp that bound to the GPU-written counts (see metalDrawCount), and
        // all three slices are compact, so stale slots beyond the counts are
        // never drawn — the previous per-frame ~12 MB zero of the whole
        // cmdBuffer is unnecessary (it's zeroed once at allocation in
        // MDICViewport). VOXY_LOD_ZERO_DRAWBUF=1 restores it as a fallback.
        if (this.backend.getType() != BackendType.OPENGL && METAL_ZERO_DRAWBUF) {
            viewport.drawCallBuffer.zero();
        }

        //Can do a sneeky trick, since the sectionRenderList is a list to things to render, it invokes the culler
        // which only marks visible sections


        {//Dispatch prep
            // M12 chunk 2: prep prepass migrated to ComputeEncoder. prep.comp
            // does not reference SceneUniform fields (only writes to the
            // DrawCommandCountBuffer at binding 1 from sectionCount at
            // binding 2), so the encoder skips binding 0 entirely — that
            // keeps the SceneUniform UBO→SSBO decision deferred to chunks
            // 3 and 4 (which DO use SceneUniform).
            try (var encoder = this.backend.beginComputePass()) {
                encoder.setPipeline(this.prepPipeline);
                encoder.setBuffer(1, viewport.drawCountCallBuffer, 0);
                encoder.setBuffer(2, viewport.getRenderList(), 0);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
                encoder.dispatch(1, 1, 1);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            }
        }
        if (computeSerialize) this.backend.submit(); // serialize: prep complete

        {//Test occlusion
            if (this.backend.getType() == BackendType.OPENGL) {
                // GL path — depth-test-based occlusion cull. Rasterizes each
                // section's AABB against MC's depth buffer with color/depth
                // masks off; raster.frag writes visibilityData for sections
                // whose AABBs survive depth test (with optional
                // NV_representative_fragment_test for perf).
                if (this.cullProgram != 0) org.lwjgl.opengl.GL20C.glUseProgram(this.cullProgram);
                if (Capabilities.INSTANCE.repFragTest) {
                    glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
                }
                glBindVertexArray(RenderBackendFactory.get().getStaticVAO());
                // SceneUniform is an SSBO now (see bindings.glsl).
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.uniform.id());
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getMetadataBuffer().id());
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.visibilityBuffer.id());
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, viewport.indirectLookupBuffer.id());
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id());
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
                glEnable(GL_DEPTH_TEST);
                glColorMask(false, false, false, false);
                glDepthMask(false);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);
                glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 6*4);
                glDepthMask(true);
                glColorMask(true, true, true, true);
                glDisable(GL_DEPTH_TEST);
                if (Capabilities.INSTANCE.repFragTest) {
                    glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
                }
            } else {
                // Non-GL path (Metal today) — compute stub that skips
                // occlusion and marks every frustum-visible section as
                // visible-this-frame + visible-last-frame. Slower than real
                // depth occlusion but functionally correct; the real cull
                // depends on cross-context MC-depth access which is part of
                // chunk 6's IGpuRenderTarget work.
                try (var encoder = this.backend.beginComputePass()) {
                    encoder.setPipeline(this.forceAllVisiblePipeline);
                    encoder.setBuffer(0, this.uniform, 0);
                    encoder.setBuffer(2, viewport.visibilityBuffer, 0);
                    encoder.setBuffer(3, viewport.indirectLookupBuffer, 0);
                    encoder.barrier(ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT,
                                    ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT);
                    // Reuses prep's dispatch sizing — cmdGenDispatchX/Y/Z at
                    // offset 0 of drawCountCallBuffer holds ceil(sectionCount/128),
                    // matching this shader's local_size_x=128.
                    encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
                    encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
                }
            }
        }
        if (computeSerialize) this.backend.submit(); // serialize: cull/visibility complete before commandGen


        {//Generate the commands
            this.distanceCountBuffer.zeroRange(0, 1024*4);
            // M12 chunk 3: commandGen migrated to ComputeEncoder. SceneUniform
            // is now an SSBO (see bindings.glsl), so binding 0 flows through
            // setBuffer just like the other SSBOs. Read count comes from
            // drawCountCallBuffer at offset 0 via dispatchIndirect.
            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero();
            }
            try (var encoder = this.backend.beginComputePass()) {
                encoder.setPipeline(this.commandGenPipeline);
                encoder.setBuffer(0, this.uniform, 0);
                encoder.setBuffer(1, viewport.drawCallBuffer, 0);
                encoder.setBuffer(2, viewport.drawCountCallBuffer, 0);
                encoder.setBuffer(3, this.geometryManager.getMetadataBuffer(), 0);
                encoder.setBuffer(4, viewport.visibilityBuffer, 0);
                encoder.setBuffer(5, viewport.indirectLookupBuffer, 0);
                encoder.setBuffer(6, viewport.positionScratchBuffer, 0);
                encoder.setBuffer(7, this.distanceCountBuffer, 0);
                if (RenderStatistics.enabled) {
                    encoder.setBuffer(STATISTICS_BUFFER_BINDING, this.statisticsBuffer, 0);
                }
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
                encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT,
                                ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT);
            }

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }

                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }
        if (computeSerialize) this.backend.submit(); // serialize: commandGen (draw commands) complete

        {//Do translucency sorting
            // M12 chunk 1: prefixSum migrated to ComputeEncoder. Runs on every
            // backend (GL lowers to glUseProgram + glBindBufferBase +
            // glDispatchCompute; Metal opens an MTLComputeCommandEncoder). The
            // previous raw-GL pattern no-opped on Metal because
            // mdicProgramId(prefixSumPipeline) returns 0.
            try (var encoder = this.backend.beginComputePass()) {
                encoder.setPipeline(this.prefixSumPipeline);
                encoder.setBuffer(0, this.distanceCountBuffer, 0);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
                encoder.dispatch(1, 1, 1);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            }

            // M12 chunk 4: translucentGen migrated to ComputeEncoder. SceneUniform
            // is bound as an SSBO at binding 0 (post-flip in chunk 3); the
            // dispatch count comes from drawCountCallBuffer at offset 0 via
            // dispatchIndirect — the same buffer doubles as the indirect arg
            // and as one of the shader's SSBO inputs at binding 2 (an unusual
            // but pre-existing read-then-dispatch pattern).
            try (var encoder = this.backend.beginComputePass()) {
                encoder.setPipeline(this.translucentGenPipeline);
                encoder.setBuffer(0, this.uniform, 0);
                encoder.setBuffer(1, viewport.drawCallBuffer, 0);
                encoder.setBuffer(2, viewport.drawCountCallBuffer, 0);
                encoder.setBuffer(3, this.geometryManager.getMetadataBuffer(), 0);
                encoder.setBuffer(4, viewport.indirectLookupBuffer, 0);
                encoder.setBuffer(5, this.distanceCountBuffer, 0);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT,
                                ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT);
                encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT,
                                ComputeEncoder.BARRIER_SHADER | ComputeEncoder.BARRIER_INDIRECT);
            }
        }

    }

    @Override
    public void renderTemporal(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        //Render temporal
        this.renderTerrain(viewport, TEMPORAL_OFFSET*5*4, 4*5, Math.min(this.geometryManager.getSectionCount(), 100_000));
    }

    private int readDrawCount(int bufferId, long offsetBytes) {
        var tmp = MemoryUtil.memAllocInt(1);
        glBindBuffer(GL_COPY_READ_BUFFER, bufferId);
        glGetBufferSubData(GL_COPY_READ_BUFFER, offsetBytes, tmp);
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        int count = tmp.get(0);
        MemoryUtil.memFree(tmp);
        return Math.max(count, 0);
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
        //lines.add("SC/GS: " + this.geometryManager.getSectionCount() + "/" + (this.geometryManager.getGeometryUsed()/(1024*1024)));//section count/geometry size (MB)
    }

    @Override
    public MDICViewport createViewport() {
        return new MDICViewport(this.geometryManager.getMaxSectionCount());
    }

    @Override
    public void free() {
        this.resources.close();
    }
}
