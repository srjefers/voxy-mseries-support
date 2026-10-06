package me.cortex.voxy.client.core.interop;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40C.*;

/** Scoped ownership of the GL state changed by Metal bridge and shader resolve passes. */
public final class GlInteropState implements AutoCloseable {
    private final int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    private final int program = glGetInteger(GL_CURRENT_PROGRAM);
    private final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int uniformBuffer = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
    private final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int[] viewport = new int[4];
    private final boolean depth = glIsEnabled(GL_DEPTH_TEST);
    private final boolean cull = glIsEnabled(GL_CULL_FACE);
    private final boolean stencil = glIsEnabled(GL_STENCIL_TEST);
    private final boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    private final TextureUnit[] textures;
    private final UniformBinding[] uniformBindings;
    private final DrawBuffer[] drawBuffers;

    public GlInteropState() { this(1); }

    /** Covers units [0, textureUnitCount) and the specified indexed uniform-buffer bindings. */
    public GlInteropState(int textureUnitCount, int... uboBindings) {
        if (textureUnitCount < 0 || textureUnitCount > glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
            throw new IllegalArgumentException("Invalid texture unit scope: " + textureUnitCount);
        glGetIntegerv(GL_VIEWPORT, viewport);
        textures = new TextureUnit[textureUnitCount];
        for (int unit = 0; unit < textureUnitCount; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            textures[unit] = new TextureUnit(glGetInteger(GL_TEXTURE_BINDING_RECTANGLE),
                    glGetInteger(GL_TEXTURE_BINDING_2D), glGetInteger(GL_SAMPLER_BINDING));
        }
        glActiveTexture(activeTexture);
        uniformBindings = new UniformBinding[uboBindings.length];
        for (int i = 0; i < uboBindings.length; i++) {
            int binding = uboBindings[i];
            uniformBindings[i] = new UniformBinding(binding, glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, binding),
                    glGetInteger64i(GL_UNIFORM_BUFFER_START, binding), glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, binding));
        }
        // Global blend/color-mask calls change every draw buffer, including Iris's indexed settings.
        drawBuffers = new DrawBuffer[glGetInteger(GL_MAX_DRAW_BUFFERS)];
        for (int i = 0; i < drawBuffers.length; i++) {
            int[] mask = new int[4]; glGetIntegeri_v(GL_COLOR_WRITEMASK, i, mask);
            drawBuffers[i] = new DrawBuffer(glIsEnabledi(GL_BLEND, i),
                    glGetIntegeri(GL_BLEND_SRC_RGB, i), glGetIntegeri(GL_BLEND_DST_RGB, i),
                    glGetIntegeri(GL_BLEND_SRC_ALPHA, i), glGetIntegeri(GL_BLEND_DST_ALPHA, i),
                    glGetIntegeri(GL_BLEND_EQUATION_RGB, i), glGetIntegeri(GL_BLEND_EQUATION_ALPHA, i), mask);
        }
    }

    @Override
    public void close() {
        for (int unit = 0; unit < textures.length; unit++) {
            var texture = textures[unit];
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_RECTANGLE, texture.rectangle);
            glBindTexture(GL_TEXTURE_2D, texture.texture2d);
            glBindSampler(unit, texture.sampler);
        }
        glActiveTexture(activeTexture);
        for (var binding : uniformBindings) {
            if (binding.buffer != 0 && binding.size > 0)
                glBindBufferRange(GL_UNIFORM_BUFFER, binding.index, binding.buffer, binding.start, binding.size);
            else glBindBufferBase(GL_UNIFORM_BUFFER, binding.index, binding.buffer);
        }
        // Indexed binds also change the generic binding; restore that last.
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        glUseProgram(program);
        glBindVertexArray(vao);
        restore(GL_DEPTH_TEST, depth);
        restore(GL_CULL_FACE, cull);
        restore(GL_STENCIL_TEST, stencil);
        restore(GL_SCISSOR_TEST, scissor);
        glDepthFunc(depthFunc);
        glDepthMask(depthMask);
        for (int i = 0; i < drawBuffers.length; i++) {
            var buffer = drawBuffers[i];
            if (buffer.blend) glEnablei(GL_BLEND, i); else glDisablei(GL_BLEND, i);
            glBlendFuncSeparatei(i, buffer.srcRgb, buffer.dstRgb, buffer.srcAlpha, buffer.dstAlpha);
            glBlendEquationSeparatei(i, buffer.equationRgb, buffer.equationAlpha);
            glColorMaski(i, buffer.mask[0] != 0, buffer.mask[1] != 0, buffer.mask[2] != 0, buffer.mask[3] != 0);
        }
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
    }

    private static void restore(int capability, boolean enabled) {
        if (enabled) glEnable(capability); else glDisable(capability);
    }

    private record TextureUnit(int rectangle, int texture2d, int sampler) { }
    private record UniformBinding(int index, int buffer, long start, long size) { }
    private record DrawBuffer(boolean blend, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
                              int equationRgb, int equationAlpha, int[] mask) { }
}
