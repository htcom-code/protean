/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.htcom.protean.gate.rules.CodeRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Code-check system (gate ②) that applies all registered {@link CodeRule}s to module bytecode.
 * Spring injects every CodeRule bean, so adding a rule bean makes it automatically enforced (optional extension).
 */
@Component
public class RuleSystem {

    private final List<CodeRule> rules;

    public RuleSystem(List<CodeRule> rules) {
        this.rules = rules;
    }

    /** Checks all module classes and returns the collected violation messages (empty list = pass). */
    public List<String> check(Map<String, byte[]> classes) {
        List<String> violations = new ArrayList<>();
        for (CodeRule rule : rules) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                violations.addAll(rule.check(e.getKey(), e.getValue()));
            }
        }
        return violations;
    }

    public List<String> ruleNames() {
        return rules.stream().map(CodeRule::name).toList();
    }
}
