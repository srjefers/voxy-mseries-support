package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.iris.UniformRegressionTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Establishes exact legacy assembly before moving BSL policy out of the generic resolve. */
public final class BslAssemblyRegressionTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("voxy.bslCompatibility","true");
        var constructor=IrisVoxyRenderPipelineData.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var data=(IrisVoxyRenderPipelineData)constructor.newInstance(UniformRegressionTest.patch(),new int[]{0},new int[]{0},null,null,new IrisVoxyRenderPipelineData.ImageSet("",ignored->{},List.of()),null);
        for(String field:new String[]{"opaquePatch","translucentPatch"}) {
            var value=IrisVoxyRenderPipelineData.class.getDeclaredField(field);value.setAccessible(true);
            value.set(data,"void voxy_emitFragment(VoxyFragmentParameters parameters) { uint face = parameters.face & 1; }\n");
        }
        Path root=Path.of("src/test/resources/fixtures/bsl-assembly");Files.createDirectories(root);
        for(boolean translucent:new boolean[]{false,true}) {
            String source=MetalVxResolvePass.assembleFragment(data,translucent);
            Path fixture=root.resolve(translucent?"translucent.glsl":"opaque.glsl");
            if(Files.exists(fixture)) {
                if(!source.equals(Files.readString(fixture))) throw new AssertionError("legacy BSL assembly changed in extraction");
            } else Files.writeString(fixture,source);
        }
        System.out.println("PASS: explicit BSL assembly matches the pre-extraction opaque/translucent fixtures");
    }
}
