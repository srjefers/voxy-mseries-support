package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GLCompat;
import me.cortex.voxy.client.core.gpu.IGpuFramebuffer;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferfv;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferiv;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class DepthFramebuffer {
    private final int depthType;
    private IGpuTexture depthBuffer;
    public final IGpuFramebuffer framebuffer = RenderBackendFactory.get().createFramebuffer();

    public DepthFramebuffer() {
        this(GL_DEPTH_COMPONENT24);
    }

    public DepthFramebuffer(int depthType) {
        this.depthType = depthType;
    }

    public boolean resize(int width, int height) {
        if (this.depthBuffer == null || this.depthBuffer.getWidth() != width || this.depthBuffer.getHeight() != height) {
            if (this.depthBuffer != null) {
                this.depthBuffer.free();
            }
            this.depthBuffer = RenderBackendFactory.get().createTexture().store(this.depthType, 1, width, height);
            this.framebuffer.bind(this.getDepthAttachmentType(), this.depthBuffer).verify();
            return true;
        }
        return false;
    }

    public int getDepthAttachmentType() {
        return this.depthType == GL_DEPTH24_STENCIL8?GL_DEPTH_STENCIL_ATTACHMENT: GL_DEPTH_ATTACHMENT;
    }

    public void clear(float depth) {
        GLCompat.clearDepthFramebuffer(this.framebuffer.id(), depth);
    }

    public void clearStencil(int to) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            nglClearNamedFramebufferiv(this.framebuffer.id(), org.lwjgl.opengl.GL11C.GL_STENCIL, 0, stack.nint(to));
        }
    }

    public IGpuTexture getDepthTex() {
        return this.depthBuffer;
    }

    public void free() {
        this.framebuffer.free();
        if (this.depthBuffer != null) {
            this.depthBuffer.free();
        }
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id());
    }

    public int getFormat() {
        return this.depthType;
    }
}
