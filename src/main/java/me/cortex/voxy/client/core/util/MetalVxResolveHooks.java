package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.MetalVxRenderPipeline;
import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.rendering.Viewport;

/**
 * The two Iris-facing branches of the SOLID-pass hook in
 * {@code MixinDefaultChunkRenderer.doRender}: the Phase C vx material resolve, FULL and TRANS-ONLY.
 *
 * <p><b>Why this code is not in the mixin.</b> Iris is optional, and a mixin body cannot name an
 * Iris type even on a path that never runs without Iris. When Mixin applies a mixin it looks up the
 * owner class of every method and field the body references
 * ({@code MixinPreProcessorStandard.transformMemberReference} → {@code ClassInfo.forName}), and it
 * recomputes the target's stack-map frames through the same {@code ClassInfo} lookups. With these
 * bodies inside the mixin, a game without Iris failed to load Sodium's {@code DefaultChunkRenderer}:
 * {@code ClassNotFoundException: net.irisshaders.iris.Iris} → {@code MixinTransformerError} →
 * "Force crashing due to exception during on game join" on every world join (P8, finding H5;
 * introduced by 60bd3e8b and 03c86a69). No runtime condition can prevent that, because it happens
 * when the class loads.
 *
 * <p>A plain class has no such problem: the JVM resolves its Iris references only when a method
 * that uses them runs. These methods run only for a {@link MetalVxRenderPipeline}, which
 * {@code RenderPipelineFactory} builds only while an Iris vx-contract pack is active. So the
 * signatures take Voxy types only, the Iris pipeline is looked up inside, and the Iris calls sit in
 * {@code *0()} methods behind {@link IrisUtil#IRIS_INSTALLED}, the same pattern as {@link IrisUtil}.
 *
 * <p>With Iris installed the behaviour is the pre-move code unchanged: same branch conditions (they
 * stay in the mixin), same call order, same {@code acquireAuxRectTex} calls and the same arguments,
 * including {@code viewport.projection} and {@code viewport.modelView}.
 *
 * <p>Keep Iris, Nvidium, Flashback, Chunky, Vivecraft and Mod Menu types out of every mixin that is
 * not in that mod's own mixin package: {@code scripts/testing/check-mixin-optional-refs.sh} scans the
 * built jar for them.
 */
public final class MetalVxResolveHooks {
    private MetalVxResolveHooks() {}

    /**
     * FULL material path, the default since 2026-09-25 ({@code VOXY_VX_MATERIAL_OPAQUE=0} selects
     * {@link #resolveTranslucentOnly}): the pack's voxy_opaque AND voxy_translucent run over the
     * Metal material g-buffer, so the LOD ring gets the same lighting as the pack's near terrain.
     * Caller guarantees: {@code pipeline.vxOpaqueMaterialMode()}, {@code metalBridge()},
     * {@code metalVxOpaque0()} and {@code metalDepthBridge()} non-null, {@code viewport} non-null.
     */
    public static void resolveFullMaterial(MetalVxRenderPipeline pipeline, Viewport<?> viewport) {
        if (!IrisUtil.IRIS_INSTALLED) return;
        resolveFullMaterial0(pipeline, viewport);
    }

    private static void resolveFullMaterial0(MetalVxRenderPipeline pipeline, Viewport<?> viewport) {
        var irisPipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
            int oP0 = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxOpaque0());
            int oP1 = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxOpaque1());
            int oP2 = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxOpaque2());
            int oD  = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalDepthBridge());
            int tP0 = pipeline.metalVxTrans0() != null ? IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans0()) : 0;
            int tP1 = pipeline.metalVxTrans1() != null ? IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans1()) : 0;
            int tP2 = pipeline.metalVxTrans2() != null ? IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans2()) : 0;
            int tD  = pipeline.metalDepthTransBridge() != null ? IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalDepthTransBridge()) : 0;
            MetalVxResolvePass.resolve(
                    pipeline.getPipelineData(), irp, viewport.projection, viewport.modelView,
                    oP0, oP1, oP2, oD, tP0, tP1, tP2, tD,
                    viewport.width, viewport.height);
        }
    }

    /**
     * TRANS-ONLY (issue #11, the mergeable shape; {@code VOXY_VX_MATERIAL_OPAQUE=0}): opaque LODs go
     * through the proven Phase-B inject (bridge colour + vxDepthTexOpaque, untouched vs dev); ONLY the
     * translucent (water) layer runs voxy_translucent over its material g-buffer → colortex16, so water
     * gets real BSL shading. Caller guarantees: {@code pipeline.vxMaterialMode()}, {@code metalBridge()},
     * {@code metalVxTrans0()} and {@code metalDepthTransBridge()} non-null, {@code viewport} non-null.
     */
    public static void resolveTranslucentOnly(MetalVxRenderPipeline pipeline, Viewport<?> viewport) {
        // 4th arg (transDepthBridge, non-null by the caller's guard): the inject's seafloor
        // water-column dim needs the LOD trans depth to reconstruct the water column above each
        // opaque pixel. transBridge stays null, so the Phase-D passthrough (which needs BOTH) stays
        // off and colortex16 is still written only by resolveTranslucentOnly.
        VxContractInjector.inject(viewport,
                pipeline.metalBridge(), pipeline.metalDepthBridge(), null,
                pipeline.metalDepthTransBridge());
        if (!IrisUtil.IRIS_INSTALLED) return;
        resolveTranslucentOnly0(pipeline, viewport);
    }

    private static void resolveTranslucentOnly0(MetalVxRenderPipeline pipeline, Viewport<?> viewport) {
        var irisPipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
            int tP0 = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans0());
            int tP1 = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans1());
            int tP2 = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans2());
            int tD  = IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalDepthTransBridge());
            MetalVxResolvePass.resolveTranslucentOnly(
                    pipeline.getPipelineData(), irp, tP0, tP1, tP2, tD,
                    viewport.width, viewport.height);
        }
    }
}
