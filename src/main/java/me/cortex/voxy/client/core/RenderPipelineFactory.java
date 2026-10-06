package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        //Note this is where will choose/create e.g. IrisRenderPipeline or normal pipeline
        AbstractRenderPipeline pipeline = null;
        // OpenGL uses the Iris renderer; Metal shades its material bridge with contract-1 pack programs.
        boolean glBackend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                == me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
        if (glBackend && IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            pipeline = createIrisPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
        } else if (!glBackend && IrisUtil.IRIS_INSTALLED
                && !"0".equals(System.getenv("VOXY_VX_MATERIAL"))
                && IrisUtil.vxContractActive()) {
            // Prepare both contract stages before selecting the Metal material renderer.
            pipeline = createMetalVxPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
            if (pipeline == null) {
                Logger.warn("VOXY_VX_MATERIAL=1 but the Metal vx material pipeline could not be created; "
                        + "falling back to NormalRenderPipeline.");
            }
        } else if (!glBackend && IrisUtil.IRIS_INSTALLED) {
            Logger.warn("Iris is installed but Voxy's Iris integration only runs on OpenGL; "
                    + "Voxy will use its NormalRenderPipeline (no shader-pack support) on this backend.");
        }
        if (pipeline == null) {
            pipeline = new NormalRenderPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        return pipeline;
    }

    private static AbstractRenderPipeline createMetalVxPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            return null;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                return null;
            }
            Logger.info("Creating Metal contract-1 material render pipeline");
            try {
                if (!me.cortex.voxy.client.core.util.MetalVxResolvePass.prepare(pipeData, irisPipe)) {
                    throw new IllegalStateException("The pack's opaque/translucent material programs did not both compile; see their shader logs");
                }
                return new MetalVxRenderPipeline(pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception e) {
                Logger.error("Failed to create Metal vx material render pipeline", e);
                me.cortex.voxy.client.core.util.MetalVxResolvePass.reset();
                IrisUtil.disableIrisShaders();
                return null;
            }
        }
        return null;
    }

    private static AbstractRenderPipeline createIrisPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            return null;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                return null;
            }
            Logger.info("Creating voxy iris render pipeline");
            try {
                return new IrisVoxyRenderPipeline(pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception e) {
                Logger.error("Failed to create iris render pipeline", e);
                IrisUtil.disableIrisShaders();
                return null;
            }
        }
        return null;
    }
}
