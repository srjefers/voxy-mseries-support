package me.cortex.voxy.client.core.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.model.bakery.ModelTextureBakery;
import me.cortex.voxy.client.core.rendering.util.RawDownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

import static me.cortex.voxy.client.core.model.ModelStore.MODEL_SIZE;
import static me.cortex.voxy.client.core.gl.GLCompat.textureSubImage2D;
import static org.lwjgl.opengl.GL11.*;

//Manages the storage and updating of model states, textures and colours

//Also has a fast long[] based metadata lookup for when the terrain mesher needs to look up the face occlusion data

//TODO: support more than 65535 states, what should actually happen is a blockstate is registered, the model data is generated, then compared
// to all other models already loaded, if it is a duplicate, create a mapping from the id to the already loaded id, this will help with meshing aswell
// as leaves and such will be able to be merged



//TODO: NOTE!!! is it worth even uploading as a 16x16 texture, since automatic lod selection... doing 8x8 textures might be perfectly ok!!!
// this _quarters_ the memory requirements for the texture atlas!!! WHICH IS HUGE saving
public class ModelFactory {
    /**
     * Metal only: bake ice / frosted ice into the solid LOD layer (see the render-layer choice in the
     * bake). VOXY_LOD_ICE_OPAQUE=0 keeps them translucent. GL keeps the upstream layer.
     */
    private static final boolean ICE_OPAQUE =
            !"0".equals(String.valueOf(System.getenv("VOXY_LOD_ICE_OPAQUE")).trim())
            && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
    static {
        if (ICE_OPAQUE) {
            me.cortex.voxy.common.Logger.info("[Metal-LODTEST] LOD ice baked as solid without a vx shader pack (no"
                    + " stacked translucent layers on frozen water); VOXY_LOD_ICE_OPAQUE=0 reverts");
        }
    }

    /**
     * H4 (Metal, with ICE_OPAQUE): the solid LOD ice showed the bare ice texture, while Sodium draws ice translucent
     * (alpha ~0.75) over the dark water under it. Measured at level 0 against Sodium, sea ice came out x1.18 brighter
     * and bluer (dE 9.8). The bake now puts each ice pixel over the water first, with its own alpha:
     * rgb = a * ice + (1 - a) * ICE_WATER, alpha 255. ICE_WATER is what Sodium shows under the ice, fitted on the
     * frozen ocean at (237, 383) from Y 300 at noon: open water there is (36, 36, 105), but with it the LOD ice came out
     * 3 % / 7 % too high in green / blue (dE 5.1); the water under ice is darker (lit through the ice) and the
     * sensitivity is ~0.24 per unit, giving (36, 19, 47). VOXY_LOD_ICE_OVER_WATER=0 reverts.
     */
    private static final boolean ICE_OVER_WATER = ICE_OPAQUE
            && !"0".equals(String.valueOf(System.getenv("VOXY_LOD_ICE_OVER_WATER")).trim());
    private static final int[] ICE_WATER = {36, 19, 47};
    static {
        if (ICE_OVER_WATER) {
            me.cortex.voxy.common.Logger.info("[Metal-LODTEST] H4 LOD ice baked over water (36,19,47) with its alpha"
                    + " without a vx shader pack, like Sodium's translucent ice; VOXY_LOD_ICE_OVER_WATER=0 reverts");
        }
    }

    /**
     * H4 (Metal): leaf models carry model flag 128 so the LOD brightness compensation (MDICSectionRenderer,
     * VOXY_LOD_BRIGHTNESS_LEAVES_ONLY) darkens canopies only: Sodium's AO darkens a canopy (~x0.89) but not flat
     * open ground. The switch VOXY_LOD_AO_LEAVES=0 lives in MDICSectionRenderer; the flag is harmless without it.
     */
    private static final boolean METAL_LEAVES = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
            != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;

