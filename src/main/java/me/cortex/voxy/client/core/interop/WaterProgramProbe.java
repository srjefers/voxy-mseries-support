package me.cortex.voxy.client.core.interop;

import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL33C.*;

/** Replays one pixel with the live water program and inputs, without blending into shared pack targets. */
public final class WaterProgramProbe {
    private WaterProgramProbe() {}

    public static float[] capturePixel(int width, int height, Runnable draw) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid probe viewport");
        int[] scissor = new int[4];
        glGetIntegerv(GL_SCISSOR_BOX, scissor);
        int pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        try (var state = new GlInteropState(1); var stack = MemoryStack.stackPush()) {
            int framebuffer = glGenFramebuffers(), texture = glGenTextures();
            try {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);
                // Full dimensions retain the program's gl_FragCoord and screen-space reconstruction.
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer)null);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                glDrawBuffer(GL_COLOR_ATTACHMENT0); glReadBuffer(GL_COLOR_ATTACHMENT0);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Water probe framebuffer incomplete");
                glViewport(0, 0, width, height);
                glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_CULL_FACE);
                glDisable(GL_BLEND); glColorMask(true, true, true, true);
                glEnable(GL_SCISSOR_TEST); glScissor(width / 2, height / 2, 1, 1);
                glClearBufferfv(GL_COLOR, 0, new float[4]);
                draw.run();
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                var pixel = stack.mallocFloat(4);
                glReadPixels(width / 2, height / 2, 1, 1, GL_RGBA, GL_FLOAT, pixel);
                return new float[]{pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
            } finally { glDeleteFramebuffers(framebuffer); glDeleteTextures(texture); }
        } finally {
            glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
        }
    }
}
