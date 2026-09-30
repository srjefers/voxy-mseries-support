package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.MetalVxResolvePass;
import me.cortex.voxy.client.core.util.VxContractInjector;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

/** Complete current-frame material inputs; missing bridges never select another shading path. */
final class MetalMaterialCompositor {
    private record Layer(int albedo, int tint, int misc, int depth) {
        boolean complete() { return this.albedo>0 && this.tint>0 && this.misc>0 && this.depth>0; }
    }
    private static int texture(IOSurfaceBridge bridge) {
        return bridge==null ? 0 : IOSurfaceBridgeCompositor.acquireAuxRectTex(bridge);
    }
    static boolean resolve(MetalVxRenderPipeline pipeline, IrisRenderingPipeline iris, Viewport<?> viewport) {
        Layer translucent=new Layer(texture(pipeline.metalVxTrans0()),texture(pipeline.metalVxTrans1()),
                texture(pipeline.metalVxTrans2()),texture(pipeline.metalDepthTransBridge()));
        if (!translucent.complete()) return false;
        if (pipeline.materialPolicy().opaque()) {
            Layer opaque=new Layer(texture(pipeline.metalVxOpaque0()),texture(pipeline.metalVxOpaque1()),
                    texture(pipeline.metalVxOpaque2()),texture(pipeline.metalDepthBridge()));
            if (!opaque.complete()) return false;
            MetalVxResolvePass.resolve(pipeline.getPipelineData(),iris,opaque.albedo,opaque.tint,opaque.misc,opaque.depth,
                    translucent.albedo,translucent.tint,translucent.misc,translucent.depth,viewport.width,viewport.height,pipeline.materialUniformPointer());
        } else {
            // The historic BSL split is selected only at renderer construction.
            VxContractInjector.inject(viewport,pipeline.metalBridge(),pipeline.metalDepthBridge(),null,pipeline.metalDepthTransBridge());
            MetalVxResolvePass.resolveTranslucentOnly(pipeline.getPipelineData(),iris,translucent.albedo,
                    translucent.tint,translucent.misc,translucent.depth,viewport.width,viewport.height,pipeline.materialUniformPointer());
        }
        return true;
    }
}
