package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.model.bakery.MetalViewCapture;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Real bakery pixels, independent of model classification and shader heuristics. */
public final class BakeRegressionTest {
    private static final ArrayList<String> failures = new ArrayList<>();
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Case { void run() throws Exception; }
    private static void check(String label, Case test) {
        try { test.run(); System.out.println("PASS: " + label); }
        catch (Throwable error) { error.printStackTrace(); failures.add(label + ": " + error); System.out.println("FAIL: " + label + ": " + error); }
    }
    public static void main(String[] args) throws Exception {
        Logger.SHUTUP = true;
        require(glfwInit(), "GLFW");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE); glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(64, 64, "Metal bake regression", 0, 0);
        require(window != 0, "GL context");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        var backend = new MetalRenderBackend(); RenderBackendFactory.set(backend);
        try {
            check("cutout backgrounds stay transparent and cannot occlude terrain", () -> bake(true, false, false));
            check("untinted pixels are written without the biome tint bit", () -> bake(false, false, false));
            check("tinted pixels retain the quad tint flag", () -> bake(false, true, false));
            check("mixed tint survives on one face", () -> bake(false, false, true));
            check("water animation preserves alpha-zero pixels", () -> {
                var method = WaterAnimator.class.getDeclaredMethod("dilateTransparentRgb", int[].class);
                method.setAccessible(true);
                int[] pixels = new int[256]; pixels[0]=0x80102030; pixels[2]=0x40102030; method.invoke(null, (Object) pixels);
                require((pixels[1] >>> 24) == 0 && (pixels[3] >>> 24) == 0, "animation invents coverage");
                require(pixels[0] == 0x80102030 && pixels[2] == 0x40102030, "written alpha changed");
            });
            check("existing bakery dilation copies nearest RGB without changing coverage", () -> {
                int[] cell = edgePixels();
                var faces = new ColourDepthTextureData[6];
                for (int face = 0; face < 6; face++) faces[face] = new ColourDepthTextureData(cell.clone(),new int[256],16,16);
                var packed = new me.cortex.voxy.common.util.MemoryBuffer(6L*340*4);
                try {
                    MipGen.putTextures(false,faces,packed);
                    require(MemoryUtil.memGetInt(packed.address+4)==0x000000ff,"nearest red edge lost");
                    require(MemoryUtil.memGetInt(packed.address+14*4)==0x00ff0000,"nearest blue edge lost");
                    require(MemoryUtil.memGetInt(packed.address)==0xff0000ff,"opaque source changed");
                } finally { packed.free(); }
            });
            check("animated water uses the same nearest RGB edge dilation", () -> {
                var method = WaterAnimator.class.getDeclaredMethod("dilateTransparentRgb",int[].class);
                method.setAccessible(true);
                int[] cell = edgePixels(); method.invoke(null,(Object)cell);
                require(cell[1]==0x000000ff && cell[14]==0x00ff0000,"animation averaged unrelated edges");
                require(cell[0]==0xff0000ff && cell[15]==0xffff0000,"animation changed source coverage");
            });
            check("tint mip packing has bounded 2048-byte model slots", () -> {
                var type = Class.forName("me.cortex.voxy.client.core.model.ModelTintData");
                var pack = type.getMethod("pack", ColourDepthTextureData[].class);
                var faces = new ColourDepthTextureData[6];
                for (int face=0;face<6;face++) {
                    int[] colors=new int[256], meta=new int[256];
                    java.util.Arrays.fill(colors, 0xffabcdef);
                    for(int i=0;i<256;i++) meta[i]=1 | ((i%16<8)?128:0);
                    faces[face]=new ColourDepthTextureData(colors,meta,16,16);
                }
                byte[] data=(byte[])pack.invoke(null,(Object)faces);
                require(data.length==2048,"slot size");
                for(int face=0;face<6;face++) {
                    int start=face*340;
                    require(Byte.toUnsignedInt(data[start+3])==255 && data[start+12]==0,"base tint pixels");
                    require(Byte.toUnsignedInt(data[start+256])==255 && data[start+256+7]==0,"8px mip");
                    require(Byte.toUnsignedInt(data[start+320])==255 && data[start+320+3]==0,"4px mip");
                    require(Byte.toUnsignedInt(data[start+336])==255 && data[start+337]==0,"2px mip");
                }
            });
            check("actual Metal shader samples tint weights at each mip", () -> {
                String helper=me.cortex.voxy.client.core.gl.shader.ShaderLoader.parse("voxy:lod/tint_weights.glsl");
                String shader=helper+"\nlayout(local_size_x=1) in;\n"+
                    "\nlayout(binding=0,std430) buffer Results { float values[]; };\nvoid main(){uint i=gl_GlobalInvocationID.x;values[i]=sampleModelTint(0u,0u,vec2((i%2u)==0u?0.125:0.875,0.5),float(i/2u));}";
                var faces=new ColourDepthTextureData[6];
                for(int f=0;f<6;f++){int[] c=new int[256],m=new int[256];java.util.Arrays.fill(c,-1);for(int i=0;i<256;i++)m[i]=1|(i%16<8?128:0);faces[f]=new ColourDepthTextureData(c,m,16,16);}
                byte[] data=ModelTintData.pack(faces);
                var input=backend.createBuffer(2048);var output=backend.createBuffer(32);
                try {
                    long ptr=me.cortex.voxy.client.core.rendering.util.UploadStream.INSTANCE.upload(input,0,2048);
                    for(int i=0;i<data.length;i++)MemoryUtil.memPutByte(ptr+i,data[i]);
                    me.cortex.voxy.client.core.rendering.util.UploadStream.INSTANCE.commit();
                    try(var pipeline=backend.createComputePipeline(new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(shader,null,null,null,1,1,1,"Tint mip pixels"))) {
                        try(var encoder=backend.beginComputePass()){encoder.setPipeline(pipeline);encoder.setBuffer(0,output,0);encoder.setBuffer(11,input,0);encoder.dispatch(8,1,1);}
                        backend.submit();long results=((me.cortex.voxy.client.core.metal.MetalBuffer)output).getContentsPtr();
                        for(int i=0;i<8;i++)require(Math.abs(MemoryUtil.memGetFloat(results+i*4L)-(i%2==0?1:0))<0.001,"wrong tint mip "+i);
                    }
                } finally { input.free();output.free(); }
            });
            check("tint-buffer growth preserves previous slots and frees ownership", () -> {
                var type=Class.forName("me.cortex.voxy.client.core.model.ModelTintBuffer");
                var owner=type.getConstructor(me.cortex.voxy.client.core.gpu.RenderBackend.class).newInstance(backend);
                var upload=type.getMethod("upload",int.class,byte[].class);
                var buffer=type.getMethod("buffer");
                try {
                    byte[] first=new byte[2048], next=new byte[2048];
                    java.util.Arrays.fill(first,(byte)5); java.util.Arrays.fill(next,(byte)9);
                    upload.invoke(owner,0,first); upload.invoke(owner,513,next); backend.submit();
                    var gpu=(me.cortex.voxy.client.core.metal.MetalBuffer)buffer.invoke(owner);
                    require(gpu.size()==1024L*2048,"unbounded growth");
                    require(MemoryUtil.memGetByte(gpu.getContentsPtr())==5,"old tint lost on growth");
                    require(MemoryUtil.memGetByte(gpu.getContentsPtr()+513L*2048)==9,"new tint lost");
                    for(int id:new int[]{-1,65536}) {
                        try { upload.invoke(owner,id,first); throw new AssertionError("invalid model id accepted"); }
                        catch(java.lang.reflect.InvocationTargetException error) {require(error.getCause() instanceof IllegalArgumentException,"wrong bound failure");}
                    }
                } finally { type.getMethod("close").invoke(owner); }
                try { buffer.invoke(owner); throw new AssertionError("freed owner still usable"); }
                catch(java.lang.reflect.InvocationTargetException error) { require(error.getCause() instanceof IllegalStateException,"closed guard"); }
            });
            check("pipeline teardown releases the real material uniform buffer",()->{
                var data=org.mockito.Mockito.mock(me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData.class);
                org.mockito.Mockito.when(data.getUniforms()).thenReturn(new me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData.StructLayout(16,"{ int frame; }",ptr->{}));
                Class<?> uniforms=Class.forName("me.cortex.voxy.client.core.MetalMaterialUniforms");
                var ctor=uniforms.getDeclaredConstructors()[0];ctor.setAccessible(true);
                Object owner=ctor.newInstance(data,backend);
                var owned=uniforms.getDeclaredField("buffer");owned.setAccessible(true);
                var resource=(me.cortex.voxy.client.core.metal.MetalBuffer)owned.get(owner);
                var pipeline=org.mockito.Mockito.mock(me.cortex.voxy.client.core.MetalVxRenderPipeline.class,org.mockito.Mockito.CALLS_REAL_METHODS);
                set(pipeline,me.cortex.voxy.client.core.MetalVxRenderPipeline.class,"uniforms",owner);
                set(pipeline,me.cortex.voxy.client.core.NormalRenderPipeline.class,"finalBlit",org.mockito.Mockito.mock(me.cortex.voxy.client.core.rendering.post.FullscreenBlit.class));
                set(pipeline,me.cortex.voxy.client.core.NormalRenderPipeline.class,"ssaoCompute",org.mockito.Mockito.mock(me.cortex.voxy.client.core.gl.shader.Shader.class));
                set(pipeline,me.cortex.voxy.client.core.NormalRenderPipeline.class,"fbSSAO",org.mockito.Mockito.mock(me.cortex.voxy.client.core.gpu.IGpuFramebuffer.class));
                Class<?> base=me.cortex.voxy.client.core.AbstractRenderPipeline.class;
                set(pipeline,base,"fb",org.mockito.Mockito.mock(me.cortex.voxy.client.core.rendering.util.DepthFramebuffer.class));
                set(pipeline,base,"sectionRenderer",org.mockito.Mockito.mock(me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer.class));
                for(String field:new String[]{"depthMaskBlit","depthSetBlit","depthCopy"})set(pipeline,base,field,org.mockito.Mockito.mock(me.cortex.voxy.client.core.rendering.post.FullscreenBlit.class));
                set(pipeline,base,"metalFrameRenderer",org.mockito.Mockito.mock(Class.forName("me.cortex.voxy.client.core.MetalFrameRenderer")));
                set(pipeline,me.cortex.voxy.common.util.TrackedObject.class,"ref",new me.cortex.voxy.common.util.TrackedObject.Ref(null,new boolean[1]));
                try {pipeline.free();require(resource.isFreed(),"material uniform buffer leaked on pipeline.free()");}
                finally {if(!resource.isFreed())resource.free();}
            });
            check("model-store constructor failure releases all allocated Metal buffers", () -> {
                var owned = new ArrayList<me.cortex.voxy.client.core.metal.MetalBuffer>();
                var failing = org.mockito.Mockito.mock(me.cortex.voxy.client.core.gpu.RenderBackend.class);
                org.mockito.Mockito.when(failing.getType()).thenReturn(me.cortex.voxy.client.core.gpu.BackendType.METAL);
                org.mockito.Mockito.when(failing.createBuffer(org.mockito.Mockito.anyLong())).thenAnswer(call -> {
                    var resource = (me.cortex.voxy.client.core.metal.MetalBuffer)backend.createBuffer(call.getArgument(0));
                    owned.add(resource); return resource;
                });
                var failure = new IllegalStateException("atlas allocation fixture");
                org.mockito.Mockito.when(failing.createTexture()).thenThrow(failure);
                RenderBackendFactory.set(failing);
                try {
                    try { new ModelStore(); throw new AssertionError("allocation failure was swallowed"); }
                    catch (IllegalStateException error) { require(error == failure,"wrong allocation failure"); }
                    require(owned.size()==3,"fixture did not allocate tint/model/color buffers");
                    require(owned.stream().allMatch(me.cortex.voxy.client.core.metal.MetalBuffer::isFreed),"constructor leaked a native buffer");
                } finally {
                    RenderBackendFactory.set(backend);
                    for (var resource : owned) if (!resource.isFreed()) resource.free();
                }
            });
        } finally { backend.shutdown(); glfwDestroyWindow(window); glfwTerminate(); RenderBackendFactory.set(null); }
        if(!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }
    private static void set(Object target,Class<?> type,String name,Object value)throws Exception {
        var field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static int[] edgePixels() {
        int[] cell = new int[256];
        for (int y = 0; y < 16; y++) { cell[y*16]=0xff0000ff; cell[y*16+15]=0xffff0000; }
        return cell;
    }
    private static void bake(boolean cutout, boolean tinted, boolean mixed) {
        int tex=glGenTextures(); long image=MemoryUtil.nmemAllocChecked(16*16*4), mesh=MemoryUtil.nmemAllocChecked(192), out=MemoryUtil.nmemAllocChecked(6*256*8);
        MetalViewCapture capture=null;
        try {
            for(int y=0;y<16;y++) for(int x=0;x<16;x++) MemoryUtil.memPutInt(image+(y*16+x)*4L,cutout&&x>=8?0:0xff302010);
            glBindTexture(GL_TEXTURE_2D,tex); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            nglTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,16,16,0,GL_RGBA,GL_UNSIGNED_BYTE,image);
            quad(mesh,-1,mixed?0:1,1|(tinted?4:0)); if(mixed) quad(mesh+96,0,1,5);
            capture=new MetalViewCapture(16,16); capture.beginBake(tex,mesh,mixed?2:1,true);
            for(int face=0;face<6;face++) capture.renderFace(face%3,face/3,new Matrix4f());
            capture.endBake(); capture.emitToStream(out);
            for(int face=0;face<6;face++) {
                long left=out+(face*256+8*16+3)*8L,right=out+(face*256+8*16+12)*8L;
                require(MemoryUtil.memGetInt(left)==0xff302010,"face ordering/color "+face);
                require((MemoryUtil.memGetInt(left+4)&255)!=0,"written marker missing "+face);
                if(cutout) { require((MemoryUtil.memGetInt(right)>>>24)==0,"opaque cutout background "+face); require(MemoryUtil.memGetInt(right+4)==0,"unwritten cutout metadata"); }
                require(((MemoryUtil.memGetInt(left+4)&128)!=0)==tinted,"wrong tint "+face);
                if(mixed) require((MemoryUtil.memGetInt(right+4)&128)!=0,"partial tint lost");
            }
            if (cutout) checkCutoutMips(out);
        } finally { if(capture!=null) capture.free(); glDeleteTextures(tex); MemoryUtil.nmemFree(image);MemoryUtil.nmemFree(mesh);MemoryUtil.nmemFree(out); }
    }
    private static void checkCutoutMips(long baked) {
        var faces = new ColourDepthTextureData[6];
        for (int face = 0; face < 6; face++) {
            int[] colors = new int[256], metadata = new int[256];
            for (int i = 0; i < 256; i++) {
                long p = baked + (face * 256L + i) * 8;
                colors[i] = MemoryUtil.memGetInt(p); metadata[i] = MemoryUtil.memGetInt(p + 4);
            }
            faces[face] = new ColourDepthTextureData(colors,metadata,16,16);
        }
        var pixels = new me.cortex.voxy.common.util.MemoryBuffer(6L*340*4);
        long readback = MemoryUtil.nmemAllocChecked(48*32*4);
        var texture = (me.cortex.voxy.client.core.metal.MetalTexture)RenderBackendFactory.get().createTexture(GL_TEXTURE_2D);
        try {
            MipGen.putTextures(false,faces,pixels);
            texture.storeUploadable(GL_RGBA8,4,48,32);
            long offset = 0;
            for (int level = 0; level < 4; level++) {
                int width = 48 >> level, height = 32 >> level, size = 16 >> level;
                texture.uploadSubImage2D(level,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels.address+offset);
                texture.getBytes(level,0,0,width,height,readback);
                require((MemoryUtil.memGetInt(readback+(size/2L*width+size*3/4)*4)>>>24)==0,"cutout background became opaque at mip "+level);
                require((MemoryUtil.memGetInt(readback+(size/2L*width+size/4)*4)>>>24)==255,"covered cutout pixel lost at mip "+level);
                offset += (long)width*height*4;
            }
        } finally { texture.free(); pixels.free(); MemoryUtil.nmemFree(readback); }
    }
    private static void quad(long ptr,float left,float right,int flags) {
        float[][] v={{left,-1,0,0},{right,-1,1,0},{right,1,1,1},{left,1,0,1}};
        for(int i=0;i<4;i++) {long p=ptr+i*24L;MemoryUtil.memPutFloat(p,v[i][0]);MemoryUtil.memPutFloat(p+4,v[i][1]);MemoryUtil.memPutFloat(p+8,0.5f);MemoryUtil.memPutInt(p+12,flags);MemoryUtil.memPutFloat(p+16,v[i][2]);MemoryUtil.memPutFloat(p+20,v[i][3]);}
    }
}
