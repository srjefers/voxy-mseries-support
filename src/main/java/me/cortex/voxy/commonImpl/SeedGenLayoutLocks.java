package me.cortex.voxy.commonImpl;

import it.unimi.dsi.fastutil.HashCommon;
import me.cortex.voxy.common.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lock stripes for Voxy SeedGen's structure layout, see MixinModStructuresWorld. SeedGen lays out every structure
 * region under one lock per world, so with modded structure sets (Dungeons and Taverns alone has 34) its fill
 * threads queue behind a single layout thread. Regions only need excluding from themselves, so each key gets a stripe.
 */
public final class SeedGenLayoutLocks {
    private static final int MASK = 63;
    private static final Object[] STRIPES = new Object[MASK + 1];
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    static {
        for (int i = 0; i < STRIPES.length; i++) {
            STRIPES[i] = new Object();
        }
    }

    private SeedGenLayoutLocks() {}

    public static Object forRegion(long key) {
        if (!ANNOUNCED.getAndSet(true)) {
            Logger.info("Voxy SeedGen structure layout runs per region instead of under one lock");
        }
        return STRIPES[HashCommon.mix((int) (key ^ (key >>> 32))) & MASK];
    }
}
