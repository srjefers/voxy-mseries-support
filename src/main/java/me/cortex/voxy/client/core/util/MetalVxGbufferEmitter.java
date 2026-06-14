package me.cortex.voxy.client.core.util;

/**
 * Phase C (issue #11) material g-buffer emitter. Provides the
 * {@code voxy_emitFragment} body appended to quads.frag (under
 * {@code PATCHED_SHADER + VOXY_VX_GBUFFER}) on the Metal LOD draw path, mirroring
 * the GL host's {@code patchOpaqueShader} append-last. Instead of calling the
 * pack's shader, it WRITES a 3-plane material g-buffer that the GL-side
 * {@link MetalVxResolvePass} later reads to run the pack's real
 * {@code voxy_emitFragment} as a fullscreen resolve.
 *
 * Plane layout (each an fbw×fbh BGRA8 IOSurface; only BGRA8 crosses Metal→GL):
 * <ul>
 *   <li>location 0 — {@code vxGbAlbedo} = sampledColour RGBA verbatim (atlas is
 *       RGBA8 → lossless; alpha carries water/cutout coverage).</li>
 *   <li>location 1 — {@code vxGbTint} = tinting RGBA verbatim (rgb biome tint,
 *       a = AO/shade factor BSL passes to GetLighting).</li>
 *   <li>location 2 — {@code vxGbMisc} nibble-packed:
 *       r = lightNibble.x&lt;&lt;4 | (face&amp;7)&lt;&lt;1, g = lightNibble.y&lt;&lt;4,
 *       b = customId&amp;0xFF, a = (customId&gt;&gt;8)&amp;0xFF. 16-bit customId covers
 *       BSL's max ~20499.</li>
 * </ul>
 *
 * The nibble pack {@code nib = round((lightMap*256 - 8)/16)} is the exact inverse
 * of {@code getLightmap}'s quantization {@code (n*16 + 8)/256} and of the decode in
 * {@link MetalVxResolvePass#assembleFragment} ({@code vxMr>>4}, {@code (vxMr>>1)&7},
 * {@code b | a<<8}) — so emitter↔resolve round-trip is lossless after one BGRA8
 * quantization. tile/uv/modelId are intentionally NOT exported (unused by BSL/CR).
 */
public final class MetalVxGbufferEmitter {
    private MetalVxGbufferEmitter() {}

    /** GL color format of every material plane (BGRA8 across the IOSurface). */
    public static final int PLANE_GL_FORMAT = 0x8058; // GL_RGBA8
    /** Number of material planes per LOD layer (opaque set + translucent set). */
    public static final int PLANE_COUNT = 3;

    /**
     * Appended after quads.frag (which, under PATCHED_SHADER, declares
     * VoxyFragmentParameters and the {@code voxy_emitFragment} forward decl and
     * calls it at the end of main). Defines the emitter body.
     */
    public static final String SOURCE = """

            // ---- Phase C material g-buffer emitter (VOXY_VX_GBUFFER) ----
            layout(location = 0) out vec4 vxGbAlbedo;
            layout(location = 1) out vec4 vxGbTint;
            layout(location = 2) out vec4 vxGbMisc;

            void voxy_emitFragment(VoxyFragmentParameters p) {
            #ifdef VOXY_VX_GBUFFER_DEBUG
                // Diagnostic: write the raw interData.x/y/z bytes into the 3 planes so a
                // CPU read-back reveals the actual vertex attributes (flags / lighting /
                // tintColour). interData is the flat varying from quads.frag.
                vxGbAlbedo = vec4(uvec4(interData.x, interData.x >> 8u, interData.x >> 16u, interData.x >> 24u) & 0xFFu) / 255.0;
                vxGbTint   = vec4(uvec4(interData.y, interData.y >> 8u, interData.y >> 16u, interData.y >> 24u) & 0xFFu) / 255.0;
                vxGbMisc   = vec4(uvec4(interData.z, interData.z >> 8u, interData.z >> 16u, interData.z >> 24u) & 0xFFu) / 255.0;
                return;
            #endif
                vxGbAlbedo = p.sampledColour;
                vxGbTint = p.tinting;
                // Quantize each lightmap channel back to its 16-level nibble — exact
                // inverse of getLightmap's (n*16+8)/256, decoded by MetalVxResolvePass.
                uvec2 nib = uvec2(round((p.lightMap * 256.0 - 8.0) / 16.0));
                vxGbMisc = vec4(
                    float((nib.x << 4) | ((p.face & 7u) << 1u)),
                    float(nib.y << 4),
                    float(p.customId & 255u),
                    float((p.customId >> 8) & 255u)) / 255.0;
            }
            """;
}
