package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.util.MetalVxResolvePass;
import me.cortex.voxy.client.core.util.VxIrisSideChannel;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.iris.UniformRegressionTest;
import me.cortex.voxy.common.Logger;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40C.*;

/** Faults are injected through actual production uniform/image/blend callbacks, on the real GL driver. */
public final class ResolveRegressionTest {
    private static final List<String> failures = new ArrayList<>();
    private static final String VERT = """
            #version 410 core
            void main() {
                vec2 p=vec2((gl_VertexID&1), (gl_VertexID>>1));
                gl_Position=vec4(p*2.0-1.0,0,1);
            }
            """;
    private static final String FRAG = """
            #version 410 core
            layout(std140) uniform ShaderUniformBindings { vec4 colour; };
            uniform sampler2DRect plane;
            out vec4 result;
            void main() { result=texture(plane,gl_FragCoord.xy)*colour; }
            """;

    public static void main(String[] args) throws Exception {
        var errors = GLFWErrorCallback.createPrint(System.err).set();
        require(glfwInit(), "GLFW init");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(32, 32, "Voxy resolve regression", 0, 0);
        require(window != 0, "GL context");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); Logger.SHUTUP = true;
            check("resolve success restores state and samples rectangle", () -> resolve("success"));
            check("generic translucent resolve preserves uncovered opaque pixels", () -> resolve("trans-preserve"));
            check("combined translucent depth keeps opaque terrain without water", ResolveRegressionTest::combinedDepth);
            check("both current depth layers exist before material programs", ResolveRegressionTest::frameOrdering);
            check("uniform failure preserves state and output", () -> resolve("uniform"));
            check("partial image binding failure preserves state and output", () -> resolve("image"));
            check("blend setup failure preserves state and output", () -> resolve("blend"));
            check("compile failure restores state", ResolveRegressionTest::compileFailure);
            check("pipeline reset always releases programs and native scratch", ResolveRegressionTest::ownedReset);
            check("failed translucent preparation rejects the whole material contract", ResolveRegressionTest::failedPreparation);
            check("scoped draw exception restores extended state", ResolveRegressionTest::scopeFailure);
            check("matched frame readback restores all GL state", ResolveRegressionTest::matchedProbeState);
            check("unblended water output preserves targets and failure state", ResolveRegressionTest::unblendedWaterProbe);
        } finally {
            glfwDestroyWindow(window); glfwTerminate(); glfwSetErrorCallback(null); errors.free();
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("PASS: 13 real GL resolve success/failure regressions");
    }

    private static void frameOrdering() throws Exception {
        MetalVxResolvePass.reset();
        int colour=rectangle(255,255,255,255,false), oldDepth=rectangle(26,0,0,255,false);
        int newDepth=rectangle(191,0,0,255,false), opaqueDepth=rectangle(230,0,0,255,false);
        int output=glGenTextures(); glBindTexture(GL_TEXTURE_2D,output);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,2,2,0,GL_RGBA,GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
        var seen=new ArrayList<Float>();
        try {
            var channel=VxIrisSideChannel.getOrCreate();
            channel.resolve(colour,opaqueDepth,2,2,true);
            channel.resolveTrans(colour,oldDepth,2,2,true);
            var data=org.mockito.Mockito.spy(data(ptr->{}, unit->{
                glActiveTexture(GL_TEXTURE0+unit);
                glBindTexture(GL_TEXTURE_2D,channel.transDepthTexId());
                float[] depths=new float[4]; glGetTexImage(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
                seen.add(depths[0]);
            },()->{}));
            String fragment="layout(location=0) out vec4 result; void voxy_emitFragment(VoxyFragmentParameters p){ result=p.sampledColour; }";
            set(data,"opaquePatch",fragment); set(data,"translucentPatch",fragment);
            org.mockito.Mockito.doReturn(new int[]{output}).when(data).resolveOpaqueTargetsNow(null);
            org.mockito.Mockito.doReturn(new int[]{output}).when(data).resolveTranslucentTargetsNow(null);
            // RAW GL depth bridges decode to window depth unless the Metal remap is enabled.
            float expected=me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP
                    ?191f/255f : (191f/255f)*.5f+.5f;
            MetalVxResolvePass.resolve(data,null,colour,colour,colour,opaqueDepth,
                    colour,colour,colour,newDepth,2,2);
            require(seen.size()==2 && seen.stream().allMatch(value->Math.abs(value-expected)<.004f),
                    "material read stale translucent depth: "+seen+" expected "+expected);
        } finally {
            MetalVxResolvePass.reset(); VxIrisSideChannel.destroy();
            glDeleteTextures(new int[]{colour,oldDepth,newDepth,opaqueDepth,output});
        }
    }

    private static void combinedDepth() {
        int opaqueColour = rectangle(255, 255, 255, 255, false);
        int translucentColour = rectangle(255, 255, 255, 255, true);
        int opaqueDepth = rectangle(191, 0, 0, 255, false);
        int combinedDepth = rectangle(191, 0, 0, 255, false);
        int readFbo = glGenFramebuffers();
        try {
            glBindTexture(GL_TEXTURE_RECTANGLE, combinedDepth);
            glTexSubImage2D(GL_TEXTURE_RECTANGLE, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                    org.lwjgl.BufferUtils.createByteBuffer(4).put(new byte[]{(byte)128, 0, 0, (byte)255}).flip());
            VxIrisSideChannel channel = VxIrisSideChannel.getOrCreate();
            require(channel.resolve(opaqueColour, opaqueDepth, 2, 2, true), "opaque depth conversion failed");
            require(channel.resolveTrans(translucentColour, combinedDepth, 2, 2, true), "combined depth conversion failed");
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, channel.transDepthTexId(), 0);
            glReadBuffer(GL_NONE);
            require(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "combined depth readback FBO incomplete");
            float[] pixels = new float[4];
            glReadPixels(0, 0, 2, 2, GL_DEPTH_COMPONENT, GL_FLOAT, pixels);
            int terrain = 0, water = 0;
            for (float pixel : pixels) {
                if (Math.abs(pixel - 191f / 255f) < .004f) terrain++;
                else if (Math.abs(pixel - 128f / 255f) < .004f) water++;
            }
            require(terrain == 3 && water == 1, "translucent depth lost terrain or water: " + Arrays.toString(pixels));
            require(glGetError() == GL_NO_ERROR, "combined depth GL error");
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glDeleteFramebuffers(readFbo);
            glDeleteTextures(new int[]{opaqueColour, translucentColour, opaqueDepth, combinedDepth});
            VxIrisSideChannel.destroy();
        }
    }

    private static int rectangle(int r, int g, int b, int a, boolean firstPixelOnly) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_RECTANGLE, texture);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(2 * 2 * 4);
        for (int i = 0; i < 4; i++) {
            int alpha = !firstPixelOnly || i == 0 ? a : 0;
            pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) alpha);
        }
        pixels.flip();
        glTexImage2D(GL_TEXTURE_RECTANGLE, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return texture;
    }

    private static void resolve(String fault) throws Exception {
        try (Resources r = new Resources()) {
            int program = compile(VERT, FRAG, "regression");
            require(program != 0, "test shader compile");
            long scratch = MemoryUtil.nmemAlloc(16);
            int ubo = glGenBuffers(), fbo = glGenFramebuffers(), vao = glGenVertexArrays();
            try {
                glUseProgram(program); glUniform1i(glGetUniformLocation(program, "plane"), 0);
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "ShaderUniformBindings"), 5);
                glUseProgram(0);
                LongConsumer uniforms = ptr -> {
                    if (fault.equals("uniform")) {
                        glBindBuffer(GL_UNIFORM_BUFFER, 0);
                        glActiveTexture(GL_TEXTURE7); glBindSampler(7, 0);
                        throw new DeliberateFailure();
                    }
                    for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(ptr + i * 4L, 1);
                };
                IntConsumer images = unit -> {
                    glActiveTexture(GL_TEXTURE0 + unit);
                    glBindTexture(GL_TEXTURE_2D, r.image);
                    glBindSampler(unit, 0);
                    if (fault.equals("image")) throw new DeliberateFailure();
                    glActiveTexture(GL_TEXTURE0 + unit + 1);
                    glBindTexture(GL_TEXTURE_2D, r.image); glBindSampler(unit + 1, 0);
                };
                Runnable blending = () -> {
                    if (fault.equals("trans-preserve")) {
                        glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA); return;
                    }
                    glDisable(GL_BLEND);
                    glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);
                    if (fault.equals("blend")) throw new DeliberateFailure();
                };
                var data = data(uniforms, images, blending);
                Class<?> progType = Class.forName(MetalVxResolvePass.class.getName() + "$Prog");
                var ctor = progType.getDeclaredConstructor(); ctor.setAccessible(true);
                Object prog = ctor.newInstance();
                set(prog, "prog", program); set(prog, "ubo", ubo); set(prog, "uboSize", 16);
                set(prog, "uboScratch", scratch); set(prog, "fbo", fbo); set(prog, "samplerCount", 2);
                setStatic(MetalVxResolvePass.class, "resolveVao", vao);
                if (fault.equals("trans-preserve")) {
                    glBindTexture(GL_TEXTURE_RECTANGLE, r.plane);
                    float[] transparent = new float[8 * 8 * 4];
                    for (int i = 0; i < 64; i++) { transparent[i*4]=.25f; transparent[i*4+1]=.5f; transparent[i*4+2]=.75f; }
                    glTexSubImage2D(GL_TEXTURE_RECTANGLE, 0, 0, 0, 8, 8, GL_RGBA, GL_FLOAT, transparent);
                }
                r.seed();
                List<Long> before = state();
                Throwable thrown = null;
                try {
                    Method run = MetalVxResolvePass.class.getDeclaredMethod("runOne", IrisVoxyRenderPipelineData.class,
                            progType, int.class, int.class, int.class, int.class, int[].class,
                            int.class, int.class, boolean.class, int.class);
                    run.setAccessible(true);
                    run.invoke(null, data, prog, r.plane, r.plane, r.plane, r.plane,
                            new int[]{r.target}, 8, 8, true, 0);
                } catch (InvocationTargetException e) { thrown = e.getCause(); }
                require((fault.equals("success") || fault.equals("trans-preserve")) ? thrown == null : thrown instanceof DeliberateFailure,
                        "wrong failure: " + thrown);
                require(before.equals(state()), "resolve leaked state; first difference " + difference(before, state()));
                r.expect(fault.equals("success") ? 64 : 255, fault.equals("success") ? 128 : 0,
                        fault.equals("success") ? 191 : 255);
                require(glGetError() == GL_NO_ERROR, "resolve GL error");
            } finally {
                setStatic(MetalVxResolvePass.class, "resolveVao", 0);
                glDeleteVertexArrays(vao); glDeleteFramebuffers(fbo); glDeleteBuffers(ubo);
                MemoryUtil.nmemFree(scratch); glDeleteProgram(program);
            }
        }
    }

    private static IrisVoxyRenderPipelineData data(LongConsumer uniforms, IntConsumer images, Runnable blend) throws Exception {
        Constructor<?> ctor = IrisVoxyRenderPipelineData.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return (IrisVoxyRenderPipelineData) ctor.newInstance(UniformRegressionTest.patch(), new int[]{0}, new int[]{0},
                new IrisVoxyRenderPipelineData.StructLayout(16, "{vec4 colour;}", uniforms), blend,
                new IrisVoxyRenderPipelineData.ImageSet("", images, List.of("a", "b")), null);
    }

    private static void compileFailure() throws Exception {
        try (Resources r = new Resources()) {
            r.seed(); List<Long> before = state();
            int result = compile(VERT, "#version 410 core\ninvalid shader", "expected-failure");
            require(result == 0, "invalid shader accepted"); require(before.equals(state()), "compile leaked state");
        }
    }

    private static void failedPreparation() throws Exception {
        MetalVxResolvePass.reset();
        try (Resources r = new Resources()) {
            var data = data(ptr -> {}, ignored -> {}, () -> {});
            set(data, "opaquePatch", "layout(location=0) out vec4 result; void voxy_emitFragment(VoxyFragmentParameters p){ result=p.sampledColour; }");
            set(data, "translucentPatch", "invalid shader");
            r.seed(); var before = state();
            try {
                require(!MetalVxResolvePass.build(data), "accepted opaque-only material preparation after translucent compile failed");
                require(before.equals(state()), "preparation leaked caller state");
            } finally { MetalVxResolvePass.reset(); }
        }
    }

    private static void ownedReset() throws Exception {
        MetalVxResolvePass.reset();
        int program = compile(VERT, FRAG, "owned-reset");
        int ubo = glGenBuffers(), fbo = glGenFramebuffers(), vao = glGenVertexArrays();
        long scratch = MemoryUtil.nmemAlloc(16);
        Class<?> type = Class.forName(MetalVxResolvePass.class.getName() + "$Prog");
        var ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
        Object prog = ctor.newInstance();
        set(prog, "prog", program); set(prog, "ubo", ubo); set(prog, "fbo", fbo); set(prog, "uboScratch", scratch);
        setStatic(MetalVxResolvePass.class, "opaque", prog); setStatic(MetalVxResolvePass.class, "resolveVao", vao);
        glBindBuffer(GL_UNIFORM_BUFFER, ubo); glBindFramebuffer(GL_FRAMEBUFFER, fbo); glBindVertexArray(vao);
        try {
            MetalVxResolvePass.reset();
            require(!glIsProgram(program) && !glIsBuffer(ubo) && !glIsFramebuffer(fbo) && !glIsVertexArray(vao), "reset retained owned GL resources");
            Field memory = type.getDeclaredField("uboScratch"); memory.setAccessible(true);
            require(memory.getLong(prog) == 0, "reset retained native scratch");
            MetalVxResolvePass.reset(); // An empty reset is safe on repeated world shutdown.
        } finally {
            Method free = MetalVxResolvePass.class.getDeclaredMethod("freeProg", type); free.setAccessible(true); free.invoke(null, prog);
            if (glIsVertexArray(vao)) glDeleteVertexArrays(vao);
            setStatic(MetalVxResolvePass.class, "opaque", null); setStatic(MetalVxResolvePass.class, "resolveVao", 0);
        }
    }

    private static void unblendedWaterProbe() throws Exception {
        var capture=Class.forName("me.cortex.voxy.client.core.interop.WaterProgramProbe")
                .getMethod("capturePixel",int.class,int.class,Runnable.class);
        try(Resources r=new Resources()) {
            int program=compile(VERT,"#version 410 core\nout vec4 result; void main(){result=vec4(.2,.4,.8,.25);}","water-alpha");
            int vao=glGenVertexArrays();
            try {
                r.seed();glUseProgram(program);glBindVertexArray(vao);glScissor(1,2,3,4);
                var before=state();int[] scissor=new int[4];glGetIntegerv(GL_SCISSOR_BOX,scissor);
                Runnable draw=()->glDrawArrays(GL_TRIANGLE_STRIP,0,4);
                var pixel=(float[])capture.invoke(null,8,8,draw);
                require(Math.abs(pixel[3]-.25f)<.001f && Math.abs(pixel[0]-.2f)<.001f,
                        "water output was blended instead of sampled: "+Arrays.toString(pixel));
                require(before.equals(state()),"water probe leaked state");
                int[] after=new int[4];glGetIntegerv(GL_SCISSOR_BOX,after);
                require(Arrays.equals(scissor,after),"water probe changed scissor rectangle");
                try {capture.invoke(null,8,8,(Runnable)()->{throw new DeliberateFailure();});throw new AssertionError("missing draw failure");}
                catch(InvocationTargetException expected){require(expected.getCause() instanceof DeliberateFailure,"wrong failure");}
                require(before.equals(state()),"water probe failed restoration on exception");
                r.expect(255,0,255);
                require(glGetError()==GL_NO_ERROR,"water probe GL error");
            } finally {glDeleteProgram(program);glDeleteVertexArrays(vao);}
        }
    }

    private static void matchedProbeState() throws Exception {
        try(Resources r=new Resources()) {
            r.seed(); var before=state();
            var type=Class.forName("me.cortex.voxy.client.core.MatchedFrameProbe");
            var read=type.getDeclaredMethod("readCurrent",int.class,int.class);read.setAccessible(true);
            String value=(String)read.invoke(null,8,8);
            require(before.equals(state()),"probe readback leaked GL state");
            require(value.contains("rgba="),"probe did not read the current framebuffer: "+value);
            require(glGetError()==GL_NO_ERROR,"probe GL error");
        }
    }

    private static void scopeFailure() throws Exception {
        try (Resources r = new Resources()) {
            r.seed(); List<Long> before = state();
            var ctor = GlInteropState.class.getDeclaredConstructor(int.class, int[].class);
            ctor.setAccessible(true);
            try (AutoCloseable ignored = (AutoCloseable) ctor.newInstance(8, new int[]{5})) {
                for (int unit = 0; unit < 8; unit++) {
                    glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_RECTANGLE, 0);
                    glBindTexture(GL_TEXTURE_2D, 0); glBindSampler(unit, 0);
                }
                glBindBufferBase(GL_UNIFORM_BUFFER, 5, 0); glBindBuffer(GL_UNIFORM_BUFFER, 0);
                glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, 2, 2);
                glDisable(GL_STENCIL_TEST); glDepthMask(false); glDepthFunc(GL_ALWAYS);
                glColorMask(false, false, false, false); glDisable(GL_BLEND);
                glBlendFunc(GL_ONE, GL_ZERO); glBlendEquation(GL_FUNC_ADD);
                throw new DeliberateFailure();
            } catch (DeliberateFailure expected) { }
            require(before.equals(state()), "scope leaked state");
        }
    }

    private static List<Long> state() {
        List<Long> s = new ArrayList<>();
        for (int p : new int[]{GL_DRAW_FRAMEBUFFER_BINDING, GL_READ_FRAMEBUFFER_BINDING, GL_CURRENT_PROGRAM,
                GL_VERTEX_ARRAY_BINDING, GL_ACTIVE_TEXTURE, GL_UNIFORM_BUFFER_BINDING, GL_DEPTH_FUNC, GL_DEPTH_WRITEMASK})
            s.add((long) glGetInteger(p));
        for (int c : new int[]{GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST}) s.add(glIsEnabled(c) ? 1L : 0L);
        int[] viewport = new int[4]; glGetIntegerv(GL_VIEWPORT, viewport);
        for (int x : viewport) s.add((long) x);
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int u = 0; u < 8; u++) {
            glActiveTexture(GL_TEXTURE0 + u);
            for (int p : new int[]{GL_TEXTURE_BINDING_RECTANGLE, GL_TEXTURE_BINDING_2D, GL_SAMPLER_BINDING}) s.add((long) glGetInteger(p));
        }
        glActiveTexture(active);
        s.add((long) glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 5));
        s.add(glGetInteger64i(GL_UNIFORM_BUFFER_START, 5)); s.add(glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 5));
        for (int i = 0; i < glGetInteger(GL_MAX_DRAW_BUFFERS); i++) {
            s.add(glIsEnabledi(GL_BLEND, i) ? 1L : 0L);
            for (int p : new int[]{GL_BLEND_SRC_RGB, GL_BLEND_DST_RGB, GL_BLEND_SRC_ALPHA, GL_BLEND_DST_ALPHA,
                    GL_BLEND_EQUATION_RGB, GL_BLEND_EQUATION_ALPHA}) s.add((long) glGetIntegeri(p, i));
            int[] mask = new int[4]; glGetIntegeri_v(GL_COLOR_WRITEMASK, i, mask);
            for (int x : mask) s.add((long) x);
        }
        return s;
    }

    private static int difference(List<Long> a, List<Long> b) {
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return i;
        return -1;
    }

    private static final class Resources implements AutoCloseable {
        final int plane = texture(GL_TEXTURE_RECTANGLE, 0.25f, 0.5f, 0.75f);
        final int image = texture(GL_TEXTURE_2D, 0, 1, 0);
        final int target = texture(GL_TEXTURE_2D, 1, 0, 1);
        final int framebuffer = glGenFramebuffers(), buffer = glGenBuffers(), genericBuffer = glGenBuffers();
        final int[] samplers = new int[8], rects = new int[8], textures = new int[8];
        Resources() {
            for (int i = 0; i < 8; i++) {
                samplers[i] = glGenSamplers(); glSamplerParameteri(samplers[i], GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                rects[i] = glGenTextures(); textures[i] = glGenTextures();
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "target FBO");
            glBindBuffer(GL_UNIFORM_BUFFER, buffer); glBufferData(GL_UNIFORM_BUFFER, 1024, GL_STATIC_DRAW);
            glBindBuffer(GL_UNIFORM_BUFFER, genericBuffer); glBufferData(GL_UNIFORM_BUFFER, 1024, GL_STATIC_DRAW);
        }
        void seed() {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            for (int i = 0; i < 8; i++) {
                glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_RECTANGLE, rects[i]);
                glBindTexture(GL_TEXTURE_2D, textures[i]); glBindSampler(i, samplers[i]);
            }
            glBindBufferRange(GL_UNIFORM_BUFFER, 5, buffer, 256, 128);
            glBindBuffer(GL_UNIFORM_BUFFER, genericBuffer); glViewport(1, 2, 3, 4);
            glEnable(GL_DEPTH_TEST); glDepthMask(true); glDepthFunc(GL_LEQUAL);
            glEnable(GL_STENCIL_TEST); glStencilFunc(GL_NEVER, 0, ~0);
            glEnable(GL_CULL_FACE); glEnable(GL_SCISSOR_TEST); glScissor(0, 0, 0, 0);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT, GL_FUNC_SUBTRACT);
            glDisablei(GL_BLEND, 1); glBlendFuncSeparatei(1, GL_DST_COLOR, GL_ONE, GL_ONE, GL_ZERO);
            glColorMask(false, true, false, true); glColorMaski(1, true, false, true, false);
            glActiveTexture(GL_TEXTURE4);
        }
        void expect(int red, int green, int blue) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            var pixel = MemoryUtil.memAlloc(4);
            try {
                glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                int[] expected = {red, green, blue};
                for (int i = 0; i < 3; i++) require(Math.abs((pixel.get(i) & 255) - expected[i]) <= 1, "wrong output component " + i);
            } finally { MemoryUtil.memFree(pixel); }
        }
        public void close() {
            glUseProgram(0); glBindVertexArray(0); glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindBufferBase(GL_UNIFORM_BUFFER, 5, 0); glBindBuffer(GL_UNIFORM_BUFFER, 0);
            glDeleteBuffers(buffer); glDeleteBuffers(genericBuffer); glDeleteFramebuffers(framebuffer);
            glDeleteTextures(plane); glDeleteTextures(image); glDeleteTextures(target);
            for (int u = 0; u < 8; u++) { glBindSampler(u, 0); glDeleteSamplers(samplers[u]); glDeleteTextures(rects[u]); glDeleteTextures(textures[u]); }
            glColorMask(true, true, true, true); glDisable(GL_BLEND);
            for (int c : new int[]{GL_DEPTH_TEST, GL_CULL_FACE, GL_STENCIL_TEST, GL_SCISSOR_TEST}) glDisable(c);
            while (glGetError() != GL_NO_ERROR) { }
        }
        private static int texture(int type, float r, float g, float b) {
            int texture = glGenTextures(); glBindTexture(type, texture);
            var pixels = MemoryUtil.memAllocFloat(8 * 8 * 4);
            try {
                for (int i = 0; i < 64; i++) pixels.put(r).put(g).put(b).put(1); pixels.flip();
                glTexImage2D(type, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_FLOAT, pixels);
                glTexParameteri(type, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(type, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            } finally { MemoryUtil.memFree(pixels); }
            return texture;
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }

    private static int compile(String vert, String frag, String label) throws Exception {
        Method method = VxIrisSideChannel.class.getDeclaredMethod("compile", String.class, String.class, String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, vert, frag, label);
    }
    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static void check(String name, CheckedRunnable run) {
        try { run.run(); require(glGetError() == GL_NO_ERROR, "unexpected GL error"); System.out.println("PASS: " + name); }
        catch (Throwable t) { failures.add(name + ": " + t); System.out.println("FAIL: " + name + ": " + t); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class DeliberateFailure extends RuntimeException { }
    private interface CheckedRunnable { void run() throws Exception; }
}
