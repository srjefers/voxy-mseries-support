#version 460 core

// Proxy geometry for the LOD cloud layer: one horizontal quad on the face of the
// cloud slab the camera looks through (top face from above, bottom face from
// below). Every ray that can hit a cloud cell crosses it first; the fragment
// shader marches the cells from there. 4 vertices, triangle strip, no vertex
// buffer.

#import <voxy:clouds/lod_clouds_params.glsl>

layout(location = 0) out vec3 entryPos;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    entryPos = vec3(corner.x * slab.z, slab.x, corner.y * slab.z);
    gl_Position = MVP * vec4(entryPos, 1.0);
}
