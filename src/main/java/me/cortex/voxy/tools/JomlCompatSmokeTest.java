package me.cortex.voxy.tools;

import me.cortex.voxy.common.util.JomlCompat;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * H6 check for {@link JomlCompat}: the LWJGL writes must produce exactly the bytes JOML writes.
 *
 * <p>For each JOML type Voxy writes to native memory (Matrix4f, Vector2f/3f/4f, Vector2i/3i) it builds random
 * values plus special floats (±0, subnormals, ±Inf, NaN with a payload), writes each one at offsets 0, 4 and 12
 * (4 and 12 are not 8-byte aligned) between sentinel bytes, and compares the whole buffer byte for byte with
 * JOML's {@code get(int, ByteBuffer)}, which works on every JVM (it falls back to MemUtilNIO). Where JOML's
 * {@code getToAddress} works, it is compared too. {@link JomlCompat#put} is compared in the mode the
 * environment selects.
 *
 * <p>It also checks that JomlCompat's one-time probe, run here before anything else touches JOML, leaves JOML's
 * own {@code get(ByteBuffer)} working: when {@code getToAddress} is the first JOML call of a process on Java 21
 * on macOS, MemUtil ends up with a broken MemUtilUnsafe instance.
 *
 * <pre>
 * ./gradlew testJomlCompat                        # on the Gradle JVM
 * ./gradlew testJomlCompat -PtestJava=launcher    # on the official launcher's Java 21
 * VOXY_JOML_FORCE_FALLBACK=1 ./gradlew testJomlCompat
 * </pre>
 * Exit status 0 = pass, 1 = a mismatch or an exception.
 */
public final class JomlCompatSmokeTest {
    private JomlCompatSmokeTest() {}

    private static final int CASES = 20_000;
    private static final int[] OFFSETS = {0, 4, 12};
    private static final int GUARD = 16;
    private static final byte SENTINEL = (byte) 0xA5;
    private static final long SEED = 0x766F78794A4F4D4CL;

    private static final float[] SPECIAL_FLOATS = {
            0f, -0f, 1f, -1f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE, -Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN,
            Float.intBitsToFloat(0x7fc12345), Float.intBitsToFloat(0xffc00001),
    };
    private static final int[] SPECIAL_INTS = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};

    @FunctionalInterface private interface AddressWriter { void write(Object value, long address); }
    @FunctionalInterface private interface BufferWriter { void write(Object value, int index, ByteBuffer buffer); }

    private record Kind(String name, int bytes, Function<Random, Object> generator, BufferWriter reference,
                        AddressWriter lwjgl, AddressWriter joml, AddressWriter compat) {}

    private static final class Result {
        long compared;
        int mismatches;
        String firstMismatch;
        Throwable error;

        String cell() {
            if (this.error != null) return "THREW " + this.error.getClass().getSimpleName();
            if (this.mismatches > 0) return "MISMATCH x" + this.mismatches;
            return this.compared == 0 ? "n/a" : "OK";
        }

        boolean failed() {
            return this.error != null || this.mismatches > 0;
        }
    }

    public static void main(String[] args) {
        // First touch of JOML in this process: JomlCompat's probe, as the class initialises.
        String mode = JomlCompat.MODE;

        System.out.println("== JomlCompat smoke test (H6)");
        System.out.println("java        " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor")
                + "), " + System.getProperty("java.home"));
        System.out.println("JOML        " + Matrix4f.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("env         VOXY_JOML_FORCE_FALLBACK=" + envOrDash("VOXY_JOML_FORCE_FALLBACK")
                + " VOXY_JOML_COMPAT=" + envOrDash("VOXY_JOML_COMPAT"));
        System.out.println("JomlCompat  mode = " + mode);

        boolean failed = false;
        String afterProbe = checkJomlAfterProbe();
        System.out.println("JOML        after the probe: " + afterProbe);
        if (!afterProbe.startsWith("OK")) failed = true;

        String unsafe = probeGetToAddress();
        boolean jomlWorks = unsafe.equals("OK");
        System.out.println("getToAddress " + unsafe);
        System.out.println();

        List<Kind> kinds = kinds();
        System.out.printf("%-9s %7s  %-24s %-24s %-24s%n", "type", "cases", "LWJGL", "getToAddress", "JomlCompat.put");
        Random random = new Random(SEED);
        for (Kind kind : kinds) {
            Result lwjgl = new Result(), joml = new Result(), compat = new Result();
            ByteBuffer ref = alloc(kind.bytes());
            ByteBuffer out = alloc(kind.bytes());
            try {
                for (int c = 0; c < CASES; c++) {
                    Object value = kind.generator().apply(random);
                    for (int off : OFFSETS) {
                        fill(ref);
                        kind.reference().write(value, GUARD + off, ref);
                        compare(kind, value, off, ref, out, kind.lwjgl(), lwjgl);
                        if (jomlWorks) compare(kind, value, off, ref, out, kind.joml(), joml);
                        compare(kind, value, off, ref, out, kind.compat(), compat);
                    }
                }
            } finally {
                MemoryUtil.memFree(ref);
                MemoryUtil.memFree(out);
            }
            System.out.printf("%-9s %7d  %-24s %-24s %-24s%n", kind.name(), CASES, lwjgl.cell(), joml.cell(), compat.cell());
            for (Result r : new Result[]{lwjgl, joml, compat}) {
                if (r.firstMismatch != null) System.out.println("          first mismatch: " + r.firstMismatch);
                if (r.error != null) System.out.println("          " + r.error);
                failed |= r.failed();
            }
        }

        System.out.println();
        System.out.println("Each cell compares the whole buffer with JOML's get(int, ByteBuffer), " + CASES
                + " values x offsets 0/4/12, " + GUARD + " sentinel bytes on each side.");
        System.out.println(failed ? "== FAIL" : "== PASS");
        System.exit(failed ? 1 : 0);
    }

    private static void compare(Kind kind, Object value, int off, ByteBuffer ref, ByteBuffer out,
                                AddressWriter writer, Result result) {
        if (result.error != null) return;
        fill(out);
        try {
            writer.write(value, MemoryUtil.memAddress(out) + GUARD + off);
        } catch (Throwable t) {
            result.error = t;
            return;
        }
        result.compared++;
        int at = ref.mismatch(out);
        if (at >= 0) {
            if (result.mismatches++ == 0) {
                result.firstMismatch = kind.name() + " offset " + off + " byte " + (at - GUARD - off) + ": expected "
                        + hex(ref, off, kind.bytes()) + " got " + hex(out, off, kind.bytes()) + " for " + value;
            }
        }
    }

    private static List<Kind> kinds() {
        List<Kind> k = new ArrayList<>();
        k.add(new Kind("Matrix4f", 64,
                r -> new Matrix4f(f(r), f(r), f(r), f(r), f(r), f(r), f(r), f(r),
                        f(r), f(r), f(r), f(r), f(r), f(r), f(r), f(r)),
                (v, i, b) -> ((Matrix4f) v).get(i, b),
                (v, a) -> JomlCompat.putLwjgl((Matrix4f) v, a),
                (v, a) -> ((Matrix4f) v).getToAddress(a),
                (v, a) -> JomlCompat.put((Matrix4f) v, a)));
        k.add(new Kind("Vector2f", 8,
                r -> new Vector2f(f(r), f(r)),
                (v, i, b) -> ((Vector2f) v).get(i, b),
                (v, a) -> JomlCompat.putLwjgl((Vector2f) v, a),
                (v, a) -> ((Vector2f) v).getToAddress(a),
                (v, a) -> JomlCompat.put((Vector2f) v, a)));
        k.add(new Kind("Vector3f", 12,
                r -> new Vector3f(f(r), f(r), f(r)),
                (v, i, b) -> ((Vector3f) v).get(i, b),
                (v, a) -> JomlCompat.putLwjgl((Vector3f) v, a),
                (v, a) -> ((Vector3f) v).getToAddress(a),
                (v, a) -> JomlCompat.put((Vector3f) v, a)));
        k.add(new Kind("Vector4f", 16,
                r -> new Vector4f(f(r), f(r), f(r), f(r)),
                (v, i, b) -> ((Vector4f) v).get(i, b),
                (v, a) -> JomlCompat.putLwjgl((Vector4f) v, a),
                (v, a) -> ((Vector4f) v).getToAddress(a),
                (v, a) -> JomlCompat.put((Vector4f) v, a)));
        k.add(new Kind("Vector2i", 8,
                r -> new Vector2i(n(r), n(r)),
                (v, i, b) -> ((Vector2i) v).get(i, b),
                (v, a) -> JomlCompat.putLwjgl((Vector2i) v, a),
                (v, a) -> ((Vector2i) v).getToAddress(a),
                (v, a) -> JomlCompat.put((Vector2i) v, a)));
        k.add(new Kind("Vector3i", 12,
                r -> new Vector3i(n(r), n(r), n(r)),
                (v, i, b) -> ((Vector3i) v).get(i, b),
                (v, a) -> JomlCompat.putLwjgl((Vector3i) v, a),
                (v, a) -> ((Vector3i) v).getToAddress(a),
                (v, a) -> JomlCompat.put((Vector3i) v, a)));
        return k;
    }

    /** A float: 1 in 8 special, 1 in 8 any bit pattern except a signalling NaN, else a finite value. */
    private static float f(Random r) {
        switch (r.nextInt(8)) {
            case 0:
                return SPECIAL_FLOATS[r.nextInt(SPECIAL_FLOATS.length)];
            case 1: {
                int bits = r.nextInt();
                boolean nan = (bits & 0x7f800000) == 0x7f800000 && (bits & 0x007fffff) != 0;
                if (nan) bits |= 0x00400000; // quiet: a CPU may set this bit on a signalling NaN
                return Float.intBitsToFloat(bits);
            }
            default:
                return (r.nextFloat() * 2f - 1f) * (float) Math.pow(10, r.nextInt(9) - 4);
        }
    }

    private static int n(Random r) {
        return r.nextInt(8) == 0 ? SPECIAL_INTS[r.nextInt(SPECIAL_INTS.length)] : r.nextInt();
    }

    private static ByteBuffer alloc(int bytes) {
        return MemoryUtil.memAlloc(GUARD + 12 + bytes + GUARD).order(ByteOrder.nativeOrder());
    }

    private static void fill(ByteBuffer b) {
        for (int i = 0; i < b.capacity(); i++) b.put(i, SENTINEL);
    }

    private static String hex(ByteBuffer b, int off, int bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes; i++) sb.append(String.format("%02x", b.get(GUARD + off + i)));
        return sb.toString();
    }

    /** JOML's own buffer path must still work after JomlCompat's probe was the first JOML use. */
    private static String checkJomlAfterProbe() {
        String instance;
        try {
            var field = Class.forName("org.joml.MemUtil").getDeclaredField("INSTANCE");
            field.setAccessible(true);
            instance = field.get(null).getClass().getName();
        } catch (Throwable t) {
            instance = "? (" + t.getClass().getSimpleName() + ")";
        }
        ByteBuffer b = alloc(64);
        try {
            new Matrix4f().get(0, b);
            return "OK, get(ByteBuffer) works; MemUtil.INSTANCE = " + instance;
        } catch (Throwable t) {
            return "BROKEN, get(ByteBuffer) threw " + t + "; MemUtil.INSTANCE = " + instance;
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    private static String probeGetToAddress() {
        long a = MemoryUtil.nmemAlloc(64);
        try {
            new Matrix4f().getToAddress(a);
            return "OK";
        } catch (Throwable t) {
            return "unavailable (" + t.getClass().getName() + ")";
        } finally {
            MemoryUtil.nmemFree(a);
        }
    }

    private static String envOrDash(String name) {
        String v = System.getenv(name);
        return v == null ? "-" : v;
    }
}
