package me.cortex.voxy.client.core;

/** Deterministic clock inputs test the production readback gate, without a GPU or sleeps. */
public final class DiagnosticWindowRegressionTest {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("me.cortex.voxy.client.core.DiagnosticWindow");
        var constructor = type.getDeclaredConstructor(boolean.class); constructor.setAccessible(true);
        var sample = type.getDeclaredMethod("shouldSample",long.class); sample.setAccessible(true);
        Object disabled = constructor.newInstance(false);
        if ((boolean)sample.invoke(disabled,0L)) throw new AssertionError("disabled readbacks ran");
        Object enabled = constructor.newInstance(true);
        int count = 0;
        for (long now = 0; now <= 65_000_000_000L; now += 1_000_000L) {
            boolean actual = (boolean)sample.invoke(enabled,now);
            boolean expected = now < 60_000_000_000L && now % 500_000_000L == 0;
            if (actual != expected) throw new AssertionError("sample gate at "+now+": "+actual);
            if (actual) count++;
        }
        if (count != 120) throw new AssertionError("expected exactly 120 bounded samples");
        System.out.println("PASS: disabled diagnostics do no reads; enabled diagnostics stop after 120 samples in 60 seconds");
    }
}
