/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.bridge.BridgeInvocationHandler;
import org.htcom.protean.bridge.EchoPort;
import org.htcom.protean.bridge.LedgerPort;
import org.htcom.protean.bridge.Point;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advanced RPC bridge checks: generic collections (List&lt;T&gt;), exception propagation, and transaction boundaries.
 * Without a worker JVM, it uses RANDOM_PORT to call the same app's main BridgeController over real HTTP and
 * directly verifies bridge serialization, the exception envelope, and the @Transactional boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RpcBridgeAdvancedTest.GreeterConfig.class)
class RpcBridgeAdvancedTest {

    /** Two implementation beans for the same interface — used to verify that specifying beanName selects exactly one. */
    public interface Greeter {
        String greet();
    }

    @TestConfiguration
    static class GreeterConfig {
        @Bean("formalGreeter")
        Greeter formal() {
            return () -> "Good day";
        }

        @Bean("casualGreeter")
        Greeter casual() {
            return () -> "yo";
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    EchoPort echo;
    LedgerPort ledger;

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + port;
        echo = proxy(EchoPort.class, base);
        ledger = proxy(LedgerPort.class, base);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> iface, String base) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{iface},
                new BridgeInvocationHandler(base, iface, mapper));
    }

    @SuppressWarnings("unchecked")
    private <T> T namedProxy(Class<T> iface, String base, String beanName) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{iface},
                new BridgeInvocationHandler(base, iface, mapper, beanName));
    }

    @Test
    void generic_list_argument_and_return_round_trip() {
        List<Point> out = echo.shift(List.of(new Point(1, 1), new Point(2, 2)), 10, 10);
        assertEquals(2, out.size());
        // must be restored as Point without erasure (not LinkedHashMap)
        assertEquals(new Point(11, 11), out.get(0));
        assertEquals(new Point(12, 12), out.get(1));
    }

    @Test
    void business_exception_is_propagated_with_type_and_message() {
        // happy-path sanity
        assertEquals(10, echo.risky(5));
        // the IllegalArgumentException thrown by the main bean propagates to the worker with the same type
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> echo.risky(-5));
        assertTrue(ex.getMessage().contains("negative not allowed: -5"), ex.getMessage());
    }

    @Test
    void map_return_round_trip() {
        Map<String, Integer> m = echo.tally(List.of("a", "bb", "ccc"));
        assertEquals(Map.of("a", 1, "bb", 2, "ccc", 3), m);
    }

    @Test
    void nested_generic_map_of_list_round_trip() {
        // Map<String, List<Point>> — nested generics must be restored as Point without erasure.
        Map<String, List<Point>> g = echo.groupByParity(
                List.of(new Point(2, 0), new Point(3, 0), new Point(4, 0)));
        assertEquals(List.of(new Point(2, 0), new Point(4, 0)), g.get("even"));
        assertEquals(List.of(new Point(3, 0)), g.get("odd"));
    }

    @Test
    void null_argument_and_null_return_round_trip() {
        assertNull(echo.echoOrNull(null), "passing a null argument and returning null must round-trip unchanged");
        assertEquals("echo:hi", echo.echoOrNull("hi"));
    }

    @Test
    void overloaded_methods_resolve_by_declared_arg_type() {
        // the identically named size must resolve precisely by the declared argument type (String vs List).
        assertEquals(3, echo.size("abc"));
        assertEquals(2, echo.size(List.of(new Point(0, 0), new Point(1, 1))));
    }

    @Test
    void exception_cause_chain_and_remote_stacktrace_are_propagated() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> echo.riskyChained(-1));
        assertEquals("outer failure", ex.getMessage());

        // the cause chain must be restored with its type and message intact.
        Throwable cause = ex.getCause();
        assertNotNull(cause, "the cause must be propagated");
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("inner cause: -1"), cause.getMessage());

        // the remote stack trace must be restored and point at the main impl frame (debuggable).
        assertTrue(ex.getStackTrace().length > 0, "the remote stack must be preserved");
        boolean refsImpl = Arrays.stream(ex.getStackTrace())
                .anyMatch(f -> f.getClassName().contains("EchoPortImpl"));
        assertTrue(refsImpl, "the stack must contain the remote EchoPortImpl frame: " + Arrays.toString(ex.getStackTrace()));
    }

    @Test
    void bean_name_selects_specific_impl_when_interface_is_ambiguous() {
        String base = "http://localhost:" + port;
        Greeter formal = namedProxy(Greeter.class, base, "formalGreeter");
        Greeter casual = namedProxy(Greeter.class, base, "casualGreeter");
        assertEquals("Good day", formal.greet());
        assertEquals("yo", casual.greet());
    }

    @Test
    void ambiguous_interface_without_bean_name_fails() {
        String base = "http://localhost:" + port;
        Greeter noName = proxy(Greeter.class, base);   // no beanName -> main tries to resolve by type -> ambiguous
        IllegalStateException ex = assertThrows(IllegalStateException.class, noName::greet);
        assertTrue(ex.getMessage().contains("bridge call failed"), ex.getMessage());
    }

    @Test
    void transactional_boundary_rolls_back_failed_call_but_commits_success() {
        int before = ledger.count();

        // exception after insert -> same transaction so it rolls back, no trace + the exception propagates
        assertThrows(IllegalStateException.class, () -> ledger.recordThenFail("x", 1));
        assertEquals(before, ledger.count(), "a failed call must roll back with no increase");

        // a successful call commits
        ledger.record("y", 2);
        assertEquals(before + 1, ledger.count(), "a successful call commits and increases by 1");
    }
}
