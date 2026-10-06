package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;
import java.util.Map;

/** Actual Metal depth restore, water depth test, export and readback across moving water masks. */
public final class WaterDepthBridgeRegressionTest {
    private static final int WIDTH=32, HEIGHT=16;
    public static void main(String[] args) {
        if(!org.lwjgl.glfw.GLFW.glfwInit())throw new AssertionError("GLFW init");
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE,org.lwjgl.glfw.GLFW.GLFW_FALSE);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR,1);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE,org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT,1);
        long window=org.lwjgl.glfw.GLFW.glfwCreateWindow(WIDTH,HEIGHT,"water bridge",0,0);
        if(window==0)throw new AssertionError("GL context");
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);org.lwjgl.opengl.GL.createCapabilities();
        Logger.SHUTUP=true;
        var backend=new MetalRenderBackend();
        var restore=new MetalDepthRestore(backend);var export=new MetalDepthExport(backend);
        var source=backend.createBuffer(WIDTH*HEIGHT*4L);
        var copied=backend.createBuffer(WIDTH*HEIGHT*4L);
        var packed=backend.createBuffer(WIDTH*HEIGHT*4L);
        var pixels=backend.createBuffer(WIDTH*HEIGHT*4L);
        var water=backend.createBuffer(WIDTH*HEIGHT*4L);
        var opaque=backend.createTexture().store(0x8CAC,1,WIDTH,HEIGHT);
        var combined=backend.createTexture().store(0x8CAC,1,WIDTH,HEIGHT);
        var bridge=backend.createTexture().store(0x8058,1,WIDTH,HEIGHT);
        var color=backend.createTexture().store(0x8058,1,WIDTH,HEIGHT);
        var shared=IOSurfaceBridge.create(backend.device(),WIDTH,HEIGHT,IOSurfaceBridge.IOSurfaceFormat.BGRA8);
        int rectangle=org.lwjgl.opengl.GL33C.glGenTextures();
        String fragment="""
                #version 460
                layout(std430,binding=0) readonly buffer WaterDepth {float depths[];};
                layout(location=0) out vec4 material;
                void main(){gl_FragDepth=depths[uint(gl_FragCoord.y)*32u+uint(gl_FragCoord.x)];material=vec4(.5,.5,.5,.7);}
                """;
        try(var pipeline=backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                ShaderLoader.parse("voxy:post/fullscreen2.vert"),fragment,Map.of(),null,null,null,null,
                0x8058,VertexLayout.EMPTY,new PipelineState(PipelineState.DepthState.DEFAULT,
                PipelineState.BlendState.OPAQUE,PipelineState.RasterState.NO_CULL),"water-depth-fixture"))) {
            for(int frame=0;frame<8;frame++) {
                for(int y=0;y<HEIGHT;y++) for(int x=0;x<WIDTH;x++) {
                    int index=y*WIDTH+x;
                    MemoryUtil.memPutFloat(((MetalBuffer)source).getContentsPtr()+index*4L,floor(x,y,frame));
                    MemoryUtil.memPutFloat(((MetalBuffer)water).getContentsPtr()+index*4L,surface(x,y,frame));
                }
                restore.render(backend,source,opaque,WIDTH,HEIGHT);
                backend.copyTextureToBuffer(opaque,copied,WIDTH,HEIGHT);
                restore.render(backend,copied,combined,WIDTH,HEIGHT);
                var pass=RenderPassDesc.builder(WIDTH,HEIGHT).clearColor(color,0,0,0,0)
                        .depthAttachment(combined,0,RenderPassDesc.LoadAction.LOAD,RenderPassDesc.StoreAction.STORE,1).build();
                try(var encoder=backend.beginRenderPass(pass)) {
                    encoder.setPipeline(pipeline);encoder.setBuffer(0,water,0);
                    encoder.setViewport(0,0,WIDTH,HEIGHT,0,1);encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLE_STRIP,0,4,1,0);
                }
                backend.copyTextureToBuffer(combined,copied,WIDTH,HEIGHT);
                export.render(backend,copied,bridge,WIDTH,HEIGHT);
                backend.copyTextureToBuffer(bridge,packed,WIDTH,HEIGHT);
                backend.copyTextureToBuffer(color,pixels,WIDTH,HEIGHT);
                export.render(backend,copied,shared.asGpuTexture(),WIDTH,HEIGHT);
                backend.submit();
                verifyGl(shared,rectangle,frame);
                for(int y=0;y<HEIGHT;y++) for(int x=0;x<WIDTH;x++) {
                    int index=y*WIDTH+x;float floor=floor(x,y,frame),surface=surface(x,y,frame);
                    float expected=Math.min(floor,surface);
                    float depth=MemoryUtil.memGetFloat(((MetalBuffer)copied).getContentsPtr()+index*4L);
                    if(Float.floatToIntBits(depth)!=Float.floatToIntBits(expected)) throw new AssertionError("Depth mismatch frame="+frame+" pixel="+x+","+y+" actual="+depth+" expected="+expected);
                    long address=((MetalBuffer)packed).getContentsPtr()+index*4L;
                    float decoded=(MemoryUtil.memGetByte(address)&255)/255f+(MemoryUtil.memGetByte(address+1)&255)/65025f+(MemoryUtil.memGetByte(address+2)&255)/16581375f;
                    if(Math.abs(decoded-expected)>2e-6f) throw new AssertionError("Packed bridge changed depth frame="+frame+" actual="+decoded+" expected="+expected);
                    int alpha=MemoryUtil.memGetByte(((MetalBuffer)pixels).getContentsPtr()+index*4L+3)&255;
                    if((alpha>0)!=(surface<=floor)) throw new AssertionError("Water coverage/depth disagreed at frame="+frame+" pixel="+x+","+y);
                }
            }
            System.out.println("PASS: actual Metal opaque-to-water restore, depth ownership, packed export, row orientation and eight changing frames");
        } finally {
            restore.close();export.close();source.free();copied.free();packed.free();pixels.free();water.free();
            opaque.free();combined.free();bridge.free();color.free();shared.close();backend.shutdown();
            me.cortex.voxy.client.core.util.VxIrisSideChannel.destroy();org.lwjgl.opengl.GL33C.glDeleteTextures(rectangle);
            org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);org.lwjgl.glfw.GLFW.glfwTerminate();
        }
    }
    private static void verifyGl(IOSurfaceBridge shared,int texture,int frame) {
        org.lwjgl.opengl.GL33C.glActiveTexture(org.lwjgl.opengl.GL33C.GL_TEXTURE0);
        org.lwjgl.opengl.GL33C.glBindTexture(org.lwjgl.opengl.GL33C.GL_TEXTURE_RECTANGLE,texture);
        if(!shared.bindToGlTexture(texture))throw new AssertionError("IOSurface GL binding failed");
        org.lwjgl.opengl.GL33C.glTexParameteri(org.lwjgl.opengl.GL33C.GL_TEXTURE_RECTANGLE,org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER,org.lwjgl.opengl.GL33C.GL_NEAREST);
        org.lwjgl.opengl.GL33C.glTexParameteri(org.lwjgl.opengl.GL33C.GL_TEXTURE_RECTANGLE,org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER,org.lwjgl.opengl.GL33C.GL_NEAREST);
        var channel=me.cortex.voxy.client.core.util.VxIrisSideChannel.getOrCreate();
        if(!channel.resolveTrans(texture,texture,WIDTH,HEIGHT,false))throw new AssertionError("GL side-channel conversion failed");
        org.lwjgl.opengl.GL33C.glBindTexture(org.lwjgl.opengl.GL33C.GL_TEXTURE_2D,channel.transDepthTexId());
        float[] values=new float[WIDTH*HEIGHT];
        org.lwjgl.opengl.GL33C.glGetTexImage(org.lwjgl.opengl.GL33C.GL_TEXTURE_2D,0,org.lwjgl.opengl.GL33C.GL_DEPTH_COMPONENT,org.lwjgl.opengl.GL33C.GL_FLOAT,values);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            int metalY=HEIGHT-1-y;
            float expected=Math.min(floor(x,metalY,frame),surface(x,metalY,frame))*.5f+.5f;
            if(Math.abs(values[y*WIDTH+x]-expected)>2e-6f)
                throw new AssertionError("Metal-to-GL water depth mismatch frame="+frame+" pixel="+x+","+y+" actual="+values[y*WIDTH+x]+" expected="+expected);
        }
        if(org.lwjgl.opengl.GL33C.glGetError()!=org.lwjgl.opengl.GL33C.GL_NO_ERROR)throw new AssertionError("Water bridge GL error");
    }
    private static float floor(int x,int y,int frame) {return .80f+.002f*y+.001f*x+.0001f*frame;}
    private static float surface(int x,int y,int frame) {return ((x+frame)%8)<4?.78f+.001f*y:.96f;}
}
