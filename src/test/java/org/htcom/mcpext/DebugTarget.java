package org.htcom.mcpext;

/**
 * Target JVM for the debug attach smoke test. Started as a JDWP server so that protean's
 * {@code DebugCore} can attach to it. Lives outside the {@code org.htcom.protean} package, so it is
 * not picked up by component scanning.
 */
public final class DebugTarget {

    private DebugTarget() {
    }

    public static void main(String[] args) throws InterruptedException {
        // Just needs to stay alive until something attaches (smoke test).
        Thread.sleep(60_000);
    }
}
