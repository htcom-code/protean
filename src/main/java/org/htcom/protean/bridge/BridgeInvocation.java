/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import java.util.List;

/**
 * RPC bridge call payload (worker to main). Supports not only simple types but also composite DTO and
 * generic collection arguments (the main-side {@code BridgeController} deserializes them accurately
 * using the method's generic signature).
 *
 * @param iface    FQCN of the shared interface
 * @param method   method name
 * @param argTypes list of argument type FQCNs (for method resolution; includes primitive type names)
 * @param args     argument values (JSON-serialized; primitives, wrappers, composite DTOs, generic collections)
 * @param beanName target bean name (specify when an interface has multiple implementations; if null/blank, resolved singly by type)
 */
public record BridgeInvocation(String iface, String method, List<String> argTypes, List<Object> args,
                               String beanName) {}
