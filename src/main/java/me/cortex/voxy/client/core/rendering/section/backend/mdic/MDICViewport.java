package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;

public class MDICViewport extends Viewport<MDICViewport> {
    public final IGpuBuffer drawCountCallBuffer = RenderBackendFactory.get().createBuffer(1024).zero();
    public final IGpuBuffer drawCallBuffer = RenderBackendFactory.get().createBuffer(5*4*(MDICSectionRenderer.OPAQUE_DRAW_COUNT+MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT+MDICSectionRenderer.TEMPORAL_DRAW_COUNT)).zero();//400k draw calls
    public final IGpuBuffer positionScratchBuffer  = RenderBackendFactory.get().createBuffer(8*400000).zero();//400k positions
    public final IGpuBuffer indirectLookupBuffer = RenderBackendFactory.get().createBuffer(HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE *4+4);//In theory, this could be global/not unique to the viewport
    public final IGpuBuffer visibilityBuffer;

    public MDICViewport(RenderProperties properties, int maxSectionCount) {
        super(properties);
        this.visibilityBuffer = RenderBackendFactory.get().createBuffer(maxSectionCount*4L);
    }

    @Override
    protected void delete0() {
        super.delete0();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public IGpuBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}
