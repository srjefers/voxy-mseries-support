package me.cortex.voxy.common.world.other;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    private static final boolean MIP_BLOCKLIGHT_FIX = !"0".equals(System.getenv("VOXY_MIP_BLOCKLIGHT_FIX"));
    static {
        me.cortex.voxy.common.Logger.info("[Voxy-SYNC] mip block-light packing: "
                + (MIP_BLOCKLIGHT_FIX ? "upstream 0eb618d1 ON (VOXY_MIP_BLOCKLIGHT_FIX=0 reverts; unvisited stored areas refresh via /voxy import current)" : "PRE-SYNC (VOXY_MIP_BLOCKLIGHT_FIX=0)"));
    }

    // ---- Metal surface mip (H13, H20) -------------------------------------------------------------------------------
    // Upstream keeps, of the 8 children, the one with the highest light opacity (dbf1188b, 2025-01-15), so the LOD
    // terrain stays solid. Two side effects, both measured on the voxytest world (~/voxy-test-results/HALLAZGOS.md H13):
    //  1. A thin top layer over something more opaque is lost as soon as a coarse cell reaches the block under it:
    //     water (1) over sand or gravel (15), ice (1) over shallow water and gravel, a snow layer (0) over grass
    //     (15). Simulated from the region files, the frozen ocean at (237, 383) seen from above has 34 % ice and
    //     40 % snow at level 0 but only 1 % ice and 13 % snow at level 4: the rest turns into sand, gravel and green
    //     snowy-grass tops. Measured against Sodium at level 1 (Y 450): colour error dE 10.1 -> 8.3 with this rule,
    //     brightness 0.90 -> 0.98, snow kept white 32 % -> 61 % (~/voxy-test-results/H20/h13-medida-pasto; 7.4 / 62 %
    //     when every snowy cell was a snow block, which broke the side view, see below).
    //  2. dbf1188b encoded the tie order as the case label x<<2|y<<1|z, so on equal opacity a bottom child with x=1
    //     beats a top child with x=0. The rule before it checked the top layer first (I111, I110, I011, I010, ...),
    //     so this was probably not intended: on slopes the dirt under the grass wins.
    // The surface rule keeps upstream's opacity order and changes only the cases above:
    //  - a fluid or ice child with nothing solid on top of it inside the cell counts as fully opaque;
    //  - a snow layer that sits on a solid child that is not leaves counts as fully opaque, and if it wins the cell
    //    takes the solid's full cube so the terrain keeps its height. Not on leaves: a snowy spruce keeps green sides
    //    under its white top, and a white cube made the forest seen from Y 700 40 % brighter than the real blocks
    //    (H20/h13-2026-11-26_12.31.21). VOXY_MIP_SNOW=0: no snow part, =2: on leaves too.
    //    Which cube: when the solid is a snowy=true block (grass, podzol, mycelium; 59 % of the snow layers in
    //    voxytest) the cell keeps that block. Its sides are vanilla's snowy side (dirt with a snow fringe) and the
    //    Metal bake draws its top as snow (ModelFactory.SNOWY_TOP; vanilla never shows that top, the snow layer
    //    always covers it), so the cell looks like level 0 from above and from the side. Any other solid (sand on
    //    frozen beaches, 14 %) becomes a snow block. Before, every cell became a snow block, white on all six faces:
    //    seen from the side a coarse level was twice as white as level 0, and since the LOD level of a spot
    //    changes with its place on screen, snow "moved with the camera" (owner, 2026-11-26; same world spot across
    //    a 20-degree turn, level 0 -> 1: white 4.2 % -> 8.1 %; H20/giro-2026-11-26_16.01.44).
    //    VOXY_MIP_SNOW_GRASS=0 makes snowy grass a snow block again;
    //  - ties: an uncovered child first, then the top layer first (then x, then z). A majority vote among the
    //    uncovered children was tried and dropped: it did nothing for H13 and cost the H20 level-1 view (eye-scale
    //    SSIM relative to level 0, 0.83 -> 0.79; ~/voxy-test-results/H20/variantes-2026-11-26_13.06.51).
    // Metal only: VoxyClient turns it on with the Metal backend (VOXY_MIP_SURFACE=0 keeps upstream's rule). The GL
    // backend never calls enableSurfaceRule, so its mip is unchanged. The rule decides what is STORED: areas already
    // in the store keep the old cells until '/voxy import current' (on the Metal backend importers rewrite every mip
    // level, with the rule on or off, WorldUpdater allLevels; live ingest only rewrites the levels of a section whose
    // blocks changed).
    private static volatile boolean SURFACE_RULE = false;
    private static volatile boolean SURFACE_SNOW = false;
    private static volatile boolean SURFACE_SNOW_ON_LEAVES = false;
    private static volatile boolean SURFACE_SNOW_GRASS = false;
    // Set on the Metal backend whether the rule is on or off, so that flipping VOXY_MIP_SURFACE and re-importing
    // rewrites the stored mips both ways (with upstream's early stop an import under VOXY_MIP_SURFACE=0 would leave
    // the surface-rule cells in place).
    private static volatile boolean IMPORT_ALL_LEVELS = false;

    public static void enableSurfaceRule(String backend) {
        IMPORT_ALL_LEVELS = true;
        if ("0".equals(System.getenv("VOXY_MIP_SURFACE"))) {
            me.cortex.voxy.common.Logger.info("[Voxy-MIP] surface mip OFF on " + backend
                    + " (VOXY_MIP_SURFACE=0): upstream opacity mip, as on the GL backend");
            return;
        }
        String snow = System.getenv("VOXY_MIP_SNOW");
        SURFACE_SNOW = !"0".equals(snow);
        SURFACE_SNOW_ON_LEAVES = "2".equals(snow);
        SURFACE_SNOW_GRASS = SURFACE_SNOW && !"0".equals(System.getenv("VOXY_MIP_SNOW_GRASS"));
        SURFACE_RULE = true;
        me.cortex.voxy.common.Logger.info("[Voxy-MIP] surface mip ON on " + backend
                + " (H13/H20: water, ice" + (SURFACE_SNOW ? (SURFACE_SNOW_ON_LEAVES ? " and snow (also on leaves, VOXY_MIP_SNOW=2)" : " and snow on the ground") : "")
                + " stay the top of coarse LOD cells, top layer wins ties; snow on snowy grass keeps the "
                + (SURFACE_SNOW_GRASS ? "grass (VOXY_MIP_SNOW_GRASS=0: snow block)" : "snow block (VOXY_MIP_SNOW_GRASS=0)")
                + "; VOXY_MIP_SURFACE=0 reverts, VOXY_MIP_SNOW=0 drops"
                + " the snow part). Stored areas keep their old cells until '/voxy import current' (imports rewrite every"
                + " mip level on this backend).");
    }

    /** True on the Metal backend: importers write every mip level (WorldUpdater allLevels), so '/voxy import current'
     *  re-mips a store with whichever rule is active. False on GL, where the import keeps upstream's early stop. */
    public static boolean importRewritesAllLevels() {
        return IMPORT_ALL_LEVELS;
    }

    private static final class Scratch {
        final long[] c = new long[8];
        final int[] blk = new int[8];
        final int[] score = new int[8];
        final boolean[] uncovered = new boolean[8];
    }
    // mip runs ~585 times per ingested 16^3 section, on the ingest/import worker threads: no per-call allocation
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static long mipSurface(long I000, long I100, long I001, long I101,
                                   long I010, long I110, long I011, long I111,
                                   Mapper mapper) {
        // Child i = x<<2 | y<<1 | z (upstream's case label); the child above i is i|0b010, the one below i&~0b010.
        final Scratch sc = SCRATCH.get();
        final long[] c = sc.c;
        c[0b000] = I000; c[0b100] = I100; c[0b001] = I001; c[0b101] = I101;
        c[0b010] = I010; c[0b110] = I110; c[0b011] = I011; c[0b111] = I111;
        final int[] blk = sc.blk;
        final int[] score = sc.score;
        final boolean[] uncovered = sc.uncovered;
        for (int i = 0; i < 8; i++) {
            if (Mapper.isAir(c[i])) {
                score[i] = -1;
                continue;
            }
            int b = Mapper.getBlockId(c[i]);
            blk[i] = b;
            int kind = mapper.getBlockStateSurfaceKind(b);
            boolean top = (i & 0b010) != 0;
            boolean cov = false;
            if (!top && !Mapper.isAir(c[i | 0b010])) {
                int a = Mapper.getBlockId(c[i | 0b010]);
                cov = mapper.getBlockStateOpacity(a) == 15 || mapper.getBlockStateSurfaceKind(a) != 0;
            }
            int s = mapper.getBlockStateOpacity(b);
            if (!cov) {
                if (kind == Mapper.SURFACE_FLUID_ICE) {
                    s = 15;
                } else if (kind == Mapper.SURFACE_SNOW_LAYER && SURFACE_SNOW && top && !Mapper.isAir(c[i & ~0b010])) {
                    int under = Mapper.getBlockId(c[i & ~0b010]);
                    if (mapper.getBlockStateOpacity(under) == 15
                            && (SURFACE_SNOW_ON_LEAVES || mapper.getBlockStateSurfaceKind(under) != Mapper.SURFACE_LEAVES)) {
                        s = 15;
                    }
                }
            }
            score[i] = s;
            uncovered[i] = !cov;
        }
        int best = -1, bestKey = -1;
        for (int i = 0; i < 8; i++) {
            if (score[i] < 0) continue;
            int rank = ((i & 0b010) << 1) | ((i & 0b100) >> 1) | (i & 0b001);// y<<2 | x<<1 | z: top layer first
            int key = (score[i] << 4) | ((uncovered[i] ? 1 : 0) << 3) | rank;
            if (key > bestKey) {
                bestKey = key;
                best = i;
            }
        }
        long id = c[best];
        if (SURFACE_SNOW && score[best] == 15 && mapper.getBlockStateSurfaceKind(blk[best]) == Mapper.SURFACE_SNOW_LAYER) {
            // A snow layer only scores 15 in the top layer, on a solid child: that child is right below it.
            int under = Mapper.getBlockId(c[best & ~0b010]);
            int cube = SURFACE_SNOW_GRASS && mapper.isBlockStateSnowy(under) ? under : mapper.getSnowBlockId();
            // The snow layer's light: it is the exposed child (the solid under it is dark inside).
            id = Mapper.composeMappingId((byte) Mapper.getLightId(id), cube, Mapper.getBiomeId(id));
        }
        return id;
    }

    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        if (SURFACE_RULE && !(Mapper.isAir(I000) && Mapper.isAir(I100) && Mapper.isAir(I001) && Mapper.isAir(I101)
                && Mapper.isAir(I010) && Mapper.isAir(I110) && Mapper.isAir(I011) && Mapper.isAir(I111))) {
            return mipSurface(I000, I100, I001, I101, I010, I110, I011, I111, mapper);
        }
        //TODO: do a stable sort on all the entires, w.r.t the opacity and maybe light as a secondary???
        // then select the highest value
        // UPDATE, dumbass, the highest value _is_ the max/min



        int max = -1;

        //TODO: mip with respect to all the variables, what that means is take whatever has the highest count and return that
        //TODO: also average out the light level and set that as the new light level
        //For now just take the most top corner

        //TODO: i think it needs to compute the _max_ light level, since e.g. if a point is bright irl
        // you can see it from really really damn far away.
        // it could be a heavily weighted average with a huge preference to the top most lighting value
        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111)<<4)|0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110)<<4)|0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011)<<4)|0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010)<<4)|0b010, max);
        }
        if (!Mapper.isAir(I101)) {
            max = Math.max((mapper.getBlockStateOpacity(I101)<<4)|0b101, max);
        }
        if (!Mapper.isAir(I100)) {
            max = Math.max((mapper.getBlockStateOpacity(I100)<<4)|0b100, max);
        }
        if (!Mapper.isAir(I001)) {
            max = Math.max((mapper.getBlockStateOpacity(I001)<<4)|0b001, max);
        }
        if (!Mapper.isAir(I000)) {
            max = Math.max((mapper.getBlockStateOpacity(I000)<<4), max);
        }

        if (max != -1) {
            return switch (max&0b111) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + (max&0b111));
            };
        } else {
            int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                    (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
            int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                    (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
            blockLight = blockLight / 8;
            skyLight = (int) Math.ceil((double) skyLight / 8);

            if (MIP_BLOCKLIGHT_FIX) {
                // Upstream 0eb618d1: blockLight is the average of the HIGH nibbles (0..240) and
                // already sits in bits 4-7. Shifting it left again pushed it past bit 7, so
                // withLight's &0xFF kept only (avg & 0x0F)<<4 — zero for a uniformly lit cell,
                // noise otherwise. This branch is the all-air cell, i.e. exactly the voxels whose
                // light byte lights the neighbouring LOD faces, so torches / lava / glowstone read
                // dark or random at distance. Areas the client re-ingests heal on their own; stored
                // areas never revisited need '/voxy import current'. VOXY_MIP_BLOCKLIGHT_FIX=0 reverts.
                return withLight(I111, (blockLight & 0xF0) | skyLight);
            }
            return withLight(I111, (blockLight << 4) | skyLight);
        }
    }
}
