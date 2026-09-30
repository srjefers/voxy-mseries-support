// Paired with ModelTintData: 2048 bytes/model, 340 bytes/face, 16/8/4/2 mips.
layout(binding=11, std430) readonly buffer ModelTintWeights { uint tintWords[]; };

float sampleTintMip(uint model, uint face, vec2 uv, uint level) {
    uint size = 16u >> level;
    uvec2 texel = uvec2(clamp(uv, vec2(0.0), vec2(0.999999)) * float(size));
    uint offsets[4] = uint[4](0u, 256u, 320u, 336u);
    uint address = model * 2048u + face * 340u + offsets[level] + texel.y * size + texel.x;
    return float((tintWords[address >> 2u] >> ((address & 3u) * 8u)) & 255u) / 255.0;
}

float sampleModelTint(uint model, uint face, vec2 uv, float lod) {
    float level = clamp(lod, 0.0, 3.0);
    uint lower = uint(floor(level));
    return mix(sampleTintMip(model, face, uv, lower), sampleTintMip(model, face, uv, min(lower+1u, 3u)), fract(level));
}
