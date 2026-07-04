#version 460 core
// M9 Phase 2 patch: bumped from 430 to 460 so gl_BaseInstance is a core
// built-in. shaderc/glslang's Vulkan profile rejects
// GL_ARB_shader_draw_parameters as an extension at earlier versions.
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif

// M13 2026-05-14 workaround: Metal's drawIndexedPrimitives:indirectBuffer:
// does NOT propagate the indirect args' baseInstance to [[base_instance]]
// in the vertex function. Diagnosed via grid + biOfs tests — gl_BaseInstance
// and gl_InstanceID both read 0 on Apple Silicon for this draw call form.
// Workaround: the Metal encoder pushes the per-draw `cmd.baseInstance` value
// as inline constant bytes at buffer index 6 via setVertexBytes before each
// drawIndexedPrimitives:indirectBuffer:. The shader reads it from this UBO
// (gated by VOXY_METAL_BI_FIX which MDIC injects on non-GL backends).
#ifdef VOXY_METAL_BI_FIX
layout(binding = 6, std140) uniform VoxyMetalPerDrawUBO {
    uint voxyMetalDrawIndex;
};
#endif

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

// M13 chunk 5: Per-vertex world-space distance from the camera, interpolated
// linearly across the quad. Used by quads.frag's USE_ENV_FOG path to mix in
// the environmental fog colour at far LOD distances. The vertex's basePoint
// + corner offset already share the same section-relative origin as
// cameraSubPos (see setupQuad in quad_util.glsl — both are post
// `- baseSectionPos<<5`), so `length(cornerPoint - cameraSubPos)` is the
// real world-space distance without needing to round-trip through the MVP.
// 2026-07-03: voxyFogDist was gated on USE_ENV_FOG only, but the vx contract
// forces useEnvFog() OFF (NormalRenderPipeline:66-68) and configs can turn
// env fog off — which silently compiled OUT the distance-mip sampling and
// the far-water alpha ramp in every Iris/BSL session (the [Metal-LODTEST]
// "ON" logs only reflect define injection, not effective compilation). Any
// consumer define now pulls the varying in.
#if defined(USE_ENV_FOG) || defined(VOXY_LOD_DIST_MIP) || defined(VOXY_WATER_FAR_ALPHA) || defined(VOXY_TRANS_NEAR_CULL)
#define VOXY_NEEDS_FOG_DIST
#endif
#ifdef VOXY_NEEDS_FOG_DIST
layout(location = 2) out float voxyFogDist;
#endif
// 2026-07-03 round 3: the near-cull compared voxyFogDist — a 3D SLANT
// distance — against a horizontal threshold, while MC renders a horizontal
// square of chunks. From a high camera (or toward the square's diagonals)
// LOD water inside the MC ring survived the cull and double-composited with
// BSL/Sodium water, gated only by the per-frame-flipping chunk-bound mask:
// the flickering pale section-aligned squares. Pass the camera-relative
// horizontal offset instead (linear in world space, so it interpolates
// exactly across merged quads; a per-vertex max(|dx|,|dz|) would overestimate
// mid-quad wherever a long quad crosses the camera axis) and let the
// fragment shader take the Chebyshev distance that mirrors MC's square.
#if defined(VOXY_TRANS_NEAR_CULL) && defined(VOXY_TRANS_NEAR_CULL_XZ)
layout(location = 3) out vec2 voxyCamRelXZ;
#endif

vec2 taaShift();

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

    QuadData quad;
#ifdef VOXY_METAL_BI_FIX
    // Replace gl_BaseInstance with the per-draw value pushed via Metal's
    // setVertexBytes (see workaround note above the UBO declaration).
    uint baseInstanceFix = voxyMetalDrawIndex;
#else
    uint baseInstanceFix = uint(gl_BaseInstance);
#endif
    setupQuad(quad, quadData[uint(gl_VertexID)>>2], positionBuffer[baseInstanceFix], (gl_VertexID&3) == 1);

    uint cornerId = gl_VertexID&3;
    gl_Position = getQuadCornerPos(quad, cornerId);

#ifdef VOXY_WATER_DEPTH_BIAS
    // Translucent LOD water only (this define is injected into the translucent
    // pipeline's vertex defines): pull the water slightly toward the camera in
    // clip space so it reliably wins the depth test against the coincident
    // opaque seafloor/terrain LOD directly below it. Fixes "holes that show the
    // seafloor through distant LOD water" (z-fighting at low far-depth
    // precision). Bias is scaled by w so it is a roughly constant NDC-z offset;
    // small enough not to punch through genuinely-closer terrain in front.
    gl_Position.z -= (VOXY_WATER_DEPTH_BIAS) * gl_Position.w;
#endif

    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;

    #ifdef VOXY_NEEDS_FOG_DIST
    // Reconstruct the corner's world-relative point in the same way
    // getQuadCornerPos does (kept inline rather than refactoring quad_util
    // to avoid touching the GL path's hot vertex code). cameraSubPos comes
    // from the SceneUniform SSBO declared above; both points share the
    // baseSectionPos-anchored frame.
    vec2 cornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vec3 cornerPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*cornerMask, 0));
    voxyFogDist = length(cornerPoint - cameraSubPos);
    #if defined(VOXY_TRANS_NEAR_CULL) && defined(VOXY_TRANS_NEAR_CULL_XZ)
    voxyCamRelXZ = cornerPoint.xz - cameraSubPos.xz;
    #endif
    #endif

    #ifdef DEBUG_RENDER
    quadDebug = uint(gl_VertexID)>>(2+5);
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif