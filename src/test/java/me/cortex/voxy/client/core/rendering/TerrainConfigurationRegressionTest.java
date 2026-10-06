package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.TestGlContext;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.mockito.Mockito.*;

/** Characterizes descriptors created by production MDIC, including modes, TAA and driver state. */
public final class TerrainConfigurationRegressionTest {
    private static final String EXPECTED = "6fc5b0aa275a8e6e58be15a3f1da5c876f33c49fee0cc5bcd0b8b1a9780f077e";
    private static String fields(Object value) throws Exception {
        var result = new StringBuilder();
        var fields = value.getClass().getFields();
        Arrays.sort(fields, Comparator.comparing(java.lang.reflect.Field::getName));
        for (var field : fields) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) result.append(field.getName()).append(field.get(value));
        }
        return result.toString();
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP = true;
        var fingerprint = new StringBuilder();
        try (var context = new TestGlContext(); var minecraft = mockStatic(Minecraft.class);
             var iris = mockStatic(me.cortex.voxy.client.core.util.IrisUtil.class)) {
            var client = mock(Minecraft.class);
            client.level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
            when(client.level.getShade(any(), anyBoolean())).thenAnswer(call -> {
                if (!(boolean) call.getArgument(1)) return 1.0f;
                return switch ((net.minecraft.core.Direction) call.getArgument(0)) {
                    case UP -> 1.0f; case DOWN -> 0.5f; case NORTH, SOUTH -> 0.8f; case EAST, WEST -> 0.6f;
                };
            });
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            for (var backendType : new BackendType[]{BackendType.METAL, BackendType.OPENGL}) {
                for (var policy : MetalMaterialPolicy.values()) {
                    for (boolean taa : new boolean[]{false, true}) {
                        var backend = mock(RenderBackend.class);
                        when(backend.getType()).thenReturn(backendType);
                        when(backend.createBuffer(anyLong())).thenAnswer(call -> mock(IGpuBuffer.class, RETURNS_SELF));
                        when(backend.createSampler(any())).thenReturn(mock(IGpuSampler.class));
                        when(backend.createComputePipeline(any())).thenReturn(mock(IGpuPipeline.class));
                        var descriptors = new ArrayList<GraphicsPipelineDesc>();
                        when(backend.createGraphicsPipeline(any())).thenAnswer(call -> {
                            GraphicsPipelineDesc desc = call.getArgument(0);
                            if (desc.label.startsWith("MDIC.")) descriptors.add(desc);
                            return mock(IGpuPipeline.class);
                        });
                        RenderBackendFactory.set(backend);
                        var pipeline = mock(AbstractRenderPipeline.class);
                        when(pipeline.materialPolicy()).thenReturn(policy);
                        when(pipeline.vxMaterialMode()).thenReturn(policy != MetalMaterialPolicy.SHADERS_OFF);
                        when(pipeline.vxOpaqueMaterialMode()).thenReturn(policy.opaque());
                        when(pipeline.useEnvFog()).thenReturn(policy == MetalMaterialPolicy.SHADERS_OFF);
                        if (taa) when(pipeline.taaFunction("taaShift")).thenReturn("vec2 taaShift() { return vec2(0.0); }");
                        var renderer = new MDICSectionRenderer(pipeline, mock(ModelStore.class), mock(BasicSectionGeometryData.class));
                        try {
                            if (descriptors.size() != 2) throw new AssertionError("missing production descriptors");
                            fingerprint.append(backendType).append(policy).append(taa);
                            for (var desc : descriptors) {
                                fingerprint.append(desc.vertexGlsl).append(desc.fragmentGlsl)
                                        .append(new TreeMap<>(desc.defines)).append(Arrays.toString(desc.colorAttachmentFormats))
                                        .append(fields(desc.state.depth)).append(fields(desc.state.blend)).append(fields(desc.state.raster));
                            }
                        } finally { renderer.free(); }
                    }
                }
            }
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
        System.out.println("Production terrain configuration SHA-256: " + digest);
        if (!Boolean.getBoolean("voxy.recordTerrainConfiguration") && !EXPECTED.equals(digest)) {
            throw new AssertionError("production shader configuration changed: " + digest);
        }
        System.out.println("PASS: both backends, all material modes, TAA, source strings, defines, targets and depth/blend/raster state");
    }
}
