package me.cortex.voxy.client.iris;

import com.google.gson.Gson;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import kroppeb.stareval.function.FunctionReturn;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.uniforms.custom.cached.*;
import org.joml.*;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.LongConsumer;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/** Exercises the actual Iris cached and dynamic writers; expected bytes never use JOML packing. */
public final class UniformRegressionTest {
    private static final List<String> failures = new ArrayList<>();
    private static final byte GUARD = (byte) 0xA5;

    public static void main(String[] args) throws Exception {
        check("float vec2", () -> floats(new Float2VectorCachedUniform("f2", PER_FRAME,
                () -> new Vector2f(1.25f, -2.5f)), 1.25f, -2.5f));
        check("float vec3", () -> floats(new Float3VectorCachedUniform("f3", PER_FRAME,
                () -> new Vector3f(1.25f, -2.5f, 3.75f)), 1.25f, -2.5f, 3.75f));
        check("float vec4", () -> floats(new Float4VectorCachedUniform("f4", PER_FRAME,
                () -> new Vector4f(1, -2, 3, -4)), 1, -2, 3, -4));
        check("integer vec2", () -> integers(new Int2VectorCachedUniform("i2", PER_FRAME,
                () -> new Vector2i(0x12345678, -7654321)), 0x12345678, -7654321));
        check("integer vec3", () -> integers(new Int3VectorCachedUniform("i3", PER_FRAME,
                () -> new Vector3i(-19, 65537, Integer.MIN_VALUE)), -19, 65537, Integer.MIN_VALUE));
        check("column-major mat4", () -> {
            Matrix4f m = new Matrix4f(1, 2, 3, 4, 5, 6, 7, 8,
                    9, 10, 11, 12, 13, 14, 15, 16);
            floats(new Float4MatrixCachedUniform("m", PER_FRAME, () -> m),
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        });
        check("real dynamic vec3 updater", UniformRegressionTest::dynamic);
        check("std140 offsets and vec3 padding", UniformRegressionTest::layout);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("PASS: 8 Iris uniform regressions, Java " + System.getProperty("java.version"));
    }

    private static LongConsumer writer(CachedUniform uniform, long offset) throws Exception {
        uniform.update();
        Method method = IrisVoxyRenderPipelineData.class.getDeclaredMethod("createWriter",
                long.class, FunctionReturn.class, CachedUniform.class);
        method.setAccessible(true);
        return (LongConsumer) method.invoke(null, offset, new FunctionReturn(), uniform);
    }

    private static void floats(CachedUniform uniform, float... expected) throws Exception {
        int[] bits = new int[expected.length];
        for (int i = 0; i < expected.length; i++) bits[i] = Float.floatToRawIntBits(expected[i]);
        verify(writer(uniform, 16), 16, bits);
    }

    private static void integers(CachedUniform uniform, int... expected) throws Exception {
        verify(writer(uniform, 16), 16, expected);
    }

    private static void verify(LongConsumer writer, int offset, int[] expected) {
        long memory = MemoryUtil.nmemAlloc(160);
        try {
            MemoryUtil.memSet(memory, GUARD & 255, 160);
            writer.accept(memory);
            for (int i = 0; i < expected.length; i++) require(
                    MemoryUtil.memGetInt(memory + offset + i * 4L) == expected[i], "wrong component " + i);
            for (int i = 0; i < 160; i++) if (i < offset || i >= offset + expected.length * 4)
                require(MemoryUtil.memGetByte(memory + i) == GUARD, "overwrote padding/guard " + i);
        } finally { MemoryUtil.nmemFree(memory); }
    }

    private static void dynamic() throws Exception {
        IrisShaderPatch patch = patch("dynamic");
        List<Object> uniforms = new ArrayList<>();
        Class<?> holderType = Class.forName(IrisVoxyRenderPipelineData.class.getName() + "$1");
        Constructor<?> ctor = holderType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] arguments = new Object[ctor.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            Class<?> type = ctor.getParameterTypes()[i];
            arguments[i] = type == IrisShaderPatch.class ? patch : type == Set.class ? new HashSet<>() : uniforms;
        }
        var holder = (DynamicLocationalUniformHolder) ctor.newInstance(arguments);
        Vector3f value = new Vector3f(10, 20, 30);
        holder.uniform3f("dynamic", () -> value, null);
        Object uniform = uniforms.getFirst();
        Method factory = uniform.getClass().getDeclaredMethod("writingFactory");
        factory.setAccessible(true);
        @SuppressWarnings("unchecked")
        var writerFactory = (Long2ObjectFunction<LongConsumer>) factory.invoke(uniform);
        var updater = writerFactory.get(32L);
        verify(updater, 32, new int[]{Float.floatToRawIntBits(10), Float.floatToRawIntBits(20), Float.floatToRawIntBits(30)});
        value.set(-1, -2, -3);
        verify(updater, 32, new int[]{Float.floatToRawIntBits(-1), Float.floatToRawIntBits(-2), Float.floatToRawIntBits(-3)});
    }

