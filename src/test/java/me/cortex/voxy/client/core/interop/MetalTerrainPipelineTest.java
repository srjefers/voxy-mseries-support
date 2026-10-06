package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.VertexLayout;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.common.Logger;


/** Establishes on-device linking before later shader-definition or pipeline refactors. */
public final class MetalTerrainPipelineTest {
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Logger.SHUTUP = true;
        MetalRenderBackend backend = new MetalRenderBackend();
        try (var context = new TestGlContext(); var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
            client.level = org.mockito.Mockito.mock(net.minecraft.client.multiplayer.ClientLevel.class);
            org.mockito.Mockito.when(client.level.getShade(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(call -> {
                if (!(boolean)call.getArgument(1)) return 1.0f;
                return switch ((net.minecraft.core.Direction)call.getArgument(0)) {
                    case UP -> 1.0f; case DOWN -> 0.5f; case NORTH, SOUTH -> 0.8f; case EAST, WEST -> 0.6f;
                };
            });
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            for (var mode : me.cortex.voxy.client.core.MetalMaterialPolicy.values()) {
                for (boolean taa : new boolean[]{false, true}) {
                    var policy = org.mockito.Mockito.mock(me.cortex.voxy.client.core.AbstractRenderPipeline.class);
                    org.mockito.Mockito.when(policy.materialPolicy()).thenReturn(mode);
                    org.mockito.Mockito.when(policy.vxMaterialMode()).thenReturn(mode != me.cortex.voxy.client.core.MetalMaterialPolicy.SHADERS_OFF);
                    org.mockito.Mockito.when(policy.vxOpaqueMaterialMode()).thenReturn(mode.opaque());
                    org.mockito.Mockito.when(policy.useEnvFog()).thenReturn(mode == me.cortex.voxy.client.core.MetalMaterialPolicy.SHADERS_OFF);
                    if (taa) org.mockito.Mockito.when(policy.taaFunction("taaShift")).thenReturn("vec2 taaShift() { return vec2(0.0); }");
                    var configuration = me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration.load(
                            policy, me.cortex.voxy.client.core.gpu.BackendType.METAL);
                    for (var descriptor : new GraphicsPipelineDesc[]{configuration.opaque(), configuration.translucent()}) {
                        try (var compiled = backend.createGraphicsPipeline(descriptor)) {
                            if (compiled == null) throw new AssertionError("missing " + descriptor.label);
                            System.out.println("PASS: production Metal pipeline " + mode + "/" + descriptor.label + "/taa=" + taa);
                        }
                    }
                }
            }
            try (var pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    ShaderLoader.parse("voxy:hiz/blit.vsh"), ShaderLoader.parse("voxy:hiz/blit.fsh"), null,
                    null, null, null, null, 0, VertexLayout.EMPTY, PipelineState.DEFAULT, "Metal Hi-Z"))) {
                if (pipeline == null) throw new AssertionError("missing Metal Hi-Z");
                System.out.println("PASS: Metal compiles and links actual Hi-Z blit pipeline");
            }
        } finally {
            backend.shutdown();
        }
    }
}
