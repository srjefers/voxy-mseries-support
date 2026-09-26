package me.cortex.voxy.client.core.model.bakery;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL14;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42C.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;

import com.mojang.blaze3d.vertex.PoseStack;

public class ModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final GlViewCapture capture;
    /** M13 chunk 1: Metal-side bake target + atlas mirror + renderer. Lazy. */
    private MetalViewCapture metalCapture;
    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();

    private final int width;
    private final int height;
    public ModelTextureBakery(int width, int height) {
        this.capture = new GlViewCapture(width, height);
        this.width = width;
        this.height = height;
    }

    public static int getMetaFromLayer(ChunkSectionLayer layer) {
        boolean hasDiscard = layer == ChunkSectionLayer.CUTOUT ||
                layer == ChunkSectionLayer.TRANSLUCENT||
                layer == ChunkSectionLayer.TRIPWIRE;

        boolean isMipped = layer == ChunkSectionLayer.SOLID ||
                layer == ChunkSectionLayer.TRANSLUCENT ||
                layer == ChunkSectionLayer.TRIPWIRE;

        int meta = hasDiscard?1:0;
        meta |= true?2:0;
        return meta;
    }

    private void bakeBlockModel(BlockState state, ChunkSectionLayer layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int meta = getMetaFromLayer(layer);

        for (var part : model.collectParts(new SingleThreadedRandomSource(42L))) {
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                var quads = part.getQuads(direction);
                for (var quad : quads) {
                    this.vc.quad(quad, meta|(quad.isTinted()?4:0));
                }
            }
        }
    }


    private void bakeFluidState(BlockState state, ChunkSectionLayer layer, int face) {
        {
            //TODO: somehow set the tint flag per quad or something?
            int metadata = getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);//Set the meta while baking
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getUnitVec3i();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.capture.free();
        if (this.metalCapture != null) {
            this.metalCapture.free();
            this.metalCapture = null;
        }
        this.vc.free();
    }


    /**
     * Run the bake for {@code state} into the capture FBO, then CPU-read the
     * FBO into {@code destAddr} (the persistent-buffer mapped CPU address
     * provided by {@link me.cortex.voxy.client.core.rendering.util.RawDownloadStream}).
     *
     * Works on every Voxy backend now (M13 chunk 1): the bake itself uses
     * MC's GL context (always present), and the readback is CPU-side via
     * {@code glGetTexImage}. The result bytes flow through the same
     * downstream callback the GL 4.3 compute path used.
     */
    public int renderToStream(BlockState state, long destAddr) {
        // GL backend path. Metal callers use renderDefaultBakeToHeap()
        // through ModelFactory so they do not write into RawDownloadStream's
        // persistent GL-mapped buffer.
        boolean isMetal = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get()
                .getType() == me.cortex.voxy.client.core.gpu.BackendType.METAL;
        boolean bakeOff = "1".equals(System.getenv("VOXY_BAKERY_OFF"));
        boolean forceOn = "1".equals(System.getenv("VOXY_BAKERY_FORCE"));
        if (bakeOff) {
            GlViewCapture.DIAG_BAKE_INVOCATIONS.incrementAndGet();
            return 0;
        }
        if (isMetal && !forceOn) throw new IllegalStateException(
                "Metal bakery must use renderDefaultBakeToHeap()");
        if (isMetal) {
            // VOXY_BAKERY_FORCE=1 — experimental Metal bakery
            return renderToStreamMetal(state, destAddr);
        }
        this.capture.clear();
        boolean isBlock = true;
        ChunkSectionLayer layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = ChunkSectionLayer.SOLID;
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        //Setup GL state
        int[] viewdat = new int[4];
        int blockTextureId;
        // Save MC's draw framebuffer so we can restore it on the way out —
        // unbinding to 0 would direct MC's compositor to the OS default
        // framebuffer instead of its post-FX target.
        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        {
            glEnable(GL_STENCIL_TEST);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                glEnable(GL_BLEND);
                glBlendFuncSeparate(GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);//FUCK YOU INTEL (screams), for _some reason_ discard or something... JUST DOESNT WORK??
                //glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ONE, GL_ONE);
            }

            glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            glStencilFunc(GL_ALWAYS, 1, 0xFF);
            glStencilMask(0xFF);

            glGetIntegerv(GL_VIEWPORT, viewdat);//TODO: faster way todo this, or just use main framebuffer resolution

            //Bind the capture framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebufferId);

            var tex = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")).getTexture();
            blockTextureId = ((com.mojang.blaze3d.opengl.GlTexture)tex).glId();
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {//only render if there... is shit to render

                //Setup for continual emission
                BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);//note: this.vc.buffer.address NOT this.vc.ptr

                var mat = new Matrix4f();
                for (int i = 0; i < VIEWS.length; i++) {
                    if (i==1||i==2||i==4) {
                        glCullFace(GL_FRONT);
                    } else {
                        glCullFace(GL_BACK);
                    }

                    glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                    //The projection matrix
                    mat.set(2, 0, 0, 0,
                            0, 2, 0, 0,
                            0, 0, -1f, 0,
                            -1, -1, 0, 1)
                            .mul(VIEWS[i]);

                    BudgetBufferRenderer.render(mat);
                }
            }
            glBindVertexArray(0);
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                if (i==1||i==2||i==4) {
                    glCullFace(GL_FRONT);
                } else {
                    glCullFace(GL_BACK);
                }

                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;
                BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);

                glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                //The projection matrix
                // M13 chunk 1: Metal-friendly projection matrix. Two
                // adjustments vs the GL version:
                //   (1) m22 = +1 (was -1): GL accepts NDC z ∈ [-1, 1] so
                //       mapping world z [0, 1] → NDC [0, -1] works; Metal
                //       only accepts NDC z ∈ [0, 1] and clips anything
                //       below 0 — that's what produced the "stretched
                //       triangles from the ground" the LOD chunks showed.
                //       Mapping z [0, 1] → NDC z [0, 1] keeps the cube
                //       inside the clip volume.
                //   (2) m11 = -2, m31 = +1 (was 2, -1): flip Y. GL stores
                //       framebuffer bottom-row-first in memory and the
                //       LOD shader was written for that — UV (0, 0)
                //       maps to the first byte = bottom-left of the
                //       rendered image. Metal stores top-row-first, so
                //       without a flip UV (0, 0) would map to top-left
                //       of the bake. Y-negating the projection makes the
                //       Metal output's first memory row contain the
                //       original image's bottom row, matching GL bytes.
                // Depth ordering between faces is irrelevant — the Metal
                // bakery pipeline runs with DepthState.DISABLED.
                mat.set(2, 0, 0, 0,
                        0, -2, 0, 0,
                        0, 0, 1f, 0,
                        -1, 1, 0, 1)
                        .mul(VIEWS[i]);

                BudgetBufferRenderer.render(mat);
            }
            glBindVertexArray(0);
        }

        //Render block model entity data if it exists
        /*
        if (bbem != null) {
            //Rerender everything again ;-; but is ok (is not)

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                if (i==1||i==2||i==4) {
                    glCullFace(GL_FRONT);
                } else {
                    glCullFace(GL_BACK);
                }

                glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                //The projection matrix
                // M13 chunk 1: Metal-friendly projection matrix. Two
                // adjustments vs the GL version:
                //   (1) m22 = +1 (was -1): GL accepts NDC z ∈ [-1, 1] so
                //       mapping world z [0, 1] → NDC [0, -1] works; Metal
                //       only accepts NDC z ∈ [0, 1] and clips anything
                //       below 0 — that's what produced the "stretched
                //       triangles from the ground" the LOD chunks showed.
                //       Mapping z [0, 1] → NDC z [0, 1] keeps the cube
                //       inside the clip volume.
                //   (2) m11 = -2, m31 = +1 (was 2, -1): flip Y. GL stores
                //       framebuffer bottom-row-first in memory and the
                //       LOD shader was written for that — UV (0, 0)
                //       maps to the first byte = bottom-left of the
                //       rendered image. Metal stores top-row-first, so
                //       without a flip UV (0, 0) would map to top-left
                //       of the bake. Y-negating the projection makes the
                //       Metal output's first memory row contain the
                //       original image's bottom row, matching GL bytes.
                // Depth ordering between faces is irrelevant — the Metal
                // bakery pipeline runs with DepthState.DISABLED.
                mat.set(2, 0, 0, 0,
                        0, -2, 0, 0,
                        0, 0, 1f, 0,
                        -1, 1, 0, 1)
                        .mul(VIEWS[i]);

                bbem.render(mat, blockTextureId);
            }
            glBindVertexArray(0);

            bbem.release();
        }*/



        //"Restore" gl state
        glViewport(viewdat[0], viewdat[1], viewdat[2], viewdat[3]);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);

        // M13 chunk 1: release the bakery's program / VAO / sampler / UBO
        // bindings. Without this, downstream consumers (Sodium chunk
        // renderer, MC UI) inherit our GL state. Apple GL has been observed
        // to return null from glMapBufferRange mid-frame when the bakery's
        // VAO is still bound, raising "Failed to map buffer" inside
        // SharedQuadIndexBuffer.grow.
        BudgetBufferRenderer.endRender();

        //Finish and download.
        this.capture.emitToStream(destAddr);

        // Clear the depth target for the next bake, then restore MC's
        // pre-bake draw framebuffer so its compositor keeps writing to the
        // post-FX target (not the OS default).
        glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebufferId);
        glClearDepth(1);
        glClear(GL_DEPTH_BUFFER_BIT);
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            //reset the blend func
            GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, prevDrawFb);

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0);
    }

    /**
     * True when Metal should bypass RawDownloadStream and write bake bytes
     * directly into heap-owned memory.
     */
    public boolean shouldUseMetalDefaultBake() {
        boolean isMetal = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get()
                .getType() == me.cortex.voxy.client.core.gpu.BackendType.METAL;
        return isMetal;
    }

    /**
     * Metal bake entry used by {@link me.cortex.voxy.client.core.model.ModelFactory}.
     *
     * <p>Default: run the real Metal-native bakery into heap-owned bake
     * memory, then ModelFactory processes that memory normally and uploads
     * real block textures into ModelStore.textures. This avoids the previous
     * RawDownloadStream path, which wrote bake bytes through a persistent
     * GL-mapped buffer and was implicated in Apple GL/Sodium map failures.
     *
     * <p>Kill switch: {@code VOXY_BAKERY_OFF=1} keeps the old hash-colour
     * fallback alive by writing only synthetic face-visibility data. The
     * terrain shader must pair that mode with {@code VOXY_NO_ATLAS}.
     */
    public int renderDefaultBakeToHeap(BlockState state, long destAddr) {
        if ("1".equals(System.getenv("VOXY_BAKERY_OFF"))) {
            GlViewCapture.DIAG_BAKE_INVOCATIONS.incrementAndGet();
            if (state.getRenderShape() == RenderShape.INVISIBLE && !(state.getBlock() instanceof LiquidBlock)) {
                zeroDestAddr(destAddr);
                return 0;
            }
            writeDefaultBakePattern(destAddr);
            GlViewCapture.DIAG_BAKE_NONZERO_PIXEL_INVOCATIONS.incrementAndGet();
            return 0;
        }
        return renderToStreamMetal(state, destAddr);
    }




    /**
     * M13 chunk 1: Metal-side renderToStream. Same overall shape as the GL
     * path — pick a layer for the state, walk the model parts into the
     * {@link ReuseVertexConsumer}, project 6 cube faces, emit packed pixels
     * into {@code destAddr} — but every GPU resource (vertex buffer, index
     * buffer, atlas texture, bake target) lives on the Metal backend, so
     * Apple's GL pixel-processor never enters the picture. The 6 face draws
     * for non-fluid blocks share a single render pass; the fluid path
     * re-uploads the mesh per face (each in its own LOAD-action pass so the
     * accumulated pixels survive).
     */
    private int renderToStreamMetal(BlockState state, long destAddr) {
        GlViewCapture.DIAG_BAKE_INVOCATIONS.incrementAndGet();
        if (state.getRenderShape() == RenderShape.INVISIBLE && !(state.getBlock() instanceof LiquidBlock)) {
            // Mirror the GL path's empty-bake behaviour — write zeros to
            // destAddr so the model store sees a blank slot.
            zeroDestAddr(destAddr);
            return 0;
        }
        if (this.metalCapture == null) {
            this.metalCapture = new MetalViewCapture(this.width, this.height);
        }

        // Mirror the GL setup() block's layer / isBlock decision.
        boolean isBlock = true;
        ChunkSectionLayer layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else if (state.getBlock() instanceof LeavesBlock) {
            layer = ChunkSectionLayer.SOLID;
        } else {
            layer = ItemBlockRenderTypes.getChunkRenderType(state);
        }

        // MC's block atlas — same lookup the GL path does. We pass the GL id
        // through to the AtlasMirror (inside MetalViewCapture) which lifts it
        // onto a Shared Metal texture lazily.
        var tex = Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"))
                .getTexture();
        int blockTextureId = ((com.mojang.blaze3d.opengl.GlTexture) tex).glId();

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;

        // Always clear at the start of the bake — fluid path appends with
        // LoadAction.LOAD so all faces accumulate cleanly into the same target.
        this.metalCapture.clear();

        Matrix4f mat = new Matrix4f();
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            isAnyShaded  |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {
                this.metalCapture.beginBake(blockTextureId,
                        this.vc.getAddress(), this.vc.quadCount(), /*clear*/false);
                for (int i = 0; i < VIEWS.length; i++) {
                    // M13 chunk 1 fix (2026-05-16): Metal-friendly projection
                    // — positive z row so world z stays in Metal's NDC z [0,1]
                    // (m22=-1 mapped to [-1,0], all clipped), and m11=-2/m31=+1
                    // Y-flip for Metal's top-row-first framebuffer convention.
                    //
                    // Water fix (2026-06-09): z is compressed to NDC [0.25,
                    // 0.75] (m22=0.5, m32=0.25) instead of [0, 1]. Each VIEWS[i]
                    // puts the far cube plane at view z = 1.0 exactly, i.e. ON
                    // Metal's far clip plane, and the 90°-rotation matrices
                    // carry ~1e-7 quaternion float error — a flat quad landing
                    // at z = 1+ε clips ENTIRELY. Blocks masked this (the near
                    // face of the cube mesh still covered the cell); the fluid
                    // path draws ONE quad per cell and lost 5 of 6 faces to it.
                    // Depth is unused: DepthState.DISABLED and emitToStream
                    // hard-codes the depth metadata, so z placement is free.
                    mat.set(2, 0, 0, 0,
                            0, -2, 0, 0,
                            0, 0, 0.5f, 0,
                            -1, 1, 0.25f, 1)
                            .mul(VIEWS[i]);
                    this.metalCapture.renderFace(i % 3, i / 3, mat);
                }
                this.metalCapture.endBake();
            }
        } else {
            // Fluid path — each face has its own mesh because bakeFluidState
            // queries the neighbouring face state. Open one pass per face
            // with LoadAction.LOAD so prior face pixels survive.
            for (int i = 0; i < VIEWS.length; i++) {
                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded  |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;
                this.metalCapture.beginBake(blockTextureId,
                        this.vc.getAddress(), this.vc.quadCount(), /*clear*/false);
                // Same Metal projection as the block loop above: m11=-2/m31=+1
                // Y-flip + z compressed to NDC [0.25, 0.75]. The z compression
                // is what makes the fluid bake produce pixels at all — each
                // per-face fluid mesh is essentially the single face quad, and
                // every face except UP (water surface sits at y≈0.89) lands at
                // view z = 1.0 ± 1e-7, i.e. straddling Metal's far clip plane;
                // an ε overshoot clipped the whole quad → zero-alpha face →
                // ModelFactory marked it nonexistent → meshing culled the
                // water surface ("grey seafloor" holes). shouldReturnAirForFluid
                // is NOT the culprit: it airs the neighbour in the +face
                // direction, which is what lets vanilla emit that face.
                mat.set(2, 0, 0, 0,
                        0, -2, 0, 0,
                        0, 0, 0.5f, 0,
                        -1, 1, 0.25f, 1)
                        .mul(VIEWS[i]);
                this.metalCapture.renderFace(i % 3, i / 3, mat);
                this.metalCapture.endBake();
            }
        }

        // Plant cross models (same predicate as ModelFactory's bit-55 flag):
        // keep the sparse blade alpha instead of dilating the cell into a
        // solid tile. See MetalViewCapture.emitToStream(long, boolean).
        boolean plantCross = isBlock
                && me.cortex.voxy.client.core.model.ModelFactory.PLANT_CROSS
                && state.getFluidState().isEmpty()
                && state.getBlock() instanceof VegetationBlock;
        this.metalCapture.emitToStream(destAddr, plantCross);
        if (!isBlock) {
            maybeLogWaterBakeDiag(state, destAddr);
        }
        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0);
    }

    /** Bounded one-shot [Metal-WATERBAKE] diagnostic: stops after the first
     * water bake with real alpha, or after 4 all-zero bakes (early bakes can
     * race the AtlasMirror warmup and legitimately come out empty). */
    private static final java.util.concurrent.atomic.AtomicInteger WATER_BAKE_DIAG_REMAINING =
            new java.util.concurrent.atomic.AtomicInteger(4);

    /**
     * Log per-face %nonzero-alpha + mean alpha of a water bake so a runtime
     * log answers "did the fluid bake produce textured faces" without a
     * debugger. Reads the face-major uvec2-per-pixel layout emitToStream
     * wrote to {@code destAddr} (face N at byte offset N*w*h*8).
     */
    private void maybeLogWaterBakeDiag(BlockState state, long destAddr) {
        if (!state.is(Blocks.WATER)) return;
        if (WATER_BAKE_DIAG_REMAINING.get() <= 0) return;
        final String[] names = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
        final int facePixels = this.width * this.height;
        StringBuilder sb = new StringBuilder("[Metal-WATERBAKE] ").append(state).append(" :");
        boolean anyAlpha = false;
        for (int face = 0; face < 6; face++) {
            long base = destAddr + (long) face * facePixels * 8L;
            int nonzero = 0;
            long alphaSum = 0;
            for (int i = 0; i < facePixels; i++) {
                int a = org.lwjgl.system.MemoryUtil.memGetInt(base + i * 8L) >>> 24;
                if (a != 0) { nonzero++; alphaSum += a; }
            }
            anyAlpha |= nonzero != 0;
            sb.append(' ').append(names[face]).append('=')
                    .append(nonzero * 100 / facePixels).append("%nz/meanA=")
                    .append(nonzero == 0 ? 0 : alphaSum / nonzero);
        }
        me.cortex.voxy.common.Logger.info(sb.toString());
        if (anyAlpha) {
            WATER_BAKE_DIAG_REMAINING.set(0);
        } else {
            WATER_BAKE_DIAG_REMAINING.decrementAndGet();
        }
    }

    /** Zero out the 8-byte-per-pixel destAddr region for invisible / empty bakes. */
    private void zeroDestAddr(long destAddr) {
        long bytes = (long) this.width * 3L * (long) this.height * 2L * 8L;
        org.lwjgl.system.MemoryUtil.memSet(destAddr, 0, bytes);
    }

    /**
     * Write a synthetic "all 6 faces fully opaque + 'drawn' marker" bake
     * pattern into {@code destAddr}. Used by the Metal-default path where
     * the real bakery is gated off — keeps every face passing both of
     * Voxy's face-visibility checks downstream so real quads get
     * generated:
     *
     * <ul>
     *   <li>{@code WRITE_CHECK_ALPHA} (CUTOUT/TRANSLUCENT): passes when
     *       {@code (colour >>> 24) > 1}. We write {@code colour=0xFFFFFFFF}
     *       — alpha = 255.</li>
     *   <li>{@code WRITE_CHECK_STENCIL} (SOLID, the majority of blocks):
     *       passes when {@code (depth & 0xFF) != 0}. The real GL bakery's
     *       output packs the tint bit at position 7 of the depth uint;
     *       SOLID blocks rely on that low byte being non-zero to mark a
     *       pixel as "drawn". We set {@code value=0x80} — bit 7 lit. The
     *       block ends up flagged as tinted for downstream tint-state
     *       computation, but the LOD shader's VOXY_NO_ATLAS path ignores
     *       atlas colour entirely and emits per-quad hash colours, so the
     *       incorrect tint flag doesn't affect the visual.</li>
     * </ul>
     *
     * Cost: a 12 KB memset per unique block state. The bake is called at
     * most a few hundred times per world load → negligible.
     */
    private void writeDefaultBakePattern(long destAddr) {
        long pixels = (long) this.width * 3L * (long) this.height * 2L;
        long addr = destAddr;
        for (long i = 0; i < pixels; i++) {
            org.lwjgl.system.MemoryUtil.memPutInt(addr,     0xFFFFFFFF);
            org.lwjgl.system.MemoryUtil.memPutInt(addr + 4, 0x80);
            addr += 8;
        }
    }

    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        VIEWS[i] = new Matrix4f(stack.last().pose());
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
