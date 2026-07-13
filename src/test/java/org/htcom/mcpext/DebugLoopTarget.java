package org.htcom.mcpext;

/**
 * Target for the breakpoint smoke test. compute() is called repeatedly in a loop so the breakpoint hits over and over.
 * The breakpoint target line is {@code return doubled;} = <b>line 21</b> (must match this file's actual line number).
 */
public final class DebugLoopTarget {

    private DebugLoopTarget() {
    }

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < 1_000_000; i++) {
            compute(i);
            Thread.sleep(5);
        }
    }

    static int compute(int i) {
        int doubled = i * 2;
        return doubled;
    }
}
