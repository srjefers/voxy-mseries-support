package me.cortex.voxy.client.core.rendering;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Uploaded data is lifecycle readiness; only this frame's prepared commands own coverage. */
public final class SodiumDrawCoverage {
    public static final SodiumDrawCoverage INSTANCE = new SodiumDrawCoverage();
    private Object owner;
    private SectionCoverageTracker.Generation generation;
    private long frame = -1;
    private final Map<String, Set<Long>> passes = new HashMap<>();

    public boolean begin(Object source, SectionCoverageTracker.Generation generation, long frame) {
        if (source == null || generation == null || generation != SectionCoverageTracker.INSTANCE.currentGeneration()) return false;
        if (this.owner == source && this.generation == generation && frame <= this.frame) return false;
        this.owner = source;
        this.generation = generation;
        this.frame = frame;
        this.passes.clear();
        return true;
    }

    public void release(Object source) {
        if (source != this.owner) return;
        this.owner = null;
        this.generation = null;
        this.frame = -1;
        this.passes.clear();
    }

    private boolean matches(Object source, SectionCoverageTracker.Generation generation, long frame) {
        return source == this.owner && generation != null && generation == this.generation && frame == this.frame
                && generation == SectionCoverageTracker.INSTANCE.currentGeneration();
    }

    public boolean publish(Object source, SectionCoverageTracker.Generation generation, long frame,
                           String pass, Set<Long> positions) {
        if (!matches(source, generation, frame)) return false;
        if (!pass.equals("solid") && !pass.equals("cutout") && !pass.equals("translucent")) throw new IllegalArgumentException(pass);
        this.passes.put(pass, Set.copyOf(positions));
        return true;
    }

    public Map<Long, SectionCoverageTracker.Coverage> mask(Object source,
            SectionCoverageTracker.Generation generation, long frame) {
        if (!matches(source, generation, frame)) return Map.of();
        var result = new HashMap<Long, SectionCoverageTracker.Coverage>();
        addReady(result, "translucent", SectionCoverageTracker.Coverage.TRANSLUCENT);
        addReady(result, "solid", SectionCoverageTracker.Coverage.OPAQUE);
        // Cutout pixels do not own the empty space within a section box.
        return result;
    }

    private void addReady(Map<Long, SectionCoverageTracker.Coverage> result, String pass,
                          SectionCoverageTracker.Coverage state) {
        for (long position : this.passes.getOrDefault(pass, Set.of())) {
            if (SectionCoverageTracker.INSTANCE.coverageAt(position) != SectionCoverageTracker.Coverage.NONE)
                result.put(position, state);
        }
    }
}
