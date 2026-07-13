/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.quickstart;

/**
 * A host-provided shared bean that dynamic modules can call. In worker/container isolation a module
 * runs in a separate JVM and cannot see the host's beans directly; declaring this interface in a
 * module's {@code bridgedInterfaces} (with {@code protean.worker.rpc-bridge=true}) injects a proxy that
 * forwards the call to the host over the RPC bridge, so it executes in the main JVM. The implementation
 * returns the main process pid so a caller can prove the method ran in the host, not the worker.
 */
public interface SharedGreeting {

    String greet(String name);
}
