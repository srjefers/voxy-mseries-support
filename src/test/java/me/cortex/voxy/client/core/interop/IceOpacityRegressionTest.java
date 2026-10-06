package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.*;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;
import java.util.Map;
import static org.lwjgl.opengl.GL33C.*;

/** Executes the production far-opacity fragment block with non-fluid and fluid inputs. */
public final class IceOpacityRegressionTest {
    public static void main(String[] args) {
        Logger.SHUTUP = true;
        String source = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
        String marker = "#if defined(TRANSLUCENT) && defined(VOXY_WATER_FAR_ALPHA)";
        int begin = source.indexOf(marker), end = source.indexOf("#endif", begin);
        if (begin < 0 || end < begin) throw new AssertionError("production opacity block missing");
        String block = source.substring(begin,end+6);
        String attributes = ShaderLoader.parse("voxy:lod/quad_util.glsl");
        int functionStart = attributes.indexOf("uvec3 makeRemainingAttributes("), braces = 0, functionEnd = -1;
        for (int i=attributes.indexOf('{',functionStart); i<attributes.length(); i++) {
            if (attributes.charAt(i)=='{') braces++;
            if (attributes.charAt(i)=='}' && --braces==0) { functionEnd=i+1; break; }
        }
        if (functionStart < 0 || functionEnd < 0) throw new AssertionError("production attribute producer missing");
        String producer = "struct BlockModel{uint flagsA;uint colourTint;}; struct Quad{uint data;};\n"
                + "uint extractLightId(Quad q){return 0u;} uint extractBiomeId(Quad q){return 0u;}\n"
                + "bool modelHasBiomeLUT(BlockModel m){return false;} uint colourData[1];\n"
                + attributes.substring(functionStart,functionEnd);
        var backend = new MetalRenderBackend();
        var target = (MetalTexture)backend.createTexture(GL_TEXTURE_2D);
        long result = MemoryUtil.nmemAllocChecked(4*4*4);
        int failures = 0;
        try {
            target.storeRenderTargetUploadable(GL_RGBA8,1,4,4);
            for (boolean fluid : new boolean[]{false,true}) {
                String fragment = "#version 430\nlayout(location=0) out vec4 outColour;\n"+producer+"\nvoid main(){\n"
                        + "BlockModel model=BlockModel("+(fluid?16:0)+"u,0xffffffffu);\n"
                        + "uvec3 attributes=makeRemainingAttributes(model,Quad(0u),3u,1u);\n"
                        + "uvec4 interData=uvec4(0,0,0,attributes.z);\n"
                        + "vec4 voxyLodParams=vec4(0,100,.01,.95); float voxyFogDist=300;\n"
                        + "outColour=vec4(.2,.4,.6,.5);\n"+block+"\n}";
                String vertex = "#version 430\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,.5,1);}";
                var desc = new GraphicsPipelineDesc(vertex,fragment,Map.of("TRANSLUCENT","","VOXY_WATER_FAR_ALPHA","","PATCHED_SHADER","","VOXY_LOD_DIST_MIP",""),
                        null,null,null,null,GL_RGBA8,VertexLayout.EMPTY,PipelineState.DEFAULT,"Ice opacity fixture");
                try (var pipeline=backend.createGraphicsPipeline(desc)) {
                    try (var encoder=backend.beginRenderPass(RenderPassDesc.builder(4,4).clearColor(target,0,0,0,0).build())) {
                        encoder.setPipeline(pipeline);encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLES,0,3,1,0);
                    }
                    backend.submit();target.getBytes(0,0,0,4,4,result);
                    int alpha=MemoryUtil.memGetInt(result+(2*4+2)*4L)>>>24;
                    int expected=fluid?242:128;
                    if(Math.abs(alpha-expected)>1){failures++;System.err.println("FAIL: fluid="+fluid+" expected alpha="+expected+" observed="+alpha);}
                    else System.out.println("PASS: production opacity fluid="+fluid+" alpha="+alpha);
                }
            }
        } finally {target.free();backend.shutdown();MemoryUtil.nmemFree(result);}
        if(failures!=0)throw new AssertionError(failures+" ice opacity regressions failed");
    }
}
