package me.cortex.voxy.client.core.util;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.core.util.*;
import me.cortex.voxy.client.iris.*;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;
import static org.mockito.Mockito.*;

public final class MaterialContractRegressionTest {
    private static IrisShaderPatch complementary;
    private static IrisVoxyRenderPipelineData complementaryData;
    private static final Map<String,String> customTypes = new HashMap<>();
    private static IrisVoxyRenderPipelineData data(IrisShaderPatch patch) throws Exception {
        var ctor = IrisVoxyRenderPipelineData.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return (IrisVoxyRenderPipelineData)ctor.newInstance(patch, new int[]{0}, new int[]{0}, null,
                patch.createBlendSetup(), new IrisVoxyRenderPipelineData.ImageSet("", ignored -> {}, List.of()), null);
    }
    private static int plane(float r, float g, float b, float a) {
        int texture = glGenTextures(); glBindTexture(GL_TEXTURE_RECTANGLE, texture);
        glTexImage2D(GL_TEXTURE_RECTANGLE, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, new float[]{r,g,b,a});
        glTexParameteri(GL_TEXTURE_RECTANGLE,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE,GL_TEXTURE_MAG_FILTER,GL_NEAREST); return texture;
    }
    public static void main(String[] args) throws Exception {
        net.fabricmc.loader.impl.launch.FabricLauncherBase.setLauncher(mock(net.fabricmc.loader.impl.launch.FabricLauncher.class));
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var irisConfig = Arrays.stream(net.irisshaders.iris.Iris.class.getDeclaredFields())
                .filter(field -> field.getType() == net.irisshaders.iris.config.IrisConfig.class).findFirst().orElseThrow();
        irisConfig.setAccessible(true); irisConfig.set(null, mock(net.irisshaders.iris.config.IrisConfig.class));
        Path packPath = Path.of(System.getProperty("voxy.complementaryPack", "test-shaderpacks/ComplementaryUnbound_r5.9.3.zip"));
        try (var pack = FileSystems.newFileSystem(packPath)) {
            Path root = pack.getPath("/shaders");
            var typePattern = java.util.regex.Pattern.compile("uniform\\.(\\w+)\\.(\\w+)\\s*=");
            var typeMatcher = typePattern.matcher(Files.readString(root.resolve("shaders.properties")));
            while (typeMatcher.find()) customTypes.put(typeMatcher.group(2), typeMatcher.group(1));
            var directory = AbsolutePackPath.fromAbsolutePath("/world0");
            var graph = new IncludeGraph(root, ImmutableList.of(directory.resolve("voxy.json"),directory.resolve("voxy_opaque.glsl"),directory.resolve("voxy_translucent.glsl")), false);
            if (!graph.getFailures().isEmpty()) throw new AssertionError(graph.getFailures());
            var options = new ShaderPackOptions(graph, Map.of());
            var includes = new IncludeProcessor(options.getIncludes());
            var defines = List.of(new StringPair("MC_VERSION","12111"),new StringPair("VOXY","1"),new StringPair("IS_IRIS",""),new StringPair("MC_GL_VERSION","410"));
            java.util.function.Function<AbsolutePackPath,String> source = path -> {
                var lines = includes.getIncludedFile(path);
                return lines == null ? null : JcppProcessor.glslPreprocessSource(String.join("\n",lines),defines);
            };
            var patch = IrisShaderPatch.makePatch(mock(net.irisshaders.iris.shaderpack.ShaderPack.class),directory,source);
            if (!Arrays.equals(patch.getOpqaueTargets(),new int[]{0,6}) || !Arrays.equals(patch.getTranslucentTargets(),new int[]{0}) || patch.emitToVanillaDepth()) throw new AssertionError("Complementary contract targets/depth were changed");
            complementary = patch;
            Path out=Path.of("build/complementary-programs");Files.createDirectories(out);
            Files.writeString(out.resolve("opaque.glsl"),patch.getPatchOpaqueSource());Files.writeString(out.resolve("translucent.glsl"),patch.getPatchTranslucentSource());Files.writeString(out.resolve("taa.glsl"),patch.getTAAShift());
            System.out.println("PASS: actual Iris-preprocessed Complementary contract has targets [0,6]/[0] and excludes vanilla depth");
        }
        if (!glfwInit()) throw new AssertionError("GLFW init");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,1);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(16,16,"material contract",0,0);
        if(window==0) throw new AssertionError("GL context");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            compileComplementary();
            globalFragmentCoordinates();
            packSamplerOwnership();
            ComplementaryWaterPixels.run(complementaryData);
            var patch = mock(IrisShaderPatch.class);
            when(patch.getPatchOpaqueSource()).thenReturn("layout(location=0) out vec4 result; void voxy_emitFragment(VoxyFragmentParameters parameters){result=vec4(parameters.lightMap,0.0,1.0);}");
            when(patch.getSamplerSet()).thenReturn(new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>());
            when(patch.getOpqaueTargets()).thenReturn(new int[]{0}); when(patch.getTranslucentTargets()).thenReturn(new int[]{0});
            int program=VxIrisSideChannel.compile(MetalVxResolvePass.RESOLVE_VERT,MetalVxResolvePass.assembleFragment(data(patch),false),"generic light contract");
            if(program==0) throw new AssertionError("generic contract failed compilation");
            int[] planes={plane(1,1,1,1),plane(1,1,1,1),plane(0,0,0,0),plane(.5f,0,0,1)};
            int output=plane(0,0,0,0),fbo=glGenFramebuffers(),vao=glGenVertexArrays();
            try {
                glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,output,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);glViewport(0,0,1,1);glUseProgram(program);glBindVertexArray(vao);
                String[] names={"uVxAlbedo","uVxTint","uVxMisc","uVxDepth"};
                for(int i=0;i<4;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_RECTANGLE,planes[i]);glUniform1i(glGetUniformLocation(program,names[i]),i);}
                glUniform1i(glGetUniformLocation(program,"uVxDepthIsWindow"),1);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
                float[] pixel=new float[4];glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,pixel);
                if(Math.abs(pixel[0]-8f/256f)>0.0001f || Math.abs(pixel[1]-8f/256f)>0.0001f) throw new AssertionError("generic contract altered packed lighting: "+Arrays.toString(pixel));
                System.out.println("PASS: real GL material resolve preserves zero block/sky light without brightness overrides");
                var pipeline = mock(me.cortex.voxy.client.core.MetalVxRenderPipeline.class, CALLS_REAL_METHODS);
                var policy = me.cortex.voxy.client.core.MetalVxRenderPipeline.class.getDeclaredField("policy");
                policy.setAccessible(true);
                policy.set(pipeline, me.cortex.voxy.client.core.MetalMaterialPolicy.CONTRACT);
                if (!pipeline.vxOpaqueMaterialMode()) throw new AssertionError("generic Metal contract still uses the BSL split path");
                System.out.println("PASS: generic Metal contract selects both opaque and translucent material passes");
                var pipelineData = me.cortex.voxy.client.core.MetalVxRenderPipeline.class.getDeclaredField("pipelineData");
                pipelineData.setAccessible(true); pipelineData.set(pipeline, complementaryData);
                String taa = pipeline.taaFunction("getTAA");
                if (taa == null || !taa.contains(complementaryData.TAA) || !taa.contains("getTAA")) {
                    throw new AssertionError("Metal terrain and coverage lack the actual pack's TAA function");
                }
                System.out.println("PASS: Metal coverage and terrain receive the actual Complementary TAA function");
            } finally {glDeleteProgram(program);glDeleteTextures(planes);glDeleteTextures(output);glDeleteFramebuffers(fbo);glDeleteVertexArrays(vao);}
        } finally {glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void globalFragmentCoordinates() throws Exception {
        var patch=mock(IrisShaderPatch.class);
        when(patch.getPatchOpaqueSource()).thenReturn("layout(location=0) out vec4 result; vec2 savedCoordinate=gl_FragCoord.xy; void voxy_emitFragment(VoxyFragmentParameters parameters){result=vec4(savedCoordinate,gl_FragCoord.z,1);}");
        when(patch.getSamplerSet()).thenReturn(new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>());
        int program=VxIrisSideChannel.compile(MetalVxResolvePass.RESOLVE_VERT,MetalVxResolvePass.assembleFragment(data(patch),false),"global fragment-coordinate fixture");
        if(program==0)throw new AssertionError("Global-coordinate fixture compilation failed");
        int[] planes={plane(1,1,1,1),plane(1,1,1,1),plane(0,0,0,0),plane(.75f,0,0,1)};
        int output=plane(0,0,0,0),fbo=glGenFramebuffers(),vao=glGenVertexArrays();
        try(var state=new me.cortex.voxy.client.core.interop.GlInteropState(4)) {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,output,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);glViewport(0,0,1,1);
            glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);
            glUseProgram(program);glBindVertexArray(vao);
            String[] names={"uVxAlbedo","uVxTint","uVxMisc","uVxDepth"};
            for(int i=0;i<4;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_RECTANGLE,planes[i]);glBindSampler(i,0);glUniform1i(glGetUniformLocation(program,names[i]),i);}
            for(int convention:new int[]{0,1}) {
                glUniform1i(glGetUniformLocation(program,"uVxDepthIsWindow"),convention);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
                float[] pixel=new float[4];glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,pixel);
                float expectedDepth=convention==1?.75f:.875f;
                if(Math.abs(pixel[0]-.5f)>.005f || Math.abs(pixel[1]-.5f)>.005f || Math.abs(pixel[2]-expectedDepth)>.005f)
                    throw new AssertionError("Pack global initializer received wrong fragment coordinates: "+Arrays.toString(pixel)+" expected=.5,.5,"+expectedDepth);
            }
            System.out.println("PASS: pack global initializers receive actual pixel coordinates and decoded window depth in both conventions");
        } finally {glDeleteProgram(program);glDeleteTextures(planes);glDeleteTextures(output);glDeleteFramebuffers(fbo);glDeleteVertexArrays(vao);}
    }
    private static void packSamplerOwnership() throws Exception {
        var patch=mock(IrisShaderPatch.class);
        var samplers=new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<String,String>();
        samplers.put("colortex19","sampler2D");samplers.put("depthtex1","sampler2D");
        when(patch.getSamplerSet()).thenReturn(samplers);
        var pipeline=mock(net.irisshaders.iris.pipeline.IrisRenderingPipeline.class);
        doAnswer(call->{
            var holder=(net.irisshaders.iris.gl.sampler.SamplerHolder)call.getArgument(0);
            holder.addDynamicSampler(net.irisshaders.iris.gl.texture.TextureType.TEXTURE_2D,()->0,null,
                    (java.util.function.Supplier<net.irisshaders.iris.gl.sampler.GlSampler>)null,"colortex19");
            holder.addDynamicSampler(net.irisshaders.iris.gl.texture.TextureType.TEXTURE_2D,()->0,null,
                    ()->null,"depthtex1");
            return null;
        }).when(pipeline).addGbufferOrShadowSamplers(any(),any(),any(),eq(false),eq(true),eq(true),eq(false));
        var factory=IrisVoxyRenderPipelineData.class.getDeclaredMethod("createImageSet",net.irisshaders.iris.pipeline.IrisRenderingPipeline.class,IrisShaderPatch.class);
        factory.setAccessible(true);
        var images=(IrisVoxyRenderPipelineData.ImageSet)factory.invoke(null,pipeline,patch);
        int stale=glGenSamplers();
        try {
            glSamplerParameteri(stale,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE);
            glSamplerParameteri(stale,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
            glBindSampler(6,stale);glBindSampler(7,stale);
            images.bindingFunction().accept(6);
            for(int unit=6;unit<8;unit++) {
                glActiveTexture(GL_TEXTURE0+unit);
                if(glGetInteger(GL_SAMPLER_BINDING)!=0) throw new AssertionError("Pack water input inherited stale sampler on unit "+unit);
            }
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("Pack sampler GL error");
            System.out.println("PASS: reflection and depth inputs reject inherited comparison/mipmap samplers");
        } finally {glBindSampler(6,0);glBindSampler(7,0);glDeleteSamplers(stale);glActiveTexture(GL_TEXTURE0);}
    }

    private static void compileComplementary() throws Exception {
        Class<?> holder = Class.forName(IrisVoxyRenderPipelineData.class.getName() + "$UniformWritingHolder");
        var holderCtor = holder.getDeclaredConstructors()[0]; holderCtor.setAccessible(true);
        var writer = IrisVoxyRenderPipelineData.class.getDeclaredMethod("createWriter",long.class,kroppeb.stareval.function.FunctionReturn.class,net.irisshaders.iris.uniforms.custom.cached.CachedUniform.class);
        writer.setAccessible(true);
        var uniforms = new ArrayList<Object>();
        var frequency = net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;
        for (String name : complementary.getUniformList()) {
            String type = customTypes.get(name);
            if (type == null) {
                if (name.startsWith("shadow") || name.startsWith("vxProj") || name.startsWith("vxModelView")) type = "mat4";
                else if (Set.of("cameraPosition","previousCameraPosition","cameraPositionFract","previousCameraPositionFract","fogColor","skyColor","relativeEyePosition","endFlashPosition").contains(name)) type = "vec3";
                else if (name.equals("cameraPositionInt")) type = "ivec3";
                else if (name.equals("eyeBrightness")) type = "ivec2";
                else if (Set.of("worldDay","worldTime","isEyeInWater","heldBlockLightValue","heldBlockLightValue2","heldItemId","heldItemId2","moonPhase","frameCounter").contains(name)) type = "int";
                else type = "float";
            }
            net.irisshaders.iris.uniforms.custom.cached.CachedUniform cached = switch(type) {
                case "mat4" -> new net.irisshaders.iris.uniforms.custom.cached.Float4MatrixCachedUniform(name,frequency,()->new org.joml.Matrix4f());
                case "vec3" -> new net.irisshaders.iris.uniforms.custom.cached.Float3VectorCachedUniform(name,frequency,()->new org.joml.Vector3f(.5f));
                case "ivec3" -> new net.irisshaders.iris.uniforms.custom.cached.Int3VectorCachedUniform(name,frequency,()->new org.joml.Vector3i());
                case "ivec2" -> new net.irisshaders.iris.uniforms.custom.cached.Int2VectorCachedUniform(name,frequency,()->new org.joml.Vector2i(240));
                case "int" -> new net.irisshaders.iris.uniforms.custom.cached.IntCachedUniform(name,frequency,()->name.equals("worldTime")?6000:0);
                case "float" -> new net.irisshaders.iris.uniforms.custom.cached.FloatCachedUniform(name,frequency,()->name.startsWith("view")?16f:name.equals("near")?.05f:name.equals("far")?512f:.5f);
                default -> throw new AssertionError("unmapped real pack type "+type+" for "+name);
            };
            cached.update();
            it.unimi.dsi.fastutil.longs.Long2ObjectFunction<java.util.function.LongConsumer> factory = offset -> {
                try { return (java.util.function.LongConsumer)writer.invoke(null,offset,new kroppeb.stareval.function.FunctionReturn(),cached); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            };
            uniforms.add(holderCtor.newInstance(name,kroppeb.stareval.function.Type.convert(cached.getType()),factory));
        }
        var makeLayout=IrisVoxyRenderPipelineData.class.getDeclaredMethod("createUniformLayoutStructAndUpdater",List.class);makeLayout.setAccessible(true);
        var layout=(IrisVoxyRenderPipelineData.StructLayout)makeLayout.invoke(null,uniforms);
        var ctor=IrisVoxyRenderPipelineData.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var data=(IrisVoxyRenderPipelineData)ctor.newInstance(complementary,new int[]{0,6},new int[]{0},layout,complementary.createBlendSetup(),new IrisVoxyRenderPipelineData.ImageSet("",ignored->{},List.copyOf(complementary.getSamplerSet().keySet())),null);
        complementaryData = data;
        long ptr=org.lwjgl.system.MemoryUtil.nmemAlloc(layout.size());
        try {
            org.lwjgl.system.MemoryUtil.memSet(ptr,0,layout.size());layout.updater().accept(ptr);
            for(boolean translucent:new boolean[]{false,true}) {
                String source=MetalVxResolvePass.assembleFragment(data,translucent);
                Files.writeString(Path.of("build/complementary-programs/"+(translucent?"translucent":"opaque")+"-resolve.frag"),source);
                int program=VxIrisSideChannel.compile(MetalVxResolvePass.RESOLVE_VERT,source,"actual Complementary "+(translucent?"translucent":"opaque"));
                if(program==0) throw new AssertionError("actual Complementary program did not compile/link");
                glDeleteProgram(program);
                System.out.println("PASS: actual Iris-preprocessed Complementary "+(translucent?"translucent":"opaque")+" program compiles/links on Apple GL with real Iris cached-uniform updates");
            }
        } finally {org.lwjgl.system.MemoryUtil.nmemFree(ptr);}
    }

}