    /** H4 ICE_OVER_WATER: replaces each written face with the ice composited over the water (see ICE_WATER). */
    private static void metalIceOverWater(ColourDepthTextureData[] textureData) {
        for (int face = 0; face < textureData.length; face++) {
            var tex = textureData[face];
            if (tex == null) continue;
            int[] src = tex.colour();
            int[] out = new int[src.length];
            for (int i = 0; i < src.length; i++) {
                int p = src[i];
                int a = p >>> 24;
                if (a == 0) {
                    out[i] = p;
                    continue;
                }
                int r = (p & 0xFF), g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
                r = (r * a + ICE_WATER[0] * (255 - a) + 127) / 255;
                g = (g * a + ICE_WATER[1] * (255 - a) + 127) / 255;
                b = (b * a + ICE_WATER[2] * (255 - a) + 127) / 255;
                out[i] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
            textureData[face] = new ColourDepthTextureData(out, tex.depth(), tex.width(), tex.height());
        }
    }

    /** Far-LOD plant work S1 (2026-09-25): plant cross models bake without
     *  dilation and get centred side faces on Metal. VOXY_LOD_PLANT_CROSS=0
     *  reverts to the hollow-box behaviour. Read from the bakery too. */
    public static final boolean PLANT_CROSS = !"0".equals(System.getenv("VOXY_LOD_PLANT_CROSS"));
    private static final java.util.concurrent.atomic.AtomicBoolean PLANT_CROSS_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * H16 (Metal only): "flat" blocks, whose shape is ONE box covering the whole footprint from the
     * floor up to less than a block (snow layers 1-7, carpets, bottom slabs, farmland, dirt path...).
     * The Metal bake writes no depth (MetalViewCapture.emitToStream), so their top face came out at
     * offset 0, on the cell top, and the bake fill turned their short side faces into full opaque
     * tiles: the LOD drew a snow layer as a full snow cube, 0.875 blocks above vanilla. At the
     * vanilla/LOD seam that left a camera-facing step (the dark "stair" line on snow, seen through the
     * spyglass), and the LOD inside the vanilla ring stood above the real snow (a red band under
     * VOXY_BOUND_DEBUG, only on snow). For these blocks the bakery keeps the real side coverage (no
     * fill), the top face gets its real indent (1 - height, what the GL bake measures), and model flag
     * 32 keeps that indent scaled with the cell at every level (quad_util), so a coarse flat voxel stays
     * a closed slab. VOXY_LOD_FLAT_HEIGHT=0 reverts. Read from the bakery too. GL is unchanged.
     */
    public static final boolean FLAT_HEIGHT =
            !"0".equals(String.valueOf(System.getenv("VOXY_LOD_FLAT_HEIGHT")).trim())
            && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
    private static final java.util.concurrent.atomic.AtomicInteger FLAT_LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Height in (0, 1) of a {@link #FLAT_HEIGHT} block's shape, or -1 for every other state (fluids,
     * waterlogged states, full or empty shapes, several boxes, an inset footprint) and when the
     * switch is off.
     */
    public static double metalFlatHeight(BlockState state) {
        if (!FLAT_HEIGHT || state.getBlock() instanceof LiquidBlock || !state.getFluidState().isEmpty()) {
            return -1;
        }
        VoxelShape shape;
        try {
            shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return -1;// a shape that needs a real level: leave the bake as it was
        }
        if (shape.isEmpty()) {
            return -1;
        }
        var boxes = shape.toAabbs();
        if (boxes.size() != 1) {
            return -1;
        }
        AABB box = boxes.get(0);
        final double eps = 1.0e-4;
        if (box.minX > eps || box.minZ > eps || box.minY > eps
                || box.maxX < 1 - eps || box.maxZ < 1 - eps
                || box.maxY <= eps || box.maxY >= 1 - eps) {
            return -1;
        }
        return box.maxY;
    }

    /**
     * H18 (Metal only): bamboo stalks. Vanilla draws a 2 px (age 0) or 3 px (age 1) square stalk at the
     * cell centre, plus leaves on two axis-aligned planes through the centre. The Metal bake writes no
     * depth (MetalViewCapture.emitToStream) and fills every empty pixel of a face cell with the cell's
     * average colour, so the stalk's side cells came out as full opaque tiles on the cell walls: the LOD
     * drew every bamboo block as a full-width column with a flat top and no leaves, at every level and
     * under the spyglass too. For bamboo the bakery now keeps the real coverage (no fill) and the side
     * faces get the stalk's real indent ({@link #metalBambooIndent}, what the GL bake's depth measures);
     * model flag 64 keeps that indent scaled with the cell at every level (quad_util), so a coarse bamboo
     * voxel stays a centred stalk, and exempts the faces from the distance-mip alpha test (quads.frag),
     * which would erase a 3/16-wide stalk at LOD distances. VOXY_LOD_BAMBOO=0 reverts. GL is unchanged.
     */
    public static final boolean BAMBOO_STALK =
            !"0".equals(String.valueOf(System.getenv("VOXY_LOD_BAMBOO")).trim())
            && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
    private static final java.util.concurrent.atomic.AtomicInteger BAMBOO_LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Indent in (0, 0.5) of a {@link #BAMBOO_STALK} state's side faces: the stalk's distance from the
     * cell wall (vanilla models bamboo{1..4}_age0: 7..9, _age1: 6.5..9.5). -1 for every other state
     * (and waterlogged ones, whose fluid overlay rides the same model) and when the switch is off.
     */
    public static float metalBambooIndent(BlockState state) {
        if (!BAMBOO_STALK || !(state.getBlock() instanceof BambooStalkBlock) || !state.getFluidState().isEmpty()) {
            return -1;
        }
        return state.getValue(BambooStalkBlock.AGE) == BambooStalkBlock.AGE_THIN_BAMBOO ? 7.0f / 16.0f : 6.5f / 16.0f;
    }

    /**
     * H19 (Metal only): leaves, and every other model with a DARK_CUTOUT texture (in vanilla only leaves and
     * mangrove roots). Vanilla mips these textures with the hole pixels counted as black, so far leaves get
     * darker, and at mip 0 it paints each hole with 3/4 of the texture's darkest pixel at alpha 0. Voxy bakes
     * leaves as SOLID (no discard in the bake), so the GL bake keeps those dark hole pixels (its stencil marks
     * them drawn) and MipGen darkens the mips the same way as vanilla. The Metal bake has no stencil and took
     * alpha 0 for "not drawn": it filled every hole with the average of the leaf pixels at alpha 255. The LOD
     * leaves came out 7-36 % brighter than vanilla's at every level (green channel: jungle +17 %, oak +19 %,
     * spruce +24 %, birch +29 %), and MipGen's darkening found no hole left to darken. For these bakes the
     * bakery now keeps the holes and counts any drawn pixel (any channel above zero) as written, which is
     * what the GL stencil records. Coverage, face bounds and occlusion stay as before (every texel is drawn).
     * VOXY_LOD_LEAVES_DARK=0 reverts. GL is unchanged.
     */
    public static final boolean LEAVES_DARK =
            !"0".equals(String.valueOf(System.getenv("VOXY_LOD_LEAVES_DARK")).trim())
            && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;

    /**
     * H19 (Metal only): bamboo leaves. Vanilla's bamboo leaves (bamboo_small_leaves / bamboo_large_leaves) are
     * {@code "shade": false} and {@code "ambientocclusion": false}; the stalk under them is shaded. Voxy keeps one
     * shade flag per model (any shaded quad sets it), so a leafy bamboo block took the stalk's flag and the LOD
     * darkened its leaves by the side-face shade (0.8 north/south, 0.6 east/west) and by the AO stand-in
     * (VOXY_LOD_BRIGHTNESS, which quads.frag now applies only to models with AO): leafy bamboo slopes came out
     * about 17 % darker than Sodium at level 0. Leafy bamboo states are now unshaded, like their leaves; the
     * 3/16-wide stalk on those blocks loses its side shade, a far smaller error. VOXY_LOD_BAMBOO_UNSHADED=0
     * reverts. GL is unchanged.
     */
    public static final boolean BAMBOO_UNSHADED =
            !"0".equals(String.valueOf(System.getenv("VOXY_LOD_BAMBOO_UNSHADED")).trim())
            && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
    private static final java.util.concurrent.atomic.AtomicInteger BAMBOO_UNSHADED_LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** True for the bamboo states {@link #BAMBOO_UNSHADED} bakes unshaded: those with leaves. */
    public static boolean metalBambooUnshaded(BlockState state) {
        return BAMBOO_UNSHADED && state.getBlock() instanceof BambooStalkBlock
                && state.getValue(BambooStalkBlock.LEAVES) != net.minecraft.world.level.block.state.properties.BambooLeaves.NONE;
    }

    /**
     * H13 snow (Metal only): snowy=true blocks (grass, podzol, mycelium with snow on top). Vanilla never shows their
     * top face (the snow layer covers it) and their model (grass_block_snow) has no tinted quad. The Metal surface
     * mip now keeps such a block for a coarse cell whose top is snow (Mipper, VOXY_MIP_SNOW_GRASS), so the bake
     * draws its UP face as snow_block's and the model gets no colour provider: the Metal capture marks every pixel
     * tinted (MetalViewCapture), which tinted the white snow fringe of the side and would tint the snow top with the
     * biome's grass colour. The cell then looks like level 0: white on top, dirt with a snow fringe on the sides.
     * VOXY_LOD_SNOWY_TOP=0 reverts. GL is unchanged.
     */
    public static final boolean SNOWY_TOP =
            !"0".equals(String.valueOf(System.getenv("VOXY_LOD_SNOWY_TOP")).trim())
            && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;

    /** True for the states {@link #SNOWY_TOP} bakes with a snow top and no tint. */
    public static boolean metalSnowyTop(BlockState state) {
        return SNOWY_TOP && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY);
    }

    public static final int MODEL_TEXTURE_SIZE = 16;
    public static final int LAYERS = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE);

    //TODO: replace the fluid BlockState with a client model id integer of the fluidState, requires looking up
    // the fluid state in the mipper
    private record ModelEntry(ColourDepthTextureData down, ColourDepthTextureData up, ColourDepthTextureData north, ColourDepthTextureData south, ColourDepthTextureData west, ColourDepthTextureData east, int fluidBlockStateId, int tintingColour) {
        public ModelEntry(ColourDepthTextureData[] textures, int fluidBlockStateId, int tintingColour) {
            this(textures[0], textures[1], textures[2], textures[3], textures[4], textures[5], fluidBlockStateId, tintingColour);
        }
    }

    private final Biome DEFAULT_BIOME = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME).getValue(Biomes.PLAINS);

    public final ModelTextureBakery bakery;


    //Model data might also contain a constant colour if the colour resolver produces a constant colour, this saves space in the
    // section buffer reverse indexing

    //model data also contains if a face should be randomly rotated,flipped etc to get rid of moire effect
    // this would be done in the fragment shader

