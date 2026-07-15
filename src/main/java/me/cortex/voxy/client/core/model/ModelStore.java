package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static me.cortex.voxy.client.core.gl.GLCompat.bindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    final IGpuBuffer modelBuffer;
    final IGpuBuffer modelColourBuffer;
    final IGpuTexture textures;
    public final int blockSampler = glGenSamplers();
    /**
     * Cross-backend sampler for {@link #textures}. Used by Metal's render
     * encoder path (MDIC's renderTerrainMetal). On GL we keep the legacy
     * {@link #blockSampler} that {@code glBindSampler}-binds directly.
     * Both samplers use the same filter/wrap state so visual output stays
     * consistent across backends.
     */
    public final me.cortex.voxy.client.core.gpu.IGpuSampler atlasSampler;

    public ModelStore() {
        this.modelBuffer = RenderBackendFactory.get().createBuffer(MODEL_SIZE * (1<<16));
        this.modelColourBuffer = RenderBackendFactory.get().createBuffer(4 * (1<<16));
        // M13 chunk 1: allocate the model atlas as CPU-uploadable. On Metal
        // this is Shared storage so `uploadSubImage2D` can push the bakery
        // results into it; on GL the call is identical to `store`. Default
        // sampler/sampling state stays GL-side.
        this.textures = RenderBackendFactory.get().createTexture()
                .storeUploadable(GL_RGBA8,
                        Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE),
                        ModelFactory.MODEL_TEXTURE_SIZE*3*256,
                        ModelFactory.MODEL_TEXTURE_SIZE*2*256)
                .name("ModelTextures");
        // Rejoin-gray forensics: the native handle identity is the whole
        // question (which MTLTexture does each session write/sample, and do
        // pointer values get recycled across teardowns). One line per store.
        if (this.textures instanceof me.cortex.voxy.client.core.metal.MetalTexture mt) {
            me.cortex.voxy.common.Logger.info(String.format(java.util.Locale.ROOT,
                    "[Metal-ATLASLIFE] model atlas CREATED handle=0x%x id=%d", mt.getHandle(), mt.id()));
        }
        zeroInitAtlas();


        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)

        // Cross-backend mirror of blockSampler — same filter/wrap state.
        // Used by Metal's RenderEncoder.setSampler path; GL still uses
        // glBindSampler(unit, this.blockSampler) for its raw-GL draws.
        this.atlasSampler = RenderBackendFactory.get().createSampler(
                me.cortex.voxy.client.core.gpu.SamplerDesc.builder()
                        .filter(me.cortex.voxy.client.core.gpu.SamplerDesc.Filter.NEAREST,
                                me.cortex.voxy.client.core.gpu.SamplerDesc.Filter.NEAREST)
                        .mipFilter(me.cortex.voxy.client.core.gpu.SamplerDesc.MipFilter.LINEAR)
                        .wrap(me.cortex.voxy.client.core.gpu.SamplerDesc.Wrap.CLAMP_TO_EDGE,
                                me.cortex.voxy.client.core.gpu.SamplerDesc.Wrap.CLAMP_TO_EDGE)
                        .lod(0, mipLvl)
                        .label("ModelAtlasSampler")
                        .build());
    }


    /**
     * World-rejoin fix: a fresh Shared MTLTexture has UNDEFINED contents.
     * The FIRST texture of a process happens to land on zeroed fresh pages
     * (unbaked atlas cells read alpha 0 and the shader alpha-discards them
     * until their bake arrives), but a texture allocated after a world
     * reload gets RECYCLED driver memory — pale garbage with nonzero alpha
     * in every cell whose bake upload hasn't landed yet, i.e. white-ish
     * "textured" LODs right after rejoin. Same bug class as the historical
     * HiZ undefined-contents fix. Zero-fill every mip so a rejoin behaves
     * exactly like the first boot; GL path untouched (upstream behaviour),
     * VOXY_ATLAS_ZERO_INIT=0 reverts.
     */
    private void zeroInitAtlas() {
        if (RenderBackendFactory.get().getType() == me.cortex.voxy.client.core.gpu.BackendType.OPENGL) return;
        if ("0".equals(System.getenv("VOXY_ATLAS_ZERO_INIT"))) return;
        long start = System.nanoTime();
        int levels = Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE);
        int w0 = ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256;
        int h0 = ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256;
        // One reusable zero band, replaceRegion'd across each mip in strips
        // (a full mip-0 scratch would be ~400 MB; the band caps it at ~24 MB).
        int bandRows = Math.max(1, (24 << 20) / (w0 * 4));
        long scratch = org.lwjgl.system.MemoryUtil.nmemCalloc(1, (long) w0 * 4 * bandRows);
        if (scratch == 0) throw new OutOfMemoryError("model atlas zero-init scratch");
        long bytes = 0;
        try {
            for (int level = 0; level < levels; level++) {
                int w = Math.max(1, w0 >> level);
                int h = Math.max(1, h0 >> level);
                int rowsPerBand = Math.max(1, (int) Math.min(h, ((long) bandRows * w0) / w));
                for (int y = 0; y < h; y += rowsPerBand) {
                    int rows = Math.min(rowsPerBand, h - y);
                    this.textures.uploadSubImage2D(level, 0, y, w, rows, GL_RGBA, GL_UNSIGNED_BYTE, scratch);
                    bytes += (long) w * rows * 4;
                }
            }
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(scratch);
        }
        me.cortex.voxy.common.Logger.info("[Metal-LODTEST] model atlas zero-init: "
                + (bytes >> 20) + " MB across " + levels + " mips in "
                + ((System.nanoTime() - start) / 1_000_000) + " ms (rejoin recycled-memory guard;"
                + " VOXY_ATLAS_ZERO_INIT=0 reverts)");
    }

    public void free() {
        if (this.textures instanceof me.cortex.voxy.client.core.metal.MetalTexture mt) {
            me.cortex.voxy.common.Logger.info(String.format(java.util.Locale.ROOT,
                    "[Metal-ATLASLIFE] model atlas FREED handle=0x%x id=%d", mt.getHandle(), mt.id()));
        }
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.textures.free();
        this.atlasSampler.close();
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id());
        bindTextureUnit(textureBindingIndex, this.textures.id());
        glBindSampler(textureBindingIndex, this.blockSampler);
    }

    /**
     * Encoder-aware overload — binds model + colour SSBOs and (M13 chunk 1
     * onward) the model atlas texture + cross-backend sampler. Used by
     * Metal's MDIC render path.
     */
    public void bindBuffers(me.cortex.voxy.client.core.gpu.RenderEncoder encoder,
                            int modelBindingIndex, int colourBindingIndex,
                            int atlasBindingIndex) {
        encoder.setBuffer(modelBindingIndex, this.modelBuffer, 0);
        encoder.setBuffer(colourBindingIndex, this.modelColourBuffer, 0);
        encoder.setTexture(atlasBindingIndex, this.textures);
        encoder.setSampler(atlasBindingIndex, this.atlasSampler);
        // Rejoin-gray forensics (VOXY_ATLAS_VERIFY): confirm the DRAW binds
        // the same native texture the uploads verified against.
        if (AtlasVerify.enabled() && (this.bindLogCounter++ % 1200) == 0
                && this.textures instanceof me.cortex.voxy.client.core.metal.MetalTexture mt) {
            me.cortex.voxy.common.Logger.info(String.format(java.util.Locale.ROOT,
                    "[Metal-ATLASLIFE] bind atlas handle=0x%x id=%d", mt.getHandle(), mt.id()));
        }
    }

    private int bindLogCounter;
}
