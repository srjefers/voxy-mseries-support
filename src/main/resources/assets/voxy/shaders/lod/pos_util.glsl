#ifndef _POS_UTIL_DECL
#define _POS_UTIL_DECL

uint getLoDLevel(uvec2 packedPos) {
    return packedPos.x>>28;
}

ivec3 getLoDPosition(uvec2 packedPos) {
    //Metal fix: (v<<L)>>R sign-extension shifts miscompile via SPIR-V->MSL; bitfieldExtract is well-defined
    int y = bitfieldExtract(int(packedPos.x), 20, 8);
    int x = bitfieldExtract(int(packedPos.y), 4, 24);
    int z = bitfieldExtract(int(((packedPos.x&((1u<<20)-1))<<4)|(packedPos.y>>28)), 0, 24);
    return ivec3(x,y,z);
}

#endif