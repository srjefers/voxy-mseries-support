package me.cortex.voxy.client.core.interop;

import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;

/** Invisible driver context for command-line assertions; no game or image inspection. */
public final class TestGlContext implements AutoCloseable {
    private final long window;
    public TestGlContext() {
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        this.window = glfwCreateWindow(32, 32, "Voxy command-line regression", 0, 0);
        if (this.window == 0) { glfwTerminate(); throw new AssertionError("GL context unavailable"); }
        glfwMakeContextCurrent(this.window);
        GL.createCapabilities();
    }
    @Override public void close() {
        GL.setCapabilities(null);
        glfwDestroyWindow(this.window);
        glfwTerminate();
    }
}
