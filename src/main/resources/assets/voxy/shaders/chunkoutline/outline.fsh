#version 430 core
#ifdef VOXY_BOUND_EPS
// Trans-only coverage epsilon (see ChunkBoundRenderer): a depth that wins
// the GREATER accumulate against the 0.0 clear (arming the trans near-cull's
// `bound > 0` coverage test) but loses to any opaque-section AABB depth and
// sits below every real LOD fragment's window z — so the opaque depth-bound
// test never discards the LOD seafloor behind a translucent-only section.
// 1e-5 comfortably exceeds DEPTH24's quantum (~6e-8). Writing gl_FragDepth
// forgoes early-Z; the epsilon set is a handful of ocean-surface sections.
void main() { gl_FragDepth = 1.0e-5; }
#else
layout(early_fragment_tests) in;

void main() {}
#endif
