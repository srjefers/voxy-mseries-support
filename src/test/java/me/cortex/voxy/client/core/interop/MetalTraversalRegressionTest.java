package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.*;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.GL_R32F;

/** Actual traversal kernel with Java-packed nodes and independently expected queue results. */
public final class MetalTraversalRegressionTest {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static long pointer(IGpuBuffer buffer) { return ((MetalBuffer)buffer).getContentsPtr(); }
    public static void main(String[] args) throws Exception {
        Logger.SHUTUP = true;
        var backend = new MetalRenderBackend();
        RenderBackendFactory.set(backend);
        var buffers = new IGpuBuffer[10];
        MetalTexture hiz = null;
        IGpuSampler sampler = null;
        IGpuPipeline pipeline = null;
        long push = MemoryUtil.nmemCallocChecked(1,16);
        try {
            for (int i = 1; i < buffers.length; i++) buffers[i] = backend.createBuffer(256).zero();
            var defines = new LinkedHashMap<String,String>();
            String[] names = {"SCENE_UNIFORM_BINDING","REQUEST_QUEUE_BINDING","RENDER_QUEUE_BINDING","NODE_DATA_BINDING","RESERVED","NODE_QUEUE_META_BINDING","NODE_QUEUE_SOURCE_BINDING","NODE_QUEUE_SINK_BINDING","RENDER_TRACKER_BINDING"};
            for (int i = 0; i < names.length; i++) defines.put(names[i],Integer.toString(i+1));
            defines.put("MAX_ITERATIONS","5"); defines.put("LOCAL_SIZE_BITS","0");
            defines.put("MAX_REQUEST_QUEUE_SIZE","16"); defines.put("HIZ_BINDING","0");
            pipeline = backend.createComputePipeline(new ComputePipelineDesc(ShaderLoader.parseAndStripPrintf("voxy:lod/hierarchical/traversal_dev.comp"),defines,null,null,1,1,1,"Traversal regression"));
            hiz = (MetalTexture)backend.createTexture(GL_TEXTURE_2D);
            hiz.storeUploadable(GL_R32F,1,1,1);
            MemoryUtil.memPutFloat(push,1); hiz.uploadSubImage2D(0,0,0,1,1,GL_RED,GL_FLOAT,push);
            sampler = backend.createSampler(SamplerDesc.builder().build());
            long scene = pointer(buffers[1]);
            for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(scene+i*20L,i==3?1:.005f);
            MemoryUtil.memPutInt(scene+76,0x00010001);
            MemoryUtil.memPutFloat(scene+92,1_000_000);
            for (int plane = 0; plane < 6; plane++) MemoryUtil.memPutFloat(scene+96+plane*16L+12,1);
            MemoryUtil.memPutInt(scene+192,32); MemoryUtil.memPutInt(scene+196,7); MemoryUtil.memPutInt(scene+200,16);
            var nodes = new NodeStore(32);
            int parent = nodes.allocate(), child = nodes.allocate();
            nodes.setNodePosition(parent,WorldEngine.getWorldSectionId(1,0,0,0));
            nodes.setNodePosition(child,WorldEngine.getWorldSectionId(0,0,0,0));
            nodes.setNodeGeometry(parent,-2); nodes.setNodeGeometry(child,7);
            nodes.setNodeChildExistence(parent,(byte)1); nodes.setChildPtr(parent,child); nodes.setChildPtrCount(parent,1);
            nodes.setChildPtrCount(child,1);
            nodes.writeNode(pointer(buffers[4]),parent); nodes.writeNode(pointer(buffers[4])+16,child);
            dispatch(backend,pipeline,buffers,hiz,sampler,push,parent,0);
            require(MemoryUtil.memGetInt(pointer(buffers[6])+28)==1,"empty parent stopped traversal");
            require(MemoryUtil.memGetInt(pointer(buffers[8]))==child,"wrong child selected");
            require(MemoryUtil.memGetInt(pointer(buffers[3]))==0,"empty parent emitted geometry");
            dispatch(backend,pipeline,buffers,hiz,sampler,push,child,1);
            require(MemoryUtil.memGetInt(pointer(buffers[3]))==1 && MemoryUtil.memGetInt(pointer(buffers[3])+4)==7,"nonempty descendant was not rendered");
            System.out.println("PASS: empty parent reaches and renders its nonempty descendant at coarse screen size");

            buffers[2].zero(); buffers[3].zero(); buffers[6].zero();
            nodes.setChildPtr(parent,-1); nodes.writeNode(pointer(buffers[4]),parent);
            dispatch(backend,pipeline,buffers,hiz,sampler,push,parent,0);
            require(MemoryUtil.memGetInt(pointer(buffers[2]))==1,"missing descendants were never requested");
            require(MemoryUtil.memGetInt(pointer(buffers[3]))==0,"empty parent invented geometry");
            System.out.println("PASS: empty parent requests unavailable descendants");

            buffers[2].zero(); buffers[3].zero(); buffers[6].zero();
            nodes.setNodeGeometry(parent,9); nodes.writeNode(pointer(buffers[4]),parent);
            MemoryUtil.memPutFloat(scene+92,-1);
            dispatch(backend,pipeline,buffers,hiz,sampler,push,parent,0);
            require(MemoryUtil.memGetInt(pointer(buffers[2]))==1,"unready descendants were not requested");
            require(MemoryUtil.memGetInt(pointer(buffers[3]))==1 && MemoryUtil.memGetInt(pointer(buffers[3])+4)==9,"available parent disappeared before descendants arrived");
            System.out.println("PASS: available parent remains visible while descendants are unavailable");
            cameraJumpChecks(backend, pipeline, buffers, hiz, sampler, push, nodes, parent, child);
        } finally {
            if (pipeline != null) pipeline.close();
            if (sampler != null) sampler.close();
            if (hiz != null) hiz.free();
            for (var buffer : buffers) if (buffer != null) buffer.free();
            MemoryUtil.nmemFree(push); backend.shutdown(); RenderBackendFactory.set(null);
        }
    }
    /** Controlled GPU scene: retained node tables, camera jumps, and readiness transitions. */
    private static void cameraJumpChecks(MetalRenderBackend backend, IGpuPipeline pipeline,
                                         IGpuBuffer[] buffers, MetalTexture hiz, IGpuSampler sampler,
                                         long push, NodeStore nodes, int parent, int child) {
        int[][] origins = {{0,0,0}, {-13,-7,-17}, {13,7,17},
                {-1_000_000,-63,1_000_000}, {1_000_000,63,-1_000_000}};
        long scene = pointer(buffers[1]);
        int frame = 100;
        int dispatches = 0;
        // Five active planes: +/- X constrain the view; the remaining three pass.
        for (int plane = 0; plane < 6; plane++) {
            for (int component = 0; component < 4; component++) {
                MemoryUtil.memPutFloat(scene + 96 + plane * 16L + component * 4L,
                        component == 3 ? 128 : 0);
            }
        }
        MemoryUtil.memPutFloat(scene + 96, 1);
        MemoryUtil.memPutFloat(scene + 112, -1);
        MemoryUtil.memPutFloat(scene + 80, 0);
        MemoryUtil.memPutFloat(scene + 84, 0);
        MemoryUtil.memPutFloat(scene + 88, 0);
        for (int[] origin : origins) {
            long parentPosition = WorldEngine.getWorldSectionId(1, origin[0], origin[1], origin[2]);
            long childPosition = WorldEngine.getWorldSectionId(0, origin[0]*2, origin[1]*2, origin[2]*2);
            nodes.setNodePosition(parent, parentPosition);
            nodes.setNodePosition(child, childPosition);
            nodes.setNodeGeometry(parent, 9);
            nodes.setNodeGeometry(child, 7);
            nodes.setNodeChildExistence(parent, (byte)1);
            nodes.setChildPtrCount(parent, 1);
            nodes.setChildPtrCount(child, 1);
            nodes.writeNode(pointer(buffers[4])+child*16L, child);
            for (int cycle = 0; cycle < 48; cycle++) {
                boolean descendantsReady = cycle % 3 != 1;
                boolean fine = cycle % 3 != 0;
                nodes.setChildPtr(parent, descendantsReady ? child : -1);
                nodes.writeNode(pointer(buffers[4])+parent*16L, parent);
                for (int view = 0; view < 4; view++) {
                    boolean away = view % 2 == 1;
                    buffers[2].zero(); buffers[3].zero(); buffers[6].zero(); buffers[8].zero();
                    MemoryUtil.memPutInt(scene + 64, origin[0]*2 + (away ? 32 : 0));
                    MemoryUtil.memPutInt(scene + 68, origin[1]*2);
                    MemoryUtil.memPutInt(scene + 72, origin[2]*2);
                    MemoryUtil.memPutFloat(scene + 92, fine ? -1 : 1_000_000);
                    MemoryUtil.memPutInt(scene + 196, ++frame);
                    dispatch(backend, pipeline, buffers, hiz, sampler, push, parent, 0);
                    dispatches++;
                    require(MemoryUtil.memGetInt(pointer(buffers[4])+parent*16L) == (int)(parentPosition >>> 32) &&
                                    MemoryUtil.memGetInt(pointer(buffers[4])+parent*16L+4) == (int)parentPosition,
                            "camera jump changed the retained parent position");
                    require((MemoryUtil.memGetInt(pointer(buffers[4])+parent*16L+8)&0xffffff) == 9,
                            "camera jump changed the retained parent mesh");
                    if (away) {
                        require(MemoryUtil.memGetInt(pointer(buffers[3])) == 0,
                                "out-of-frustum retained node was drawn");
                        require(MemoryUtil.memGetInt(pointer(buffers[2])) == 0,
                                "out-of-frustum retained node requested geometry");
                        continue;
                    }
                    if (fine && descendantsReady) {
                        require(MemoryUtil.memGetInt(pointer(buffers[6])+28) == 1,
                                "returned camera lost the ready child");
                        require(MemoryUtil.memGetInt(pointer(buffers[8])) == child,
                                "returned camera selected the wrong child");
                        dispatch(backend, pipeline, buffers, hiz, sampler, push, child, 1);
                        dispatches++;
                    }
                    int mesh = fine && descendantsReady ? 7 : 9;
                    int drawnNode = fine && descendantsReady ? child : parent;
                    require(MemoryUtil.memGetInt(pointer(buffers[3])) == 1 &&
                                    MemoryUtil.memGetInt(pointer(buffers[3])+4) == mesh,
                            "return after camera jump lost or changed cached geometry");
                    require(MemoryUtil.memGetInt(pointer(buffers[9])+drawnNode*4L) == frame,
                            "returned geometry received a stale visibility stamp");
                    if (fine && !descendantsReady) {
                        require(MemoryUtil.memGetInt(pointer(buffers[2])) == 1,
                                "unavailable descendants were not requested on return");
                        // Simulate CPU acknowledgement before the next independent view.
                        nodes.writeNode(pointer(buffers[4])+parent*16L, parent);
                    }
                }
            }
        }
        System.out.println("PASS: 960 camera-jump frames / " + dispatches +
                " Metal dispatches preserve cached mesh identities, parent fallback, signed positions and visibility stamps");
    }
    private static void dispatch(MetalRenderBackend backend,IGpuPipeline pipeline,IGpuBuffer[] buffers,
                                 MetalTexture hiz,IGpuSampler sampler,long push,int node,int iteration) {
        MemoryUtil.memPutInt(pointer(buffers[7]),node);
        MemoryUtil.memPutInt(pointer(buffers[6])+iteration*16L+12,1);
        MemoryUtil.memPutInt(push,iteration);
        try (var encoder = backend.beginComputePass()) {
            encoder.setPipeline(pipeline);
            for (int i = 1; i < buffers.length; i++) encoder.setBuffer(i,buffers[i],0);
            encoder.setBytes(14,push,16); encoder.setTexture(0,hiz); encoder.setSampler(0,sampler);
            encoder.dispatch(1,1,1);
        }
        backend.submit();
    }
}
