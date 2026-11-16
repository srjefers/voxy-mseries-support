package me.cortex.voxy.commonImpl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Voxy SeedGen collect() call, see MixinModStructuresWorld. At the start of a fill every worker needs the same
 * structure regions, so with per-region locks they still queue behind whichever worker is laying out a region. Here a
 * worker skips regions another worker is laying out, does its other regions, and comes back to the skipped ones at the
 * end of collect(), when they are usually solved. Each consider() writes into its own slice and the slices are replayed
 * in call order with consider()'s own cap, so the placement list comes out exactly as it would without skipping.
 */
public final class SeedGenDeferredLayout {
    // ModStructures.MAX_PLACEMENTS: consider() adds nothing once the list already holds this many
    private static final int MAX_PLACEMENTS = 8192;
    private static final Set<Long> LAYING_OUT = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<SeedGenDeferredLayout> CURRENT = ThreadLocal.withInitial(SeedGenDeferredLayout::new);

    private record Skipped(Operation<Void> consider, Object[] args) {}

    private final List<Object> call = new ArrayList<>();
    private final List<Object> placements = new ArrayList<>();
    private final List<Object> order = new ArrayList<>();// int[]{from, to} slices of placements, or Skipped

    public static void begin() {
        CURRENT.get().clear();
    }

    /** args are consider()'s arguments with its placement list last, which is replaced. */
    public static void consider(Object world, Map<Long, ?> solved, long key, Operation<Void> consider, Object[] args) {
        var self = CURRENT.get();
        if (solved.get(key) != null) {
            self.run(consider, args);
            return;
        }
        long claim = key * 31 + System.identityHashCode(world);
        if (!LAYING_OUT.add(claim)) {
            self.order.add(new Skipped(consider, args));
            return;
        }
        try {
            self.run(consider, args);
        } finally {
            LAYING_OUT.remove(claim);
        }
    }

    public static void finish(List<Object> out) {
        var self = CURRENT.get();
        int size = out.size();
        for (Object entry : self.order) {
            if (size >= MAX_PLACEMENTS) {
                break;
            }
            if (entry instanceof int[] slice) {
                out.addAll(self.placements.subList(slice[0], slice[1]));
                size += slice[1] - slice[0];
            } else {
                var skipped = (Skipped) entry;
                self.call.clear();
                skipped.args[skipped.args.length - 1] = self.call;
                skipped.consider.call(skipped.args);
                out.addAll(self.call);
                size += self.call.size();
            }
        }
        self.clear();
    }

    private void run(Operation<Void> consider, Object[] args) {
        this.call.clear();
        args[args.length - 1] = this.call;
        consider.call(args);
        int from = this.placements.size();
        this.placements.addAll(this.call);
        this.order.add(new int[]{from, this.placements.size()});
    }

    private void clear() {
        this.call.clear();
        this.placements.clear();
        this.order.clear();
    }
}
