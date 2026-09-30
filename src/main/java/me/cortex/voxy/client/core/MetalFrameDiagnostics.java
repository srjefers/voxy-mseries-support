package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalNative;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.SectionCoverageTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.Logger;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.Locale;

/** Explicit, bounded CPU readbacks after the normal Metal-to-GL visibility wait. */
final class MetalFrameDiagnostics implements AutoCloseable {
    private IGpuBuffer depthReadback;
    private IGpuBuffer translucentDepthReadback;

    void sample(MetalRenderBackend backend, Viewport<?> viewport, IOSurfaceBridge color,
                IGpuTexture depth, IOSurfaceBridge translucentColor, IGpuTexture translucentDepth,
                BasicSectionGeometryData geometry, boolean pending,
                me.cortex.voxy.common.world.WorldEngine world, IGpuBuffer nodes, int maxNode) {
        var level=net.minecraft.client.Minecraft.getInstance().level;
        var probe=level==null?null:SectionProbe.snapshot(world,level.dimension().identifier().toString(),System.nanoTime());
        if (probe==null) return;
        long bytes = (long) viewport.width * viewport.height * Float.BYTES;
        if (this.depthReadback == null || this.depthReadback.size() != bytes) {
            if (this.depthReadback != null) this.depthReadback.free();
            this.depthReadback = backend.createBuffer(bytes);
        }
        backend.copyTextureToBuffer(depth, this.depthReadback, viewport.width, viewport.height);
        boolean viewWater = probe != null && probe.view() && translucentColor != null && translucentDepth != null;
        if (viewWater) {
            if (this.translucentDepthReadback == null || this.translucentDepthReadback.size() != bytes) {
                if (this.translucentDepthReadback != null) this.translucentDepthReadback.free();
                this.translucentDepthReadback = backend.createBuffer(bytes);
            }
            backend.copyTextureToBuffer(translucentDepth, this.translucentDepthReadback, viewport.width, viewport.height);
        }
        backend.submit(); // The optional readback requires its own visibility wait.
        var coverage = SectionCoverageTracker.INSTANCE.subscribe();
        SectionCoverageTracker.Update snapshot;
        try { snapshot = coverage.drain(); } finally { coverage.close(); }
        String selection = "unavailable", draws = "unavailable";
        if (viewport instanceof MDICViewport mdic && mdic.getRenderList() instanceof MetalBuffer list) {
            int count = Math.max(0, Math.min(MemoryUtil.memGetInt(list.getContentsPtr()), (int)(list.size()/4 - 1)));
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < Math.min(count, 8); i++) {
                int id = MemoryUtil.memGetInt(list.getContentsPtr() + 4L*(i+1));
                ids.append(id);
                if (geometry != null && geometry.getMetadataBuffer() instanceof MetalBuffer meta
                        && id >= 0 && (id+1L)*32 <= meta.size()) {
                    ids.append("@lod").append(MemoryUtil.memGetInt(meta.getContentsPtr()+id*32L) >>> 28);
                }
                ids.append(',');
            }
            selection = count + " [" + ids + "]";
            if (mdic.drawCountCallBuffer instanceof MetalBuffer counts) {
                draws = MemoryUtil.memGetInt(counts.getContentsPtr()+12) + "/"
                        + MemoryUtil.memGetInt(counts.getContentsPtr()+16) + "/"
                        + MemoryUtil.memGetInt(counts.getContentsPtr()+20);
            }
        }
        this.probe(probe,viewport,color,viewWater ? translucentColor : null,
                geometry,nodes,maxNode,snapshot.generation(),snapshot.changes(),selection,draws);
    }

    private void probe(SectionProbe.Snapshot probe, Viewport<?> viewport, IOSurfaceBridge color,
                       IOSurfaceBridge translucentColor,
                       BasicSectionGeometryData geometry, IGpuBuffer nodes, int maxNode, long generation,
                       java.util.Map<Long,SectionCoverageTracker.Coverage> coverage, String selected, String drawCounts) {
        var description=new StringBuilder();
        if (nodes instanceof MetalBuffer buffer) {
            long pointer=buffer.getContentsPtr();
            // NodeStore reports the inclusive highest allocated ID.
            int limit=Math.min(maxNode+1,(int)(buffer.size()/16));
            for (int id=0;id<limit;id++) {
                long p=pointer+id*16L;
                long position=((long)MemoryUtil.memGetInt(p)<<32)|Integer.toUnsignedLong(MemoryUtil.memGetInt(p+4));
                if(!probe.stages().containsKey(position)) continue;
                int mesh=MemoryUtil.memGetInt(p+8)&0xffffff, children=MemoryUtil.memGetInt(p+12)&0xffffff;
                boolean inList=false;
                if(viewport instanceof MDICViewport mdic && mdic.getRenderList() instanceof MetalBuffer list) {
                    int count=Math.max(0,Math.min(MemoryUtil.memGetInt(list.getContentsPtr()),(int)(list.size()/4-1)));
                    for(int i=0;i<count;i++) if(MemoryUtil.memGetInt(list.getContentsPtr()+4L*(i+1))==mesh){inList=true;break;}
                }
                description.append("node=").append(id).append(" pos=").append(me.cortex.voxy.common.world.WorldEngine.pprintPos(position))
                        .append(" mesh=").append(mesh).append(" childPtr=").append(children).append(" selected=").append(inList);
                if(geometry!=null && mesh<0xfffffe && geometry.getMetadataBuffer() instanceof MetalBuffer meta && (mesh+1L)*32<=meta.size()) {
                    long m=meta.getContentsPtr()+mesh*32L;
                    long uploaded=((long)MemoryUtil.memGetInt(m)<<32)|Integer.toUnsignedLong(MemoryUtil.memGetInt(m+4));
                    description.append(" uploadedPos=").append(me.cortex.voxy.common.world.WorldEngine.pprintPos(uploaded));
                }
                description.append(';');
            }
        }
        long section=SectionPos.asLong(probe.x()>>4,probe.y()>>4,probe.z()>>4);
        String target=probe.view()?"crosshair":probe.x()+","+probe.y()+","+probe.z();
        String drawable=probe.draws().entrySet().stream().map(e->e.getKey()+"="+e.getValue().size())
                .collect(java.util.stream.Collectors.joining(",","{","}"));
        String center=probe.view()?centerSections(viewport,coverage,probe.draws()):"n/a";
        Logger.info("[Metal-Probe] target="+target+" dim="+probe.dimension()
                +" frame="+WorldFrameCapture.frame()+" viewportFrame="+viewport.frameId+" generation="+generation+" sodiumCoverage="+
                (probe.view()?"view":SectionCoverageTracker.INSTANCE.coverageAt(section))
                +" drawable="+drawable+" centerSections="+center+" stages="+probe.stages()+" graph="+description+" draws="+selected
                +" drawCounts="+drawCounts+" targetPixel="+targetPixel(probe,color,viewport)+" pixels="+pixels(color,viewport,probe.view())
                +(translucentColor == null ? "" : " transPixels="+translucentPixels(translucentColor,viewport)));
    }

    private static String centerSections(Viewport<?> viewport,
                                         java.util.Map<Long,SectionCoverageTracker.Coverage> coverage,
                                         java.util.Map<String,java.util.Set<Long>> draws) {
        var positions=new java.util.HashSet<Long>(coverage.keySet());
        draws.values().forEach(positions::addAll);
        var result=new StringBuilder("[");
        int matched=0;
        for (long position:positions) {
            int x=SectionPos.x(position),y=SectionPos.y(position),z=SectionPos.z(position);
            if(!SectionScreenProjection.touchesCenter(viewport.MVP,viewport.cameraX,viewport.cameraY,viewport.cameraZ,
                    x,y,z,viewport.width,viewport.height,16)) continue;
            if(matched++>=12) continue;
            if(result.length()>1) result.append(';');
            result.append(x).append(',').append(y).append(',').append(z).append(':')
                    .append(coverage.getOrDefault(position,SectionCoverageTracker.Coverage.NONE)).append('/');
            draws.forEach((pass,sections)->{if(sections.contains(position)) result.append(pass).append('+');});
        }
        return result.append("] total=").append(matched).toString();
    }

    private String targetPixel(SectionProbe.Snapshot probe, IOSurfaceBridge color, Viewport<?> viewport) {
        int x=viewport.width/2,y=viewport.height/2;
        if(!probe.view()) {
            var point=new org.joml.Vector4f((float)(probe.x()+0.5-viewport.cameraX),(float)(probe.y()+0.5-viewport.cameraY),(float)(probe.z()+0.5-viewport.cameraZ),1);
            viewport.MVP.transform(point);
            if(!Float.isFinite(point.w) || point.w<=0) return "behind-camera";
            x=(int)((point.x/point.w*0.5+0.5)*viewport.width);
            y=(int)((0.5-point.y/point.w*0.5)*viewport.height);
        }
        if(x<0 || y<0 || x>=viewport.width || y>=viewport.height) return "off-screen";
        long surface=color.ioSurfaceHandle();
        if(MetalNative.iosurfaceLockReadOnly(surface)!=0) return "surface-lock-failed";
        try {
            long p=4L*((long)y*viewport.width+x), base=MetalNative.iosurfaceGetBaseAddress(surface);
            int stride=MetalNative.iosurfaceGetBytesPerRow(surface);
            float bound=viewport.metalBoundReadBuffer instanceof MetalBuffer buffer && p+20<=buffer.size()?MemoryUtil.memGetFloat(buffer.getContentsPtr()+16+p):Float.NaN;
            float depth=MemoryUtil.memGetFloat(((MetalBuffer)this.depthReadback).getContentsPtr()+p);
            return x+","+y+":"+Integer.toHexString(MemoryUtil.memGetInt(base+(long)y*stride+4L*x))+"/depth="+depth+"/bound="+bound;
        } finally { MetalNative.iosurfaceUnlockReadOnly(surface); }
    }

    private String pixels(IOSurfaceBridge color, Viewport<?> viewport) {
        return pixels(color,viewport,false);
    }

    private String pixels(IOSurfaceBridge color, Viewport<?> viewport, boolean view) {
        long surface = color.ioSurfaceHandle();
        if (MetalNative.iosurfaceLockReadOnly(surface) != 0) return "surface-lock-failed";
        try {
            long base = MetalNative.iosurfaceGetBaseAddress(surface);
            int stride = MetalNative.iosurfaceGetBytesPerRow(surface);
            long depth = ((MetalBuffer)this.depthReadback).getContentsPtr();
            var result = new StringBuilder();
            int[] ys=view?new int[]{viewport.height/2-16,viewport.height/2,viewport.height/2+16}
                    :new int[]{viewport.height/3,viewport.height/2,viewport.height*2/3};
            int[] xs=view?new int[]{viewport.width/2-16,viewport.width/2,viewport.width/2+16}
                    :new int[]{viewport.width/3,viewport.width/2,viewport.width*2/3};
            for (int y : ys) {
                for (int x : xs) {
                    if(x<0 || y<0 || x>=viewport.width || y>=viewport.height) continue;
                    long offset = 4L*((long)y*viewport.width+x);
                    float bound = viewport.metalBoundReadBuffer instanceof MetalBuffer buffer
                            && offset+20 <= buffer.size() ? MemoryUtil.memGetFloat(buffer.getContentsPtr()+16+offset) : Float.NaN;
                    result.append(String.format(Locale.ROOT, "(%d,%d):%08x/%.7f/%.7f;",
                            x,y,MemoryUtil.memGetInt(base+(long)y*stride+4L*x),MemoryUtil.memGetFloat(depth+offset),bound));
                }
            }
            return result.toString();
        } finally { MetalNative.iosurfaceUnlockReadOnly(surface); }
    }

    private String translucentPixels(IOSurfaceBridge color, Viewport<?> viewport) {
        long surface=color.ioSurfaceHandle();
        if (MetalNative.iosurfaceLockReadOnly(surface)!=0) return "surface-lock-failed";
        try {
            long base=MetalNative.iosurfaceGetBaseAddress(surface);
            int stride=MetalNative.iosurfaceGetBytesPerRow(surface);
            long depth=((MetalBuffer)this.translucentDepthReadback).getContentsPtr();
            var result=new StringBuilder();
            for(int y: new int[]{viewport.height/2-16,viewport.height/2,viewport.height/2+16}) {
                for(int x: new int[]{viewport.width/2-16,viewport.width/2,viewport.width/2+16}) {
                    if(x<0 || y<0 || x>=viewport.width || y>=viewport.height) continue;
                    long offset=4L*((long)y*viewport.width+x);
                    result.append(String.format(Locale.ROOT,"(%d,%d):%08x/%.7f;",x,y,
                            MemoryUtil.memGetInt(base+(long)y*stride+4L*x),MemoryUtil.memGetFloat(depth+offset)));
                }
            }
            return result.toString();
        } finally { MetalNative.iosurfaceUnlockReadOnly(surface); }
    }

    @Override public void close() {
        if (this.depthReadback != null) { this.depthReadback.free(); this.depthReadback = null; }
        if (this.translucentDepthReadback != null) { this.translucentDepthReadback.free(); this.translucentDepthReadback = null; }
    }
}
