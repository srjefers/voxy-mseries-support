package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.util.Mth;
import org.joml.*;

import java.lang.reflect.Field;

public abstract class Viewport <A extends Viewport<A>> {
    //public final HiZBuffer2 hiZBuffer = new HiZBuffer2();
    // The HiZ stencil aspect is never used (depth-only FBO attach + depth
    // sampling). On Metal D24S8 maps to the PACKED Depth32Float_Stencil8,
    // which Metal requires on BOTH depth and stencil attachments — the
    // zero-fill/mip passes attach depth only, so use a pure depth format
    // there. GL keeps the upstream D24S8.
    public final HiZBuffer hiZBuffer = new HiZBuffer(
            RenderBackendFactory.get().getType() != BackendType.OPENGL
                    ? org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F
                    : org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8);
    public final DepthFramebuffer depthBoundingBuffer = new DepthFramebuffer();
    /**
     * Metal only (round 20): {@link #depthBoundingBuffer}'s depth blitted to
     * raw floats so quads.frag's bound test reads a BUFFER instead of
     * sampling a depth texture (which silently reads zeros through the
     * texture2d&lt;float&gt; declaration SPIRV-Cross emits — the mask was
     * inert since M13 chunk 3). Layout: uint width + 12 pad bytes, then
     * width*height floats. Owned/resized by ChunkBoundRenderer; null on GL.
     */
    public me.cortex.voxy.client.core.gpu.IGpuBuffer metalBoundReadBuffer;

    private static final Field planesField;
    static {
        try {
            planesField = FrustumIntersection.class.getDeclaredField("planes");
            planesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public int width;
    public int height;
    public int frameId;
    public Matrix4f vanillaProjection = new Matrix4f();
    public Matrix4f projection = new Matrix4f();
    public Matrix4f modelView = new Matrix4f();
    public final FrustumIntersection frustum = new FrustumIntersection();
    public final Vector4f[] frustumPlanes;
    public double cameraX;
    public double cameraY;
    public double cameraZ;
    public FogParameters fogParameters;

    public final Matrix4f MVP = new Matrix4f();
    public final Vector3i section = new Vector3i();
    public final Vector3f innerTranslation = new Vector3f();

    protected Viewport() {
        Vector4f[] planes = null;
        try {
             planes = (Vector4f[]) planesField.get(this.frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.frustumPlanes = planes;
    }

    public final void delete() {
        this.delete0();
    }

    protected void delete0() {
        this.hiZBuffer.free();
        this.depthBoundingBuffer.free();
        if (this.metalBoundReadBuffer != null) {
            this.metalBoundReadBuffer.free();
            this.metalBoundReadBuffer = null;
        }
    }

    public A setVanillaProjection(Matrix4fc projection) {
        this.vanillaProjection.set(projection);
        return (A) this;
    }

    public A setProjection(Matrix4f projection) {
        this.projection = projection;
        return (A) this;
    }

    public A setModelView(Matrix4f modelView) {
        this.modelView = modelView;
        return (A) this;
    }

    public A setCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return (A) this;
    }

    public A setScreenSize(int width, int height) {
        this.width = width;
        this.height = height;
        return (A) this;
    }

    public A setFogParameters(FogParameters fogParameters) {
        this.fogParameters = fogParameters;
        return (A) this;
    }

    /**
     * Angular safety margin for the LOD cull frustum on Metal (2026-05-26).
     * The frustum planes are extracted from a slightly WIDER-FOV copy of the
     * projection so far LOD sections at the frustum edge don't flicker in/out
     * from per-frame view micro-jitter (sub-pixel / view-bob). At LOD range a
     * tiny angular jitter sweeps a huge WORLD distance, so a fixed world-space
     * margin can't absorb it (it's a tiny angle far away) — a constant ANGULAR
     * margin (wider FOV) does, at all distances, for a small fps cost. The
     * value scales projection m00/m11: smaller = wider FOV = bigger margin.
     * 1.0 disables. GL keeps the exact frustum (no flicker there).
     */
    private static final float CULL_FOV_WIDEN = parseCullFovWiden();
    private static boolean FOV_WIDEN_LOGGED = false;
    private static float parseCullFovWiden() {
        // Default 1.0 = OFF (2026-05-26 round 6). The cull itself is off by
        // default now (no-flicker), so this angular margin only matters if the
        // user opts into the cull (VOXY_LOD_FRUSTUM_CULL=1); then they can widen
        // the FOV here to soften the resulting flicker.
        String v = System.getenv("VOXY_LOD_FRUSTUM_FOV_WIDEN");
        if (v == null) return 1.0f;
        try {
            return java.lang.Math.min(1.0f, java.lang.Math.max(0.5f, Float.parseFloat(v.trim())));
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    public A update() {
        //MVP
        this.projection.mul(this.modelView, this.MVP);

        //Update the frustum. On Metal widen the FOV slightly for an angular
        //cull margin (kills far-edge LOD flicker from per-frame view jitter);
        //GL uses the exact MVP.
        if (CULL_FOV_WIDEN != 1.0f
                && RenderBackendFactory.get().getType() != BackendType.OPENGL) {
            if (!FOV_WIDEN_LOGGED) {
                FOV_WIDEN_LOGGED = true;
                me.cortex.voxy.common.Logger.info(
                        "[Metal] LOD cull frustum FOV widen=" + CULL_FOV_WIDEN
                        + " (angular anti-flicker margin; VOXY_LOD_FRUSTUM_FOV_WIDEN to tune, 1.0=off)");
            }
            Matrix4f cullProj = new Matrix4f(this.projection);
            cullProj.m00(cullProj.m00() * CULL_FOV_WIDEN);
            cullProj.m11(cullProj.m11() * CULL_FOV_WIDEN);
            this.frustum.set(cullProj.mul(this.modelView, new Matrix4f()), false);
        } else {
            this.frustum.set(this.MVP, false);
        }

        //Translation vectors
        int sx = Mth.floor(this.cameraX)>>5;
        int sy = Mth.floor(this.cameraY)>>5;
        int sz = Mth.floor(this.cameraZ)>>5;
        this.section.set(sx, sy, sz);

        this.innerTranslation.set(
                (float) (this.cameraX-(sx<<5)),
                (float) (this.cameraY-(sy<<5)),
                (float) (this.cameraZ-(sz<<5)));

        if (this.depthBoundingBuffer.resize(this.width, this.height)) {
            this.depthBoundingBuffer.clear(0.0f);
        }

        return (A) this;
    }

    public abstract IGpuBuffer getRenderList();
}
