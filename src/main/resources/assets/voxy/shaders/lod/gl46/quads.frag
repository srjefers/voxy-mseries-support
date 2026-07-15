#version 460 core
//Use quad shuffling to compute fragment mip
//#extension GL_KHR_shader_subgroup_quad: enable
// M9 Phase 2 patch: bumped to #version 460 so gl_HelperInvocation is a
// core built-in. shaderc/glslang's Vulkan profile doesn't accept
// GL_ARB_shader_helper_invocation as an extension; 4.50+ has it built-in.
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(binding = 2) uniform sampler2D depthTex;

#ifdef VOXY_METAL_BOUND_SSBO
// Metal-only (round 20): the chunk-bound mask arrives as raw floats in a
// plain buffer, NOT via depthTex — sampling a depth-format texture through
// the texture2d<float> declaration SPIRV-Cross emits for sampler2D silently
// reads ZEROS on Metal (same bug class as the round-18 Iris depth export),
// which left this mask inert since M13 chunk 3. ChunkBoundRenderer blits
// the bound depth into this buffer after the bound pass; width rides in
// the header so no pipeline rebuild is needed on resize.
// Binding 9: 0-5 are the terrain draw's buffer table, 6 is quads3.vert's
// per-draw UBO (Metal setVertexBytes slot — setBuffer would clobber it),
// 7/8 belong to the cmdgen compute defines.
layout(binding = 9, std430) readonly restrict buffer BoundDepthBuffer {
    uint boundWidth;
    uint _boundPad1;
    uint _boundPad2;
    uint _boundPad3;
    float boundDepths[];
};
#endif

//#define DEBUG_RENDER

//TODO: need to fix when merged quads have discardAlpha set to false but they span multiple tiles
// however they are not a full block

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif

// M13 chunk 5: per-vertex world distance to camera, interpolated.
// 2026-07-03: decoupled from USE_ENV_FOG — the vx contract forces env fog
// off, which silently compiled out the distance-mip + far-water-alpha
// features in every Iris/BSL session. Must mirror quads3.vert's guard.
#if defined(USE_ENV_FOG) || defined(VOXY_LOD_DIST_MIP) || defined(VOXY_WATER_FAR_ALPHA) || defined(VOXY_TRANS_NEAR_CULL)
#define VOXY_NEEDS_FOG_DIST
#endif
#ifdef VOXY_NEEDS_FOG_DIST
layout(location = 2) in float voxyFogDist;
#endif
// Camera-relative horizontal offset for the near-cull's Chebyshev distance
// (see quads3.vert — the slant-distance cull leaked LOD water inside the MC
// square from high/diagonal viewpoints). Must mirror quads3.vert's guard.
#if defined(VOXY_TRANS_NEAR_CULL) && defined(VOXY_TRANS_NEAR_CULL_XZ)
layout(location = 3) in vec2 voxyCamRelXZ;
#endif

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

//Bind the model buffer and import the model system as we need it
#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>

#endif

#import <voxy:lod/gl46/bindings.glsl>

vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

//bool useMipmaps() {
//    return (interData.x&2u)==0u;
//}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}

#ifdef PATCHED_SHADER
vec2 getLightmap() {
    return clamp(vec2((interData.y>>4)&0xFu, interData.y&0xFu)/15, vec2(8.0f/256), vec2(248.0f/256));
}
#endif

uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    //TODO: pass in derivative data
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else

