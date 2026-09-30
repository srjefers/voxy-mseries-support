package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.core.rendering.util.NativeUniformWriter;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.util.*;
import static org.lwjgl.opengl.GL41C.*;

/** Actual Complementary program, real material bytes, independently constructed horizontal water/seafloor planes. */
final class ComplementaryWaterPixels {
    private static final int SIZE=16;
    private static final Matrix4f PROJECTION=new Matrix4f().perspective((float)Math.toRadians(70),1,16,1024);
    private static final Matrix4f VIEW=new Matrix4f().rotateX((float)Math.toRadians(45));
    static void run(IrisVoxyRenderPipelineData data) throws Exception {
        int program=VxIrisSideChannel.compile(MetalVxResolvePass.RESOLVE_VERT,MetalVxResolvePass.assembleFragment(data,true),"water pixel fixture");
        if(program==0) throw new AssertionError("Water fixture compilation failed");
        int vao=glGenVertexArrays(),ubo=glGenBuffers(),fbo=glGenFramebuffers();
        var textures=new ArrayList<Integer>();
        long pointer=MemoryUtil.nmemCalloc(1,data.getUniforms().size());
        try {
            data.getUniforms().updater().accept(pointer);
            matrix(program,pointer,"vxProj",PROJECTION);matrix(program,pointer,"vxProjInv",new Matrix4f(PROJECTION).invert());
            matrix(program,pointer,"vxProjPrev",PROJECTION);matrix(program,pointer,"vxModelView",VIEW);
            matrix(program,pointer,"vxModelViewInv",new Matrix4f(VIEW).invert());matrix(program,pointer,"vxModelViewPrev",VIEW);
            write(program,pointer,"viewWidth",SIZE);write(program,pointer,"viewHeight",SIZE);
            write(program,pointer,"cameraPosition",0,80,0);write(program,pointer,"previousCameraPosition",0,80,0);
            write(program,pointer,"cameraPositionInt",0,80,0);write(program,pointer,"cameraPositionFract",0,0,0);
            write(program,pointer,"previousCameraPositionFract",0,0,0);write(program,pointer,"sunAngle",.25f);
            write(program,pointer,"timeAngle",.25f);write(program,pointer,"eyeBrightness",240,240);
            for(String name:List.of("frameCounter","frameTimeCounter","framemod8","rainFactor","rainStrength",
                    "isEyeInWater","nightVision","blindness","darknessFactor","maxBlindnessDarkness","endFlashIntensityM"))
                write(program,pointer,name,0);
            glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferData(GL_UNIFORM_BUFFER,MemoryUtil.memByteBuffer(pointer,data.getUniforms().size()),GL_DYNAMIC_DRAW);
            glUniformBlockBinding(program,glGetUniformBlockIndex(program,"ShaderUniformBindings"),5);glBindBufferBase(GL_UNIFORM_BUFFER,5,ubo);
            glUseProgram(program);glBindVertexArray(vao);
            int output=texture(GL_TEXTURE_2D,filled(.1f,.2f,.3f,1),textures);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Water fixture FBO");
            glViewport(0,0,SIZE,SIZE);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);
            int[] material={texture(GL_TEXTURE_RECTANGLE,filled(.5f,.5f,.5f,.7f),textures),
                    texture(GL_TEXTURE_RECTANGLE,filled(.2392157f,.34117648f,.83921576f,1),textures),
                    texture(GL_TEXTURE_RECTANGLE,filled(2f/255,240f/255,0,125f/255),textures),
                    texture(GL_TEXTURE_RECTANGLE,waterDepth(true,80),textures)};
            String[] names={"uVxAlbedo","uVxTint","uVxMisc","uVxDepth"};
            for(int i=0;i<4;i++) {glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_RECTANGLE,material[i]);glBindSampler(i,0);glUniform1i(glGetUniformLocation(program,names[i]),i);}
            glUniform1i(glGetUniformLocation(program,"uVxDepthIsWindow"),1);
            int opaque=texture(GL_TEXTURE_2D,waterDepth(false,81),textures);
            var inputs=new LinkedHashMap<String,Integer>();
            int unit=6;
            for(String name:data.getImageSet().orderedNames()) {
                glActiveTexture(GL_TEXTURE0+unit);
                int texture;
                if(name.equals("vxDepthTexOpaque")) texture=opaque;
                else if(name.startsWith("shadowtex")) {
                    texture=glGenTextures();textures.add(texture);glBindTexture(GL_TEXTURE_2D,texture);
                    float[] pixels=new float[SIZE*SIZE];Arrays.fill(pixels,1);
                    glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,SIZE,SIZE,0,GL_DEPTH_COMPONENT,GL_FLOAT,pixels);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE);
                } else texture=texture(GL_TEXTURE_2D,name.startsWith("depth") || name.startsWith("vxDepth")?waterDepth(false,80):filled(.5f,.5f,.5f,1),textures);
                glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(unit,0);
                inputs.put(name,texture);
                glUniform1i(glGetUniformLocation(program,name),unit++);
            }
            var pipeline=org.mockito.Mockito.mock(net.irisshaders.iris.pipeline.IrisRenderingPipeline.class);
            var patch=org.mockito.Mockito.mock(me.cortex.voxy.client.iris.IrisShaderPatch.class);
            var declarations=new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<String,String>();
            inputs.keySet().forEach(name->declarations.put(name,data.samplerDecls.get(name)));
            org.mockito.Mockito.when(patch.getSamplerSet()).thenReturn(declarations);
            org.mockito.Mockito.doAnswer(call->{
                var holder=(net.irisshaders.iris.gl.sampler.SamplerHolder)call.getArgument(0);
                for(var entry:inputs.entrySet()) holder.addDynamicSampler(net.irisshaders.iris.gl.texture.TextureType.TEXTURE_2D,
                        entry::getValue,null,(java.util.function.Supplier<net.irisshaders.iris.gl.sampler.GlSampler>)null,entry.getKey());
                return null;
            }).when(pipeline).addGbufferOrShadowSamplers(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.eq(true),
                    org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.eq(false));
            var factory=IrisVoxyRenderPipelineData.class.getDeclaredMethod("createImageSet",net.irisshaders.iris.pipeline.IrisRenderingPipeline.class,me.cortex.voxy.client.iris.IrisShaderPatch.class);
            factory.setAccessible(true);
            var images=(IrisVoxyRenderPipelineData.ImageSet)factory.invoke(null,pipeline,patch);
            images.bindingFunction().accept(6);
            int inputUnit=6;
            for(var entry:inputs.entrySet()) {
                glActiveTexture(GL_TEXTURE0+inputUnit++);
                if(glGetInteger(GL_TEXTURE_BINDING_2D)!=entry.getValue()) throw new AssertionError("Water fixture input binding: "+entry.getKey());
            }
            float[] shallow=draw();
            glActiveTexture(GL_TEXTURE0+6+data.getImageSet().orderedNames().indexOf("vxDepthTexOpaque"));
            glBindTexture(GL_TEXTURE_2D,opaque);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL_RGBA,GL_FLOAT,waterDepth(false,160));
            float[] deep=draw();
            if(!(shallow[3]>.1f && shallow[3]<1 && deep[3]>shallow[3]+.1f && deep[3]<=1.001f))
                throw new AssertionError("Known water/seafloor depths produced wrong alpha: shallow="+Arrays.toString(shallow)+" deep="+Arrays.toString(deep));
            // A distant corner is unrelated to this water pixel's seafloor; changing it cannot change fog opacity.
            float[] unrelatedCorner=Arrays.copyOf(waterDepth(false,81),4);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RGBA,GL_FLOAT,unrelatedCorner);
            float[] cornerChanged=draw();
            if(Math.abs(deep[3]-cornerChanged[3])>.0001f)
                throw new AssertionError("Water at center sampled unrelated corner depth: deep="+Arrays.toString(deep)+" cornerChanged="+Arrays.toString(cornerChanged));
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RGBA,GL_FLOAT,Arrays.copyOf(waterDepth(false,160),4));
            System.out.println("PASS: actual Complementary water fog uses the current pixel's seafloor, independent of unrelated corner depth");
            int stale=glGenSamplers();
            try {
                glSamplerParameteri(stale,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
                glSamplerParameteri(stale,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE);
                for(int frame=0;frame<8;frame++) {
                    for(int i=0;i<inputs.size();i++) glBindSampler(6+i,(frame&1)==0?stale:0);
                    images.bindingFunction().accept(6);
                    if(!Arrays.equals(deep,draw())) throw new AssertionError("Caller sampler state changed actual water pixels");
                }
            } finally {for(int i=0;i<inputs.size();i++)glBindSampler(6+i,0);glDeleteSamplers(stale);}
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("Water fixture GL error");
            System.out.println("PASS: actual Complementary water pixels: finite output, shallow/deep alpha, stable repeated frames; shallow="+Arrays.toString(shallow)+" deep="+Arrays.toString(deep));
        } finally {
            glDeleteProgram(program);glDeleteBuffers(ubo);glDeleteVertexArrays(vao);glDeleteFramebuffers(fbo);
            for(int texture:textures) glDeleteTextures(texture);MemoryUtil.nmemFree(pointer);
            glActiveTexture(GL_TEXTURE0);glBindBufferBase(GL_UNIFORM_BUFFER,5,0);
        }
    }
    private static float[] draw() {
        glClearBufferfv(GL_COLOR,0,new float[4]);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        float[] pixel=new float[4];glReadPixels(SIZE/2,SIZE/2,1,1,GL_RGBA,GL_FLOAT,pixel);
        for(float value:pixel) if(!Float.isFinite(value)) throw new AssertionError("Nonfinite actual water output: "+Arrays.toString(pixel));
        return pixel;
    }
    private static float[] waterDepth(boolean packed,float height) {
        float[] data=new float[SIZE*SIZE*4];var inverse=new Matrix4f(PROJECTION).invert();var inverseView=new Matrix4f(VIEW).invert();
        for(int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
            // Native IOSurface material rows are top-down; the GL opaque-depth texture is bottom-up.
            int screenY=packed?SIZE-1-y:y;
            var ray=new Vector4f((x+.5f)/SIZE*2-1,(screenY+.5f)/SIZE*2-1,-1,1).mul(inverse);
            ray.div(ray.w);ray.w=0;ray.mul(inverseView);ray.mul(-height/ray.y);ray.w=1;ray.mul(VIEW).mul(PROJECTION);
            float depth=ray.z/ray.w*.5f+.5f;int i=(y*SIZE+x)*4;
            if(packed) {float a=depth-(float)Math.floor(depth),b=depth*255-(float)Math.floor(depth*255),c=depth*65025-(float)Math.floor(depth*65025);data[i]=a-b/255;data[i+1]=b-c/255;data[i+2]=c;}
            else data[i]=data[i+1]=data[i+2]=depth;
            data[i+3]=1;
        }
        return data;
    }
    private static float[] filled(float r,float g,float b,float a) {float[] result=new float[SIZE*SIZE*4];for(int i=0;i<result.length;i+=4){result[i]=r;result[i+1]=g;result[i+2]=b;result[i+3]=a;}return result;}
    private static int texture(int type,float[] pixels,List<Integer> textures) {
        int texture=glGenTextures();textures.add(texture);glBindTexture(type,texture);
        glTexImage2D(type,0,GL_RGBA32F,SIZE,SIZE,0,GL_RGBA,GL_FLOAT,pixels);
        glTexParameteri(type,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(type,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(type,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(type,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return texture;
    }
    private static long offset(int program,long pointer,String name) {int index=glGetUniformIndices(program,name);return index==GL_INVALID_INDEX?0:pointer+glGetActiveUniformsi(program,index,GL_UNIFORM_OFFSET);}
    private static void matrix(int program,long pointer,String name,Matrix4f matrix) {long address=offset(program,pointer,name);if(address!=0)NativeUniformWriter.putMatrix4f(address,matrix);}
    private static void write(int program,long pointer,String name,float... values) {
        int index=glGetUniformIndices(program,name);if(index==GL_INVALID_INDEX)return;
        long address=pointer+glGetActiveUniformsi(program,index,GL_UNIFORM_OFFSET);int type=glGetActiveUniformsi(program,index,GL_UNIFORM_TYPE);
        boolean integer=type==GL_INT || type==GL_INT_VEC2 || type==GL_INT_VEC3 || type==GL_INT_VEC4;
        for(int i=0;i<values.length;i++) if(integer)MemoryUtil.memPutInt(address+i*4L,(int)values[i]);else MemoryUtil.memPutFloat(address+i*4L,values[i]);
    }
}
