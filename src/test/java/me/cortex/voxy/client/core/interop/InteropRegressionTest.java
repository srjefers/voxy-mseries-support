package me.cortex.voxy.client.core.interop;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.common.Logger;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Standalone tests use real driver pixels and state, rather than mocking GL calls. */
public final class InteropRegressionTest {
    private static final List<String> failures = new ArrayList<>();
    private static MetalRenderBackend metal;

    public static void main(String[] args) throws Exception {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errors);
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(64, 64, "Voxy interop regression", 0, 0);
        if (window == 0) throw new AssertionError("GL context unavailable");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            RenderSystem.initRenderer(window, 0, false, (id, type) -> null, false);
            // Logger.error otherwise initializes Fabric/game chat in this standalone process.
            Logger.SHUTUP = true;
            metal = new MetalRenderBackend();
            check("fresh main target and replacement", InteropRegressionTest::mainTarget);
            check("rectangle sampling ignores inherited mip sampler", () -> composition(false, false));
            check("shader pass ignores inherited clipping and color mask", () -> composition(false, true));
            check("blit pass ignores inherited scissor", () -> composition(true, true));
            check("failed refresh skips stale bridge and recovers", InteropRegressionTest::failedRefresh);
            check("failed initial binding recovers", InteropRegressionTest::failedInitialBind);
            check("closed bridge leaves destination intact", InteropRegressionTest::closedBridge);
            check("bridge resize", InteropRegressionTest::resize);
            check("shader compilation failure preserves state", InteropRegressionTest::shaderFailure);
            check("exception restores scoped GL state", InteropRegressionTest::exceptionRestoration);
            check("coverage split matches shader contract", InteropRegressionTest::coveragePolicy);
        } finally {
            if (metal != null) metal.shutdown();
            if (RenderSystem.tryGetDevice() != null) RenderSystem.getDevice().close();
            glfwDestroyWindow(window);
            glfwTerminate();
            glfwSetErrorCallback(null);
            errors.free();
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("PASS: all 11 GL/Metal interop and coverage regressions");
    }

    private static void check(String label, CheckedRunnable test) {
        resetState();
        try {
            test.run();
            require(glGetError() == GL_NO_ERROR, "unexpected GL error");
            System.out.println("PASS: " + label);
        } catch (Throwable e) {
            if (e instanceof InvocationTargetException invocation) e = invocation.getCause();
            failures.add(label + ": " + e);
            System.out.println("FAIL: " + label + ": " + e);
        } finally {
            resetState();
        }
    }

    private static void mainTarget() throws Exception {
        Method resolve = method(IOSurfaceBridgeCompositor.class, "resolveMcMainFbo", RenderTarget.class);
        try (TestTarget target = new TestTarget(12, 8)) {
            int fbo = (int) resolve.invoke(null, target);
            verifyTarget(fbo, target);
            // Keep the color texture, replace depth: caching only color/FBO must not retain old depth.
            target.replaceDepth();
            verifyTarget((int) resolve.invoke(null, target), target);
            try (TestTarget replacement = new TestTarget(19, 11)) {
                verifyTarget((int) resolve.invoke(null, replacement), replacement);
            }
        }
    }

    private static void verifyTarget(int fbo, TestTarget target) {
        require(fbo > 0, "fresh target must resolve a real FBO, got " + fbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        require(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "target incomplete");
        require(glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) == ((GlTexture) target.getColorTexture()).glId(), "wrong color");
        int depth = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        require(depth == ((GlTexture) target.getDepthTexture()).glId(),
                "stale depth: got " + depth + ", expected " + ((GlTexture) target.getDepthTexture()).glId());
    }

    private static void composition(boolean blit, boolean clip) throws Exception {
        try (Framebuffer target = new Framebuffer(24, 16); IOSurfaceBridge bridge = bridge(24, 16)) {
            target.clear(0, 0, 0);
            int sampler = glGenSamplers();
            int rect = glGenTextures();
            int texture = glGenTextures();
            int vao = glGenVertexArrays();
            try {
                glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glActiveTexture(GL_TEXTURE0);
                glBindSampler(0, sampler);
                glBindTexture(GL_TEXTURE_RECTANGLE, rect);
                glBindTexture(GL_TEXTURE_2D, texture);
                glBindVertexArray(vao);
                glViewport(2, 3, 7, 8);
                glEnable(GL_BLEND);
                glEnable(GL_DEPTH_TEST);
                glEnable(GL_CULL_FACE);
                if (clip) {
                    glEnable(GL_SCISSOR_TEST);
                    glScissor(0, 0, 0, 0);
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_NEVER, 0, ~0);
                    glColorMask(false, false, false, false);
                }
                glActiveTexture(GL_TEXTURE3);
                int[] before = state();
                require(compose(bridge, blit, target), "composition skipped");
                require(Arrays.equals(before, state()), "composition leaked GL state");
                target.expect(64, 128, 191);
            } finally {
                glBindSampler(0, 0);
                glDeleteSamplers(sampler);
                glDeleteTextures(rect);
                glDeleteTextures(texture);
                glDeleteVertexArrays(vao);
            }
        }
    }

    private static void failedRefresh() throws Exception {
        try (Framebuffer target = new Framebuffer(16, 12); IOSurfaceBridge bridge = bridge(16, 12)) {
            require(compose(bridge, true, target), "initial composition failed");
            target.clear(1, 0, 1);
            IOSurfaceBridge invalid = invalidSizeView(bridge);
            int[] before = state();
            require(!compose(invalid, true, target), "failed refresh displayed stale IOSurface");
            consumeInjectedBindError();
            require(Arrays.equals(before, state()), "failed refresh leaked GL state");
            target.expect(255, 0, 255);
            require(compose(bridge, true, target), "valid bridge did not recover");
            target.expect(64, 128, 191);
        }
    }

    private static void failedInitialBind() throws Exception {
        try (Framebuffer target = new Framebuffer(16, 12); IOSurfaceBridge bridge = bridge(16, 12)) {
            // Force the real first-bind path, without faking an invalid native pointer.
            var field = IOSurfaceBridgeCompositor.class.getDeclaredField("boundIoSurface");
            field.setAccessible(true);
            field.setLong(null, 0);
            target.clear(1, 0, 1);
            int[] before = state();
            require(!compose(invalidSizeView(bridge), false, target), "invalid bridge composed");
            consumeInjectedBindError();
            require(Arrays.equals(before, state()), "failed initial bind leaked state");
            target.expect(255, 0, 255);
            require(compose(bridge, false, target), "failed first bind permanently disabled compositor");
            target.expect(64, 128, 191);
        }
    }

    private static IOSurfaceBridge invalidSizeView(IOSurfaceBridge bridge) throws Exception {
        var constructor = IOSurfaceBridge.class.getDeclaredConstructor(int.class, int.class,
                IOSurfaceBridge.IOSurfaceFormat.class, long.class, long.class);
        constructor.setAccessible(true);
        // Deliberately non-owning view of a VALID IOSurface. Never close it (the original owns it).
        return constructor.newInstance(-1, bridge.height(),
                bridge.format(), bridge.ioSurfaceHandle(), 0L);
    }

    private static void consumeInjectedBindError() {
        // CGL reports the deliberately invalid width through both its return value and GL's error queue.
        int error = glGetError();
        require(error == GL_INVALID_VALUE || error == GL_NO_ERROR, "unexpected native fault error: " + error);
    }

    private static void closedBridge() throws Exception {
        try (Framebuffer target = new Framebuffer(16, 12)) {
            IOSurfaceBridge bridge = bridge(16, 12);
            bridge.close();
            target.clear(1, 0, 1);
            int[] before = state();
            require(!compose(bridge, true, target), "closed bridge composed");
            require(Arrays.equals(before, state()), "closed bridge changed state");
            target.expect(255, 0, 255);
        }
    }

    private static void resize() throws Exception {
        for (boolean blit : new boolean[]{true, false}) {
            for (int width : new int[]{8, 21, 13}) {
                try (IOSurfaceBridge bridge = bridge(width, 9); Framebuffer target = new Framebuffer(width, 9)) {
                    int[] before = state();
                    require(compose(bridge, blit, target), "resized bridge skipped");
                    require(Arrays.equals(before, state()), "resize leaked state");
                    target.expect(64, 128, 191);
                }
            }
        }
    }

    private static void shaderFailure() throws Exception {
        int[] before = state();
        int shader = (int) method(IOSurfaceBridgeCompositor.class, "compileShader", int.class, String.class)
                .invoke(null, GL_FRAGMENT_SHADER, "#version 150 core\nthis is deliberately invalid GLSL");
        require(shader == 0, "bad shader accepted");
        require(Arrays.equals(before, state()), "shader failure changed state");
    }

    private static void exceptionRestoration() throws Exception {
        Class<?> scopeClass = Class.forName("me.cortex.voxy.client.core.interop.GlInteropState");
        var constructor = scopeClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        int[] before = state();
        try (AutoCloseable scope = (AutoCloseable) constructor.newInstance()) {
            glViewport(1, 2, 3, 4);
            glEnable(GL_SCISSOR_TEST);
            glColorMask(false, true, false, true);
            glActiveTexture(GL_TEXTURE0);
            throw new DeliberateFailure();
        } catch (DeliberateFailure expected) {
            require(Arrays.equals(before, state()), "exception leaked state");
        }
    }

    private static void coveragePolicy() throws Exception {
        Method policy = method(ChunkBoundRenderer.class, "splitActive", BackendType.class,
                boolean.class, boolean.class, String.class);
        for (BackendType backend : BackendType.values()) {
            for (boolean contract : new boolean[]{false, true}) {
                for (boolean nearCull : new boolean[]{false, true}) {
                    for (String setting : new String[]{null, "0", "1", "all"}) {
                        boolean expected = backend != BackendType.OPENGL && !"0".equals(setting)
                                && ("all".equals(setting) || (contract && nearCull));
                        boolean actual = (boolean) policy.invoke(null, backend, contract, nearCull, setting);
                        require(actual == expected, "wrong coverage for " + backend + "/" + contract
                                + "/" + nearCull + "/" + setting);
                    }
                }
            }
        }
    }

    private static IOSurfaceBridge bridge(int width, int height) {
        IOSurfaceBridge bridge = IOSurfaceBridge.create(metal.device(), width, height, IOSurfaceBridge.IOSurfaceFormat.BGRA8);
        try (var encoder = metal.beginRenderPass(RenderPassDesc.builder(width, height)
                .clearColor(bridge.asGpuTexture(), 0.25f, 0.5f, 0.75f, 1).build())) {
            require(encoder != null, "missing Metal encoder");
        }
        metal.submit(); // Keep the actual Metal-to-GL visibility wait.
        return bridge;
    }

    private static boolean compose(IOSurfaceBridge bridge, boolean blit, Framebuffer target) throws Exception {
        return (boolean) method(IOSurfaceBridgeCompositor.class, "compositeToFramebuffer", IOSurfaceBridge.class,
                boolean.class, int.class, int.class, int.class).invoke(null, bridge, blit, target.fbo, target.width, target.height);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static int[] state() {
        List<Integer> values = new ArrayList<>();
        for (int name : new int[]{GL_READ_FRAMEBUFFER_BINDING, GL_DRAW_FRAMEBUFFER_BINDING,
                GL_CURRENT_PROGRAM, GL_VERTEX_ARRAY_BINDING, GL_ACTIVE_TEXTURE, GL_DEPTH_FUNC,
                GL_DEPTH_WRITEMASK, GL_BLEND_SRC_RGB, GL_BLEND_DST_RGB, GL_BLEND_SRC_ALPHA, GL_BLEND_DST_ALPHA}) {
            values.add(glGetInteger(name));
        }
        for (int name : new int[]{GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST}) {
            values.add(glIsEnabled(name) ? 1 : 0);
        }
        for (int name : new int[]{GL_VIEWPORT, GL_SCISSOR_BOX, GL_COLOR_WRITEMASK}) {
            int[] components = new int[4];
            glGetIntegerv(name, components);
            for (int component : components) values.add(component);
        }
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int unit : new int[]{GL_TEXTURE0, GL_TEXTURE3}) {
            glActiveTexture(unit);
            values.add(glGetInteger(GL_TEXTURE_BINDING_RECTANGLE));
            values.add(glGetInteger(GL_TEXTURE_BINDING_2D));
            values.add(glGetInteger(GL_SAMPLER_BINDING));
        }
        glActiveTexture(active);
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void resetState() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_RECTANGLE, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindSampler(0, 0);
        glColorMask(true, true, true, true);
        for (int cap : new int[]{GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST}) glDisable(cap);
        while (glGetError() != GL_NO_ERROR) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestTarget extends RenderTarget implements AutoCloseable {
        TestTarget(int width, int height) {
            super("interop-test", true);
            this.width = width;
            this.height = height;
            colorTexture = RenderSystem.getDevice().createTexture("test-color", GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.RGBA8, width, height, 1, 1);
            replaceDepth();
        }

        void replaceDepth() {
            GpuTexture previous = depthTexture;
            depthTexture = RenderSystem.getDevice().createTexture("test-depth", GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.DEPTH32, width, height, 1, 1);
            if (previous != null) previous.close();
        }

        @Override public void close() {
            colorTexture.close();
            depthTexture.close();
        }
    }

    private static final class Framebuffer implements AutoCloseable {
        final int width, height, texture, fbo;

        Framebuffer(int width, int height) {
            this.width = width;
            this.height = height;
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "test framebuffer incomplete");
        }

        void clear(float r, float g, float b) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glClearColor(r, g, b, 1);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        void expect(int red, int green, int blue) {
            int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            var pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                for (int offset = 0; offset < pixels.capacity(); offset += 4) {
                    require(Math.abs(Byte.toUnsignedInt(pixels.get(offset)) - red) <= 1
                            && Math.abs(Byte.toUnsignedInt(pixels.get(offset + 1)) - green) <= 1
                            && Math.abs(Byte.toUnsignedInt(pixels.get(offset + 2)) - blue) <= 1,
                            "wrong pixel at " + offset / 4 + ": " + Byte.toUnsignedInt(pixels.get(offset))
                                    + "," + Byte.toUnsignedInt(pixels.get(offset + 1)) + "," + Byte.toUnsignedInt(pixels.get(offset + 2)));
                }
            } finally {
                MemoryUtil.memFree(pixels);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            }
        }

        @Override public void close() {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
        }
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    private static final class DeliberateFailure extends RuntimeException { }
}
