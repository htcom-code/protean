package org.htcom.mcpext;

import java.util.List;

/**
 * Target for verifying debug.evaluate. The instance method {@code run} holds locals
 * (String/object/array/List) plus this-fields; a breakpoint on the {@code return marker;} line
 * exercises expression evaluation. {@code run} is called repeatedly so the breakpoint hits over and over.
 */
public final class DebugEvalTarget {

    static final class User {
        private final String name;
        private final int age;

        User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        String getName() {
            return name;
        }

        int getAge() {
            return age;
        }
    }

    private final String tag = "eval-tag";  // for verifying this-field resolution
    private int counter = 7;                 // non-final: for verifying this-field assignment

    public static void main(String[] args) throws InterruptedException {
        DebugEvalTarget self = new DebugEvalTarget();
        for (int i = 0; i < 1_000_000; i++) {
            self.run(i);
            Thread.sleep(5);
        }
    }

    int run(int i) {
        String name = "protean";
        User user = new User("neo", 29);
        int[] nums = {10, 20, 30};
        List<String> items = List.of("a", "b");
        int marker = i;
        return marker;
    }
}
