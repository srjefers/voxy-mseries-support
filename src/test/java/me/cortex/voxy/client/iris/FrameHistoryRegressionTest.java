package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;

/** Exercises the matrix suppliers actually registered with Iris, not a duplicate history algorithm. */
public final class FrameHistoryRegressionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var client=mock(Minecraft.class);
        var level=mock(LevelRenderer.class,withSettings().extraInterfaces(IGetVoxyRenderSystem.class));
        var field=Minecraft.class.getDeclaredField("levelRenderer"); field.setAccessible(true); field.set(client,level);
        var renderer=mock(VoxyRenderSystem.class);
        when(((IGetVoxyRenderSystem)level).getVoxyRenderSystem()).thenReturn(renderer);
        var viewport=mock(Viewport.class);
        when(renderer.getViewport()).thenReturn(viewport);
        Object history=null;
        try {
            var getter=VoxyRenderSystem.class.getMethod("getFrameTransforms");
            history=getter.getReturnType().getConstructor().newInstance();
            when(getter.invoke(renderer)).thenReturn(history);
        } catch (NoSuchMethodException precedingImplementation) { }
        var holder=mock(UniformHolder.class,RETURNS_SELF);
        Map<String,Supplier<Matrix4fc>> matrices=new HashMap<>();
        doAnswer(call->{ matrices.put(call.getArgument(1),call.getArgument(2)); return holder; })
                .when(holder).uniformMatrix(any(),anyString(),any());
        Object generation=new Object();
        try (var singleton=mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(client);
            VoxyUniforms.addUniforms(holder);
            var first=new Matrix4f().translation(2,3,4).rotateY(.2f);
            prepare(history,viewport,generation,1,1280,720,false,first);
            // Prime old suppliers, then observe two consumers within the same next frame.
            matrices.get("vxModelViewPrev").get();
            var second=new Matrix4f().translation(7,8,9).rotateY(.4f);
            prepare(history,viewport,generation,2,1280,720,false,second);
            Matrix4f a=new Matrix4f(matrices.get("vxModelViewPrev").get());
            Matrix4f b=new Matrix4f(matrices.get("vxModelViewPrev").get());
            equal(first,a,"previous frame must be first frame");
            equal(a,b,"repeated previous-frame reads changed history");
            // All consumers and inverse uniforms share the same canonical frame.
            equal(second,matrices.get("vxModelView").get(),"current frame mismatch");
            equal(new Matrix4f(second).invert(),matrices.get("vxModelViewInv").get(),"inverse mismatch");
            prepare(history,viewport,generation,2,1280,720,false,new Matrix4f().translation(100,0,0));
            equal(second,matrices.get("vxModelView").get(),"second preparation advanced same frame");
            prepare(history,viewport,generation,3,1280,720,true,new Matrix4f().translation(-50,0,0));
            equal(first,matrices.get("vxModelViewPrev").get(),"shadow reentry advanced main history");
            prepare(history,viewport,generation,3,1920,1080,false,second);
            equal(second,matrices.get("vxModelViewPrev").get(),"resize did not reset history");
            prepare(history,viewport,new Object(),1,1920,1080,false,first);
            equal(first,matrices.get("vxModelViewPrev").get(),"renderer replacement retained stale history");
            Matrix4f exposed=(Matrix4f)matrices.get("vxModelViewPrev").get(); exposed.identity();
            equal(first,matrices.get("vxModelViewPrev").get(),"consumer mutated stored history");
        }
        System.out.println("PASS: actual Iris suppliers preserve frame history across reads, shadows, resize and generations");
    }
    private static void prepare(Object history,Viewport<?> viewport,Object generation,long frame,int width,int height,boolean shadow,Matrix4f modelView) throws Exception {
        if(history!=null) {
            history.getClass().getMethod("advance",Object.class,long.class,int.class,int.class,boolean.class,Matrix4fc.class,Matrix4fc.class)
                    .invoke(history,generation,frame,width,height,shadow,new Matrix4f().perspective(1,16f/9f,16,48000),modelView);
        } else { viewport.modelView=new Matrix4f(modelView); viewport.projection=new Matrix4f(); }
    }
    private static void equal(Matrix4fc expected,Matrix4fc actual,String message) {
        if(!expected.equals(actual,1e-5f)) throw new AssertionError(message);
    }
}
