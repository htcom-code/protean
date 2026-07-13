/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug core — <b>protocol-agnostic</b>. Attaches over a JDI socket to a target JVM (debug worker) with JDWP enabled
 * and manages session lifecycles. MCP is only a thin adapter on top of this, and this core knows nothing about the
 * transport or protocol — mirroring the "protocol-agnostic core" pattern of the Lua debugger. zero-dep: JDI is a JDK
 * standard (jdk.jdi).
 *
 * <p><b>Idle auto-reclamation (leak guard)</b>: when {@code idleTimeoutMillis > 0}, a daemon sweeper periodically
 * calls {@link #terminate} on sessions whose idle time since last activity exceeds the threshold — so that even if an
 * agent forgets to call {@code debug.terminate}, the debug-launch worker JVM does not leak (terminate → dispose hook →
 * worker kill). The activity timestamp is refreshed at the tool-access choke point {@link #session(String)}.
 */
public class DebugCore {

    private static final Logger log = LoggerFactory.getLogger(DebugCore.class);
    private static final String SOCKET_ATTACH = "com.sun.jdi.SocketAttach";

    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private final long idleTimeoutMillis;             // <=0 = disabled
    private final ScheduledExecutorService sweeper;   // null = disabled

    /** Core without a sweeper (for tests / manual lifecycle management). */
    public DebugCore() {
        this(0L);
    }

    /** @param idleTimeoutMillis session idle threshold (ms). If {@code <=0}, auto-reclamation is disabled. */
    public DebugCore(long idleTimeoutMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        if (idleTimeoutMillis > 0) {
            this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "protean-debug-idle-sweeper");
                t.setDaemon(true);
                return t;
            });
            long periodMs = Math.min(Math.max(idleTimeoutMillis / 4, 1_000L), 60_000L);
            sweeper.scheduleWithFixedDelay(this::reclaimIdle, periodMs, periodMs, TimeUnit.MILLISECONDS);
            log.info("debug session idle auto-reclamation enabled: timeout {}ms, sweep {}ms", idleTimeoutMillis, periodMs);
        } else {
            this.sweeper = null;
        }
    }

    /** Attaches to a target running a JDWP server ({@code host:port}) and opens a session (owner not recorded). */
    public DebugSession attach(String host, int port) throws IOException {
        return attach(host, port, null);
    }

    /** Attaches to a JDWP target and opens a session. {@code owner} is the requester identity (nullable) — basis for future per-user lookups. */
    public DebugSession attach(String host, int port, String owner) throws IOException {
        AttachingConnector connector = socketAttachConnector();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));
        VirtualMachine vm;
        try {
            vm = connector.attach(args);
        } catch (IllegalConnectorArgumentsException e) {
            throw new IllegalStateException("JDI attach argument error: " + e.argumentNames(), e);
        }
        String id = "dbg-" + seq.incrementAndGet();
        DebugSession session = new DebugSession(id, vm, owner);
        sessions.put(id, session);
        return session;
    }

    /** Snapshot of active sessions (for list_sessions). Listing does not count as activity (no touch). */
    public java.util.Collection<DebugSession> sessions() {
        return java.util.List.copyOf(sessions.values());
    }

    /** Looks up a session. The lookup itself counts as "activity" and resets the idle timer (tool-access choke point). */
    public DebugSession session(String id) {
        DebugSession session = sessions.get(id);
        if (session != null) {
            session.touch();
        }
        return session;
    }

    /** Terminates a session and unregisters it. */
    public void terminate(String id) {
        DebugSession session = sessions.remove(id);
        if (session != null) {
            session.dispose();
        }
    }

    /** Cleans up all sessions (shutdown / leak prevention). */
    public void terminateAll() {
        sessions.keySet().forEach(this::terminate);
    }

    /** Stops the sweeper and cleans up all sessions (on bean destruction). */
    public void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        terminateAll();
    }

    /** Reclaims sessions that have exceeded the idle threshold (sweeper callback). */
    private void reclaimIdle() {
        long now = System.nanoTime();
        long ttlNanos = idleTimeoutMillis * 1_000_000L;
        for (Map.Entry<String, DebugSession> e : sessions.entrySet()) {
            long idleNanos = now - e.getValue().lastActivityNanos();
            if (idleNanos > ttlNanos) {
                log.warn("auto-reclaiming idle debug session: {} (idle {}ms > {}ms); presumed debug.terminate was not called",
                        e.getKey(), idleNanos / 1_000_000L, idleTimeoutMillis);
                try {
                    terminate(e.getKey());
                } catch (RuntimeException ex) {
                    log.warn("failed to reclaim idle session (ignored): {} - {}", e.getKey(), ex.getMessage());
                }
            }
        }
    }

    private AttachingConnector socketAttachConnector() {
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (SOCKET_ATTACH.equals(c.name())) {
                return c;
            }
        }
        throw new IllegalStateException("no JDI SocketAttach connector — verify you are running on a JDK (jdk.jdi)");
    }
}
