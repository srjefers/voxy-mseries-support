package me.cortex.voxy.client.core.rendering.util;

import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector2ic;
import org.joml.Vector3fc;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes shader values without JOML's direct-address API, which requires
 * MemUtilUnsafe and can fail when the JVM's matrix field layout differs.
 * Writes only component bytes; callers own any shader-layout padding.
 */
public final class NativeUniformWriter {
    private NativeUniformWriter() {}

    /** Writes a 64-byte column-major mat4 (mColRow), as expected by GLSL/MSL. */
    public static void putMatrix4f(long address, Matrix4fc matrix) {
        MemoryUtil.memPutFloat(address,      matrix.m00());
        MemoryUtil.memPutFloat(address + 4,  matrix.m01());
        MemoryUtil.memPutFloat(address + 8,  matrix.m02());
        MemoryUtil.memPutFloat(address + 12, matrix.m03());
        MemoryUtil.memPutFloat(address + 16, matrix.m10());
        MemoryUtil.memPutFloat(address + 20, matrix.m11());
        MemoryUtil.memPutFloat(address + 24, matrix.m12());
        MemoryUtil.memPutFloat(address + 28, matrix.m13());
        MemoryUtil.memPutFloat(address + 32, matrix.m20());
        MemoryUtil.memPutFloat(address + 36, matrix.m21());
        MemoryUtil.memPutFloat(address + 40, matrix.m22());
        MemoryUtil.memPutFloat(address + 44, matrix.m23());
        MemoryUtil.memPutFloat(address + 48, matrix.m30());
        MemoryUtil.memPutFloat(address + 52, matrix.m31());
        MemoryUtil.memPutFloat(address + 56, matrix.m32());
        MemoryUtil.memPutFloat(address + 60, matrix.m33());
    }

    public static void putVector3f(long address, Vector3fc vector) {
        MemoryUtil.memPutFloat(address,     vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
        MemoryUtil.memPutFloat(address + 8, vector.z());
    }

    public static void putVector3i(long address, Vector3ic vector) {
        MemoryUtil.memPutInt(address,     vector.x());
        MemoryUtil.memPutInt(address + 4, vector.y());
        MemoryUtil.memPutInt(address + 8, vector.z());
    }

    public static void putVector2f(long address, Vector2fc vector) {
        MemoryUtil.memPutFloat(address, vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
    }

    public static void putVector4f(long address, Vector4fc vector) {
        MemoryUtil.memPutFloat(address, vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
        MemoryUtil.memPutFloat(address + 8, vector.z());
        MemoryUtil.memPutFloat(address + 12, vector.w());
    }

    public static void putVector2i(long address, Vector2ic vector) {
        MemoryUtil.memPutInt(address, vector.x());
        MemoryUtil.memPutInt(address + 4, vector.y());
    }
}
