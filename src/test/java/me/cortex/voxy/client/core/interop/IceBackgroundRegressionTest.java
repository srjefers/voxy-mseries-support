package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.*;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration;
import me.cortex.voxy.common.util.ResourceScope;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import java.util.function.BiConsumer;
import static org.lwjgl.opengl.GL33C.*;
import static org.mockito.Mockito.*;

/** Numeric ice/water/background ownership, independent of submission order. No visible window. */
public final class IceBackgroundRegressionTest {
    private static final int SIZE=4, ICE=0xbeffb792, WATER=0xbfc04020;
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
        int errors=0;
        try(var context=new TestGlContext();var minecraft=mockStatic(Minecraft.class);var resources=new ResourceScope()) {
            var client=mock(Minecraft.class);client.level=mock(net.minecraft.client.multiplayer.ClientLevel.class);
            when(client.level.getShade(any(),anyBoolean())).thenReturn(1f);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            var backend=resources.own(new MetalRenderBackend(),MetalRenderBackend::shutdown);
            var depthRead=buffer(resources,backend,SIZE*SIZE*4);
            var scene=buffer(resources,backend,1024);var bounds=buffer(resources,backend,16+SIZE*SIZE*4);
            var tint=buffer(resources,backend,2048);var model=buffer(resources,backend,64);
            MemoryUtil.memPutInt(bounds.getContentsPtr(),SIZE);
            var sampler=resources.own(backend.createSampler(SamplerDesc.builder().build()),IGpuSampler::close);
            var ice=texture(resources,backend,ICE);var water=texture(resources,backend,WATER);
            var empty=texture(resources,backend,0);
            var destination=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
            destination.storeRenderTargetUploadable(GL_RGBA8,1,SIZE,SIZE);
            var depth=resources.own(backend.createTexture().store(GL_DEPTH_COMPONENT32F,1,SIZE,SIZE),IGpuTexture::free);
            var policy=mock(AbstractRenderPipeline.class);
            when(policy.materialPolicy()).thenReturn(MetalMaterialPolicy.SHADERS_OFF);when(policy.useEnvFog()).thenReturn(true);
            var config=TerrainShaderConfiguration.load(policy,BackendType.METAL);
            var production=config.translucent();
            var fixture=new GraphicsPipelineDesc(VERTEX,production.fragmentGlsl,production.defines,null,null,null,null,
                    production.colorAttachmentFormats,VertexLayout.EMPTY,production.state,"Ice background fixture");
            var fixtures=new TerrainShaderConfiguration(config.opaque(),fixture);
            var layers=resources.own(new MetalPrelitLayers(backend,SIZE,SIZE,fixtures),MetalPrelitLayers::close);
            long params=MemoryUtil.nmemAllocChecked(16),pixels=MemoryUtil.nmemAllocChecked(SIZE*SIZE*4);
            try {
                for(int face=0;face<6;face++)for(int scenario=0;scenario<10;scenario++)for(boolean reverse:new boolean[]{false,true}) {
                    int f=face,s=scenario;
                    boolean iceVisible=s!=3 && s!=4 && s!=8, waterVisible=s!=2 && s!=4 && s!=7;
                    float opaqueDepth=s==5?.2f:s==6?.5f:1f;
                    float iceDepth=s==1?.6f:.3f,waterDepth=s==1?.3f:.6f;
                    BiConsumer<RenderEncoder,IGpuPipeline> draw=(encoder,pipeline)->{
                        encoder.setPipeline(pipeline);encoder.setBuffer(0,scene,0);encoder.setBuffer(9,bounds,0);
                        encoder.setBuffer(11,tint,0);encoder.setBuffer(3,model,0);encoder.setSampler(0,sampler);
                        if(reverse)draw(encoder,waterVisible?water:empty,params,waterDepth,1,f);
                        draw(encoder,iceVisible?ice:empty,params,iceDepth,0,f);
                        // A farther back face of the same ice must not accumulate a second time.
                        if(s==9)draw(encoder,ice,params,.75f,0,f);
                        if(!reverse)draw(encoder,waterVisible?water:empty,params,waterDepth,1,f);
                    };
                    try(var clear=backend.beginRenderPass(RenderPassDesc.builder(SIZE,SIZE).clearColor(destination,.1f,.1f,.1f,1).clearDepth(depth,opaqueDepth).build())) { }
                    layers.render(destination,depth,draw);
                    backend.copyTextureToBuffer(depth,depthRead,SIZE,SIZE);
                    backend.submit();destination.getBytes(0,0,0,SIZE,SIZE,pixels);
                    float[] expected={.1f,.1f,.1f};
                    boolean hasIce=iceVisible && iceDepth<opaqueDepth,hasWater=waterVisible && waterDepth<opaqueDepth;
                    if(iceDepth<waterDepth) {if(hasWater)over(expected,WATER);if(hasIce)over(expected,ICE);}
                    else {if(hasIce)over(expected,ICE);if(hasWater)over(expected,WATER);}
                    float expectedDepth=Math.min(opaqueDepth,Math.min(hasIce?iceDepth:1,hasWater?waterDepth:1));
                    float actualDepth=MemoryUtil.memGetFloat(depthRead.getContentsPtr()+(2*SIZE+2)*4);
                    if(Math.abs(actualDepth-expectedDepth)>.00001f) {
                        errors++;if(errors<10)System.err.println("FAIL: combined nearest depth scenario="+s+" actual="+actualDepth+" expected="+expectedDepth);
                    }
                    long pixel=pixels+(2*SIZE+2)*4;
                    for(int c=0;c<3;c++) {
                        int actual=MemoryUtil.memGetByte(pixel+c)&255,want=Math.round(expected[c]*255);
                        if(Math.abs(actual-want)>2){errors++;if(errors<10)System.err.println("FAIL: ice background face="+face+" scenario="+s+" reverse="+reverse+" channel="+c+" actual="+actual+" ordered-blend="+want);}
                    }
                    if((MemoryUtil.memGetByte(pixel+3)&255)!=255)throw new AssertionError("opaque bridge alpha changed");
                }
            } finally {MemoryUtil.nmemFree(params);MemoryUtil.nmemFree(pixels);}
        }
        if(errors>0)throw new AssertionError(errors+" ice/water/background channel mismatches");
        System.out.println("PASS: 120 ice/water/background pixels, both depth orders and submission orders, empty layers, opaque occlusion, back faces and changing draw counts");
    }
    private static void over(float[] rgb,int rgba) {
        float a=(rgba>>>24)/255f;
        for(int c=0;c<3;c++)rgb[c]=((rgba>>(c*8))&255)/255f*a+rgb[c]*(1-a);
    }
    private static MetalBuffer buffer(ResourceScope scope,MetalRenderBackend backend,int bytes) {
        var b=scope.own((MetalBuffer)backend.createBuffer(bytes),MetalBuffer::free);MemoryUtil.memSet(b.getContentsPtr(),0,bytes);return b;
    }
    private static MetalTexture texture(ResourceScope scope,MetalRenderBackend backend,int rgba) {
        var t=scope.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);t.storeUploadable(GL_RGBA8,1,1,1);
        long p=MemoryUtil.nmemAllocChecked(4);
        try{MemoryUtil.memPutInt(p,rgba);t.uploadSubImage2D(0,0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,p);}finally{MemoryUtil.nmemFree(p);}return t;
    }
    private static void draw(RenderEncoder encoder,IGpuTexture texture,long params,float depth,int fluid,int face) {
        MemoryUtil.memSet(params,0,16);MemoryUtil.memPutFloat(params,depth);MemoryUtil.memPutFloat(params+4,fluid);MemoryUtil.memPutFloat(params+8,face);
        encoder.setTexture(0,texture);encoder.setBytes(14,params,16);encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLES,0,3,1,0);
    }
}