vec4 computeColour(vec2 texturePos, vec4 colour) {
    //Conditional tinting, TODO: FIXME: this is better but still not great, try encode data into the top bit of alpha so its per pixel

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


void main() {
#if defined(VOXY_BOUND_DEBUG) && defined(PATCHED_SHADER)
    // See the bound-mask block below: PATCHED variants paint the debug red
    // at the emit site via this flag (no outColour exists to write here).
    bool voxyBoundDebugHit = false;
#endif
#if defined(TRANSLUCENT) && !defined(PATCHED_SHADER)
    #ifdef VOXY_FLAT_WATER
    // Escape hatch (VOXY_LOD_FLAT_WATER=1): the 2026-05-26 interim flat ocean
    // blue, fog-faded like the opaque terrain. The DEFAULT is now the real
    // translucent path below (atlas sample + biome tint + blend) — the old
    // gate was bare TRANSLUCENT, which is injected for EVERY backend in
    // MDICSectionRenderer (~:240), so the flat colour also hijacked plain-GL
    // runs. VOXY_FLAT_WATER is only injected inside the non-GL guard.
    vec3 waterColour = vec3(0.15, 0.42, 0.72);
    #ifdef USE_ENV_FOG
    if (voxyFogColour.a > 0.0) {
        float fogLerp = clamp(fma(voxyFogDist, voxyFogEndParams.x, voxyFogEndParams.y),
                              0.0, voxyFogEndParams.z);
        waterColour = mix(waterColour, voxyFogColour.rgb, fogLerp * voxyFogColour.a);
    }
    #endif
    outColour = vec4(waterColour, 1.0);
    return;
    #endif
#endif

#if defined(TRANSLUCENT) && defined(VOXY_TRANS_NEAR_CULL)
    // vx contract (2026-07-03): BSL composites the injected LOD water
    // (colortex16, via deferred1's nearer-than-scene gate) and ALSO draws
    // MC's own water inside the render distance. LOD water surviving the
    // chunk-bound depth mask there (the mask compare flips with camera
    // pitch at grazing angles) double-blends with Sodium/BSL water into a
    // pale higher-opacity veil — the section-aligned "white squares" on
    // near/mid water. Hard-cull translucent LOD fragments inside the MC
    // ring; voxyLodParams2.x = renderDistanceBlocks - margin (0 disables).
    // Injected only when the vx contract is active; VOXY_TRANS_NEAR_CULL=0
    // is the kill switch.
    //
    // 2026-07-03 round 3: compare in the metric MC actually renders in.
    // voxyFogDist is a 3D slant distance, so from a high camera (or toward
    // the render square's diagonals, up to RD*sqrt(2)) LOD water INSIDE the
    // MC square passed the `< threshold` test's complement and survived,
    // double-compositing with BSL/Sodium water wherever the chunk-bound
    // mask misfired (its compare flips per frame -> the flickering pale
    // 16-block squares). Horizontal Chebyshev distance max(|dx|,|dz|)
    // mirrors the loaded-chunk square at every altitude and diagonal.
    // VOXY_TRANS_NEAR_CULL_XZ=0 restores the slant metric.
    //
    // 2026-07-03 round 5: Sodium 0.8.1 section culling is a Euclidean XZ
    // CYLINDER (fx*fx+fz*fz <= r*r, OcclusionCuller), NOT a square — the
    // Chebyshev cull left a no-water ring toward the render square's
    // diagonals (Euclid RD .. RD*sqrt(2)): MC water already absent there,
    // LOD water still discarded -> the naked kelp/seafloor band above a
    // sawtooth waterline. Cull in the metric Sodium actually renders in.
    // VOXY_TRANS_NEAR_CULL_RADIAL=0 falls back to the Chebyshev square.
#if defined(VOXY_TRANS_NEAR_CULL_XZ) && defined(VOXY_TRANS_NEAR_CULL_RADIAL)
    float voxyNearCullDist = length(voxyCamRelXZ);
#elif defined(VOXY_TRANS_NEAR_CULL_XZ)
    float voxyNearCullDist = max(abs(voxyCamRelXZ.x), abs(voxyCamRelXZ.y));
#else
    float voxyNearCullDist = voxyFogDist;
#endif
    if (voxyLodParams2.x > 0.0 && voxyNearCullDist < voxyLodParams2.x) {
#if defined(VOXY_TRANS_NEAR_CULL_MASKED) && !defined(VOXY_NO_DEPTH_BOUND)
        // 2026-07-04 "gray squares" root cause: the UNCONDITIONAL distance
        // cull assumed MC water covers everything inside the ring, but Sodium
        // only renders water where the section is BUILT — over unbuilt/
        // unloaded sections (chunk gen lag, server load radius < client RD)
        // the cull stripped the LOD water and exposed naked pale seafloor
        // quads: the stable flat "gray squares on water", checkerboarded at
        // section granularity. Gate the cull on chunk-bound mask COVERAGE:
        // a built section rasterized this pixel (bound clear = 0.0) -> MC
        // water is really there -> cull; no coverage -> keep LOD water as
        // the fallback. Coverage is binary, immune to the grazing-angle
        // depth-compare flip that motivated the distance cull.
        // VOXY_TRANS_NEAR_CULL_MASKED=0 restores the unconditional cull.
#ifdef VOXY_METAL_BOUND_SSBO
        float voxyNearCullBound = boundDepths[uint(gl_FragCoord.y) * boundWidth + uint(gl_FragCoord.x)];
#else
        float voxyNearCullBound = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
#endif
        if (voxyNearCullBound > 0.0) {
#ifdef VOXY_TRANS_NEAR_CULL_GHOST
            // 2026-07-14 ghost depth: built-surface coverage must suppress the
            // LOD water COLOUR (Sodium/BSL draw the real water here) but a
            // plain discard also erases the water-surface DEPTH write — the
            // trans bridge then holds the RESTORED opaque depth, dT == d, and
            // VxContractInjector's seafloor water-column dim can never reach
            // the bright LOD floor injected under real MC water (the near pale
            // patches). Emit a ZERO-ALPHA fragment instead: invisible
            // (premultiplied blend no-op; material plane 0 alpha 0 -> the
            // trans resolve discards, so no double water and no pack depth
            // write) but the depth write survives -> dT = water surface depth
            // -> the existing dim darkens the floor like a real BSL seafloor.
            // Returns BEFORE the chunk-bound test below, which would otherwise
            // discard a water top face inside the built AABB.
            // VOXY_TRANS_NEAR_CULL_GHOST=0 reverts to the discard.
    #ifdef PATCHED_SHADER
            // lightMap 8/256 is getLightmap()'s clamp floor: the emitter's
            // nibble quantization round((lightMap*256-8)/16) lands exactly on
            // 0 — no round(-0.5)->uint UB for the Metal compiler to exploit.
            voxy_emitFragment(VoxyFragmentParameters(
                    vec4(0.0), vec2(0.0), vec2(0.0), 0u, 0u,
                    vec2(8.0/256.0), vec4(1.0), 0u));
    #else
            outColour = vec4(0.0);
    #endif
            return;
#else
            discard;
            return;
#endif
        }
#else
        discard;
        return;
#endif
    }
#endif

#if defined(TRANSLUCENT) && defined(VOXY_LOD_WATER_DEBUG)
    // Diagnostic (VOXY_LOD_WATER_DEBUG=1): render translucent LOD water as
    // unmistakable solid magenta — no fog, no blend — so a screenshot shows
    // EXACTLY where the LOD water geometry rasterizes. Distinguishes "water
    // missing / clipped / depth-rejected" (no magenta where water should be)
    // from "water present but wrong colour/fog" (magenta is there, just the
    // normal path renders it wrong). Sits AFTER the near-cull block: only
    // fragments that SURVIVE the cull paint, so under the vx contract this
    // classifies the residual near pale quads — magenta on them = kept
    // fallback LOD water (surface colour problem), no magenta = no LOD
    // water data there at all (ingestion gap).
    #ifdef PATCHED_SHADER
    // 2026-07-14: the original outColour arm is DEAD in material mode (the
    // translucent program is PATCHED — no outColour exists), which made this
    // probe silently no-op under the vx contract. Emit through the pack
    // emitter instead: full-alpha magenta albedo, lightMap 248/256 (the
    // emitter's nibble quantization lands on 15 — bright regardless of sky).
    voxy_emitFragment(VoxyFragmentParameters(
            vec4(1.0, 0.0, 1.0, 1.0), vec2(0.0), vec2(0.0), 0u, 0u,
            vec2(248.0/256.0), vec4(1.0), 0u));
    #else
    outColour = vec4(1.0, 0.0, 1.0, 1.0);
    #endif
    return;
#endif

    //vec2 uv = vec2(0);
    //Tile is the tile we are in
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    vec2 uv2 = modf(uv, tile)*(1.0/(vec2(3.0,2.0)*256.0));
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();

#ifdef VOXY_NO_ATLAS
    // M12 Metal path: ModelTextureBakery is still GL-only, so the
    // blockModelAtlas + depthBoundingBuffer textures aren't populated /
    // bound. Skip atlas sampling and emit a deterministic per-quad
    // debug color hashed from `interData.x` (a flat varying carrying the
    // model id + face + flags — varies per quad / section). Then modulate
    // by the *real* MC lightmap colour packed into `interData.y` by the
    // vertex shader (see makeRemainingAttributes in quad_util.glsl —
    // it samples lightSampler and multiplies by computeDirectionalFaceTint,
    // which already encodes UP/DOWN/Z/X face shade from MC's level). M13
    // chunk 2 wired the lightmap sampler on Metal, so this is the real
    // lighting; the synthetic face-Lambertian shade from M12 is gone.
    // Drops the depth-bounding and alpha-discard checks that depend on
    // the unbound atlas textures. `gl_InstanceID` lives only in the
    // vertex stage so we can't use it here; interData.x gives sufficient
    // variation.
    {
        uint hash = interData.x * 2654435761u;
        hash ^= hash >> 13;
        hash *= 1274126177u;
        hash ^= hash >> 16;
        // Map the hash channels into [0.55, 1.0] so every block reads as
        // a saturated bright colour. The plain `(hash & 0xFF) / 255` from
        // earlier let random channels collapse near zero, which combined
        // with lightmap shading would have collapsed too dark for many
        // blocks. Bias the range so the visual contrast is always strong.
        colour = vec4(
            float((hash >>  0) & 0xFFu) / 255.0 * 0.45 + 0.55,
            float((hash >>  8) & 0xFFu) / 255.0 * 0.45 + 0.55,
            float((hash >> 16) & 0xFFu) / 255.0 * 0.45 + 0.55,
            1.0
        );
        // Modulate by the real lightmap + face-shade tinting baked into
        // interData.y by makeRemainingAttributes. Keep alpha at 1.0 —
        // interData.y's alpha channel carries packed face/lod metadata
        // for the non-translucent path (see `addin` in quad_util.glsl)
        // and would zero the fragment.
        colour.rgb *= uint2vec4RGBA(interData.y).rgb;

        // Procedural per-pixel pattern — gives each face a "textured"
        // look instead of a solid colour. Combines a small-grid checker
        // (4x4 cells per quad) with a value-noise speckle so flat block
        // colours read as 3D-textured surfaces. Real model textures from
        // ModelTextureBakery are still M13 chunk 1 — this is the
        // VOXY_NO_ATLAS debug visualization until that lands.
#ifndef USE_NV_BARRY
        {
            uint face = getFace();
            // 4x4 grid checker — gives subtle "tile" structure.
            ivec2 cell = ivec2(floor(uv * 4.0));
            float checker = ((cell.x ^ cell.y) & 1) == 0 ? 1.0 : 0.85;
            // Value-noise speckle — high-frequency variation hiding the
            // flat per-quad fill. Hash from (uv * 16, face) so the
            // pattern is stable per-pixel but uncorrelated across faces.
            vec2 noiseInput = uv * 16.0 + float(face) * 17.0;
            float noise = fract(sin(dot(noiseInput, vec2(12.9898, 78.233))) * 43758.5453);
            float speckle = 0.88 + 0.12 * noise;
            colour.rgb *= checker * speckle;
        }
#endif
    }
#else
//This is deprecated, TODO: remove the non mip code path
    //if (useMipmaps())
    {
#ifdef VOXY_LOD_DIST_MIP
        // Distance-based mip selection (Metal, 2026-07-03). Fixed mip 0 made
        // every distant pixel pick one arbitrary texel of its 16x16 face cell
        // (NEAREST + no minification) — the spyglass moire/shimmer on LOD
        // water and the pixel noise on distant terrain. Screen-space
        // derivatives are NOT trustworthy here (1-2 px quads gave the noisy
        // dFdx that forced fixed-mip in the first place), so compute the mip
        // ANALYTICALLY: one atlas texel covers lodScale/16 world units; one
        // screen pixel covers voxyFogDist * voxyLodParams.x world units
        // (2*tan(fovY/2)/viewportH, per frame — tracks spyglass zoom).
        // Clamp to VOXY_ATLAS_MAX_LOD: bakes upload mips 16/8/4/2 only.
        float voxyAtlasLod = 0.0;
        #ifdef VOXY_NEEDS_FOG_DIST
        if (voxyLodParams.x > 0.0) {
            float texelWorld = float(1u<<((interData.w>>16)&7u)) * (1.0/16.0);
            float pixelWorld = voxyFogDist * voxyLodParams.x;
            voxyAtlasLod = clamp(log2(max(pixelWorld, 1e-6) / texelWorld) + VOXY_LOD_DIST_MIP_BIAS,
                                 0.0, VOXY_ATLAS_MAX_LOD);
        }
        #endif
        colour = textureLod(blockModelAtlas, texPos, voxyAtlasLod);
#elif defined(VOXY_LOD_FIXED_MIP)
        // DIAGNOSTIC (2026-05-25): sample the atlas at a fixed LOD 0 instead of
        // the derivative-based mip. Tests whether the LOD flicker is unstable
        // mip selection on small/distant quads (noisy dFdx/dFdy) — the "small
        // quad is really fking over the mipping level" issue noted below.
        colour = textureLod(blockModelAtlas, texPos, 0.0);
#else
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);//vec2(lDx, dDx);
        vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
#endif
    }// else {
    //    colour = textureLod(blockModelAtlas, texPos, 0);
    //}

    // Metal bakery debug aid. The normal Metal path samples the real model
    // atlas; define VOXY_DEBUG_MAGENTA_MISSING to make any fully empty bake
    // cell visible instead of silently black/discarded.
    #ifdef VOXY_DEBUG_MAGENTA_MISSING
    if (colour.a == 0.0) {
        outColour = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    }
    #endif
#endif

    //If we are in shaders and are a helper invocation, just exit, as it enables extra performance gains for small sized
    // fragments, we do this here after derivative computation
    //Trying it with all shaders
    //#ifdef PATCHED_SHADER
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif
    //#endif

    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        discard;
        return;
    }

