/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import java.util.List;

/**
 * One runtime that can host modules: the main JVM, a worker JVM, or a worker container.
 *
 * <p>Module status already carries a {@code runtimeId}, so grouping modules by it shows how the platform packed them.
 * What that grouping cannot show is a runtime with <b>no</b> modules — a warm worker kept for reuse, or one that is
 * retiring while its last request drains. Those are exactly the states an operator needs when reasoning about capacity
 * and about a scope's blast radius, and no consumer can add the surface that reveals them, so the platform reports it.
 *
 * <p>{@code runtimeId} is the same opaque value module status reports, and is the join key between the two. It never
 * carries the worker's port: the control plane is the platform's business, not the operator's addressing scheme.
 *
 * @param runtimeId  opaque host id ({@code main}, {@code worker:<uuid>}, {@code container:<name>})
 * @param mode       isolation mode this runtime implements ({@code in-process} | {@code worker} | {@code container})
 * @param scope      DB scope the runtime is bound to under auto-provision; null when provisioning is off
 * @param state      {@code LIVE} — accepting modules; {@code RETIRING} — pulled from the pool, draining before teardown
 * @param sinceEpochMs  when this runtime was started (host clock), for uptime
 * @param moduleIds  modules currently hosted here; empty for a warm or retiring runtime
 */
public record RuntimeInfo(
        String runtimeId,
        String mode,
        String scope,
        State state,
        long sinceEpochMs,
        List<String> moduleIds
) {

    /** Whether the runtime still accepts modules. A RETIRING runtime is torn down once its drain completes. */
    public enum State { LIVE, RETIRING }

    public RuntimeInfo {
        moduleIds = moduleIds == null ? List.of() : List.copyOf(moduleIds);
    }

    /** Same runtime with its membership attached — the platform fills this in by grouping on {@code runtimeId}. */
    public RuntimeInfo withModules(List<String> hosted) {
        return new RuntimeInfo(runtimeId, mode, scope, state, sinceEpochMs, hosted);
    }
}
