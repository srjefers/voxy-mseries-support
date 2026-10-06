# Rendering domain

- **Renderer generation**: one `VoxyRenderSystem` and the workers, GPU resources,
  callbacks, and world reference acquired for its lifetime. Replacement creates a
  new generation; an obsolete generation cannot own current frame work.
- **Main-view frame**: a successful non-shadow camera capture. Iris uniform
  preparation, Sodium terrain preparation, transform history, and material
  publication use this identity. Traversal's integer visibility tag is derived
  from it and can remain frozen for the existing occlusion debug mode.
- **Frame target set**: all Metal color/material planes, depth textures, depth
  bridges, and conversion resources required for one size and rendering mode.
  A replacement is published only after its complete acquisition succeeds.
- **Geometry publication**: the owned scratch payload joining geometry copies,
  node/metadata writes, cleaner changes, and top-level readiness. Worker assembly
  and render-thread recording transfer ownership through the existing result
  slots. Readiness follows successful recording; a recording failure is terminal
  for that generation.
- **Uploaded coverage**: Sodium's authoritative lifecycle readiness. OpenGL
  consumes incremental updates; Metal consumes generation resets and combines
  uploaded readiness with current-frame drawable coverage.
- **Drawable coverage**: Sodium commands actually prepared for this main-view
  frame, separated by terrain pass. Uploaded data alone does not own a pixel.
- **Material contract**: the pack's opaque and translucent programs, targets,
  uniforms, depth rules, and blend settings. Generic contracts and explicitly
  selected BSL compatibility remain different rendering policies.
- **Failed save claim**: a dirty section, retained reference, and queue flag kept
  after a failed storage write. Controlled retry releases the claim only when the
  write succeeds; persistent failure is reported rather than silently unloaded.