#ifndef VOXY_NO_DEPTH_BOUND
    //Check the minimum bounding texture and ensure we are greater than it.
    // M13 chunk 1 split: this used to live under `#ifndef VOXY_NO_ATLAS` so
    // the atlas-disabled debug path also skipped the depth-bounding check.
    // M13 chunk 3: the chunk-bound depth mask now renders on Metal too
    // (ChunkBoundRenderer.renderMetal → depthBoundingBuffer, bound at
    // texture slot 2), so this check is ON by default on every backend;
    // VOXY_NO_DEPTH_BOUND=1 is the Metal kill switch that removes it.
#ifdef VOXY_METAL_BOUND_SSBO
    float voxyBoundDepth = boundDepths[uint(gl_FragCoord.y) * boundWidth + uint(gl_FragCoord.x)];
#else
    float voxyBoundDepth = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
#endif
    if (gl_FragCoord.z < voxyBoundDepth) {
        #ifdef VOXY_BOUND_DEBUG
        // VOXY_BOUND_DEBUG=1 (Metal mask-verification aid): paint the
        // bound-discarded fragments solid red instead of discarding so a
        // screenshot shows exactly where the chunk-bound depth mask bites.
        // PATCHED (vx material) variant has no outColour — flag the hit and
        // paint at the emit site instead (writing outColour here failed to
        // compile and took the whole vx pipeline down -> silent Iris-off
        // fallback, 2026-07-04).
        #ifdef PATCHED_SHADER
        voxyBoundDebugHit = true;
        #else
        outColour = vec4(1.0, 0.0, 0.0, 1.0);
        return;
        #endif
        #else
        discard;
        return;
        #endif
    }
