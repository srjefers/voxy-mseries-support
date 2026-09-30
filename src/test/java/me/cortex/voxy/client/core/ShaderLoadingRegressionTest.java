package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;

public final class ShaderLoadingRegressionTest {
    public static void main(String[] args) {
        String result=ShaderLoader.parse("voxy:loader_regression/root.glsl");
        if(result.lines().filter(line->line.startsWith("#version")).count()!=1) throw new AssertionError("nested shader versions were not stripped: "+result);
        if(result.contains("#import") || !result.contains("void child() {}") || !result.contains("void main() { child(); }")) throw new AssertionError("includes were not expanded");
        String traversal=ShaderLoader.parseAndStripPrintf("voxy:lod/hierarchical/traversal_dev.comp");
        if(traversal.lines().anyMatch(line->line.stripLeading().startsWith("printf("))) throw new AssertionError("Metal printf stripping was lost");
        System.out.println("PASS: nested shader includes emit one version; production traversal retains Metal printf compatibility");
    }
}
