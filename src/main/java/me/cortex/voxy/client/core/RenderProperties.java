package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import net.irisshaders.iris.Iris;

import static org.lwjgl.opengl.GL11C.*;

public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {

    public <T extends Shader.Builder<J>, J extends Shader> T apply(T builder) {
        return (T) builder.defineIf("USE_ZERO_ONE_DEPTH", this.isZero2One)
                .defineIf("USE_REVERSE_Z", this.isReverseZ);
    }

    /** Same defines as {@link #apply}, for pipelines built from a defines map (Metal/Vulkan path). */
    public java.util.Map<String, String> defineMap() {
        var map = new java.util.LinkedHashMap<String, String>();
        if (this.isZero2One) map.put("USE_ZERO_ONE_DEPTH", "");
        if (this.isReverseZ) map.put("USE_REVERSE_Z", "");
        return map;
    }

    public int closerEqualDepthCompare() {
        return this.isReverseZ?GL_GEQUAL:GL_LEQUAL;
    }

    public int closerDepthCompare() {
        return this.isReverseZ?GL_GREATER:GL_LESS;
    }

    public int furtherDepthCompare() {
        return this.isReverseZ?GL_LESS:GL_GREATER;
    }

    public float clearDepth() {
        return this.isReverseZ?0.0f:1.0f;
    }

    public float inverseClearDepth() {
        return this.isReverseZ?1.0f:0.0f;
    }







    private static boolean irisUseBlockAtlasUv() {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            return false;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                return false;
            }
            //return pipeData.useBlockAtlasUV;
            return false;
        }
        return false;
    }

    private static boolean useReverseZ() {
        //if (IrisUtil.irisShaderPackEnabled()) return false;
        return DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
    }

    public static RenderProperties getRenderProperties() {
        // Metal: the LOD pass renders into its own depth and is composited as colour only,
        // so it keeps the fork's GL-style (non-reversed, -1..1) depth that its pipeline
        // states and clears assume, regardless of MC 26.3's reverse-Z.
        if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            return new RenderProperties(false, false, false);
        }
        RenderProperties properties = new RenderProperties(
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
                useReverseZ(),
                false);

        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            properties = new RenderProperties(properties.isZero2One(), properties.isReverseZ(), irisUseBlockAtlasUv());
        }

        return properties;
    }
}