#endif // VOXY_NO_DEPTH_BOUND

#ifndef VOXY_NO_ATLAS
#ifndef VOXY_LOD_NO_DISCARD
    //Also, small quad is really fking over the mipping level somehow
    #ifndef TRANSLUCENT
    if (useDiscard() && (textureLod(blockModelAtlas, texPos, 0).a <= 0.1f)) {
    //if (useDiscard() && (colour.a <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        //This is stupidly stupidly bad for divergence
        //TODO: FIXME, basicly what this do is sample the exact pixel (no lod) for discarding, this stops mipmapping fucking it over
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }
#endif // VOXY_LOD_NO_DISCARD
#endif // VOXY_NO_ATLAS — closes the alpha-discard block above

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    #ifndef PATCHED_SHADER
#ifdef VOXY_NO_ATLAS
    // Already computed a debug colour up top; skip computeColour (which
    // re-samples blockModelAtlas via textureLod). Emit straight to outColour.
    outColour = colour;
#else
    colour = computeColour(texPos, colour);
    outColour = colour;
    #ifdef VOXY_FORCE_OPAQUE_ALPHA
    // Metal composites the IOSurface back into Minecraft's main render target.
    // The opaque path otherwise writes LOD/face metadata into alpha, which
    // spyglass/post overlays can interpret as real framebuffer transparency.
    outColour.a = 1.0;
    #endif