    private static void layout() throws Exception {
        Class<?> holder = Class.forName(IrisVoxyRenderPipelineData.class.getName() + "$UniformWritingHolder");
        Constructor<?> ctor = holder.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        List<Object> uniforms = new ArrayList<>();
        uniforms.add(ctor.newInstance("direction", UniformType.VEC3,
                (Long2ObjectFunction<LongConsumer>) off -> ptr -> {
                    MemoryUtil.memPutFloat(ptr + off, 7);
                    MemoryUtil.memPutFloat(ptr + off + 4, 8);
                    MemoryUtil.memPutFloat(ptr + off + 8, 9);
                }));
        uniforms.add(ctor.newInstance("uv", UniformType.VEC2,
                (Long2ObjectFunction<LongConsumer>) off -> ptr -> {
                    MemoryUtil.memPutFloat(ptr + off, 10);
                    MemoryUtil.memPutFloat(ptr + off + 4, 11);
                }));
        Method layout = IrisVoxyRenderPipelineData.class.getDeclaredMethod("createUniformLayoutStructAndUpdater", List.class);
        layout.setAccessible(true);
        var result = (IrisVoxyRenderPipelineData.StructLayout) layout.invoke(null, uniforms);
        require(result.size() == 32, "std140 block size must include trailing 16-byte alignment, got " + result.size());
        require(result.layout().indexOf("direction") < result.layout().indexOf("uv"), "unexpected GLSL member order");
        long memory = MemoryUtil.nmemAlloc(64);
        try {
            MemoryUtil.memSet(memory, GUARD & 255, 64);
            result.updater().accept(memory);
            require(MemoryUtil.memGetFloat(memory) == 7 && MemoryUtil.memGetFloat(memory + 8) == 9, "vec3 offsets");
            require(MemoryUtil.memGetInt(memory + 12) == 0xA5A5A5A5, "vec3 padding changed");
            require(MemoryUtil.memGetFloat(memory + 16) == 10 && MemoryUtil.memGetFloat(memory + 20) == 11, "vec2 alignment");
            for (int i = 24; i < 64; i++) require(MemoryUtil.memGetByte(memory + i) == GUARD, "tail guard");
        } finally { MemoryUtil.nmemFree(memory); }
    }

    public static IrisShaderPatch patch(String... names) throws Exception {
        Class<?> type = Class.forName(IrisShaderPatch.class.getName() + "$PatchGson");
        Object gson = new Gson().fromJson("{\"version\":1,\"opaqueDrawBuffers\":[0],\"translucentDrawBuffers\":[0],"
                + "\"uniforms\":[],\"opaquePatchData\":\"\",\"translucentPatchData\":\"\"}", type);
        Field field = type.getDeclaredField("uniforms"); field.setAccessible(true); field.set(gson, names);
        Constructor<?> constructor = IrisShaderPatch.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (IrisShaderPatch) constructor.newInstance(gson, null);
    }

    private static void check(String name, CheckedRunnable runnable) {
        try { runnable.run(); System.out.println("PASS: " + name); }
        catch (Throwable e) {
            if (e instanceof InvocationTargetException invocation) e = invocation.getCause();
            failures.add(name + ": " + e); System.out.println("FAIL: " + name + ": " + e);
        }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private interface CheckedRunnable { void run() throws Exception; }
}
