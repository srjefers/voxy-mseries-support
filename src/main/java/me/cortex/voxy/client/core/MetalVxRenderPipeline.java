package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;

import java.util.function.BooleanSupplier;

/**
 * Phase C (issue #11): Metal-side native vx contract pipeline. The Metal LOD
 * pass renders a 3-plane material g-buffer (enabled via {@link #vxMaterialMode()}
 * → MDICSectionRenderer's PATCHED_SHADER + {@code MetalVxGbufferEmitter}
 * pipelines) which a GL-side resolve ({@code MetalVxResolvePass}, increment 5)
 * shades with the pack's real {@code voxy_opaque}/{@code voxy_translucent}.
 *
 * Selected by {@link RenderPipelineFactory} only when Metal + Iris + a voxy.json
 * pack + the {@code VOXY_VX_MATERIAL=1} kill switch are all active; otherwise the
 * default {@link NormalRenderPipeline} (flat Phase D-lite water) runs unchanged.
 *
 * Extends NormalRenderPipeline so it inherits the Metal render path
 * ({@code runPipelineMetal}) and the pack-aware {@code useEnvFog()=false}; it only
 * flips on the material g-buffer mode and supplies the pack's block→customId
 * mapping + the contract data the GL resolve needs.
 */
public class MetalVxRenderPipeline extends NormalRenderPipeline {
    private final IrisVoxyRenderPipelineData pipelineData;

    public MetalVxRenderPipeline(IrisVoxyRenderPipelineData data,
                                 AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                 HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.pipelineData = data;
    }

    @Override
    public boolean vxMaterialMode() {
        return true;
    }

    /**
     * Mirror the GL Iris path (IrisVoxyRenderPipeline.setupExtraModelBakeryData):
     * feed the pack's block→customId mapping to the bakery so the material
     * g-buffer's misc plane carries the pack's block ids (BSL water = 20000).
     */
    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    /** The pack contract data, consumed by the GL resolve pass (increment 5). */
    public IrisVoxyRenderPipelineData getPipelineData() {
        return this.pipelineData;
    }
}
