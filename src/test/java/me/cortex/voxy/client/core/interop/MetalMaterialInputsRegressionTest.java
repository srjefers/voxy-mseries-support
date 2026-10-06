package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.*;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration;
import me.cortex.voxy.client.core.rendering.util.NativeUniformWriter;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.util.ResourceScope;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;
import static org.mockito.Mockito.*;

/** Reads actual production vertex/fragment outputs for all cube normals, light channels and tint states. */
public final class MetalMaterialInputsRegressionTest {
    private static final int SIZE=8, CUSTOM_ID=17154;
    private static MetalBuffer buffer(ResourceScope scope, MetalRenderBackend backend, int bytes) {
        var result=scope.own((MetalBuffer)backend.createBuffer(bytes),MetalBuffer::free);
        MemoryUtil.memSet(result.getContentsPtr(),0,bytes);return result;
    }
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP=true;
        try(var context=new TestGlContext();var minecraft=mockStatic(Minecraft.class);var resources=new ResourceScope()) {
            var client=mock(Minecraft.class);client.level=mock(net.minecraft.client.multiplayer.ClientLevel.class);
            when(client.level.getShade(any(),anyBoolean())).thenAnswer(call -> {
                if(!(boolean)call.getArgument(1))return 1f;
                return switch((net.minecraft.core.Direction)call.getArgument(0)) {
                    case UP -> 1f; case DOWN -> .5f; case NORTH,SOUTH -> .8f; case EAST,WEST -> .6f;
                };
            });
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            var backend=resources.own(new MetalRenderBackend(),MetalRenderBackend::shutdown);
            var scene=buffer(resources,backend,1024);var quad=buffer(resources,backend,8);
            var model=buffer(resources,backend,64);var color=buffer(resources,backend,64);
            var position=buffer(resources,backend,8);var perDraw=buffer(resources,backend,16);
            var bounds=buffer(resources,backend,16+SIZE*SIZE*4);var tint=buffer(resources,backend,2048);
            var indices=buffer(resources,backend,12);var miscRead=buffer(resources,backend,SIZE*SIZE*4);
            var tintRead=buffer(resources,backend,SIZE*SIZE*4);var albedoRead=buffer(resources,backend,SIZE*SIZE*4);
            var productionIndices=SharedIndexBuffer.generateQuadIndicesShort(1);
            try {
                MemoryUtil.memCopy(productionIndices.address,indices.getContentsPtr(),12);
            } finally {productionIndices.free();}
            MemoryUtil.memPutInt(bounds.getContentsPtr(),SIZE);
            MemoryUtil.memPutInt(model.getContentsPtr()+28,0xffb06020);
            MemoryUtil.memPutInt(model.getContentsPtr()+32,CUSTOM_ID);
            var atlas=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
            atlas.storeUploadable(GL_RGBA8,1,1,1);
            long pixel=MemoryUtil.nmemAllocChecked(4);
            try {MemoryUtil.memPutInt(pixel,0xffffffff);atlas.uploadSubImage2D(0,0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);}
            finally {MemoryUtil.nmemFree(pixel);}
            var sampler=resources.own(backend.createSampler(SamplerDesc.builder().build()),IGpuSampler::close);
            var albedo=resources.own(backend.createTexture().store(GL_RGBA8,1,SIZE,SIZE),IGpuTexture::free);
            var tintTarget=resources.own(backend.createTexture().store(GL_RGBA8,1,SIZE,SIZE),IGpuTexture::free);
            var misc=resources.own(backend.createTexture().store(GL_RGBA8,1,SIZE,SIZE),IGpuTexture::free);
            var depth=resources.own(backend.createTexture().store(GL_DEPTH_COMPONENT32F,1,SIZE,SIZE),IGpuTexture::free);
            var policy=mock(AbstractRenderPipeline.class);
            when(policy.materialPolicy()).thenReturn(MetalMaterialPolicy.CONTRACT);
            when(policy.vxMaterialMode()).thenReturn(true);when(policy.vxOpaqueMaterialMode()).thenReturn(true);
            var descriptor=TerrainShaderConfiguration.load(policy,BackendType.METAL).opaque();
            try(var pipeline=backend.createGraphicsPipeline(descriptor)) {
                int checks=0;
                for(int face=0;face<6;face++) {
                    float sign=(face&1)==0?-1:1;
                    var eye=switch(face>>1) {case 0->new Vector3f(.5f,sign*2,.5f);case 1->new Vector3f(.5f,.5f,sign*2);default->new Vector3f(sign*2,.5f,.5f);};
                    var up=(face>>1)==0?new Vector3f(0,0,-1):new Vector3f(0,1,0);
                    var matrix=new Matrix4f().ortho(-.75f,.75f,-.75f,.75f,.1f,10f,true).lookAt(eye,new Vector3f(.5f),up);
                    NativeUniformWriter.putMatrix4f(scene.getContentsPtr(),matrix);
                    NativeUniformWriter.putVector3f(scene.getContentsPtr()+80,eye);
                    for(int tinted=0;tinted<2;tinted++) {
                        for(int f=0;f<6;f++)MemoryUtil.memPutInt(model.getContentsPtr()+f*4,0xf0f0|(tinted*2<<24));
                        // Exhaust every packed light level. Expected values are the original independent nibbles.
                        for(int light=0;light<256;light++) {
                            MemoryUtil.memPutLong(quad.getContentsPtr(),face|((long)light<<55));
                            var pass=RenderPassDesc.builder(SIZE,SIZE).clearColor(albedo,0,0,0,0)
                                    .clearColor(tintTarget,0,0,0,0).clearColor(misc,0,0,0,0).clearDepth(depth,1).build();
                            try(var encoder=backend.beginRenderPass(pass)) {
                                encoder.setPipeline(pipeline);encoder.setViewport(0,0,SIZE,SIZE,0,1);
                                encoder.setBuffer(0,scene,0);encoder.setBuffer(1,quad,0);encoder.setBuffer(3,model,0);
                                encoder.setBuffer(4,color,0);encoder.setBuffer(5,position,0);encoder.setBuffer(6,perDraw,0);
                                encoder.setBuffer(9,bounds,0);encoder.setBuffer(11,tint,0);encoder.setTexture(0,atlas);encoder.setSampler(0,sampler);
                                encoder.bindIndexBuffer(indices,0,RenderEncoder.INDEX_TYPE_UINT16);
                                encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,6,1,0,0,0);
                            }
                            backend.copyTextureToBuffer(misc,miscRead,SIZE,SIZE);backend.copyTextureToBuffer(tintTarget,tintRead,SIZE,SIZE);
                            backend.copyTextureToBuffer(albedo,albedoRead,SIZE,SIZE);backend.submit();
                            int offset=(3*SIZE+4)*4;
                            int packed=MemoryUtil.memGetInt(miscRead.getContentsPtr()+offset);
                            int expected=(light&0xf0)|(face<<1)|((light&15)<<12)|(CUSTOM_ID<<16);
                            int tintPixel=MemoryUtil.memGetInt(tintRead.getContentsPtr()+offset);
                            int expectedTint=tinted==0?0xffffffff:0xff2060b0;
                            if(packed!=expected || tintPixel!=expectedTint || MemoryUtil.memGetInt(albedoRead.getContentsPtr()+offset)!=0xffffffff)
                                throw new AssertionError("production material input face="+face+" light="+light+" tint="+tinted+" misc="+Integer.toHexString(packed)+" expected="+Integer.toHexString(expected)+" tintPixel="+Integer.toHexString(tintPixel));
                            checks++;
                        }
                    }
                    System.out.println("PASS: actual cube face="+face+" preserves normal, all 256 block/sky combinations, albedo and tinted/untinted inputs");
                }
                System.out.println("PASS: "+checks+" production material-input pixels");
            }
            // Flat unoccluded faces should preserve Minecraft's lightmap and directional shade.
            when(policy.materialPolicy()).thenReturn(MetalMaterialPolicy.SHADERS_OFF);
            when(policy.vxMaterialMode()).thenReturn(false);when(policy.vxOpaqueMaterialMode()).thenReturn(false);
            when(policy.useEnvFog()).thenReturn(true); // Fog alpha remains zero: no environmental attenuation.
            var lighting=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
            lighting.storeUploadable(GL_RGBA8,1,1,1);
            MemoryUtil.memPutInt(model.getContentsPtr()+24,8); // vanilla face-shaded opaque model
            MemoryUtil.memPutInt(model.getContentsPtr()+28,0xffffffff);
            for(int face=0;face<6;face++)MemoryUtil.memPutInt(model.getContentsPtr()+face*4,0xf0f0);
            var prelit=TerrainShaderConfiguration.load(policy,BackendType.METAL).opaque();
            int errors=0;
            long texel=MemoryUtil.nmemAllocChecked(4);
            try(var pipeline=backend.createGraphicsPipeline(prelit)) {
                for(int face=0;face<6;face++) {
                    float sign=(face&1)==0?-1:1;
                    var eye=switch(face>>1) {case 0->new Vector3f(.5f,sign*2,.5f);case 1->new Vector3f(.5f,.5f,sign*2);default->new Vector3f(sign*2,.5f,.5f);};
                    var up=(face>>1)==0?new Vector3f(0,0,-1):new Vector3f(0,1,0);
                    NativeUniformWriter.putMatrix4f(scene.getContentsPtr(),new Matrix4f().ortho(-.75f,.75f,-.75f,.75f,.1f,10f,true).lookAt(eye,new Vector3f(.5f),up));
                    NativeUniformWriter.putVector3f(scene.getContentsPtr()+80,eye);
                    MemoryUtil.memPutLong(quad.getContentsPtr(),face|(255L<<55));
                    float shade=switch(face) {case 0->.5f;case 1->1f;case 2,3->.8f;default->.6f;};
                    for(int rgb:new int[]{0x00ffffff,0x00a08060,0x00203040,0x00000000}) {
                        MemoryUtil.memPutInt(texel,0xff000000|rgb);
                        lighting.uploadSubImage2D(0,0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,texel);
                        var pass=RenderPassDesc.builder(SIZE,SIZE).clearColor(albedo,0,0,0,0).clearDepth(depth,1).build();
                        try(var encoder=backend.beginRenderPass(pass)) {
                            encoder.setPipeline(pipeline);encoder.setViewport(0,0,SIZE,SIZE,0,1);
                            encoder.setBuffer(0,scene,0);encoder.setBuffer(1,quad,0);encoder.setBuffer(3,model,0);
                            encoder.setBuffer(4,color,0);encoder.setBuffer(5,position,0);encoder.setBuffer(6,perDraw,0);
                            encoder.setBuffer(9,bounds,0);encoder.setBuffer(11,tint,0);
                            encoder.setTexture(0,atlas);encoder.setSampler(0,sampler);encoder.setTexture(1,lighting);encoder.setSampler(1,sampler);
                            encoder.bindIndexBuffer(indices,0,RenderEncoder.INDEX_TYPE_UINT16);
                            encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,6,1,0,0,0);
                        }
                        backend.copyTextureToBuffer(albedo,albedoRead,SIZE,SIZE);backend.submit();
                        long result=albedoRead.getContentsPtr()+(3*SIZE+4)*4;
                        for(int channel=0;channel<3;channel++) {
                            int expected=(int)(((rgb>>(channel*8))&255)/255f*shade*255f);
                            int actual=MemoryUtil.memGetByte(result+channel)&255;
                            if(Math.abs(expected-actual)>1) {
                                errors++;System.err.println("FAIL: flat vanilla shade face="+face+" rgb="+Integer.toHexString(rgb)+" channel="+channel+" actual="+actual+" expected="+expected);
                            }
                        }
                    }
                }
                // Sodium's compressed light coordinates are (n*16+8)/256. A real 16x16
                // gradient catches interpolation errors hidden by the constant-color fixtures.
                var gradient=resources.own((MetalTexture)backend.createTexture(GL_TEXTURE_2D),MetalTexture::free);
                gradient.storeUploadable(GL_RGBA8,1,16,16);
                var linear=resources.own(backend.createSampler(SamplerDesc.builder()
                        .filter(SamplerDesc.Filter.LINEAR,SamplerDesc.Filter.LINEAR).build()),IGpuSampler::close);
                long grid=MemoryUtil.nmemAllocChecked(16*16*4);
                try {
                    for(int sky=0;sky<16;sky++)for(int block=0;block<16;block++)
                        MemoryUtil.memPutInt(grid+4L*(sky*16+block),0xff000000|(sky*17<<8)|(block*17));
                    gradient.uploadSubImage2D(0,0,0,16,16,GL_RGBA,GL_UNSIGNED_BYTE,grid);
                } finally {MemoryUtil.nmemFree(grid);}
                var eye=new Vector3f(.5f,2,.5f);
                NativeUniformWriter.putMatrix4f(scene.getContentsPtr(),new Matrix4f().ortho(-.75f,.75f,-.75f,.75f,.1f,10f,true)
                        .lookAt(eye,new Vector3f(.5f),new Vector3f(0,0,-1)));
                NativeUniformWriter.putVector3f(scene.getContentsPtr()+80,eye);
                int coordinateErrors=0;
                for(int light=0;light<256;light++) {
                    MemoryUtil.memPutLong(quad.getContentsPtr(),1|(long)light<<55);
                    try(var encoder=backend.beginRenderPass(RenderPassDesc.builder(SIZE,SIZE)
                            .clearColor(albedo,0,0,0,0).clearDepth(depth,1).build())) {
                        encoder.setPipeline(pipeline);encoder.setViewport(0,0,SIZE,SIZE,0,1);
                        encoder.setBuffer(0,scene,0);encoder.setBuffer(1,quad,0);encoder.setBuffer(3,model,0);
                        encoder.setBuffer(4,color,0);encoder.setBuffer(5,position,0);encoder.setBuffer(6,perDraw,0);
                        encoder.setBuffer(9,bounds,0);encoder.setBuffer(11,tint,0);
                        encoder.setTexture(0,atlas);encoder.setSampler(0,sampler);
                        encoder.setTexture(1,gradient);encoder.setSampler(1,linear);
                        encoder.bindIndexBuffer(indices,0,RenderEncoder.INDEX_TYPE_UINT16);
                        encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,6,1,0,0,0);
                    }
                    backend.copyTextureToBuffer(albedo,albedoRead,SIZE,SIZE);backend.submit();
                    long result=albedoRead.getContentsPtr()+(3*SIZE+4)*4;
                    for(int channel=0;channel<2;channel++) {
                        int expected=(channel==0?light>>4:light&15)*17;
                        int actual=MemoryUtil.memGetByte(result+channel)&255;
                        if(Math.abs(actual-expected)>1) {
                            coordinateErrors++;if(coordinateErrors<5)System.err.println("FAIL: lightmap texel light="+light+" channel="+channel+" actual="+actual+" expected="+expected);
                        }
                    }
                }
                if(coordinateErrors>0)throw new AssertionError(coordinateErrors+" lightmap texel-center mismatches");
                System.out.println("PASS: every block/sky light pair samples the same 16x16 texel center as Sodium");
            } finally {MemoryUtil.nmemFree(texel);}
            if(errors>0)throw new AssertionError(errors+" shaders-off lightmap/directional shade mismatches");
            System.out.println("PASS: 24 shaders-off flat-face fixtures preserve day/night lightmap colors and vanilla directional shade without global darkening");
        }
    }
}