#endif

    // M13 chunk 5: environmental fog on the Metal terrain path. Mirrors the
    // GL post-pass formula from blit_texture_depth_cutout.frag (lines 71–74)
    // so distant LOD chunks fade into the sky/biome fog colour the same way
    // Sodium's near terrain does. Injected only on the Metal pipeline (see
    // MDICSectionRenderer constructor) — the GL pipeline still applies fog
    // in the depth-cutout post-pass and would double-apply if this branch
    // also ran. fogColour.a == 0 short-circuits so a feature-flagged-off
    // upload (zero alpha) is cheap.
    // Seam-ring brightness parity (Metal): GL runs ssao.comp between opaque
    // and translucent, approximating the vertex AO Sodium bakes into its near
    // terrain; that pass is parked on Metal, leaving LOD ~10% brighter than
    // the AO-darkened Sodium terrain at the render-distance boundary. Applied
    // BEFORE fog so the compensation darkens terrain, not the fog colour.
    // Injected Metal-only with the tunable value (VOXY_LOD_BRIGHTNESS env).
    #ifdef VOXY_LOD_BRIGHTNESS
    outColour.rgb *= VOXY_LOD_BRIGHTNESS;
    #endif

    // Water-ring parity (Metal, translucent program only): LOD water reads
    // pale and shallow next to MC's near water — MC water darkens with depth
    // while LOD water blends a light texture over the bright fog-coloured
    // bridge clear. Darken it and floor its opacity so the boundary reads as
    // continuous deep water. VOXY_WATER_SHADE / VOXY_WATER_MIN_ALPHA envs.
    #ifdef VOXY_WATER_SHADE
    outColour.rgb *= VOXY_WATER_SHADE;
    #endif
    #ifdef VOXY_WATER_MIN_ALPHA
    outColour.a = max(outColour.a, VOXY_WATER_MIN_ALPHA);
    #endif

    // Far-water opacity ramp (Metal, translucent only, 2026-07-03). MC water
    // is alpha 0.706 and that parity MUST hold at the LOD<->MC seam — but a
    // constant 0.706 out to the horizon lets kilometre-deep seafloor/kelp
    // ghost through the surface and lets the fog-coloured bridge clear bleed
    // up through it (the washed-out flat-blue sheet). Physically, the view
    // path through water at those grazing distances is opaque. Smoothstep
    // the alpha from the vanilla texel value at voxyLodParams.y blocks (past
    // the seam, so ring parity is untouched) to voxyLodParams.w at the far
    // end. w == 0 disables (VOXY_WATER_FAR_ALPHA=0 kill switch, params from
    // MDICSectionRenderer.uploadUniform).
    #if defined(TRANSLUCENT) && defined(VOXY_WATER_FAR_ALPHA)
    if (voxyLodParams.w > 0.0) {
        float farLerp = clamp((voxyFogDist - voxyLodParams.y) * voxyLodParams.z, 0.0, 1.0);
        farLerp = farLerp * farLerp * (3.0 - 2.0 * farLerp);
        outColour.a = mix(outColour.a, max(outColour.a, voxyLodParams.w), farLerp);
    }
    #endif

    #ifdef USE_ENV_FOG
    if (voxyFogColour.a > 0.0) {
        float fogLerp = clamp(fma(voxyFogDist, voxyFogEndParams.x, voxyFogEndParams.y),
                              0.0, voxyFogEndParams.z);
        outColour.rgb = mix(outColour.rgb, voxyFogColour.rgb, fogLerp * voxyFogColour.a);
    }
    #endif

    #ifdef DEBUG_RENDER
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction==1) {//Partial tint
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    #ifdef VOXY_DEBUG_WLOG_TINT
    // Adjudication aid (VOXY_DEBUG_WLOG_TINT=1): solid magenta on every quad
    // whose model carries the biome-LUT flag with the -1 colour sentinel (the
    // waterlogged-plant pale-squares mechanism). One screenshot decides: the
    // pale squares turning magenta confirms the mechanism.
    if (modelHasBiomeLUT(model) && model.colourTint == uint(-1)) {
        colour = vec4(1.0, 0.0, 1.0, 1.0);
        tint = vec4(1.0);
    }
    #endif

    #ifdef VOXY_BOUND_DEBUG
    // PATCHED half of the bound-mask debug (see the bound block above).
    if (voxyBoundDebugHit) {
        colour = vec4(1.0, 0.0, 0.0, 1.0);
        tint = vec4(1.0);
    }
    #endif

    #if !defined(TRANSLUCENT) && defined(VOXY_OPAQUE_WATER_DEBUG)
    // Probe (VOXY_LOD_OPAQUE_WATER_DEBUG=1): any ORANGE in-game = a water
    // customId rasterized by the OPAQUE pass — LOD water that bypasses
    // voxy_translucent (and every pack-side water fix) entirely.
    if (model.customId / 100u == 200u || model.customId / 100u == 204u) {
        colour = vec4(1.0, 0.5, 0.0, 1.0);
        tint = vec4(1.0);
    }
    #endif

    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));
    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmap(), tint, model.customId));

    #endif
}



//#ifdef GL_KHR_shader_subgroup_quad
/*
uint hash = (uint(tile.x)*(1<<16))^uint(tile.y);
uint horiz = subgroupQuadSwapHorizontal(hash);
bool sameTile = horiz==hash;
uint sv = mix(uint(-1), hash, sameTile);
uint vert = subgroupQuadSwapVertical(sv);
sameTile = sameTile&&vert==hash;
mipBias = sameTile?0:-5.0;
*/
/*
vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
float lDx = subgroupQuadSwapHorizontal(uvSmol.x)-uvSmol.x;
float lDy = subgroupQuadSwapVertical(uvSmol.y)-uvSmol.y;
float dDx = subgroupQuadSwapDiagonal(lDx);
float dDy = subgroupQuadSwapDiagonal(lDy);
vec2 dx = vec2(lDx, dDx);
vec2 dy = vec2(lDy, dDy);
colour = textureGrad(blockModelAtlas, texPos, dx, dy);
*/
//#else
//colour = texture(blockModelAtlas, texPos);
//#endif
