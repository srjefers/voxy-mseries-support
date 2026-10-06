package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.TestGlContext;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import net.minecraft.client.Minecraft;
import java.util.*;
import static org.mockito.Mockito.*;

/** Real MDIC construction/free, with allocation and release faults at the backend interface. */
public final class MdicOwnershipRegressionTest {
    private static void scenario(int failAt, boolean failRelease) {
        var acquired = new ArrayList<Object>(); var count = new int[1];
        var injected = new IllegalStateException("MDIC allocation " + failAt);
        Runnable acquisition = () -> { if (++count[0] == failAt) throw injected; };
        var backend = mock(RenderBackend.class); when(backend.getType()).thenReturn(BackendType.METAL);
        when(backend.createBuffer(anyLong())).thenAnswer(call -> { acquisition.run(); var resource = mock(IGpuBuffer.class, RETURNS_SELF); acquired.add(resource); return resource; });
        when(backend.createComputePipeline(any())).thenAnswer(call -> { acquisition.run(); var resource = mock(IGpuPipeline.class); acquired.add(resource); return resource; });
        when(backend.createGraphicsPipeline(any())).thenAnswer(call -> { acquisition.run(); var resource = mock(IGpuPipeline.class); acquired.add(resource); return resource; });
        when(backend.createSampler(any())).thenAnswer(call -> { acquisition.run(); var resource = mock(IGpuSampler.class); acquired.add(resource); return resource; });
        RenderBackendFactory.set(backend);
        var policy = mock(AbstractRenderPipeline.class); when(policy.materialPolicy()).thenReturn(MetalMaterialPolicy.SHADERS_OFF);
        MDICSectionRenderer renderer = null;
        try {
            renderer = new MDICSectionRenderer(policy, mock(ModelStore.class), mock(BasicSectionGeometryData.class));
            if (failAt != 0) throw new AssertionError("allocation fault not exercised " + failAt);
            if (failRelease) {
                var failing = acquired.getLast();
                if (failing instanceof IGpuPipeline pipeline) doThrow(injected).when(pipeline).close();
                else throw new AssertionError("expected a final pipeline acquisition");
            }
        } catch (RuntimeException failure) {
            if (failure != injected || failAt == 0) throw failure;
        } finally {
            if (renderer != null) {
                try { renderer.free(); if (failRelease) throw new AssertionError("release failure hidden"); }
                catch (RuntimeException failure) { if (!failRelease || failure != injected) throw failure; }
            }
        }
        for (var resource : acquired) {
            if (resource instanceof IGpuPipeline pipeline) verify(pipeline).close();
            else if (resource instanceof IGpuSampler sampler) verify(sampler).close();
            else verify((IGpuBuffer)resource).free();
        }
        if (renderer != null) {
            for (var resource : acquired) clearInvocations(resource);
            renderer.free();
            for (var resource : acquired) verifyNoInteractions(resource);
        }
    }
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP = true;
        int failures = 0;
        try (var context = new TestGlContext(); var minecraft = mockStatic(Minecraft.class)) {
            var client = mock(Minecraft.class); client.level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
            when(client.level.getShade(any(), anyBoolean())).thenReturn(1.0f);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            for (int stage = 0; stage <= 12; stage++) {
                try { scenario(stage, false); System.out.println("PASS: MDIC ownership, allocation " + stage); }
                catch (Throwable failure) { failures++; failure.printStackTrace(); System.err.println("FAIL: MDIC ownership, allocation " + stage); }
            }
            try { scenario(0, true); System.out.println("PASS: MDIC release failure attempts all owners"); }
            catch (Throwable failure) { failures++; failure.printStackTrace(); System.err.println("FAIL: MDIC release failure attempts all owners"); }
        }
        if (failures != 0) throw new AssertionError(failures + " MDIC ownership failures");
    }
}
