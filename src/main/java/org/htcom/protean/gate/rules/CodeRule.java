/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate.rules;

import java.util.List;

/**
 * SPI for promotion gate ②'s code-check rules. Statically inspects compiled class bytecode.
 *
 * Built-in rules (e.g. {@link ForbiddenApiRule}) always run, and additional rules are automatically collected and
 * applied by the Rule System once you register a CodeRule bean (server-side code-check enforcement — optional).
 */
public interface CodeRule {

    String name();

    /** Checks a single class and returns the list of violation messages (empty list = pass). */
    List<String> check(String className, byte[] bytecode);
}
