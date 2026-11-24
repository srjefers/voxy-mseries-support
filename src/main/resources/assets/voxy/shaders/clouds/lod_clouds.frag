#version 460 core

// LOD cloud layer (see LodClouds.java / LodCloudLayer.java): continues vanilla's
// cloud mesh past its radius, over the LOD terrain. Marches the 12x12 cloud cells
// the pixel's ray crosses inside the cloud slab (2D DDA) and shades the first
// filled one like rendertype_clouds.vsh does: the entry face (top 1.0 / bottom
// 0.7) or the side face it entered through (west/east 0.9, north/south 0.8).
// Writes the hit's depth so LOD terrain in front of the cloud hides it.
//
// Metal GLSL rules from CLAUDE.md apply: no % or left shift on negative ints,
// no boolean mix().

#import <voxy:clouds/lod_clouds_params.glsl>

layout(binding = 1, std430) readonly buffer LodCloudCells {
    // One bit per texture cell, row-major (x + z * width): 1 = cloud.
    uint cellBits[];
};

layout(binding = 2, std430) readonly buffer LodCloudCoverage {
    // Where the LOD terrain is (LodTerrainCoverage.fill): a grid of size x size LOD
    // columns (512 blocks) centred on the camera's; column (gx, gz) owns words
    // (gx + gz * size) * 32 .. +31, one per row (z) of 16-block cells, one bit per
    // cell (x): 1 = terrain.
    uint coverageWords[];
};

layout(location = 0) in vec3 entryPos;
layout(location = 0) out vec4 outColour;

const float CELL = 12.0;
const float TERRAIN_CELL = 16.0;
const float SHADE_X_FACE = 0.9;
const float SHADE_Z_FACE = 0.8;

// Math.floorMod for a cell coordinate, in float (exact well past any cell index here).
int wrapCell(int c, int size) {
    float fc = float(c);
    float fs = float(size);
    int m = int(fc - fs * floor(fc / fs));
    return clamp(m, 0, size - 1);
}

// rel: cell offset from the camera's cell, as CloudRenderer.tryBuildCell indexes it.
bool cellFilled(ivec2 rel) {
    int x = wrapCell(cellInfo.x + rel.x, cellInfo.z);
    int z = wrapCell(cellInfo.y + rel.y, cellInfo.w);
    uint idx = uint(x + z * cellInfo.z);
    return ((cellBits[idx >> 5u] >> (idx & 31u)) & 1u) != 0u;
}

// c: 16-block cell counted from the grid's corner (only shifted once known >= 0).
float terrainAt(ivec2 c, int size) {
    int span = size * 32;
    if (c.x < 0 || c.y < 0 || c.x >= span || c.y >= span) {
        return 0.0;
    }
    int column = (c.x >> 5) + (c.y >> 5) * size;
    uint row = coverageWords[column * 32 + (c.y & 31)];
    return ((row >> uint(c.x & 31)) & 1u) != 0u ? 1.0 : 0.0;
}

// How much LOD terrain lies under the camera-relative point rel (0..1): the four
// nearest 16-block cell centres, interpolated, so a cloud thins out across the edge
// of the LOD terrain instead of being cut on the cell grid.
float lodCoverage(vec2 rel) {
    int size = int(coverage.z);
    // coverage.xy: camera position from the corner of the grid's centre column.
    vec2 u = (rel + coverage.xy) / TERRAIN_CELL + float((size / 2) * 32) - 0.5;
    vec2 base = floor(u);
    vec2 f = u - base;
    ivec2 c = ivec2(base);
    float c00 = terrainAt(c, size);
    float c10 = terrainAt(c + ivec2(1, 0), size);
    float c01 = terrainAt(c + ivec2(0, 1), size);
    float c11 = terrainAt(c + ivec2(1, 1), size);
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

void main() {
    float tEntry = length(entryPos);
    if (tEntry <= 0.0) {
        discard;
    }
    vec3 dir = entryPos / tEntry;

    // Distance the ray travels inside the slab; 0 for flat (FAST) clouds.
    float dy = slab.y - slab.x;
    float tSlab = 0.0;
    if (abs(dy) > 0.0) {
        tSlab = abs(dir.y) > 1.0e-6 ? abs(dy / dir.y) : 1.0e30;
    }

    // Cloud grid coordinates: cell = floor(q / 12); the camera's cell is (0, 0).
    vec2 q = entryPos.xz + cellPos.xy;
    float meshR = cellPos.z;

    // The ray leaves the slab before the vanilla mesh's edge: every cell it can
    // meet is vanilla's.
    vec2 exitQ = q + dir.xz * min(tSlab, 1.0e6);
    if (length(exitQ) / CELL < meshR - 2.0) {
        discard;
    }

    ivec2 cell = ivec2(floor(q / CELL));
    ivec2 stepDir = ivec2(dir.x >= 0.0 ? 1 : -1, dir.z >= 0.0 ? 1 : -1);
    vec2 adir = abs(dir.xz);
    vec2 tDelta = vec2(adir.x > 1.0e-6 ? CELL / adir.x : 1.0e30,
                       adir.y > 1.0e-6 ? CELL / adir.y : 1.0e30);
    vec2 boundary = (vec2(cell) + vec2(stepDir.x > 0 ? 1.0 : 0.0, stepDir.y > 0 ? 1.0 : 0.0)) * CELL;
    vec2 tMax = vec2(adir.x > 1.0e-6 ? (boundary.x - q.x) / dir.x : 1.0e30,
                     adir.y > 1.0e-6 ? (boundary.y - q.y) / dir.z : 1.0e30);

    float t = 0.0;
    float shade = slab.w;
    bool hit = false;
    bool left = false;
    int maxSteps = int(fade.z);
    for (int i = 0; i < maxSteps; i++) {
        if (cellFilled(cell)) {
            hit = true;
            break;
        }
        if (tMax.x < tMax.y) {
            t = tMax.x;
            tMax.x += tDelta.x;
            cell.x += stepDir.x;
            shade = SHADE_X_FACE;
        } else {
            t = tMax.y;
            tMax.y += tDelta.y;
            cell.y += stepDir.y;
            shade = SHADE_Z_FACE;
        }
        if (t > tSlab) {
            left = true;
            break;
        }
    }
    if (!hit && left) {
        discard;
    }
    // Neither hit nor left: the march ran out at a grazing angle, still inside the
    // slab. Vanilla's mesh shows a wall of cloud sides there; draw one.

    // Cells inside the vanilla mesh's circle are vanilla's (and hide what is behind them).
    if (float(cell.x * cell.x + cell.y * cell.y) <= meshR * meshR) {
        discard;
    }

    float dist = tEntry + t;
    float alpha = colour.a * (1.0 - smoothstep(fade.x, fade.y, dist));
    // Only over LOD terrain: no clouds over ground that was never generated or explored.
    if (coverage.w > 0.5) {
        alpha *= smoothstep(0.25, 0.75, lodCoverage(dir.xz * dist));
    }
    if (alpha <= 0.002) {
        discard;
    }

    vec4 clip = MVP * vec4(dir * dist, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);

    vec3 rgb = cellPos.w > 0.5 ? vec3(1.0, 0.0, 1.0) : colour.rgb * shade;
    outColour = vec4(rgb, alpha);
}
