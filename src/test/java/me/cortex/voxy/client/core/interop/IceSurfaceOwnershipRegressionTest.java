package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.*;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration;
import me.cortex.voxy.common.util.ResourceScope;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;
import static org.mockito.Mockito.*;

/** Real production fragment/state: water submitted behind ice cannot replace its visible surface. */
public final class IceSurfaceOwnershipRegressionTest {
    private static final int SIZE=4;
    private static final String VERTEX="""
            #version 460
            layout(binding=14,std140) uniform Fixture {vec4 params;};
            layout(location=0) out flat uvec4 interData;
            layout(location=1) out vec2 uv;
            layout(location=2) out float voxyFogDist;
            void main(){
                vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                gl_Position=vec4(p*2-1,params.x,1);
                interData=uvec4(uint(params.z)<<4,0xffffffffu,0xffffffffu,uint(params.y)<<19);
                uv=vec2(.5);voxyFogDist=300;
            }
            """;
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP=true;
        int failures=0;
        try(var context=new TestGlContext();var minecraft=mockStatic(Minecraft.class);var resources=new ResourceScope()) {
            var client=mock(Minecraft.class);client.level=mock(net.minecraft.client.multiplayer.ClientLevel.class);
            when(client.level.getShade(any(),anyBoolean())).thenReturn(1f);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            var backend=resources.own(new MetalRenderBackend(),MetalRenderBackend::shutdown);
            var scene=resources.own(backend.createBuffer(1024),IGpuBuffer::free);
            var bounds=resources.own(backend.createBuffer(16+SIZE*SIZE*4),IGpuBuffer::free);
            var tint=resources.own(backend.createBuffer(2048),IGpuBuffer::free);
            var model=resources.own(backend.createBuffer(64),IGpuBuffer::free);
            var readDepth=resources.own(backend.createBuffer(SIZE*SIZE*4),IGpuBuffer::free);
            for(var buffer:new IGpuBuffer[]{scene,bounds,tint,model})MemoryUtil.memSet(((MetalBuffer)buffer).getContentsPtr(),0,buffer.size());
            MemoryUtil.memPutInt(((MetalBuffer)bounds).getContentsPtr(),SIZE);
            long scenePtr=((MetalBuffer)scene).getContentsPtr();
            MemoryUtil.memPutFloat(scenePtr+132,100);MemoryUtil.memPutFloat(scenePtr+136,.01f);MemoryUtil.memPutFloat(scenePtr+140,.95f);
            var sampler=resources.own(backend.createSampler(SamplerDesc.builder().build()),IGpuSampler::close);
            var ice=texture(resources,backend,0x80ffd9bf);
            var water=texture(resources,backend,0xbfc04020);
            var empty=texture(resources,backend,0x00ffd9bf);
            var target=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
            target.storeRenderTargetUploadable(GL_RGBA8,1,SIZE,SIZE);
            var second=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
            second.storeRenderTargetUploadable(GL_RGBA8,1,SIZE,SIZE);
            var third=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
            third.storeRenderTargetUploadable(GL_RGBA8,1,SIZE,SIZE);
            var depth=resources.own(backend.createTexture().store(GL_DEPTH_COMPONENT32F,1,SIZE,SIZE),IGpuTexture::free);
            long params=MemoryUtil.nmemAllocChecked(16),pixels=MemoryUtil.nmemAllocChecked(SIZE*SIZE*4);
            try {
                for(var mode:new MetalMaterialPolicy[]{MetalMaterialPolicy.SHADERS_OFF,MetalMaterialPolicy.CONTRACT}) {
                    var policy=mock(AbstractRenderPipeline.class);
                    when(policy.materialPolicy()).thenReturn(mode);when(policy.vxMaterialMode()).thenReturn(mode==MetalMaterialPolicy.CONTRACT);
                    when(policy.vxOpaqueMaterialMode()).thenReturn(mode==MetalMaterialPolicy.CONTRACT);
                    when(policy.useEnvFog()).thenReturn(mode==MetalMaterialPolicy.SHADERS_OFF);
                    var production=TerrainShaderConfiguration.load(policy,BackendType.METAL).translucent();
                    var descriptor=new GraphicsPipelineDesc(VERTEX,production.fragmentGlsl,production.defines,null,null,null,null,
                            production.colorAttachmentFormats,VertexLayout.EMPTY,production.state,"Ice surface ownership "+mode);
                    MetalPrelitResolve resolve=mode==MetalMaterialPolicy.SHADERS_OFF?new MetalPrelitResolve(backend):null;
                    try(var pipeline=backend.createGraphicsPipeline(descriptor)) {
                        for(int face=0;face<6;face++) {
                            int expected=0;
                            for(int sequence=0;sequence<6;sequence++) {
                                if(resolve!=null)try(var clear=backend.beginRenderPass(RenderPassDesc.builder(SIZE,SIZE).clearColor(second,.1f,.1f,.1f,1).build())) { }
                                var pass=RenderPassDesc.builder(SIZE,SIZE)
                                        .clearColor(target,resolve==null?.1f:0,resolve==null?.1f:0,resolve==null?.1f:0,resolve==null?1:0)
                                        .clearDepth(depth,sequence==5?.2f:.8f);
                                if(mode==MetalMaterialPolicy.CONTRACT)pass.clearColor(second,0,0,0,0).clearColor(third,0,0,0,0);
                                try(var encoder=backend.beginRenderPass(pass.build())) {
                                    encoder.setPipeline(pipeline);encoder.setViewport(0,0,SIZE,SIZE,0,1);
                                    encoder.setBuffer(0,scene,0);encoder.setBuffer(9,bounds,0);encoder.setBuffer(11,tint,0);encoder.setBuffer(3,model,0);
                                    encoder.setSampler(0,sampler);
                                    if(sequence==2)draw(encoder,water,params,.6f,1,face);
                                    draw(encoder,sequence==3 || sequence==4?empty:ice,params,.3f,0,face);
                                    if(sequence==1 || sequence==3 || sequence==5)draw(encoder,water,params,.6f,1,face);
                                }
                                if(resolve!=null)resolve.render(second,target,SIZE,SIZE);
                                backend.copyTextureToBuffer(depth,readDepth,SIZE,SIZE);backend.submit();
                                (resolve==null?target:second).getBytes(0,0,0,SIZE,SIZE,pixels);
                                int actual=MemoryUtil.memGetInt(pixels+(2*SIZE+2)*4);
                                float actualDepth=MemoryUtil.memGetFloat(((MetalBuffer)readDepth).getContentsPtr()+(2*SIZE+2)*4);
                                float expectedDepth=sequence==5?.2f:sequence==4?.8f:sequence==3?.6f:.3f;
                                if(sequence==0)expected=actual;
                                if(((sequence==1 || sequence==2) && actual!=expected)
                                        || ((sequence==4 || sequence==5) && actual!=0xff1a1a1a) || Math.abs(actualDepth-expectedDepth)>.00001f) {
                                    failures++;System.err.println("FAIL: "+mode+" face="+face+" sequence="+sequence+" pixel="+Integer.toHexString(actual)
                                            +" nearest-only="+Integer.toHexString(expected)+" depth="+actualDepth+" expectedDepth="+expectedDepth);
                                } else System.out.println("PASS: "+mode+" face="+face+" sequence="+sequence+" nearest-depth="+actualDepth);
                            }
                        }
                    } finally {if(resolve!=null)resolve.close();}
                }
            } finally {MemoryUtil.nmemFree(params);MemoryUtil.nmemFree(pixels);}
        }
        bridgeResolve();
        if(failures!=0)throw new AssertionError(failures+" ice surface ownership failures");
    }
    private static void bridgeResolve() {
        try(var context=new TestGlContext();var resources=new ResourceScope()) {
            var backend=resources.own(new MetalRenderBackend(),MetalRenderBackend::shutdown);
            var resolve=resources.own(new MetalPrelitResolve(backend),MetalPrelitResolve::close);
            // Non-square gradients expose row, channel, alpha and resize mistakes across the real IOSurface.
            for(int width:new int[]{8,4,16}) {
                int height=4;
                var layer=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
                layer.storeUploadable(GL_RGBA8,1,width,height);
                var bridge=resources.own(IOSurfaceBridge.create(backend.device(),width,height,IOSurfaceBridge.IOSurfaceFormat.BGRA8),IOSurfaceBridge::close);
                long data=MemoryUtil.nmemAllocChecked(width*height*4L);
                try {
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                        long pixel=data+4L*(y*width+x);
                        MemoryUtil.memPutByte(pixel,(byte)(32+x*8));MemoryUtil.memPutByte(pixel+1,(byte)(48+y*32));
                        MemoryUtil.memPutByte(pixel+2,(byte)160);MemoryUtil.memPutByte(pixel+3,(byte)(x%3==0?0:x%3==1?128:255));
                    }
                    layer.uploadSubImage2D(0,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,data);
                    try(var clear=backend.beginRenderPass(RenderPassDesc.builder(width,height).clearColor(bridge.asGpuTexture(),.1f,.2f,.3f,1).build())) { }
                    resolve.render(bridge.asGpuTexture(),layer,width,height);backend.submit();
                    long surface=bridge.ioSurfaceHandle();
                    if(MetalNative.iosurfaceLockReadOnly(surface)!=0)throw new AssertionError("bridge read lock failed");
                    try {
                        long base=MetalNative.iosurfaceGetBaseAddress(surface);int stride=MetalNative.iosurfaceGetBytesPerRow(surface);
                        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                            long src=data+4L*(y*width+x),dest=base+(long)y*stride+x*4L;
                            float alpha=(MemoryUtil.memGetByte(src+3)&255)/255f;
                            for(int channel=0;channel<3;channel++) {
                                int actual=MemoryUtil.memGetByte(dest+2-channel)&255;
                                int expected=Math.round((MemoryUtil.memGetByte(src+channel)&255)*alpha+(channel+1)*25.5f*(1-alpha));
                                if(Math.abs(actual-expected)>1)throw new AssertionError("BGRA bridge resolve pixel="+x+","+y+" channel="+channel+" actual="+actual+" expected="+expected);
                            }
                            if((MemoryUtil.memGetByte(dest+3)&255)!=255)throw new AssertionError("resolve changed opaque destination alpha");
                        }
                    } finally {MetalNative.iosurfaceUnlockReadOnly(surface);}
                } finally {MemoryUtil.nmemFree(data);}
            }
            System.out.println("PASS: real BGRA IOSurface blend preserves RGB channels, row coordinates, empty pixels, destination alpha and changing dimensions");
        }
    }
    private static MetalTexture texture(ResourceScope scope,MetalRenderBackend backend,int rgba) {
        var texture=scope.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
        texture.storeUploadable(GL_RGBA8,1,1,1);
        long pointer=MemoryUtil.nmemAllocChecked(4);
        try {MemoryUtil.memPutInt(pointer,rgba);texture.uploadSubImage2D(0,0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pointer);}
        finally {MemoryUtil.nmemFree(pointer);}return texture;
    }
    private static void draw(RenderEncoder encoder,IGpuTexture texture,long params,float depth,int fluid,int face) {
        MemoryUtil.memSet(params,0,16);MemoryUtil.memPutFloat(params,depth);MemoryUtil.memPutFloat(params+4,fluid);MemoryUtil.memPutFloat(params+8,face);
        encoder.setTexture(0,texture);encoder.setBytes(14,params,16);encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLES,0,3,1,0);
    }
}
