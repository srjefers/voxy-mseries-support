package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.VxFogCap;
import me.cortex.voxy.client.iris.VxSsrMaskFix;

/** BSL's source-level water/fog assumptions must not rewrite a generic shader contract. */
public final class BslSourcePatchScopeRegressionTest {
    private static void check(Class<?> patch) throws Exception {
        var method=patch.getDeclaredMethod("isAllowed",boolean.class);
        method.setAccessible(true);
        if ((boolean)method.invoke(null,false)) throw new AssertionError(patch.getSimpleName()+" changed generic contracts");
        if (!(boolean)method.invoke(null,true)) throw new AssertionError(patch.getSimpleName()+" lost explicit BSL mode");
    }

    public static void main(String[] args) throws Exception {
        check(VxFogCap.class);
        check(VxSsrMaskFix.class);
        System.out.println("PASS: BSL-only source rewrites require explicit compatibility mode");
    }
}