    //The Meta-cache contains critical information needed for meshing, colour provider bit, per-face = is empty, has alpha, is solid, full width, full height
    // alpha means that some pixels have alpha values and belong in the translucent rendering layer,
    // is empty means that the face is air/shouldent be rendered as there is nothing there
    // is solid means that every pixel is fully opaque
    // full width, height, is if the blockmodel dimentions occupy a full block, e.g. comparator, some faces do some dont and some only in a specific axis

    //FIXME: the issue is e.g. leaves are translucent but the alpha value is used to colour the leaves, so a block can have alpha but still be only made up of transparent or opaque pixels
    // will need to find a way to send this info to the shader via the material, if it is in the opaque phase render as transparent with blending shiz

    //TODO: ADD an occlusion mask that can be queried (16x16 pixels takes up 4 longs) this mask shows what pixels are exactly occluded at the edge of the block
    // so that full block occlusion can work nicely


    //TODO: what might work maybe, is that all the transparent pixels should be set to the average of the other pixels
    // that way the block is always "fully occluding" (if the block model doesnt cover the entire thing), maybe
    // this has some issues with quad merging
    //TODO: ACTUALLY, full out all the transparent pixels that are _within_ the bounding box of the model
    // this will mean that when quad merging and rendering, the transparent pixels of the block where there shouldent be
    // might still work???

    // this has an issue with scaffolding i believe tho, so maybe make it a probability to render??? idk
    private final long[] metadataCache;
    private final int[] fluidStateLUT;

    //Provides a map from id -> model id as multiple ids might have the same internal model id
    private final int[] idMappings;
    private final Object2IntOpenHashMap<ModelEntry> modelTexture2id = new Object2IntOpenHashMap<>();

    //Contains the set of all block ids that are currently inflight/being baked
    // this is required due to "async" nature of gpu feedback
    private final IntOpenHashSet blockStatesInFlight = new IntOpenHashSet();
    private final ReentrantLock blockStatesInFlightLock = new ReentrantLock();

    private final List<Biome> biomes = new ArrayList<>();
    private final List<Pair<Integer, BlockState>> modelsRequiringBiomeColours = new ArrayList<>();

    private static final ObjectSet<BlockState> LOGGED_SELF_CULLING_WARNING = new ObjectOpenHashSet<>();

    private final Mapper mapper;
    private final ModelStore storage;
    // Metal-only LOD water animation (null on GL / when killed via
    // VOXY_WATER_ANIMATE=0) — see WaterAnimator.
    private final WaterAnimator waterAnimator;
    private final RawDownloadStream downstream = new RawDownloadStream(8*1024*1024);//8mb downstream

    private final ConcurrentLinkedDeque<RawBakeResult> rawBakeResults = new ConcurrentLinkedDeque<>();

    /** Diagnostics for the bakery→atlas pipeline (read by AbstractRenderPipeline). */
    public static final java.util.concurrent.atomic.AtomicLong DIAG_ADDENTRY_CALLS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DIAG_CPYBUF_CALLBACKS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DIAG_PROCESS_MODEL_RESULTS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DIAG_ATLAS_UPLOADS = new java.util.concurrent.atomic.AtomicLong();

    private final ConcurrentLinkedDeque<ResultUploader> uploadResults = new ConcurrentLinkedDeque<>();

    private Object2IntMap<BlockState> customBlockStateIdMapping;

    //TODO: NOTE!!! is it worth even uploading as a 16x16 texture, since automatic lod selection... doing 8x8 textures might be perfectly ok!!!
    // this _quarters_ the memory requirements for the texture atlas!!! WHICH IS HUGE saving
    public ModelFactory(Mapper mapper, ModelStore storage) {
        this.mapper = mapper;
        this.storage = storage;
        this.waterAnimator = WaterAnimator.createIfEnabled(storage);
        this.bakery = new ModelTextureBakery(MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);

        this.metadataCache = new long[1<<16];
        this.fluidStateLUT = new int[1<<16];
        this.idMappings = new int[1<<20];//Max of 1 million blockstates mapping to 65k model states
        Arrays.fill(this.idMappings, -1);
        Arrays.fill(this.fluidStateLUT, -1);

        this.modelTexture2id.defaultReturnValue(-1);
        this.addEntry(0);//Add air as the first entry
    }

