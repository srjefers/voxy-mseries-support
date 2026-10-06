#version 430
layout(binding=0) uniform sampler2D prelitLayer;
#ifdef TWO_LAYERS
layout(binding=1) uniform sampler2D surfaceDepth;
layout(binding=2) uniform sampler2D fluidLayer;
layout(binding=3) uniform sampler2D fluidDepth;
#endif
layout(location=0) out vec4 colour;
void main() {
    // All inputs are Metal attachments with identical coordinates; no GL bridge flip.
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    colour = texelFetch(prelitLayer, pixel, 0);
#ifdef TWO_LAYERS
    vec4 fluid = texelFetch(fluidLayer, pixel, 0);
    float surfaceZ = texelFetch(surfaceDepth, pixel, 0).r;
    float fluidZ = texelFetch(fluidDepth, pixel, 0).r;
    bool surfaceIsNear = surfaceZ <= fluidZ;
    vec4 nearLayer = surfaceIsNear ? colour : fluid;
    vec4 farLayer = surfaceIsNear ? fluid : colour;
    float alpha = nearLayer.a + farLayer.a * (1.0 - nearLayer.a);
    if (alpha <= 0.0) discard; // Preserve opaque color and depth when neither layer owns this pixel.
    gl_FragDepth = min(surfaceZ, fluidZ);
    vec3 premultiplied = nearLayer.rgb * nearLayer.a
                      + farLayer.rgb * farLayer.a * (1.0 - nearLayer.a);
    // Fixed-function blending applies the combined alpha to the opaque destination once.
    colour = vec4(premultiplied / alpha, alpha);
#endif
}
