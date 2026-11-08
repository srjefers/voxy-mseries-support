package me.cortex.voxy.jmh;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class WorldUpdaterJMH {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;

    @State(Scope.Benchmark)
    public static class BState {
        private int i;
        private VoxelizedSection[] vSections;
        private WorldSection[] sections;
    }

    @Setup(Level.Trial)
    public void init(BState state) {
        state.sections = new WorldSection[WIDTH*WIDTH*HEIGHT];
        state.vSections = new VoxelizedSection[8000];
        var R = new Random(4326987911L);
        for (int i = 0; i < state.vSections.length; i++) {
            state.vSections[i] = VoxelizedSection.createEmpty().setPosition(R.nextInt(WIDTH*2), R.nextInt(HEIGHT*2), R.nextInt(WIDTH*2));
        }
        for (int i = 0; i < state.sections.length; i++) {
            state.sections[i] = WorldSection._createRawUntrackedUnsafeSection(0, i%WIDTH, i/(WIDTH*WIDTH), (i/WIDTH)%WIDTH);
        }
        for (var vsec : state.vSections) {
            for (int i = 0; i < 30000; i++) {
                vsec.section[R.nextInt(vsec.section.length)] = Mapper.composeMappingId((byte) R.nextInt(), R.nextInt() & 127, 1);
            }
        }
    }

    @Setup(Level.Iteration)
    public void initIter(BState state) {
        state.i = 0;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static long insert(VoxelizedSection vsec, WorldSection sec) {
        return WorldUpdater.insertSectionLvlIntoWorld(vsec, sec);
    }

    @Benchmark
    @Fork(1)
    @Warmup(iterations = 4, time = 3)
    @Measurement(iterations = 5, time = 5)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public long benchWorldInsert(BState state) {
        state.i &= Integer.MAX_VALUE>>1;
        var vsec = state.vSections[(state.i+=999)%state.vSections.length];
        return insert(vsec, state.sections[(vsec.x>>>1)+(vsec.z>>>1)*WIDTH+(vsec.y>>>1)*WIDTH*WIDTH]);
    }
}
