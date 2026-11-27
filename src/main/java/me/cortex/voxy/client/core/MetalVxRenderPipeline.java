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
 * Selected by {@link RenderPipelineFactory} when Metal + Iris + a voxy.json
 * pack are all active (default ON since 2026-07-03 — the flat passthrough left
 * LOD water vanilla-blue under packs); {@code VOXY_VX_MATERIAL=0} is the kill
 * switch that falls back to {@link NormalRenderPipeline} (flat Phase D-lite
 * water) unchanged.
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
        // H11/H4 with BSL (2026-11-27): Sodium's ice goes through the pack's water program (sky reflection and sun
        // specular by fresnel); the solid LOD ice of the no-pack path (H15 solid bake, H4 bake over water) went through
        // voxy_opaque without either and read up to 25 % duller at the Render Distance 16 ring (CIEDE2000 4.3 noon,
        // 6.9 low sun). Kept translucent it reaches the pack's voxy_translucent: 1.3 / 3.0, and no H15 mosaic (the
        // pack composites the LOD translucent layer itself). VOXY_VX_ICE_TRANSLUCENT=0 keeps the solid ice.
        modelService.factory.setPackIceTranslucent(PACK_ICE_TRANSLUCENT);
        if (!loggedIce) {
            loggedIce = true;
            me.cortex.voxy.common.Logger.info("[Metal-LODTEST] vx pack LOD ice "
                    + (PACK_ICE_TRANSLUCENT ? "translucent (the pack's water program shades it, like Sodium's ice)"
                                            : "solid (VOXY_VX_ICE_TRANSLUCENT=0: the no-pack H15/H4 bake)")
                    + "; VOXY_VX_ICE_TRANSLUCENT=0 keeps it solid");
        }
    }

    private static final boolean PACK_ICE_TRANSLUCENT = !"0".equals(System.getenv("VOXY_VX_ICE_TRANSLUCENT"));
    private static boolean loggedIce;

    /** The pack contract data, consumed by the GL resolve pass (increment 5). */
    public IrisVoxyRenderPipelineData getPipelineData() {
        return this.pipelineData;
    }
}
