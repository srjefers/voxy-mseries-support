package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4f;

/** Main-world capture, preparation and terrain ownership share one frame identity. Render-thread only. */
public final class WorldFrameCapture {
    private record Frame(VoxyRenderSystem owner, ChunkRenderMatrices matrices, FogParameters fog,
                         double x, double y, double z) { }
    private static long frame;
    private static Frame captured;
    private static Viewport<?> prepared;
    private WorldFrameCapture() { }

    public static void capture(VoxyRenderSystem renderer, ChunkRenderMatrices inputs, FogParameters parameters,
                               double cameraX, double cameraY, double cameraZ) {
        if (IrisUtil.shadowsBeingRendered()) return;
        var copied = new ChunkRenderMatrices(new Matrix4f(inputs.projection()), new Matrix4f(inputs.modelView()));
        var next = new Frame(renderer, copied, parameters, cameraX, cameraY, cameraZ);
        captured = next;
        frame++;
        prepared = null;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) SectionProbe.beginFrame(renderer.getEngine(), level.dimension().identifier().toString(), frame, System.nanoTime());
    }
    public static long frame() { return frame; }
    public static Viewport<?> prepare(VoxyRenderSystem renderer) {
        var current = captured;
        if (current == null || current.owner() != renderer) return null;
        if (prepared == null) prepared = renderer.setupViewport(current.matrices(), current.fog(), current.x(), current.y(), current.z());
        return prepared;
    }
    public static Viewport<?> prepare(VoxyRenderSystem renderer, ChunkRenderMatrices inputs, FogParameters parameters,
                                      double cameraX, double cameraY, double cameraZ) {
        var viewport = prepare(renderer);
        if (viewport == null) {
            // A renderer can be created after the main-world capture hook.
            capture(renderer, inputs, parameters, cameraX, cameraY, cameraZ);
            viewport = prepare(renderer);
        }
        return viewport;
    }
    public static Viewport<?> prepareTerrain(VoxyRenderSystem renderer, ChunkRenderMatrices inputs, FogParameters parameters,
                                             double cameraX, double cameraY, double cameraZ) {
        var viewport = prepare(renderer, inputs, parameters, cameraX, cameraY, cameraZ);
        if (viewport == null) return null;
        if (renderer.getPipeline() instanceof MetalVxRenderPipeline material && !material.beginMaterialFrame()) return null;
        return viewport;
    }
    public static void release(VoxyRenderSystem renderer) {
        if (captured != null && captured.owner() == renderer) { captured = null; prepared = null; }
    }
}
