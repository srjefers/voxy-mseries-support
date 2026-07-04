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
        // M9 gate: Iris transforms GLSL via its own pipeline and binds directly
        // to MC's OpenGL context. None of that survives the move to Metal/Vulkan
        // until a parallel Iris-on-encoder rewrite lands (out of M9 scope; the
        // user accepted "Mac launches without Iris in M14, phase 2 evaluates with
        // data" as part of the original plan). On non-OpenGL backends, skip the
        // Iris path entirely so the NormalRenderPipeline fallback runs.
        boolean glBackend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                == me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
        if (glBackend && IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            pipeline = createIrisPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
        } else if (!glBackend && IrisUtil.IRIS_INSTALLED
                && !"0".equals(System.getenv("VOXY_VX_MATERIAL"))
                && IrisUtil.vxContractActive()) {
            // Phase C (issue #11): Metal native vx contract — the LOD pass renders
            // a material g-buffer the pack's voxy_opaque/voxy_translucent shades
            // GL-side. Default ON when the pack ships the contract (2026-07-03):
            // without it the translucent inject is a flat passthrough, so the
            // pack's voxy_translucent never runs and LOD water stays vanilla
            // blue next to the pack-shaded near water. VOXY_VX_MATERIAL=0 is the
            // kill switch; falls through to NormalRenderPipeline (flat Phase
            // D-lite water) when disabled or if the contract data isn't available.
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
            Logger.info("Creating Metal vx material render pipeline (Phase C)");
            try {
                return new MetalVxRenderPipeline(pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception e) {
                Logger.error("Failed to create Metal vx material render pipeline", e);
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
