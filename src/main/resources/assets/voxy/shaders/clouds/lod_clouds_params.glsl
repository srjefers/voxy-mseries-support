// Shared by lod_clouds.vert/.frag (Metal LOD frame, see LodCloudLayer.java).
// std140, 160 bytes; LodCloudLayer.render/writeParams write the same offsets.
layout(binding = 0, std140) uniform LodCloudParams {
    // Camera-relative world position -> LOD clip space (the LOD terrain's MVP).
    mat4 MVP;                 // offset 0
    // x: camera-relative height of the face the rays enter the cloud slab through,
    // y: camera-relative height of the face they leave by (== x for flat clouds),
    // z: half size of the proxy quad (blocks), w: shade of the entry face (top 1.0, bottom 0.7).
    vec4 slab;                // offset 64
    // xy: camera position inside its cloud cell ([0, 12) blocks), z: radius of the vanilla
    // cloud mesh in cells (those cells are vanilla's), w: 1 = debug tint.
    vec4 cellPos;             // offset 80
    // xy: the camera's cloud cell (wrapped into the texture), zw: texture size in cells.
    ivec4 cellInfo;           // offset 96
    // Vanilla cloud colour, rgba.
    vec4 colour;              // offset 112
    // x: fade start, y: fade end (blocks from the camera), z: max cells marched, w: unused.
    vec4 fade;                // offset 128
    // Where the LOD terrain is (LodTerrainCoverage): xy: camera position from the
    // corner of the coverage grid's centre column (512 blocks), z: side of the grid
    // in columns, w: 1 = clouds only over LOD terrain, 0 = no limit.
    vec4 coverage;            // offset 144
};
