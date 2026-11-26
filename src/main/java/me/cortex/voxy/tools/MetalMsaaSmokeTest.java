package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.gpu.shader.RuntimeShaderCompiler;
import me.cortex.voxy.client.core.metal.MetalLodMsaa;
import me.cortex.voxy.client.core.metal.MetalNative;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * H17 verification: the LOD pass's multisample path ({@link MetalLodMsaa}). Draws the tools triangle
 * twice with the SAME pipeline, once in a normal pass and once through {@code MetalLodMsaa.begin}
 * (memoryless 4x colour + depth resolved into the target), then compares the read-backs:
 * <ul>
 *   <li>the interior must match (MSAA changes coverage, not shading);</li>
 *   <li>some edge pixels must differ, and be blends (strictly between the 1x pixel and the clear);</li>
 *   <li>far from the edges nothing may change.</li>
 * </ul>
 * Run with {@code ./gradlew testMetalMsaa}.
 */
public final class MetalMsaaSmokeTest {

    private MetalMsaaSmokeTest() {}

    private static final float CR = 0.1f, CG = 0.1f, CB = 0.15f;

    public static void main(String[] args) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!(os.contains("mac") && arch.contains("aarch64"))) {
            System.err.println("MSAA smoke test requires macOS aarch64 (got " + os + "/" + arch + ")");
            System.exit(2);
        }
        if (!MetalNative.load()) {
            System.err.println("Metal native library failed to load");
            System.exit(2);
        }
        if (MetalLodMsaa.SAMPLES <= 1) {
            System.err.println("VOXY_LOD_MSAA turns MSAA off; unset it to run this test");
            System.exit(2);
        }

        Path shadersRoot = Path.of("src/main/resources/assets/voxy/shaders/tools").toAbsolutePath();
        String vertGlsl = Files.readString(shadersRoot.resolve("triangle.vert"), StandardCharsets.UTF_8);
        String fragGlsl = Files.readString(shadersRoot.resolve("triangle.frag"), StandardCharsets.UTF_8);
        var vert = RuntimeShaderCompiler.compile(vertGlsl, RuntimeShaderCompiler.Stage.VERTEX, Map.of(),
                RuntimeShaderCompiler.Target.METAL_MSL);
        var frag = RuntimeShaderCompiler.compile(fragGlsl, RuntimeShaderCompiler.Stage.FRAGMENT, Map.of(),
                RuntimeShaderCompiler.Target.METAL_MSL);

        MetalRenderBackend backend = new MetalRenderBackend();
        IGpuPipeline pipeline = null;
        MetalLodMsaa msaa = new MetalLodMsaa();
        try {
            final int GL_RGBA8 = 0x8058;
            final int GL_TEXTURE_2D = 0x0DE1;
            final int GL_DEPTH_COMPONENT32F = 0x8CAC;
            final int W = 256, H = 256;

            IGpuTexture plain = backend.createTexture(GL_TEXTURE_2D);
            plain.store(GL_RGBA8, 1, W, H);
            IGpuTexture resolved = backend.createTexture(GL_TEXTURE_2D);
            resolved.store(GL_RGBA8, 1, W, H);
            IGpuTexture depth = backend.createTexture(GL_TEXTURE_2D);
            depth.store(GL_DEPTH_COMPONENT32F, 1, W, H);

            pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    vert.mslSource(), frag.mslSource(), vert.spirv(), frag.spirv(),
                    GL_RGBA8, "voxy:tools/triangle"));

            // 1x: the normal pass, same attachments shape as the LOD pass (colour + depth).
            try (RenderEncoder enc = backend.beginRenderPass(RenderPassDesc.builder(W, H)
                    .clearColor(plain, CR, CG, CB, 1.0f).clearDepth(depth, 1.0f).build())) {
                enc.setPipeline(pipeline);
                enc.draw(RenderEncoder.PRIMITIVE_TRIANGLES, 0, 3, 1, 0);
            }
            backend.submit();

            // 4x through the production entry point; the same pipeline object binds its variant.
            RenderEncoder msEnc = msaa.begin(backend, RenderPassDesc.builder(W, H)
                    .clearColor(resolved, CR, CG, CB, 1.0f).clearDepth(depth, 1.0f).build());
            if (msEnc == null) throw new RuntimeException("MetalLodMsaa.begin returned null (MSAA refused)");
            try (RenderEncoder enc = msEnc) {
                enc.setPipeline(pipeline);
                enc.draw(RenderEncoder.PRIMITIVE_TRIANGLES, 0, 3, 1, 0);
            }
            backend.submit();

            byte[] a = backend.readPixelsRGBA8(plain, 0, 0, W, H);
            byte[] b = backend.readPixelsRGBA8(resolved, 0, 0, W, H);
            int clear = rgb(Math.round(CR * 255), Math.round(CG * 255), Math.round(CB * 255));

            int differ = 0, blends = 0, interiorDiff = 0, drawn1x = 0, drawn4x = 0;
            for (int y = 1; y < H - 1; y++) {
                for (int x = 1; x < W - 1; x++) {
                    int pa = px(a, W, x, y), pb = px(b, W, x, y);
                    if (pa != clear) drawn1x++;
                    if (pb != clear) drawn4x++;
                    boolean edge = false;   // a 1x pixel next to a different-coverage 1x pixel
                    for (int k = 0; k < 4 && !edge; k++) {
                        int q = px(a, W, x + (k == 0 ? 1 : k == 1 ? -1 : 0), y + (k == 2 ? 1 : k == 3 ? -1 : 0));
                        edge = (q == clear) != (pa == clear);
                    }
                    if (dist(pa, pb) > 6) {
                        differ++;
                        if (!edge) interiorDiff++;
                        int shaded = pa != clear ? pa : neighbourDrawn(a, W, x, y, clear);
                        if (shaded != -1 && between(pb, clear, shaded)) blends++;
                    }
                }
            }
            System.out.printf("1x drawn px %d, 4x drawn px %d%n", drawn1x, drawn4x);
            System.out.printf("pixels that differ 1x vs 4x: %d (blends between clear and triangle: %d, away from edges: %d)%n",
                    differ, blends, interiorDiff);

            if (drawn1x < 1000) throw new RuntimeException("1x triangle missing");
            if (differ < 50) throw new RuntimeException("4x looks identical to 1x: MSAA did not resolve");
            if (blends < differ / 2) throw new RuntimeException("4x edge pixels are not blends");
            if (interiorDiff > differ / 10) throw new RuntimeException("4x changed pixels away from the edges");
            if (Math.abs(drawn4x - drawn1x) > drawn1x / 10) throw new RuntimeException("4x coverage far from 1x");

            System.out.println();
            System.out.println("MSAA SMOKE OK — " + msaa.samples() + "x pass (asked " + MetalLodMsaa.SAMPLES
                    + ") resolved, edges antialiased, interior unchanged");
        } finally {
            msaa.close();
            if (pipeline != null) pipeline.close();
            backend.shutdown();
        }
    }

    private static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }

    private static int px(byte[] p, int w, int x, int y) {
        int o = (y * w + x) * 4;
        return rgb(p[o] & 0xFF, p[o + 1] & 0xFF, p[o + 2] & 0xFF);
    }

    private static int neighbourDrawn(byte[] p, int w, int x, int y, int clear) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int q = px(p, w, x + dx, y + dy);
            if (q != clear) return q;
        }
        return -1;
    }

    private static int dist(int a, int b) {
        int d = 0;
        for (int s = 0; s < 24; s += 8) d = Math.max(d, Math.abs(((a >> s) & 0xFF) - ((b >> s) & 0xFF)));
        return d;
    }

    /** Each channel of {@code v} within [min, max] of the two ends (+-6), i.e. a blend of them. */
    private static boolean between(int v, int e0, int e1) {
        for (int s = 0; s < 24; s += 8) {
            int c = (v >> s) & 0xFF, a = (e0 >> s) & 0xFF, b = (e1 >> s) & 0xFF;
            if (c < Math.min(a, b) - 6 || c > Math.max(a, b) + 6) return false;
        }
        return true;
    }
}
