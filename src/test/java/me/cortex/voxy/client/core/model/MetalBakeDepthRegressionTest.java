package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.model.bakery.MetalViewCapture;
import me.cortex.voxy.client.core.model.bakery.ModelTextureBakery;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Known partial-height planes through the real Metal bakery and production face transforms. */
public final class MetalBakeDepthRegressionTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Logger.SHUTUP = true;
        int failures = 0;
        try (var context = new HiddenContext()) {
            var backend = new MetalRenderBackend(); RenderBackendFactory.set(backend);
            int texture = glGenTextures();
            long pixels = MemoryUtil.nmemAllocChecked(8);
            long mesh = MemoryUtil.nmemAllocChecked(192);
            long output = MemoryUtil.nmemAllocChecked(6 * 256 * 8);
            MetalViewCapture capture = null;
            try {
                MemoryUtil.memPutInt(pixels, 0xffffffff); MemoryUtil.memPutInt(pixels + 4, 0xff202020);
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                nglTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                var viewsField = ModelTextureBakery.class.getDeclaredField("VIEWS"); viewsField.setAccessible(true);
                var views = (Matrix4f[])viewsField.get(null);
                var projection = new Matrix4f().set(2,0,0,0, 0,-2,0,0, 0,0,.5f,0, -1,1,.25f,1).mul(views[1]);
                capture = new MetalViewCapture(16,16);
                for (float height : new float[]{.125f, .5f, 1f}) {
                    quad(mesh, height, .25f);
                    capture.beginBake(texture, mesh, 1, true); capture.renderFace(1,0,projection);
                    capture.endBake(); capture.emitToStream(output);
                    int[] colors = new int[256], metadata = new int[256];
                    for (int i = 0; i < 256; i++) {
                        long address = output + (256L+i)*8;
                        colors[i] = MemoryUtil.memGetInt(address); metadata[i] = MemoryUtil.memGetInt(address+4);
                    }
                    var face = new ColourDepthTextureData(colors,metadata,16,16);
                    if (TextureUtils.getWrittenPixelCount(face, TextureUtils.WRITE_CHECK_STENCIL) != 256)
                        throw new AssertionError("partial-height top plane fixture did not cover the face");
                    float actual = TextureUtils.computeDepth(face,TextureUtils.DEPTH_MODE_AVG,TextureUtils.WRITE_CHECK_STENCIL);
                    float expected = 1-height;
                    if (Math.abs(actual-expected) > .002f) {
                        failures++; System.err.println("FAIL: plane height="+height+" expected inset="+expected+" actual="+actual);
                    } else System.out.println("PASS: bakery preserves plane inset for height="+height);
                }
                // All production face transforms must preserve inset, including fluid height 8/9.
                for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
                    for (float visibleDepth : new float[]{.125f, .5f, 8f/9f, 1f}) {
                        viewQuad(mesh, views[faceIndex], visibleDepth);
                        var faceProjection = new Matrix4f().set(2,0,0,0, 0,-2,0,0, 0,0,.5f,0, -1,1,.25f,1).mul(views[faceIndex]);
                        capture.beginBake(texture, mesh, 1, true);
                        capture.renderFace(faceIndex%3, faceIndex/3, faceProjection);
                        capture.endBake(); capture.emitToStream(output);
                        int[] colors = new int[256], metadata = new int[256];
                        for (int i = 0; i < 256; i++) {
                            long address = output + (faceIndex*256L+i)*8;
                            colors[i] = MemoryUtil.memGetInt(address); metadata[i] = MemoryUtil.memGetInt(address+4);
                        }
                        var face = new ColourDepthTextureData(colors,metadata,16,16);
                        if (TextureUtils.getWrittenPixelCount(face, TextureUtils.WRITE_CHECK_STENCIL) != 256)
                            throw new AssertionError("face "+faceIndex+" fixture was not covered");
                        float actual = TextureUtils.computeDepth(face,TextureUtils.DEPTH_MODE_AVG,TextureUtils.WRITE_CHECK_STENCIL);
                        if (Math.abs(actual-(1-visibleDepth)) > .002f) {
                            failures++; System.err.println("FAIL: face="+faceIndex+" depth="+visibleDepth+" inset="+actual);
                        } else System.out.println("PASS: face="+faceIndex+" depth="+visibleDepth);
                        for (int emptyFace=0; emptyFace<6; emptyFace++) {
                            if (emptyFace == faceIndex) continue;
                            for (int i=0; i<256; i++) {
                                if (MemoryUtil.memGetLong(output+(emptyFace*256L+i)*8) != 0)
                                    throw new AssertionError("untouched face leaked color/depth metadata");
                            }
                        }
                    }
                }
                // A translucent ice-like surface must keep its alpha when the opposite surface is drawn later.
                MemoryUtil.memPutInt(pixels, 0x80ffffff); MemoryUtil.memPutInt(pixels+4, 0x40202020);
                glBindTexture(GL_TEXTURE_2D, texture);
                nglTexSubImage2D(GL_TEXTURE_2D,0,0,0,2,1,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                for (boolean farLast : new boolean[]{false,true}) {
                    quad(mesh, farLast ? 1f : 0f, farLast ? .25f : .75f);
                    quad(mesh+96, farLast ? 0f : 1f, farLast ? .75f : .25f);
                    capture.beginBake(texture,mesh,2,true); capture.renderFace(1,0,projection);
                    capture.endBake(); capture.emitToStream(output);
                    int actual = MemoryUtil.memGetInt(output+(256L+8*16+8)*8);
                    if (actual != 0x80ffffff) {
                        failures++; System.err.println("FAIL: ice-like front surface lost alpha/color; farLast="+farLast+" color="+Integer.toHexString(actual));
                    } else System.out.println("PASS: ice-like front surface retains alpha; farLast="+farLast);
                }
                MemoryUtil.memPutInt(pixels, 0xffffffff); MemoryUtil.memPutInt(pixels+4, 0xff202020);
                glBindTexture(GL_TEXTURE_2D, texture);
                nglTexSubImage2D(GL_TEXTURE_2D,0,0,0,2,1,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                // The top plane must remain visible regardless of submission order of the lower plane.
                for (boolean farLast : new boolean[]{false,true}) {
                    quad(mesh, farLast ? .125f : 0f, farLast ? .25f : .75f);
                    quad(mesh+96, farLast ? 0f : .125f, farLast ? .75f : .25f);
                    capture.beginBake(texture,mesh,2,true); capture.renderFace(1,0,projection);
                    capture.endBake(); capture.emitToStream(output);
                    int actual = MemoryUtil.memGetInt(output+(256L+8*16+8)*8);
                    if (actual != 0xffffffff) {
                        failures++; System.err.println("FAIL: lower plane overwrote visible top; farLast="+farLast+" color="+Integer.toHexString(actual));
                    } else System.out.println("PASS: visible top wins; farLast="+farLast);
                }
            } finally {
                if (capture != null) capture.free();
                glDeleteTextures(texture); MemoryUtil.nmemFree(pixels); MemoryUtil.nmemFree(mesh); MemoryUtil.nmemFree(output);
                backend.shutdown(); RenderBackendFactory.set(null);
            }
        }
        if (failures != 0) throw new AssertionError(failures+" real Metal bakery depth/visibility regressions failed");
    }
    private static final class HiddenContext implements AutoCloseable {
        private final long window;
        HiddenContext() {
            if (!org.lwjgl.glfw.GLFW.glfwInit()) throw new IllegalStateException("GLFW unavailable");
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT, org.lwjgl.glfw.GLFW.GLFW_TRUE);
            window = org.lwjgl.glfw.GLFW.glfwCreateWindow(64,64,"Numeric bakery depth regression",0,0);
            if (window == 0) { org.lwjgl.glfw.GLFW.glfwTerminate(); throw new IllegalStateException("GL context unavailable"); }
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window); org.lwjgl.opengl.GL.createCapabilities();
        }
        public void close() { org.lwjgl.glfw.GLFW.glfwDestroyWindow(window); org.lwjgl.glfw.GLFW.glfwTerminate(); }
    }
    private static void viewQuad(long pointer, Matrix4f view, float depth) {
        var inverse = new Matrix4f(view).invert();
        float[][] corners = {{0,0},{1,0},{1,1},{0,1}};
        for (int i=0; i<4; i++) {
            var position = inverse.transformPosition(new Vector3f(corners[i][0],corners[i][1],depth));
            long p = pointer+i*24L;
            MemoryUtil.memPutFloat(p,position.x); MemoryUtil.memPutFloat(p+4,position.y); MemoryUtil.memPutFloat(p+8,position.z);
            MemoryUtil.memPutInt(p+12,1); MemoryUtil.memPutFloat(p+16,.25f); MemoryUtil.memPutFloat(p+20,.5f);
        }
    }
    private static void quad(long pointer, float height, float u) {
        float[][] vertices = {{0,height,0},{1,height,0},{1,height,1},{0,height,1}};
        for (int i=0;i<4;i++) {
            long p = pointer+i*24L;
            for (int axis=0;axis<3;axis++) MemoryUtil.memPutFloat(p+axis*4L,vertices[i][axis]);
            MemoryUtil.memPutInt(p+12,1); MemoryUtil.memPutFloat(p+16,u); MemoryUtil.memPutFloat(p+20,.5f);
        }
    }
}
