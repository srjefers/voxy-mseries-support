package me.cortex.voxy.common.util;

import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes JOML matrices and vectors to native memory without depending on JOML's
 * {@code MemUtilUnsafe}. Use {@code JomlCompat.put(x, address)} instead of {@code x.getToAddress(address)}.
 *
 * <p><b>Why.</b> JOML 1.10.8's {@code getToAddress(long)} always calls {@code MemUtil.MemUtilUnsafe.put},
 * with no fallback. {@code MemUtilUnsafe} initialises only if the fields {@code m00}…{@code m33} of
 * {@code Matrix4f} sit next to each other in declaration order, because it copies the matrix as raw
 * memory. HotSpot 21 on macOS (the Java 21 the official launcher ships for Minecraft 1.21.11) lays them
 * out as {@code m33@12, m00@16 … m32@72}. JOML itself then falls back to {@code MemUtilNIO}, and Minecraft
 * keeps working, but every {@code getToAddress} throws
 * {@code NoClassDefFoundError: Could not initialize class org.joml.MemUtil$MemUtilUnsafe}. That crashed every
 * Mac player as soon as Voxy drew its first frame (H6, Pre-P8 2026-11-23). JDK 27, which the dev client ran
 * on, keeps declaration order, so no test had seen it.
 *
 * <p><b>How.</b> The first use checks once whether {@code getToAddress} works on this JVM, by writing each type
 * used here to a scratch buffer. If it works, {@link #put} calls {@code getToAddress}, exactly as before, so
 * Windows, Linux and the GL backend run the same code as upstream. If it throws, {@link #put} writes with
 * LWJGL's {@code MemoryUtil.memPutFloat}/{@code memPutInt}, in JOML's order: column-major for the matrix
 * ({@code m00, m01, m02, m03, m10, …, m33}) and {@code x, y, z, w} for the vectors. Those are the bytes
 * {@code MemUtilUnsafe} copies and the bytes {@code get(ByteBuffer)} writes; {@code ./gradlew testJomlCompat}
 * compares them byte for byte.
 *
 * <p>The chosen path is logged once: {@code [JomlCompat] …}.
 *
 * <p>Environment switches (A/B; {@code VOXY_JOML_COMPAT=0} wins if both are set):
 * <ul>
 *   <li>{@code VOXY_JOML_FORCE_FALLBACK=1}: always use the LWJGL writes, also where {@code getToAddress} works
 *       (Java 27), to compare the two paths in the dev client.</li>
 *   <li>{@code VOXY_JOML_COMPAT=0}: call {@code getToAddress} directly with no check, the pre-fix behaviour. It
 *       crashes where {@code MemUtilUnsafe} cannot initialise.</li>
 * </ul>
 */
public final class JomlCompat {
    private JomlCompat() {}

    /** Selected path: {@code "joml"}, {@code "lwjgl"}, {@code "lwjgl-forced"} or {@code "direct"}. */
    public static final String MODE;
    /** True when {@link #put} calls JOML's {@code getToAddress}. */
    private static final boolean USE_JOML;

    static {
        String java = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
        if ("0".equals(System.getenv("VOXY_JOML_COMPAT"))) {
            MODE = "direct";
            USE_JOML = true;
            Logger.info("[JomlCompat] VOXY_JOML_COMPAT=0: calling JOML getToAddress directly, no check (pre-fix"
                    + " behaviour), Java " + java);
        } else {
            Throwable failure = probe();
            String unsafe = failure == null ? "OK" : "unavailable (" + failure.getClass().getName() + ")";
            if ("1".equals(System.getenv("VOXY_JOML_FORCE_FALLBACK"))) {
                MODE = "lwjgl-forced";
                USE_JOML = false;
                Logger.info("[JomlCompat] VOXY_JOML_FORCE_FALLBACK=1: using LWJGL writes (MemUtilUnsafe " + unsafe
                        + "), Java " + java);
            } else if (failure == null) {
                MODE = "joml";
                USE_JOML = true;
                Logger.info("[JomlCompat] MemUtilUnsafe OK: using JOML getToAddress, Java " + java);
            } else {
                MODE = "lwjgl";
                USE_JOML = false;
                // Class name only: the message carries the text that log-check.sh counts as the H6 crash.
                Logger.info("[JomlCompat] MemUtilUnsafe " + unsafe + ": using LWJGL writes, Java " + java);
            }
        }
    }

    /** Null if {@code getToAddress} works for every type used here, else what it threw. */
    private static Throwable probe() {
        // Initialise MemUtil first, the way Minecraft does, before anything touches MemUtilUnsafe. If
        // getToAddress were the first JOML call in the process, MemUtilUnsafe's initialiser would run
        // MemUtil's recursively, and on Java 21/macOS MemUtil.INSTANCE would stay a broken MemUtilUnsafe,
        // so every get(ByteBuffer) in the process would throw too (checked with the 1.10.8 jar).
        try {
            new Matrix4f().zero();
        } catch (Throwable t) {
            return t;
        }
        long scratch = MemoryUtil.nmemAlloc(64);
        if (scratch == 0L) {
            return new OutOfMemoryError("nmemAlloc(64) for the JOML probe");
        }
        try {
            new Matrix4f().getToAddress(scratch);
            new Vector2f().getToAddress(scratch);
            new Vector3f().getToAddress(scratch);
            new Vector4f().getToAddress(scratch);
            new Vector2i().getToAddress(scratch);
            new Vector3i().getToAddress(scratch);
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            MemoryUtil.nmemFree(scratch);
        }
    }

    /** Writes 64 bytes: the 16 floats in column-major order, like {@code m.getToAddress(address)}. */
    public static void put(Matrix4fc m, long address) {
        if (USE_JOML) m.getToAddress(address); else putLwjgl(m, address);
    }

    /** Writes 8 bytes: x, y. */
    public static void put(Vector2fc v, long address) {
        if (USE_JOML) v.getToAddress(address); else putLwjgl(v, address);
    }

    /** Writes 12 bytes: x, y, z. */
    public static void put(Vector3fc v, long address) {
        if (USE_JOML) v.getToAddress(address); else putLwjgl(v, address);
    }

    /** Writes 16 bytes: x, y, z, w. */
    public static void put(Vector4fc v, long address) {
        if (USE_JOML) v.getToAddress(address); else putLwjgl(v, address);
    }

    /** Writes 8 bytes: x, y. */
    public static void put(Vector2ic v, long address) {
        if (USE_JOML) v.getToAddress(address); else putLwjgl(v, address);
    }

    /** Writes 12 bytes: x, y, z. */
    public static void put(Vector3ic v, long address) {
        if (USE_JOML) v.getToAddress(address); else putLwjgl(v, address);
    }

    // The LWJGL writes. Public so the smoke test can compare them on a JVM where getToAddress works.

    public static void putLwjgl(Matrix4fc m, long address) {
        MemoryUtil.memPutFloat(address,      m.m00());
        MemoryUtil.memPutFloat(address + 4,  m.m01());
        MemoryUtil.memPutFloat(address + 8,  m.m02());
        MemoryUtil.memPutFloat(address + 12, m.m03());
        MemoryUtil.memPutFloat(address + 16, m.m10());
        MemoryUtil.memPutFloat(address + 20, m.m11());
        MemoryUtil.memPutFloat(address + 24, m.m12());
        MemoryUtil.memPutFloat(address + 28, m.m13());
        MemoryUtil.memPutFloat(address + 32, m.m20());
        MemoryUtil.memPutFloat(address + 36, m.m21());
        MemoryUtil.memPutFloat(address + 40, m.m22());
        MemoryUtil.memPutFloat(address + 44, m.m23());
        MemoryUtil.memPutFloat(address + 48, m.m30());
        MemoryUtil.memPutFloat(address + 52, m.m31());
        MemoryUtil.memPutFloat(address + 56, m.m32());
        MemoryUtil.memPutFloat(address + 60, m.m33());
    }

    public static void putLwjgl(Vector2fc v, long address) {
        MemoryUtil.memPutFloat(address,     v.x());
        MemoryUtil.memPutFloat(address + 4, v.y());
    }

    public static void putLwjgl(Vector3fc v, long address) {
        MemoryUtil.memPutFloat(address,     v.x());
        MemoryUtil.memPutFloat(address + 4, v.y());
        MemoryUtil.memPutFloat(address + 8, v.z());
    }

    public static void putLwjgl(Vector4fc v, long address) {
        MemoryUtil.memPutFloat(address,      v.x());
        MemoryUtil.memPutFloat(address + 4,  v.y());
        MemoryUtil.memPutFloat(address + 8,  v.z());
        MemoryUtil.memPutFloat(address + 12, v.w());
    }

    public static void putLwjgl(Vector2ic v, long address) {
        MemoryUtil.memPutInt(address,     v.x());
        MemoryUtil.memPutInt(address + 4, v.y());
    }

    public static void putLwjgl(Vector3ic v, long address) {
        MemoryUtil.memPutInt(address,     v.x());
        MemoryUtil.memPutInt(address + 4, v.y());
        MemoryUtil.memPutInt(address + 8, v.z());
    }
}
