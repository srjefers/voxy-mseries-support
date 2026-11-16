package me.cortex.voxy.common.util;

import org.joml.*;

import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

/**
 * Writes JOML values to native memory without JOML's getToAddress, which only
 * works with its Unsafe backend. JOML falls back to NIO when Unsafe rejects
 * the field layout ("Unexpected Matrix4f element offset"), and getToAddress
 * then throws NoClassDefFoundError on the render thread.
 */
public final class JomlMem {
    private JomlMem() {}

    /** Column-major, same layout as Matrix4f.getToAddress. */
    public static void put(Matrix4fc m, long ptr) {
        memPutFloat(ptr,      m.m00()); memPutFloat(ptr + 4,  m.m01()); memPutFloat(ptr + 8,  m.m02()); memPutFloat(ptr + 12, m.m03());
        memPutFloat(ptr + 16, m.m10()); memPutFloat(ptr + 20, m.m11()); memPutFloat(ptr + 24, m.m12()); memPutFloat(ptr + 28, m.m13());
        memPutFloat(ptr + 32, m.m20()); memPutFloat(ptr + 36, m.m21()); memPutFloat(ptr + 40, m.m22()); memPutFloat(ptr + 44, m.m23());
        memPutFloat(ptr + 48, m.m30()); memPutFloat(ptr + 52, m.m31()); memPutFloat(ptr + 56, m.m32()); memPutFloat(ptr + 60, m.m33());
    }

    public static void put(Vector2fc v, long ptr) { memPutFloat(ptr, v.x()); memPutFloat(ptr + 4, v.y()); }
    public static void put(Vector3fc v, long ptr) { memPutFloat(ptr, v.x()); memPutFloat(ptr + 4, v.y()); memPutFloat(ptr + 8, v.z()); }
    public static void put(Vector4fc v, long ptr) { memPutFloat(ptr, v.x()); memPutFloat(ptr + 4, v.y()); memPutFloat(ptr + 8, v.z()); memPutFloat(ptr + 12, v.w()); }
    public static void put(Vector2ic v, long ptr) { memPutInt(ptr, v.x()); memPutInt(ptr + 4, v.y()); }
    public static void put(Vector3ic v, long ptr) { memPutInt(ptr, v.x()); memPutInt(ptr + 4, v.y()); memPutInt(ptr + 8, v.z()); }
}
