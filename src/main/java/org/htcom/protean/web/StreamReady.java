/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

/**
 * Connection acknowledgement: the first frame of every {@code /platform/traces/stream} connection
 * ({@code event: ready}). It exists because "the stream is open" and "the server is actually running"
 * are indistinguishable to a client on a quiet platform — nothing else arrives until traffic does.
 *
 * <p>This is an ack, not a data frame. It carries only values a client must know <em>once, at connect
 * time</em> and cannot derive from the stream afterwards; anything that changes on a schedule belongs to
 * the {@code metrics}/{@code modules}/{@code summary} frames instead.
 *
 * @param platform        implementation identifier, constant {@code "protean"}. Present so a console that
 *                        watches several implementations does not have to read a bare version number and
 *                        guess whose it is. Display/diagnostics only — clients must not branch behaviour on it.
 * @param platformVersion this library's version, read from the jar manifest ({@code Implementation-Version}).
 *                        {@code null} when running from a layout that has no manifest (exploded classes: tests,
 *                        IDE runs) — the version is genuinely unknown there, and a placeholder string would be
 *                        a fabricated answer to "which version am I talking to".
 * @param tracesEnabled   {@code protean.trace.enabled}: distinguishes "recording is off" from "recording is on
 *                        but nothing has happened yet", which an empty stream cannot.
 * @param metricsEnabled  {@code protean.trace.metrics.enabled}: same distinction for the {@code metrics} frame.
 * @param buffered        how many rows the {@code trace} frame that follows this one carries — <em>not</em> how
 *                        many the ring holds. The two differ whenever the ring is larger than the replay cap, and
 *                        announcing a count while withholding the rows is worse than announcing neither.
 * @param tickMs          the push period, so a client's silence watchdog has a contract to size itself against
 *                        instead of assuming one.
 * @param capacity        {@code protean.trace.capacity} <em>as of this connection</em>, letting a client say
 *                        "nothing older than this is on the server". It is a live key, so a later change does not
 *                        reach an already-connected client; the value is re-sent on reconnect.
 */
public record StreamReady(
        String platform,
        String platformVersion,
        boolean tracesEnabled,
        boolean metricsEnabled,
        int buffered,
        long tickMs,
        int capacity
) {

    private static final String PLATFORM = "protean";

    /** Resolved once: a jar manifest cannot change while the JVM runs. */
    private static final String VERSION = version();

    private static String version() {
        Package pkg = StreamReady.class.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
    }

    /** Ack for a connection that is about to replay {@code buffered} trace rows. */
    public static StreamReady of(boolean tracesEnabled, boolean metricsEnabled, int buffered, long tickMs, int capacity) {
        return new StreamReady(PLATFORM, VERSION, tracesEnabled, metricsEnabled, buffered, tickMs, capacity);
    }
}
