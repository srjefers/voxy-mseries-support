package me.cortex.voxy.jmh;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class SaveLoadJMH {
    @State(Scope.Benchmark)
    public static class BState {
        private int i;
        private MemoryBuffer scratchBuffer;
        private WorldSection scratchSection;
        private WorldSection[] sections;
        private MemoryBuffer[] buffers;
    }

    @Setup(Level.Trial)
    public void init(BState state) {
        state.sections = new WorldSection[100];
        state.buffers = new MemoryBuffer[state.sections.length];
        var R = new Random(4326987911L);
        for (int i = 0; i < state.sections.length; i++) {
            state.sections[i] = WorldSection._createRawUntrackedUnsafeSection(0, (int) (R.nextFloat() * 100 - 50), (int) (R.nextFloat() * 10 - 5), (int) (R.nextFloat() * 100 - 50));
            state.buffers[i] = new MemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE);
        }
        state.scratchBuffer = new MemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE);
        state.scratchSection = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        for (var sec : state.sections) {
            var raw = sec._unsafeGetRawDataArray();
            int notAir = 0;
            for (int i = 0; i < 10000; i++) {
                int idx = R.nextInt(raw.length);
                long p = raw[idx];
                long n = raw[idx] = R.nextLong() & 1023;
                if (p == 0 && n != 0) {
                    notAir++;
                }
                if (n == 0 && p != 0) {
                    notAir--;
                }
            }
            sec.addNonEmptyBlockCount(notAir);
        }
        for (int i = 0; i < state.sections.length; i++) {
            var buf = SaveLoadSystem3.serialize(state.sections[i]);
            buf.cpyTo(state.buffers[i].address);
        }
    }

    @Setup(Level.Iteration)
    public void initIter(BState state) {
        state.i = 0;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static boolean deserialize(WorldSection sec, MemoryBuffer buffer) {
        return SaveLoadSystem3.deserialize(sec, buffer);
    }

    @Benchmark
    @Fork(1)
    @Warmup(iterations = 4, time = 3)
    @Measurement(iterations = 5, time = 5)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public boolean benchDeserial(BState state) {
        int i = (state.i++)%state.buffers.length;
        return deserialize(state.sections[i], state.buffers[i]);
    }
}
