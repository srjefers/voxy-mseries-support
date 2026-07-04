#version 460

// Metal LOD depth export (Iris gbuffer injection), buffer-read edition.
// Sampling the depth attachment directly is NOT possible: SPIRV-Cross
// transpiles plain sampler2D to texture2d<float>, but Metal requires
// depth-format textures to be declared depth2d — the mismatch silently
// reads ZEROS (verified in the cached MSL after the on-device
// full-screen-red debug result; it also means quads.frag's depth-bound
// texelFetch never actually read depth). So the depth attachment is first
// blitted into a plain MTLBuffer (D32F→buffer blits are format-legal) and
// read here as raw floats — buffer reads are format-blind. WIDTH is a
// compile-time define (pipeline rebuilt per width; resize is rare). The
// blit stores rows in Metal's top-left-origin texel order and gl_FragCoord
// shares that origin, so output texel (x,y) is exactly depth texel (x,y) —
// no flip.
layout(std430, binding = 0) readonly buffer DepthData {
    float depths[];
};

layout(location = 0) out vec4 outDepth;

void main() {
    float d = depths[int(gl_FragCoord.y) * WIDTH + int(gl_FragCoord.x)];
    // 24-bit RGB pack (classic EncodeFloatRGB): Apple GL samples zeros from
    // R32F IOSurfaces, so depth crosses the bridge as packed bytes in the
    // proven BGRA8 format. Decode GL-side: dot(rgb, vec3(1, 1/255, 1/65025)).
    d = clamp(d, 0.0, 1.0 - 1.0e-7);
    vec3 enc = fract(vec3(1.0, 255.0, 65025.0) * d);
    enc -= enc.yzz * vec3(1.0 / 255.0, 1.0 / 255.0, 0.0);
    outDepth = vec4(enc, 1.0);
}
