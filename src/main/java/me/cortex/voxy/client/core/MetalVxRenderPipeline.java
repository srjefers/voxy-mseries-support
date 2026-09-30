package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;

import java.util.function.BooleanSupplier;

/** Metal material owner: both contract layers resolve through the pack's actual programs.
 * Policy and Iris generation are fixed until renderer replacement; incomplete frames are skipped.
 */
public class MetalVxRenderPipeline extends NormalRenderPipeline {
    private final IrisVoxyRenderPipelineData pipelineData;
    private final MetalMaterialUniforms uniforms;
    private final MetalMaterialPolicy policy;
    private final MaterialFrameState frames;
    private boolean warnedIncomplete;

    public MetalVxRenderPipeline(IrisVoxyRenderPipelineData data,
                                 AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                 HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.policy=MetalMaterialPolicy.select(Boolean.getBoolean("voxy.bslCompatibility"),super.vxOpaqueMaterialMode());
        this.frames=new MaterialFrameState(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable());
        this.pipelineData = data;
        this.uniforms = new MetalMaterialUniforms(data, me.cortex.voxy.client.core.gpu.RenderBackendFactory.get());
    }

    @Override public void preSetup(me.cortex.voxy.client.core.rendering.Viewport<?> viewport) {
        this.uniforms.capture(WorldFrameCapture.frame());
    }

    @Override public void bindUniforms(me.cortex.voxy.client.core.gpu.RenderEncoder encoder) {
        this.uniforms.bind(encoder);
    }

    @Override public String taaFunction(String name) {
        return this.taaFunction(MetalMaterialUniforms.BINDING, name);
    }

    @Override public String taaFunction(int binding, String name) {
        return MetalMaterialUniforms.taa(this.pipelineData, binding, name);
    }

    @Override protected void free0() {
        try {
            this.uniforms.close();
            me.cortex.voxy.client.core.util.MetalVxResolvePass.reset();
        } finally { super.free0(); }
    }

    @Override
    public boolean vxMaterialMode() {
        return true;
    }

    @Override
    public boolean vxOpaqueMaterialMode() {
        // Contract 1 shades both layers. The legacy BSL split remains an explicit opt-in.
        return this.policy.opaque();
    }

    @Override public MetalMaterialPolicy materialPolicy() { return this.policy; }

    public boolean beginMaterialFrame() {
        return this.frames.begin(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable(),
                net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt(),
                me.cortex.voxy.client.core.util.IrisUtil.shadowsBeingRendered());
    }
    public void publishMaterialFrame() {
        this.frames.publish(net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt());
    }
    public void resolveMaterialFrame(me.cortex.voxy.client.core.rendering.Viewport<?> viewport) {
        var iris=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (this.frames.consume(iris,net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt())
                && iris instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline pipeline) {
            if (!MetalMaterialCompositor.resolve(this,pipeline,viewport) && !this.warnedIncomplete) {
                this.warnedIncomplete=true;
                me.cortex.voxy.common.Logger.warn("Material frame skipped: incomplete Metal bridge binding. The renderer will retry on the next frame.");
            }
        }
    }

    /**
     * Mirror the GL Iris path (IrisVoxyRenderPipeline.setupExtraModelBakeryData):
     * feed the pack's block→customId mapping to the bakery so the material
     * g-buffer's misc plane carries the pack's block IDs.
     */
    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    public long materialUniformPointer() { return this.uniforms.pointer(); }

    /** Pack programs, targets, uniforms and blend state for the GL material resolve. */
    public IrisVoxyRenderPipelineData getPipelineData() {
        return this.pipelineData;
    }
}
