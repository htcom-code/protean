/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request execution watchdog — interrupts a thread when its registered task exceeds the deadline.
 *
 * <b>Cooperative limitation</b>: interrupt only stops blocking work (e.g. Thread.sleep,
 * interruptible I/O). A CPU spin (while(true){}) ignores interrupts and cannot be stopped. Hard
 * caps belong to the OS/process-isolation layer.
 */
@Component
public class ModuleExecutionWatchdog {

    private record Watch(Thread thread, long deadlineNanos) {}

    private final ConcurrentHashMap<Long, Watch> watches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> interrupted = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private ScheduledExecutorService scanner;

    @PostConstruct
    void start() {
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "module-exec-watchdog");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleAtFixedRate(this::scan, 50, 50, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scanner != null) {
            scanner.shutdownNow();
        }
    }

    private void scan() {
        long now = System.nanoTime();
        watches.forEach((token, w) -> {
            if (now >= w.deadlineNanos()) {
                interrupted.put(token, Boolean.TRUE);
                w.thread().interrupt();
            }
        });
    }

    /** Registers the current thread to be interrupted after the timeout and returns a token. */
    public long register(long timeoutMs) {
        long token = seq.incrementAndGet();
        watches.put(token, new Watch(Thread.currentThread(), System.nanoTime() + timeoutMs * 1_000_000));
        return token;
    }

    /** Deregisters. Returns true if the watchdog interrupted this task. */
    public boolean deregister(long token) {
        watches.remove(token);
        return interrupted.remove(token) != null;
    }
}
