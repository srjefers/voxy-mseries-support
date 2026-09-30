package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4f;

/** Main-world frame boundary shared by Iris uniform preparation and Sodium terrain drawing. */
public final class WorldFrameCapture {
    private static long frame;
    private static VoxyRenderSystem owner;
    private static ChunkRenderMatrices matrices;
    private static FogParameters fog;
    private static double x,y,z;
    private static Viewport<?> prepared;
    private WorldFrameCapture() {}

    public static void capture(VoxyRenderSystem renderer, ChunkRenderMatrices inputs, FogParameters parameters,
                               double cameraX,double cameraY,double cameraZ) {
        frame++;
        owner=renderer;
        matrices=new ChunkRenderMatrices(new Matrix4f(inputs.projection()),new Matrix4f(inputs.modelView()));
        fog=parameters; x=cameraX; y=cameraY; z=cameraZ;
        prepared=null;
        var level=net.minecraft.client.Minecraft.getInstance().level;
        if(level!=null) SectionProbe.beginFrame(renderer.getEngine(),level.dimension().identifier().toString(),frame,System.nanoTime());
    }
    public static long frame() { return frame; }
    public static Viewport<?> prepare(VoxyRenderSystem renderer) {
        if(owner!=renderer || matrices==null) return null;
        if(prepared==null) prepared=renderer.setupViewport(matrices,fog,x,y,z);
        return prepared;
    }
    public static Viewport<?> prepare(VoxyRenderSystem renderer, ChunkRenderMatrices inputs, FogParameters parameters,
                                       double cameraX,double cameraY,double cameraZ) {
        var viewport=prepare(renderer);
        // Handles renderer creation during the frame, before a main-world capture exists.
        if(viewport==null) {
            capture(renderer,inputs,parameters,cameraX,cameraY,cameraZ);
            viewport=prepare(renderer);
        }
        return viewport;
    }
    public static void release(VoxyRenderSystem renderer) {
        if(owner==renderer) { owner=null; matrices=null; fog=null; prepared=null; }
    }
}
