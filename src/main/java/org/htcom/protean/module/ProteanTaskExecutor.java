/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Module-owned managed executor. When a module injects this instead of using raw {@code new Thread} to run
 * async/scheduled work, closing the child context on module unload triggers {@link #close()} on this bean,
 * cleaning up its threads and jobs (prevents thread leaks that pin a dead ClassLoader).
 *
 * <p>Per-module, lazy (created on injection), bounded (fixed pool). Threads are daemons tagged with the moduleId.
 */
public final class ProteanTaskExecutor implements AutoCloseable {

    private final ScheduledExecutorService delegate;

    public ProteanTaskExecutor(String moduleId, int poolSize) {
        AtomicInteger seq = new AtomicInteger();
        this.delegate = Executors.newScheduledThreadPool(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "protean-mod-" + moduleId + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void execute(Runnable task) {
        delegate.execute(task);
    }

    public Future<?> submit(Runnable task) {
        return delegate.submit(task);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(task);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return delegate.schedule(task, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return delegate.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    /** Underlying scheduled executor (escape hatch). Do not leak the returned reference outside the module. */
    public ScheduledExecutorService raw() {
        return delegate;
    }

    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    /** Invoked on unload (child.close() -&gt; AutoCloseable). Interrupts in-flight jobs and reclaims threads. */
    @Override
    public void close() {
        delegate.shutdownNow();
    }
}
