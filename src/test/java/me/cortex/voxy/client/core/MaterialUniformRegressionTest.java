package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import org.lwjgl.system.MemoryUtil;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

public final class MaterialUniformRegressionTest {
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName("me.cortex.voxy.client.core.MetalMaterialUniforms");
        var counter=new AtomicInteger(1);
        var reads=new AtomicInteger();
        var patch=mock(IrisShaderPatch.class);
        when(patch.getTAAShift()).thenReturn("{ return vec2(float(frameCounter)); }");
        var layout=new IrisVoxyRenderPipelineData.StructLayout(16,"{ int frameCounter; }",ptr->{reads.incrementAndGet();MemoryUtil.memPutInt(ptr,counter.get());});
        var ctor=IrisVoxyRenderPipelineData.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var data=(IrisVoxyRenderPipelineData)ctor.newInstance(patch,new int[]{0},new int[]{0},layout,null,new IrisVoxyRenderPipelineData.ImageSet("",ignored->{}, List.of()),null);
        var backend=new MetalRenderBackend();
        try {
            var make=type.getDeclaredConstructor(IrisVoxyRenderPipelineData.class,RenderBackend.class);make.setAccessible(true);
            Object uniforms=make.newInstance(data,backend);
            var update=type.getDeclaredMethod("update");update.setAccessible(true);
            var bind=type.getDeclaredMethod("bind",RenderEncoder.class);bind.setAccessible(true);
            var close=type.getDeclaredMethod("close");close.setAccessible(true);
            var capture=type.getDeclaredMethod("capture",long.class);capture.setAccessible(true);
            var pointer=type.getDeclaredMethod("pointer");pointer.setAccessible(true);
            var encoder=mock(RenderEncoder.class);
            var buffer=org.mockito.ArgumentCaptor.forClass(me.cortex.voxy.client.core.gpu.IGpuBuffer.class);
            try {
                update.invoke(uniforms);bind.invoke(uniforms,encoder);
                verify(encoder).setBuffer(eq(10),buffer.capture(),eq(0L));
                if(MemoryUtil.memGetInt(((MetalBuffer)buffer.getValue()).getContentsPtr())!=1) throw new AssertionError("first uniform frame not uploaded");
                counter.set(7);update.invoke(uniforms);
                if(MemoryUtil.memGetInt(((MetalBuffer)buffer.getValue()).getContentsPtr())!=7) throw new AssertionError("uniform frame remained stale");
                reads.set(0); counter.set(11); capture.invoke(uniforms,42L);
                counter.set(99); capture.invoke(uniforms,42L);
                if(reads.get()!=1 || MemoryUtil.memGetInt((long)pointer.invoke(uniforms))!=11)
                    throw new AssertionError("material uniform capture changed within one frame");
                capture.invoke(uniforms,43L);
                if(reads.get()!=2 || MemoryUtil.memGetInt((long)pointer.invoke(uniforms))!=99)
                    throw new AssertionError("next frame uniform snapshot did not advance");
            } finally { close.invoke(uniforms);close.invoke(uniforms); }
        } finally { backend.shutdown(); }
        System.out.println("PASS: material TAA uniform updates reach a real shared Metal buffer at distinct binding 10 and close safely");
    }
}
