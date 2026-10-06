package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.common.util.ResourceScope;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F;

/** A complete target set is acquired before publication. Partial acquisition has the same release path. */
final class MetalFrameTargets implements AutoCloseable {
    record Layout(int width, int height, boolean opaqueMaterial, boolean material, boolean exportDepth, boolean splitWater) { }
    final Layout layout;
    final IOSurfaceBridge color, packedDepth, transColor, packedTransDepth;
    final MetalPrelitLayers prelit;
    final IOSurfaceBridge[] opaque, translucent;
    final IGpuTexture depth, transDepth;
    final IGpuBuffer depthRead, transRead;
    final MetalDepthExport export;
    final MetalDepthRestore restore;
    private final ResourceScope resources = new ResourceScope();

    MetalFrameTargets(MetalRenderBackend backend, Layout layout, me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration configuration) {
        this.layout = layout;
        try {
            this.color = bridge(backend);
            this.depth = depth(backend).name("VoxyMetalDepth");
            this.prelit = !layout.exportDepth()
                    ? this.resources.own(new MetalPrelitLayers(backend, layout.width(), layout.height(), configuration), MetalPrelitLayers::close) : null;
            this.opaque = planes(backend, layout.opaqueMaterial());
            this.translucent = planes(backend, layout.material());
            this.packedDepth = layout.exportDepth() ? bridge(backend) : null;
            this.depthRead = layout.exportDepth() ? this.resources.own(backend.createBuffer((long)layout.width()*layout.height()*4), IGpuBuffer::free) : null;
            this.export = layout.exportDepth() ? this.resources.own(new MetalDepthExport(backend), MetalDepthExport::close) : null;
            this.transColor = layout.splitWater() && !layout.material() ? bridge(backend) : null;
            this.packedTransDepth = layout.splitWater() ? bridge(backend) : null;
            this.transDepth = layout.splitWater() ? depth(backend) : null;
            this.transRead = layout.splitWater() ? this.resources.own(backend.createBuffer((long)layout.width()*layout.height()*4), IGpuBuffer::free) : null;
            this.restore = layout.splitWater() ? this.resources.own(new MetalDepthRestore(backend), MetalDepthRestore::close) : null;
        } catch (RuntimeException | Error failure) {
            this.resources.rollback(failure);
            throw failure;
        }
    }
    private IOSurfaceBridge bridge(MetalRenderBackend backend) {
        return this.resources.own(IOSurfaceBridge.create(backend.device(), layout.width(), layout.height(), IOSurfaceBridge.IOSurfaceFormat.BGRA8), IOSurfaceBridge::close);
    }
    private IGpuTexture depth(MetalRenderBackend backend) {
        // Register before storage allocation, which can itself fail.
        return this.resources.own(backend.createTexture(), IGpuTexture::free).store(GL_DEPTH_COMPONENT32F, 1, layout.width(), layout.height());
    }
    private IOSurfaceBridge[] planes(MetalRenderBackend backend, boolean enabled) {
        return enabled ? new IOSurfaceBridge[]{bridge(backend), bridge(backend), bridge(backend)} : null;
    }
    @Override public void close() { this.resources.close(); }
}
