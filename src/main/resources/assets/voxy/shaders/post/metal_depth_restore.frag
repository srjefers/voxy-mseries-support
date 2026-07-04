#version 460

// Phase D (issue #11): seed the translucent LOD pass's depth attachment
// with the opaque pass's depth so water hidden behind terrain still
// depth-tests away. Reads the raw floats the opaque depth blit produced
// (same buffer the depth export consumes — buffer reads are format-blind,
// depth textures can't be sampled on Metal; see round 18). WIDTH is a
// compile-time define; rows are top-left-origin matching gl_FragCoord.
layout(std430, binding = 0) readonly restrict buffer DepthData {
    float depths[];
};

void main() {
    gl_FragDepth = depths[uint(gl_FragCoord.y) * WIDTH + uint(gl_FragCoord.x)];
}
