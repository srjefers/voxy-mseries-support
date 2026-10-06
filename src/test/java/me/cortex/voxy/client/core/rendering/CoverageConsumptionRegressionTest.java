package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.MetalMaterialPolicy;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import static org.mockito.Mockito.*;

/** Exercises the subscription installed by the real coverage consumer. */
public final class CoverageConsumptionRegressionTest {
    public static void main(String[] args) throws Exception {
        try (var context = new me.cortex.voxy.client.core.interop.TestGlContext()) {
            me.cortex.voxy.common.Logger.SHUTUP = true;
            run();
        }
    }
    private static void run() throws Exception {
        var tracker = SectionCoverageTracker.INSTANCE;
        var generation = tracker.beginGeneration();
        try (var delta = tracker.subscribe()) {
            delta.drain();
            tracker.publish(generation, 99, SectionCoverageTracker.Coverage.OPAQUE);
            require(delta.drain().changes().get(99L) == SectionCoverageTracker.Coverage.OPAQUE,
                    "incremental consumer lost an upload");
            require(delta.drain().changes().isEmpty(), "incremental consumer replayed changes");
        }
        var backend = mock(RenderBackend.class);
        when(backend.getType()).thenReturn(BackendType.METAL);
        when(backend.createBuffer(anyLong())).thenAnswer(call -> mock(IGpuBuffer.class, RETURNS_SELF));
        when(backend.createGraphicsPipeline(any())).thenReturn(mock(IGpuPipeline.class));
        RenderBackendFactory.set(backend);
        var pipeline = mock(AbstractRenderPipeline.class);
        when(pipeline.materialPolicy()).thenReturn(MetalMaterialPolicy.SHADERS_OFF);
        try (var shaders = mockStatic(ShaderLoader.class)) {
            shaders.when(() -> ShaderLoader.parse(anyString())).thenReturn("#version 450\nvoid main() {}\n");
            var renderer = new ChunkBoundRenderer(pipeline);
            try {
                var field = ChunkBoundRenderer.class.getDeclaredField("coverage"); field.setAccessible(true);
                var subscription = (SectionCoverageTracker.Subscription) field.get(renderer);
                require(subscription.drain().reset(), "renderer creation did not reset coverage");
                tracker.publish(generation, 100, SectionCoverageTracker.Coverage.TRANSLUCENT);
                require(subscription.drain().changes().isEmpty(), "Metal retained unused upload delta contents");
                var next = tracker.beginGeneration();
                var update = subscription.drain();
                require(update.reset() && update.generation() == next.id(), "replacement lost generation reset");
                require(!subscription.drain().reset(), "generation reset replayed twice");
            } finally { renderer.free(); }
        }
        System.out.println("PASS: incremental uploads preserved; real Metal consumer retains only generation reset");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
