struct BlockModel {
    uint faceData[6];
    uint flagsA;
    uint colourTint;
    uint customId;
    uint _pad[7];
};

//TODO: FIXME: this isnt actually correct cause depending on the face (i think) it could be 1/64 th of a position off
// but im going to assume that since we are dealing with huge render distances, this shouldent matter that much
float extractFaceIndentation(uint faceData) {
    uint enc = (faceData>>16)&63u;
    enc += uint(enc==63u);//convert 63 to 64 cause of pain reasons
    return float(enc)/64.0;
}

vec4 extractFaceSizes(uint faceData) {
    return (vec4(faceData&0xFu, (faceData>>4)&0xFu, (faceData>>8)&0xFu, (faceData>>12)&0xFu)/16.0)+vec4(0.0,1.0/16.0,0.0,1.0/16.0);
}

uint faceHasAlphaCuttout(uint faceData) {
    return (faceData>>22)&1u;
}

//TODO: try and get rid of
uint faceHasAlphaCuttoutOverride(uint faceData) {
    return (faceData>>23)&1u;
}

uint faceTintState(uint faceData) {
    return (faceData>>24)&3u;
}

bool modelHasBiomeLUT(BlockModel model) {
    return ((model.flagsA)&2u) != 0;
}

bool modelIsTranslucent(BlockModel model) {
    return ((model.flagsA)&4u) != 0;
}

//Metal-only plant cross model (ModelFactory sets bit 16 only on non-GL backends)
bool modelIsPlantCross(BlockModel model) {
    return ((model.flagsA)&16u) != 0;
}

//Metal-only flat block, H16 (ModelFactory.FLAT_HEIGHT sets bit 32 only on non-GL backends):
// snow layers, carpets, bottom slabs... whose top face is indented by 1 - height
bool modelIsFlat(BlockModel model) {
    return ((model.flagsA)&32u) != 0;
}

//Metal-only bamboo stalk, H18 (ModelFactory.BAMBOO_STALK sets bit 64 only on non-GL backends):
// side faces indented to the stalk at the cell centre, undilated bake
bool modelIsBamboo(BlockModel model) {
    return ((model.flagsA)&64u) != 0;
}

//Metal-only leaves, H4 (ModelFactory sets bit 128 only on non-GL backends): the LOD brightness compensation
// darkens leaves only (VOXY_LOD_BRIGHTNESS_LEAVES_ONLY)
bool modelIsLeaves(BlockModel model) {
    return ((model.flagsA)&128u) != 0;
}

bool modelIsShaded(BlockModel model) {
    return ((model.flagsA)&8u) != 0;
}