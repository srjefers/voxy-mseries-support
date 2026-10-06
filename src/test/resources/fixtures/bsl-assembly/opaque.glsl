#version 410 core

#define texture2D texture
#define texture3D texture
#define texture2DLod textureLod
#define shadow2D texture

uniform sampler2DRect uVxAlbedo;
uniform sampler2DRect uVxTint;
uniform sampler2DRect uVxMisc;
uniform sampler2DRect uVxDepth;
uniform int uVxDepthIsWindow;
uniform float uVxRingCull;

struct VoxyFragmentParameters {
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;
};

void voxy_emitFragment(VoxyFragmentParameters parameters);

vec4 vx_fragCoord;

void main() {
    ivec2 vxSz = textureSize(uVxDepth);
    vec2 vxTexel = vec2(gl_FragCoord.x, float(vxSz.y) - gl_FragCoord.y);
    vec3 vxDEnc = texture(uVxDepth, vxTexel).rgb;
    float vxD = dot(vxDEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
    if (vxD <= 0.0 || vxD >= 0.9999999) discard;
    vec4 vxAlbedo = texture(uVxAlbedo, vxTexel);
    if (vxAlbedo.a <= 0.001) discard;
    vec4 vxTint = texture(uVxTint, vxTexel);
    vec4 vxMisc = texture(uVxMisc, vxTexel) * 255.0;
    uint vxMr = uint(vxMisc.r + 0.5);
    uint vxMg = uint(vxMisc.g + 0.5);
    uint vxFace = (vxMr >> 1u) & 7u;
    vec2 vxLight = vec2(
        (float(vxMr >> 4u) * 16.0 + 8.0) / 256.0,
        (float(vxMg >> 4u) * 16.0 + 8.0) / 256.0);
    uint vxCustomId = uint(vxMisc.b + 0.5) | (uint(vxMisc.a + 0.5) << 8u);
    vxLight.y = max(vxLight.y, 0.80000);
if ((vxCustomId / 100u) == 200u || (vxCustomId / 100u) == 204u) vxLight.y = max(vxLight.y, 0.96875);
float vxWz = (uVxDepthIsWindow == 1) ? vxD : vxD * 0.5 + 0.5;
    gl_FragDepth = vxWz;
    vx_fragCoord = vec4(gl_FragCoord.xy, vxWz, 1.0);
    voxy_emitFragment(VoxyFragmentParameters(
        vxAlbedo, vec2(0.0), vec2(0.0), vxFace, 0u, vxLight, vxTint, vxCustomId));
}

#define gl_FragCoord vx_fragCoord

void voxy_emitFragment(VoxyFragmentParameters parameters) { uint face = parameters.face & 1u; }

