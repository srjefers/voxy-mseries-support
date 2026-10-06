package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.FrameTransformHistory;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {

    private static FrameTransformHistory history() {
        var client=Minecraft.getInstance();
        if(client!=null && client.levelRenderer instanceof IGetVoxyRenderSystem holder) {
            var renderer=holder.getVoxyRenderSystem();
            if(renderer!=null) return renderer.getFrameTransforms();
        }
        return EMPTY_HISTORY;
    }
    private static final FrameTransformHistory EMPTY_HISTORY = new FrameTransformHistory();
    public static Matrix4f getViewProjection() { return history().viewProjection(); }
    public static Matrix4f getModelView() { return history().modelView(); }
    public static Matrix4f getProjection() { return history().projection(); }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
                .uniform1i(PER_FRAME, "vxRenderDistance", ()-> VoxyConfig.CONFIG.sectionRenderDistance*32)//In chunks
                .uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection)
                .uniformMatrix(PER_FRAME, "vxViewProjInv", ()->history().viewProjectionInverse())
                .uniformMatrix(PER_FRAME, "vxViewProjPrev", ()->history().previousViewProjection())
                .uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView)
                .uniformMatrix(PER_FRAME, "vxModelViewInv", ()->history().modelViewInverse())
                .uniformMatrix(PER_FRAME, "vxModelViewPrev", ()->history().previousModelView())
                .uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection)
                .uniformMatrix(PER_FRAME, "vxProjInv", ()->history().projectionInverse())
                .uniformMatrix(PER_FRAME, "vxProjPrev", ()->history().previousProjection());

        if (IrisShaderPatch.IMPERSONATE_DISTANT_HORIZONS) {
            uniforms
                    .uniform1f(PER_FRAME, "dhNearPlane", ()->16)//Presently hardcoded in voxy
                    .uniform1f(PER_FRAME, "dhFarPlane", ()->16*3000)//Presently hardcoded in voxy

                    .uniform1i(PER_FRAME, "dhRenderDistance", ()-> VoxyConfig.CONFIG.sectionRenderDistance*32*16)//In blocks
                    .uniformMatrix(PER_FRAME, "dhProjection", VoxyUniforms::getProjection)
                    .uniformMatrix(PER_FRAME, "dhProjectionInverse", ()->history().projectionInverse())
                    .uniformMatrix(PER_FRAME, "dhPreviousProjection", ()->history().previousProjection());
        }
    }



}