    public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
        this.customBlockStateIdMapping = mapping;
    }

    /**
     * Set by MetalVxRenderPipeline (Metal + an Iris pack with the vx contract) before any bake: ice stays in its
     * vanilla translucent layer, without the H15 solid bake and the H4 bake over water. Those two match the plain
     * no-pack path; under a pack Sodium's ice goes through the pack's water program (BSL: id 202, sky reflection and
     * sun specular by fresnel), and only a translucent LOD ice reaches the pack's voxy_translucent to get the same.
     */
    private boolean packIceTranslucent = false;

    public void setPackIceTranslucent(boolean packIceTranslucent) {
        this.packIceTranslucent = packIceTranslucent;
    }

    private static final class RawBakeResult {
        private final int blockId;
        private final BlockState blockState;
        private final MemoryBuffer rawData;

        public boolean isShaded;
        public boolean hasDarkenedTextures;

        public RawBakeResult(int blockId, BlockState blockState, MemoryBuffer rawData) {
            this.blockId = blockId;
            this.blockState = blockState;
            this.rawData = rawData;
        }

        public RawBakeResult(int blockId, BlockState blockState) {
            this(blockId, blockState, new MemoryBuffer(MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*2*4*6));
        }

        public RawBakeResult cpyBuf(long ptr) {
            this.rawData.cpyFrom(ptr);
            return this;
        }
    }

    public boolean addEntry(int blockId) {
        if (this.idMappings[blockId] != -1) {
            return false;
        }
        //We are (probably) going to be baking the block id
        // check that it is currently not inflight, if it is, return as its already being baked
        // else add it to the flight as it is going to be baked
        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.add(blockId)) {
            this.blockStatesInFlightLock.unlock();
            //Block baking is already in-flight
            return false;
        }
        this.blockStatesInFlightLock.unlock();

        VarHandle.loadLoadFence();

        //We need to get it twice cause of threading
        if (this.idMappings[blockId] != -1) {
            return false;
        }

        var blockState = this.mapper.getBlockStateFromBlockId(blockId);

        //Before we enqueue the baking of this blockstate, we must check if it has a fluid state associated with it
        // if it does, we must ensure that it is (effectivly) baked BEFORE we bake this blockstate
        boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            //Insert into the fluid LUT
            var fluidState = blockState.getFluidState().createLegacyBlock();

            int fluidStateId = this.mapper.getIdForBlockState(fluidState);

            if (this.idMappings[fluidStateId] == -1) {
                //Dont have to check for inflight as that is done recursively :p

                //This is a hack but does work :tm: due to how the download stream is setup
                // it should enforce that the fluid state is processed before our blockstate
                addEntry(fluidStateId);
            }
        }

        DIAG_ADDENTRY_CALLS.incrementAndGet();

        RawBakeResult result = new RawBakeResult(blockId, blockState);
        if (this.bakery.shouldUseMetalDefaultBake()) {
            int flags = this.bakery.renderDefaultBakeToHeap(blockState, result.rawData.address);
            result.hasDarkenedTextures = (flags&2)!=0;
            result.isShaded = (flags&1)!=0;
            this.rawBakeResults.add(result);
            return true;
        }

        int allocation = this.downstream.download(MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*2*4*6, ptr -> {
            DIAG_CPYBUF_CALLBACKS.incrementAndGet();
            this.rawBakeResults.add(result.cpyBuf(ptr));
        });
        // M13 chunk 1: renderToStream now takes the CPU-mapped destination
        // address directly; the bakery does a glFinish + glGetTexImage CPU
        // readback into this addr instead of issuing a GL 4.3 compute that
        // writes the persistent buffer through an SSBO bind. Works on
        // Apple's GL 4.1 cap; the downstream fence still signals on next
        // tick so callbacks fire in normal order.
        int flags = this.bakery.renderToStream(blockState, this.downstream.getBufferAddr() + allocation);
        result.hasDarkenedTextures = (flags&2)!=0;
        result.isShaded = (flags&1)!=0;
        return true;
    }

    private boolean processModelResult() {
        var result = this.rawBakeResults.poll();
        if (result == null) return false;
        ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];
        {//Create texture data
            long ptr = result.rawData.address;
            final int FACE_SIZE = MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
            for (int face = 0; face < 6; face++) {
                long faceDataPtr = ptr + (FACE_SIZE * 4) * face * 2;
                int[] colour = new int[FACE_SIZE];
                int[] depth = new int[FACE_SIZE];

                //Copy out colour
                for (int i = 0; i < FACE_SIZE; i++) {
                    //De-interpolate results
                    colour[i] = MemoryUtil.memGetInt(faceDataPtr + (i * 4 * 2));
                    depth[i] = MemoryUtil.memGetInt(faceDataPtr + (i * 4 * 2) + 4);
                }
                textureData[face] = new ColourDepthTextureData(colour, depth, MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);
            }
        }
        result.rawData.free();
        var bakeResult = this.processTextureBakeResult(result.blockId, result.blockState, textureData, result.isShaded, result.hasDarkenedTextures);
        if (bakeResult!=null) {
            DIAG_PROCESS_MODEL_RESULTS.incrementAndGet();
            this.uploadResults.add(bakeResult);
        }
        return !this.rawBakeResults.isEmpty();
    }

    private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();
    public void addBiome(Mapper.BiomeEntry biome) {
        this.biomeQueue.add(biome);
    }

    public void processAllThings() {
        var biomeEntry = this.biomeQueue.poll();
        while (biomeEntry != null) {
            var biomeRegistry = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME);
            var mcbiomeEntry = biomeRegistry.get(Identifier.parse(biomeEntry.biome));
            if (!mcbiomeEntry.isPresent()) {
                Logger.error("Could not find biome: " + biomeEntry.biome + " using default");
            }
            var res = this.addBiome0(biomeEntry.id, mcbiomeEntry.isPresent()?mcbiomeEntry.orElseThrow().value():DEFAULT_BIOME);
            if (res != null) {
                this.uploadResults.add(res);
            }
            biomeEntry = this.biomeQueue.poll();
        }

        while (this.processModelResult());
    }

    public void tickAndProcessUploads() {
        this.downstream.tick();

        // Metal water animation: runs BEFORE the upload drain so a target
        // registered below (same tick as its bake upload) gets its first
        // animated frame on the NEXT tick — always after the frozen bake
        // frame landed, never racing it.
        if (this.waterAnimator != null) {
            this.waterAnimator.tick();
        }

        // Rejoin-gray forensics: sentinel re-check must run every tick, even
        // when no uploads are pending (it hunts post-hoc scribbling).
        AtlasVerify.tick(this.storage.textures);

        var upload = this.uploadResults.poll();
        if (upload==null) return;

        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        do {
            // Register water-animated cells on the render thread while the
            // upload still carries its modelId (upload() resets it to -1).
            if (this.waterAnimator != null
                    && upload instanceof ModelBakeResultUpload bake
                    && bake.waterAnimFaces != 0) {
                this.waterAnimator.register(bake.modelId, bake.waterAnimFaces);
            }
            upload.upload(this.storage);
            DIAG_ATLAS_UPLOADS.incrementAndGet();
            upload.free();
            upload = this.uploadResults.poll();
        } while (upload != null);
        UploadStream.INSTANCE.commit();
    }

    private interface ResultUploader {
        void upload(ModelStore store);
        void free();
    }

    private static final class ModelBakeResultUpload implements ResultUploader {
        private final MemoryBuffer model = new MemoryBuffer(MODEL_SIZE).zero();
        private final MemoryBuffer texture = new MemoryBuffer((2L*3*computeSizeWithMips(MODEL_TEXTURE_SIZE))*4);

        public int modelId = -1;

        /** Metal water animation: face mask (WaterAnimator.STILL_WATER_FACES)
         * set on the factory thread for source-water fluid bakes; consumed on
         * the render thread in tickAndProcessUploads. 0 = not animated. */
        public int waterAnimFaces = 0;

        public int biomeUploadIndex = -1;
        public @Nullable MemoryBuffer biomeUpload;

        public void upload(ModelStore store) {//Uploads and resets for reuse
            this.upload(store.modelBuffer, store.modelColourBuffer, store.textures);
        }

        public void upload(IGpuBuffer modelBuffer, IGpuBuffer colourBuffer, IGpuTexture atlas) {//Uploads and resets for reuse
            this.model.cpyTo(UploadStream.INSTANCE.upload(modelBuffer, (long) this.modelId * MODEL_SIZE, MODEL_SIZE));
            if (this.biomeUploadIndex != -1) {
                this.biomeUpload.cpyTo(UploadStream.INSTANCE.upload(colourBuffer, this.biomeUploadIndex * 4L, this.biomeUpload.size));
                this.biomeUploadIndex = -1;
                this.biomeUpload.free();
                this.biomeUpload = null;
            }

            int X = (this.modelId&0xFF) * MODEL_TEXTURE_SIZE*3;
            int Y = ((this.modelId>>8)&0xFF) * MODEL_TEXTURE_SIZE*2;

            long cAddr = this.texture.address;
            for (int lvl = 0; lvl < LAYERS; lvl++) {
                // M13 chunk 1: route the atlas upload through the cross-backend
                // primitive so the Metal-side atlas (Shared-storage MetalTexture
                // allocated via storeUploadable) receives the data correctly.
                // On the GL backend this still lowers to glTextureSubImage2D /
                // glTexSubImage2D via GLCompat.
                atlas.uploadSubImage2D(lvl, X >> lvl, Y >> lvl, (MODEL_TEXTURE_SIZE*3) >> lvl, (MODEL_TEXTURE_SIZE*2) >> lvl, GL_RGBA, GL_UNSIGNED_BYTE, cAddr);
                cAddr += (MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*3*2*4)>>(lvl<<1);
            }

            // Rejoin-gray forensics (VOXY_ATLAS_VERIFY): read the cell straight
            // back out of the MTLTexture and compare with what was just written.
            AtlasVerify.onUpload(atlas, this.modelId, this.texture.address, this.waterAnimFaces != 0);

            this.modelId = -1;
        }

        public void free() {
            this.model.free();
            this.texture.free();
            if (this.biomeUpload != null) {
                this.biomeUpload.free();
            }
        }
    }

    private ModelBakeResultUpload processTextureBakeResult(int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean isShaded, boolean darkenedTinting) {
        if (this.idMappings[blockId] != -1) {
            //This should be impossible to reach as it means that multiple bakes for the same blockId happened and where inflight at the same time!
            throw new IllegalStateException("Block id already added: " + blockId + " for state: " + blockState);
        }

        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.contains(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
        }
        this.blockStatesInFlightLock.unlock();

        //TODO: add thing for `blockState.hasEmissiveLighting()` and `blockState.getLuminance()`

        // H4: before the dedup below (it hashes the texture data)
        if (ICE_OVER_WATER && !this.packIceTranslucent && (blockState.is(Blocks.ICE) || blockState.is(Blocks.FROSTED_ICE))) {
            metalIceOverWater(textureData);
        }

        boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
        int modelId = -1;


        int clientFluidStateId = -1;

        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            //Insert into the fluid LUT
            var fluidState = blockState.getFluidState().createLegacyBlock();

            int fluidStateId = this.mapper.getIdForBlockState(fluidState);

            clientFluidStateId = this.idMappings[fluidStateId];
            if (clientFluidStateId == -1) {
                throw new IllegalStateException("Block has a fluid state but fluid state is not already baked!!!");
            }
        }

        // H13 snow (SNOWY_TOP): snowy grass has no tinted quad in vanilla; on Metal the capture would tint it all.
        var colourProvider = metalSnowyTop(blockState) ? null : getColourProvider(blockState.getBlock());

        boolean isBiomeColourDependent = false;
        if (colourProvider != null) {
            isBiomeColourDependent = isBiomeDependentColour(colourProvider, blockState);
        }

        ModelEntry entry;
        {//Deduplicate same entries
            entry = new ModelEntry(textureData, clientFluidStateId, isBiomeColourDependent||colourProvider==null?-1:captureColourConstant(colourProvider, blockState, DEFAULT_BIOME)|0xFF000000);
            int possibleDuplicate = this.modelTexture2id.getInt(entry);
            if (possibleDuplicate != -1) {//Duplicate found
                this.idMappings[blockId] = possibleDuplicate;
                modelId = possibleDuplicate;
                //Remove from flight
                this.blockStatesInFlightLock.lock();
                if (!this.blockStatesInFlight.remove(blockId)) {
                    this.blockStatesInFlightLock.unlock();
                    throw new IllegalStateException();
                }
                this.blockStatesInFlightLock.unlock();
                return null;
            } else {//Not a duplicate so create a new entry
                modelId = this.modelTexture2id.size();
                //NOTE: we set the mapping at the very end so that race conditions with this and getMetadata dont occur
                //this.idMappings[blockId] = modelId;
                this.modelTexture2id.put(entry, modelId);
            }
        }

        if (isFluid) {
            this.fluidStateLUT[modelId] = modelId;
        } else if (clientFluidStateId != -1) {
            this.fluidStateLUT[modelId] = clientFluidStateId;
        }

        ChunkSectionLayer blockRenderLayer = null;
        if (blockState.getBlock() instanceof LiquidBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(blockState.getFluidState());
        } else {
            if (blockState.getBlock() instanceof LeavesBlock) {
                blockRenderLayer = ChunkSectionLayer.SOLID;
            } else if (ICE_OPAQUE && !this.packIceTranslucent && (blockState.is(Blocks.ICE) || blockState.is(Blocks.FROSTED_ICE))) {
                // Metal (H15, 2026-11-24): frozen ocean stacks three translucent LOD
                // layers within a block — the ice top, the ice bottom and the top of
                // the water under it (translucent ice occludes nothing). Without a
                // shader pack the LOD translucent pass blends with no depth write and
                // no sorting, so every greedy quad came out lighter or greyer
                // depending on its draw order: the grey rectangle mosaic on far ice.
                // Render LOD ice as solid: it then occludes the water top beneath
                // and goes through the depth-written opaque pass (alpha forced to 1;
                // the ice texture covers the face, so no alpha discard). From LOD
                // distances it reads as the ice colour. VOXY_LOD_ICE_OPAQUE=0 reverts.
                blockRenderLayer = ChunkSectionLayer.SOLID;
            } else {
                blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(blockState);
            }
        }


        int checkMode = blockRenderLayer==ChunkSectionLayer.SOLID?TextureUtils.WRITE_CHECK_STENCIL:TextureUtils.WRITE_CHECK_ALPHA;




        ModelBakeResultUpload uploadResult = new ModelBakeResultUpload();
        uploadResult.modelId = modelId;
        long uploadPtr = uploadResult.model.address;

        // Metal water animation (2026-06-10): mark the source-water fluid
        // bake so the render thread registers its water_still cells (UP +
        // DOWN — the side faces sample water_flow, follow-up) with the
        // WaterAnimator once this upload lands. Waterlogged blocks map to
        // this same model through the fluid LUT, so they share the cells.
        if (this.waterAnimator != null && isFluid
                && blockState.is(Blocks.WATER)
                && blockState.getFluidState().isSource()) {
            uploadResult.waterAnimFaces = WaterAnimator.STILL_WATER_FACES;
        }

        //TODO: implement;
        // TODO: if it has a constant colour instead... idk why (apparently for things like spruce leaves)?? but premultiply the texture data by the constant colour

        //If it contains fluid but isnt a fluid
        if ((!isFluid) && (!blockState.getFluidState().isEmpty()) && clientFluidStateId != -1) {

            //Or it with the fluid state biome dependency
            isBiomeColourDependent |= ModelQueries.isBiomeColoured(this.getModelMetadataFromClientId(clientFluidStateId));
        }



        //TODO: special case stuff like vines and glow lichen, where it can be represented by a single double sided quad
        // since that would help alot with perf of lots of vines, can be done by having one of the faces just not exist and the other be in no occlusion mode

        var sizes = this.computeModelDepth(textureData, checkMode);

        // Metal water faces (2026-06-09 update). The 2026-05-26 zero-alpha fluid
        // bakes were the far-plane clip in renderToStreamMetal: every fluid face
        // quad except UP sat at view z = 1.0 ± float-ε, i.e. ON Metal's far clip
        // plane, and an ε overshoot clipped the whole quad → empty cell → fd=-1
        // here → faceExists()=false → the mesher culled the water surface (the
        // "grey seafloor"). Fixed at the source: the Metal bake projection now
        // compresses z to NDC [0.25, 0.75], so water faces bake real alpha and
        // computeModelDepth (WRITE_CHECK_ALPHA for TRANSLUCENT) keeps them.
        //
        // VOXY_WATER_FORCE_FACES therefore stays DEFAULT OFF: with the bake
        // fixed it is a no-op (sizes[face] >= 0 already), and when a face bake
        // is genuinely empty, forcing it only emits quads whose atlas cell is
        // transparent — the shader's alpha==0 discard makes them invisible, and
        // computeBounds on an empty face packs out-of-range face sizes (minX=16
        // overflows the 4-bit field below). Kept as an opt-in diagnostic.
        if (isFluid && "1".equals(System.getenv("VOXY_WATER_FORCE_FACES"))) {
            for (int face = 0; face < 6; face++) {
                if (sizes[face] < -0.1f) {
                    sizes[face] = 0.0f; // face at the block boundary → a flat water quad
                }
            }
        }

        // Metal fluid surface height (2026-06-10): the Metal capture path
        // synthesizes depth metadata from alpha (MetalViewCapture.emitToStream
        // writes 0x80/0 — no real depth bits), so computeModelDepth returns 0
        // for the fluid UP face and the LOD water plane lands at the full
        // block top (1.0) instead of MC's 8/9 ≈ 0.889 — a visible step at the
        // LOD<->MC water seam and a 0.111-block eye-level window where the two
        // sides disagree about "above water". Use the fluid's real own height
        // (packs enc=7 → plane at 0.8906, within 0.002 of MC). GL bakes real
        // depth and never hits the ==0 condition.
        if (isFluid
                && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                        != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            int up = Direction.UP.get3DDataValue();
            float ownHeight = blockState.getFluidState().getOwnHeight();
            if (sizes[up] >= 0.0f && sizes[up] < 0.01f && ownHeight > 0.0f && ownHeight < 1.0f) {
                sizes[up] = 1.0f - ownHeight;
            }
        }

        // Metal plant cross models (2026-09-25, far-LOD plant work S1): the
        // Metal capture writes no depth bits, so a cross model's four side
        // faces come out at offset 0 — ON the cell walls — instead of the
        // ~0.5 the GL bake measures for two 45-degree blades. Together with
        // the bake-fill dilation (skipped for plants now, see
        // MetalViewCapture.emitToStream) that made every tuft a hollow green
        // box at LOD 0 and let it occlude its neighbours' side faces. Put the
        // side faces at 0.5 (enc 32): quad_util mixes depthOffset with
        // 1-depthOffset per face parity, so both opposing faces coincide at
        // the cell centre = upstream's axis-aligned '+' cross, self-lit and
        // non-occluding (offset >= 0.1 clears occludesFace below).
        // GL bakes real depth and never hits the == 0 condition.
        boolean plantCrossModel = false;
        if (PLANT_CROSS && !isFluid
                && blockState.getFluidState().isEmpty()
                && blockState.getBlock() instanceof VegetationBlock
                && me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                        != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            plantCrossModel = true; // GPU model flag bit 16 (quads.frag edge-fade), Metal only
            boolean any = false;
            for (int face = 2; face < 6; face++) { // NORTH, SOUTH, WEST, EAST
                if (sizes[face] >= 0.0f && sizes[face] < 0.01f) {
                    sizes[face] = 0.5f;
                    any = true;
                }
            }
            if (any && PLANT_CROSS_LOGGED.compareAndSet(false, true)) {
                Logger.info("[Metal-LODTEST] plant cross bake ON: undilated side cells + centred depth 0.5 for "
                        + "VegetationBlock models (VOXY_LOD_PLANT_CROSS=0 reverts); first model " + blockState);
            }
        }

        // H16 flat blocks (see FLAT_HEIGHT): the top face gets its real indent. The bakery already
        // skipped the fill for them, so the side faces keep their real height (computeBounds below).
        boolean flatModel = false;
        if (!isFluid && !plantCrossModel) {
            double flatHeight = metalFlatHeight(blockState);
            int up = Direction.UP.get3DDataValue();
            if (flatHeight > 0 && sizes[up] >= 0.0f && sizes[up] < 0.01f) {
                sizes[up] = (float) (1.0 - flatHeight);
                flatModel = true; // GPU model flag bit 32 (quad_util keeps the indent scaled), Metal only
                boolean snow = blockState.getBlock() instanceof SnowLayerBlock;
                if (snow || FLAT_LOGGED.getAndIncrement() < 3) {
                    int[] north = TextureUtils.computeBounds(textureData[Direction.NORTH.get3DDataValue()], checkMode);
                    Logger.info("[Metal-LODTEST] H16 flat block " + blockState + ": height " + flatHeight
                            + ", top indent " + sizes[up] + ", north face rows " + north[2] + ".." + north[3]
                            + " of 0..15 (VOXY_LOD_FLAT_HEIGHT=0 reverts)");
                }
            }
        }

        // H18 bamboo (see BAMBOO_STALK): the side faces get the stalk's real indent. The bakery already
        // skipped the fill for them, so the face bounds (computeBounds below) are the stalk strip, or the
        // leaves' width on leafy blocks, instead of the whole cell. UP/DOWN stay on the cell boundary,
        // where the stalk ends in vanilla too.
        boolean bambooModel = false;
        if (!isFluid && !plantCrossModel && !flatModel) {
            float indent = metalBambooIndent(blockState);
            if (indent > 0) {
                for (int face = 2; face < 6; face++) { // NORTH, SOUTH, WEST, EAST
                    if (sizes[face] >= 0.0f && sizes[face] < 0.01f) {
                        sizes[face] = indent;
                        bambooModel = true; // GPU model flag bit 64 (quad_util, quads.frag), Metal only
                    }
                }
                if (bambooModel && BAMBOO_LOGGED.getAndIncrement() < 6) {
                    int north = Direction.NORTH.get3DDataValue();
                    int[] nb = TextureUtils.computeBounds(textureData[north], checkMode);
                    Logger.info("[Metal-LODTEST] H18 bamboo " + blockState + ": side indent " + indent
                            + ", north face cols " + nb[0] + ".." + nb[1] + " rows " + nb[2] + ".." + nb[3]
                            + ", " + TextureUtils.getWrittenPixelCount(textureData[north], checkMode)
                            + " px written (VOXY_LOD_BAMBOO=0 reverts)");
                }
            }
        }

        //TODO: THIS, note this can be tested for in 2 ways, re render the model with quad culling disabled and see if the result
        // is the same, (if yes then needs double sided quads)
        // another way to test it is if e.g. up and down havent got anything rendered but the sides do (e.g. all plants etc)
        boolean needsDoubleSidedQuads = (sizes[0] < -0.1 && sizes[1] < -0.1) || (sizes[2] < -0.1 && sizes[3] < -0.1) || (sizes[4] < -0.1 && sizes[5] < -0.1);


        boolean cullsSame = false;

        {
            //TODO: Could also move this into the RenderDataFactory and do it on the actual blockstates instead of a guestimation
            boolean allTrue = true;
            boolean allFalse = true;
            //Guestimation test for if the block culls itself
            for (var dir : Direction.values()) {
                if (blockState.skipRendering(blockState, dir)) {
                    allFalse = false;
                } else {
                    allTrue = false;
                }
            }

            if (allFalse == allTrue) {//If only some sides where self culled then abort
                cullsSame = false;
                //if (LOGGED_SELF_CULLING_WARNING.add(blockState))
                //    Logger.info("Warning! blockstate: " + blockState + " only culled against its self some of the time");
            }

            if (allTrue) {
                cullsSame = true;
            }
        }


        //Each face gets 1 byte, with the top 2 bytes being for whatever
        long metadata = 0;
        metadata |= isBiomeColourDependent?1:0;
        metadata |= blockRenderLayer == ChunkSectionLayer.TRANSLUCENT?2:0;
        metadata |= needsDoubleSidedQuads?4:0;
        metadata |= ((!isFluid) && !blockState.getFluidState().isEmpty())?8:0;//Has a fluid state accosiacted with it and is not itself a fluid
        metadata |= isFluid?16:0;//Is a fluid

        metadata |= cullsSame?32:0;

        // Cross-model vegetation (VegetationBlock: grass/flowers/saplings/ferns on
        // 1.21.11 — BushBlock is now the decorative bush block) has no UP/DOWN bake
        // and the mesher scales its side quads to the full 2^L cell, so a single
        // blade that wins the Mipper's representative-voxel selection becomes a
        // hollow green cube shell at distance. Flag it (128 → bit 55 after the
        // face-loop shift; bits 48-54 are taken) so RenderDataFactory can treat far
        // plant voxels as air. Waterlogged plants are excluded: culling the voxel
        // would also cull its fluid overlay (containsFluid rides the same entry).
        metadata |= ((!isFluid) && blockState.getFluidState().isEmpty()
                && blockState.getBlock() instanceof VegetationBlock)?128:0;

        boolean fullyOpaque = true;

        //TODO: FIXME faces that have the same "alignment depth" e.g. (sizes[0]+sizes[1])~=1 can be merged into a double faced single quad

        //TODO: add a bunch of control config options for overriding/setting options of metadata for each face of each type
        for (int face = 5; face != -1; face--) {//In reverse order to make indexing into the metadata long easier
            long faceUploadPtr = uploadPtr + 4L * face;//Each face gets 4 bytes worth of data
            metadata <<= 8;
            float offset = sizes[face];
            if (offset < -0.1) {//Face is empty, so ignore
                metadata |= 0xFF;//Mark the face as non-existent
                //Set to -1 as safepoint
                MemoryUtil.memPutInt(faceUploadPtr, -1);

                fullyOpaque = false;
                continue;
            }
            var faceSize = TextureUtils.computeBounds(textureData[face], checkMode);
            int writeCount = TextureUtils.getWrittenPixelCount(textureData[face], checkMode);

            boolean faceCoversFullBlock = faceSize[0] == 0 && faceSize[2] == 0 &&
                    faceSize[1] == (MODEL_TEXTURE_SIZE-1) && faceSize[3] == (MODEL_TEXTURE_SIZE-1);

            //TODO: use faceSize and the depths to compute if mesh can be correctly rendered

            metadata |= faceCoversFullBlock?2:0;

            //TODO: add alot of config options for the following
            boolean occludesFace = true;
            occludesFace &= blockRenderLayer != ChunkSectionLayer.TRANSLUCENT;//If its translucent, it doesnt occlude

            //TODO: make this an option, basicly if the face is really close, it occludes otherwise it doesnt
            occludesFace &= offset < 0.1;//If the face is rendered far away from the other face, then it doesnt occlude

            if (occludesFace) {
                occludesFace &= ((float)writeCount)/(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE) > 0.9;// only occlude if the face covers more than 90% of the face
            }
            metadata |= occludesFace?1:0;
            fullyOpaque &= occludesFace;



            boolean canBeOccluded = true;
            //TODO: make this an option on how far/close
            canBeOccluded &= offset < 0.3;//If the face is rendered far away from the other face, then it cant be occluded

            metadata |= canBeOccluded?4:0;

            //Face uses its own lighting if its not flat against the adjacent block & isnt traslucent
            metadata |= (offset > 0.01 || blockRenderLayer == ChunkSectionLayer.TRANSLUCENT)?0b1000:0;



            if (MODEL_TEXTURE_SIZE-1 != 15) {
                //Scale face size from 0->this.modelTextureSize-1 to 0->15
                for (int i = 0; i < 4; i++) {
                    faceSize[i] = Math.round((((float) faceSize[i]) / (MODEL_TEXTURE_SIZE - 1)) * 15);
                }
            }

            int faceModelData = 0;
            faceModelData |= faceSize[0] | (faceSize[1]<<4) | (faceSize[2]<<8) | (faceSize[3]<<12);
            //Change the scale from 0->1 (ends inclusive)
            // this is cursed also warning stuff at 63 (i.e half a pixel from the end will be clamped to the end)
            int enc = Math.round(offset*64);
            faceModelData |= Math.min(enc,62)<<16;
            //Still have 11 bits free

            //Stuff like fences are solid, however they have extra side piece that mean it needs to have discard on
            int area = (faceSize[1]-faceSize[0]+1) * (faceSize[3]-faceSize[2]+1);
            boolean needsAlphaDiscard = ((float)writeCount)/area<0.9;//If the amount of area covered by written pixels is less than a threashold, disable discard as its not needed

            needsAlphaDiscard |= blockRenderLayer != ChunkSectionLayer.SOLID;
            needsAlphaDiscard &= blockRenderLayer != ChunkSectionLayer.TRANSLUCENT;//Translucent doesnt have alpha discard
            faceModelData |= needsAlphaDiscard?1<<22:0;

            faceModelData |= ((!faceCoversFullBlock)&&blockRenderLayer != ChunkSectionLayer.TRANSLUCENT)?1<<23:0;//Alpha discard override, translucency doesnt have alpha discard

            //Bits 24,25 are tint metadata
            if (colourProvider!=null) {//We have a tint
                int tintState = TextureUtils.computeFaceTint(textureData[face], checkMode);
                if (tintState == 2) {//Partial tint
                    faceModelData |= 1<<24;
                } else if (tintState == 3) {//Full tint
                    faceModelData |= 2<<24;
                }
            }

            MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
        }

        metadata |= fullyOpaque?(1L<<(48+6)):0;

        boolean canBeCorrectlyRendered = true;//This represents if a model can be correctly (perfectly) represented
        // i.e. no gaps

        this.metadataCache[modelId] = metadata;

        uploadPtr += 4*6;
        //Have 40 bytes free for remaining model data
        // todo: put in like the render layer type ig? along with colour resolver info
        int modelFlags = 0;
        modelFlags |= colourProvider != null?1:0;
        modelFlags |= isBiomeColourDependent?2:0;//Basicly whether to use the next int as a colour or as a base index/id into a colour buffer for biome dependent colours
        modelFlags |= blockRenderLayer == ChunkSectionLayer.TRANSLUCENT?4:0;//Is translucent


        // H19 bamboo leaves (see BAMBOO_UNSHADED): leafy bamboo is unshaded and has no AO, like its leaves.
        if (isShaded && metalBambooUnshaded(blockState)) {
            isShaded = false;
            if (BAMBOO_UNSHADED_LOGGED.getAndIncrement() < 2) {
                Logger.info("[Metal-LODTEST] H19 bamboo leaves unshaded: " + blockState
                        + " (no side-face shade, no AO darkening; VOXY_LOD_BAMBOO_UNSHADED=0 reverts)");
            }
        }

        //TODO: THIS
        modelFlags |= isShaded?8:0;//model has AO and shade
        modelFlags |= plantCrossModel?16:0;//Metal plant cross (see quads.frag VOXY_LOD_PLANT_EDGEFADE); never set on GL
        modelFlags |= flatModel?32:0;//Metal flat block, H16 (see quad_util VOXY_LOD_ABS_INDENT); never set on GL
        modelFlags |= bambooModel?64:0;//Metal bamboo stalk, H18 (see quad_util, quads.frag); never set on GL
        modelFlags |= METAL_LEAVES && blockState.getBlock() instanceof LeavesBlock?128:0;//Metal leaves, H4 (LOD brightness on leaves only, quads.frag); never set on GL

        //modelFlags |= blockRenderLayer == RenderLayer.getSolid()?0:1;// should discard alpha
        MemoryUtil.memPutInt(uploadPtr, modelFlags); uploadPtr += 4;


        //Temporary override to always be non biome specific
        if (colourProvider == null) {
            MemoryUtil.memPutInt(uploadPtr, -1);//Set the default to nothing so that its faster on the gpu
        } else if (!isBiomeColourDependent) {
            MemoryUtil.memPutInt(uploadPtr, entry.tintingColour);
        } else if (!this.biomes.isEmpty()) {
            //Populate the list of biomes for the model state
            int biomeIndex = this.modelsRequiringBiomeColours.size() * this.biomes.size();
            MemoryUtil.memPutInt(uploadPtr, biomeIndex);
            this.modelsRequiringBiomeColours.add(new Pair<>(modelId, blockState));

            uploadResult.biomeUploadIndex = biomeIndex;
            long clrUploadPtr = (uploadResult.biomeUpload = new MemoryBuffer(4L * this.biomes.size())).address;
            for (var biome : this.biomes) {
                MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, blockState, biome)|0xFF000000); clrUploadPtr += 4;
            }
        }
        uploadPtr += 4;

        //have 32 bytes of free space after here

        //install the custom mapping id if it exists
        if (this.customBlockStateIdMapping != null && this.customBlockStateIdMapping.containsKey(blockState)) {
            MemoryUtil.memPutInt(uploadPtr, this.customBlockStateIdMapping.getInt(blockState));
        } else {
            MemoryUtil.memPutInt(uploadPtr, 0);
        } uploadPtr += 4;


        //Note: if the layer isSolid then need to fill all the points in the texture where alpha == 0 with the average colour
        // of the surrounding blocks but only within the computed face size bounds

        //TODO callback to inject extra data into the model data


        MipGen.putTextures(darkenedTinting, textureData, uploadResult.texture);

        //glGenerateTextureMipmap(this.textures.id);

        //Set the mapping at the very end
        this.idMappings[blockId] = modelId;

        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.remove(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
        }
        this.blockStatesInFlightLock.unlock();

        return uploadResult;
    }

    private static final class BiomeUploadResult implements ResultUploader {
        private final MemoryBuffer biomeColourBuffer;
        private final MemoryBuffer modelBiomeIndexPairs;
        private BiomeUploadResult(int biomes, int models) {
            this.biomeColourBuffer = new MemoryBuffer(biomes*models*4);
            this.modelBiomeIndexPairs = new MemoryBuffer(models*8);
        }

        public void upload(ModelStore store) {
            this.upload(store.modelBuffer, store.modelColourBuffer);
        }

        public void upload(IGpuBuffer modelBuffer, IGpuBuffer modelColourBuffer) {
            this.biomeColourBuffer.cpyTo(UploadStream.INSTANCE.upload(modelColourBuffer, 0, this.biomeColourBuffer.size));

            //TODO: optimize this to like a compute scatter update or something
            long ptr = this.modelBiomeIndexPairs.address;
            for (long offset = 0; offset < this.modelBiomeIndexPairs.size; offset += 8) {
                long v = MemoryUtil.memGetLong(ptr);ptr += 8;
                MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(modelBuffer, (MODEL_SIZE*(v&((1L<<32)-1)))+ 4*6 + 4, 4), (int) (v>>>32));
            }

            this.biomeColourBuffer.free();
            this.modelBiomeIndexPairs.free();
        }

        public void free() {
            if (!this.biomeColourBuffer.isFreed()) {
                this.biomeColourBuffer.free();
                this.modelBiomeIndexPairs.free();
            }
        }
    }

    private BiomeUploadResult addBiome0(int id, Biome biome) {
        if (biome == null) {
            throw new IllegalStateException("Null biome");
        }
        for (int i = this.biomes.size(); i <= id; i++) {
            this.biomes.add(null);
        }
        var oldBiome = this.biomes.set(id, biome);

        if (oldBiome != null && oldBiome != biome) {
            throw new IllegalStateException("Biome was put in an id that was not null");
        }
        if (oldBiome == biome) {
            Logger.error("Biome added was a duplicate: " + id);
            return null;
        }

        if (this.modelsRequiringBiomeColours.isEmpty()) return null;

        var result = new BiomeUploadResult(this.biomes.size(), this.modelsRequiringBiomeColours.size());

        int i = 0;
        long modelUpPtr = result.modelBiomeIndexPairs.address;
        for (var entry : this.modelsRequiringBiomeColours) {
            var colourProvider = getColourProvider(entry.right().getBlock());
            if (colourProvider == null) {
                throw new IllegalStateException();
            }
            //Populate the list of biomes for the model state
            int biomeIndex = (i++) * this.biomes.size();
            MemoryUtil.memPutLong(modelUpPtr, Integer.toUnsignedLong(entry.left())|(Integer.toUnsignedLong(biomeIndex)<<32));modelUpPtr+=8;
            long clrUploadPtr = result.biomeColourBuffer.address + biomeIndex * 4L;
            for (var biomeE : this.biomes) {
                if (biomeE == null) {
                    continue;//If null, ignore
                }
                MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, entry.right(), biomeE)|0xFF000000); clrUploadPtr += 4;
            }
        }

        return result;
    }

    private static BlockColor getColourProvider(Block block) {
        return Minecraft.getInstance().getBlockColors().blockColors.byId(BuiltInRegistries.BLOCK.getId(block));
    }

    //TODO: add a method to detect biome dependent colours (can do by detecting if getColor is ever called)
    // if it is, need to add it to a list and mark it as biome colour dependent or something then the shader
    // will either use the uint as an index or a direct colour multiplier
    private static int captureColourConstant(BlockColor colorProvider, BlockState state, Biome biome) {
        var getter = new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                if (colorResolver == null) {
                    Logger.error("Block state: " + state + " colourprovider: " + colorProvider + " had a null colorresolver");
                    return 0;
                }
                return colorResolver.getColor(biome, 0, 0);
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
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
        };
        //Multiple layer bs to do with flower beds
        int c = colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
        if (c!=-1) return c;
        return colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
    }

    private static boolean isBiomeDependentColour(BlockColor colorProvider, BlockState state) {
        boolean[] biomeDependent = new boolean[1];
        var getter = new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                biomeDependent[0] = true;
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
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
        };
        colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
        colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
        return biomeDependent[0];
    }

    private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode) {
        float[] res = new float[6];
        for (var dir : Direction.values()) {
            var data = textures[dir.get3DDataValue()];
            float fd = TextureUtils.computeDepth(data, TextureUtils.DEPTH_MODE_AVG, checkMode);//Compute the min float depth, smaller means closer to the camera, range 0-1
            //int depth = Math.round(fd * MODEL_TEXTURE_SIZE);
            //If fd is -1, it means that there was nothing rendered on that face and it should be discarded
            if (fd < -0.1) {
                res[dir.ordinal()] = -1;
            } else {
                res[dir.ordinal()] = fd;//((float) depth)/MODEL_TEXTURE_SIZE;
            }
        }
        return res;
    }

    public int[] _unsafeRawAccess() {
        return this.idMappings;
    }

    public int getModelId(int blockId) {
        int map = this.idMappings[blockId];
        if (map == -1) {
            throw new IdNotYetComputedException(blockId, true);
        }
        return map;
    }

    public boolean hasModelForBlockId(int blockId) {
        return this.idMappings[blockId] != -1;
    }

    public int getFluidClientStateId(int clientBlockStateId) {
        int map = this.fluidStateLUT[clientBlockStateId];
        if (map == -1) {
            throw new IdNotYetComputedException(clientBlockStateId, false);
        }
        return map;
    }

    public final long getModelMetadataFromClientId(int clientId) {
        return this.metadataCache[clientId];
    }


    public void free() {
        this.bakery.free();
        this.downstream.free();
        if (this.waterAnimator != null) {
            this.waterAnimator.free();
        }
        while (!this.rawBakeResults.isEmpty()) {
            this.rawBakeResults.poll().rawData.free();
        }
        while (!this.uploadResults.isEmpty()) {
            this.uploadResults.poll().free();
        }
    }

    public int getBakedCount() {
        return this.modelTexture2id.size();
    }

    public int getInflightCount() {
        //TODO replace all of this with an atomic?
        int size = this.blockStatesInFlight.size();
        size += this.uploadResults.size();
        size += this.biomeQueue.size();
        return size;
    }


    private static int computeSizeWithMips(int size) {
        int total = 0;
        for (;size!=0;size>>=1) total += size*size;
        return total;
    }
}
