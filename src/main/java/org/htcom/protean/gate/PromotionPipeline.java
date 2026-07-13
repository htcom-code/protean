/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.htcom.protean.module.ModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Promotion gate. ① unit tests → ② review (code check).
 *
 * Modules are required to bundle unit tests; they are compiled and run in an isolated stage and must all be green
 * to be promoted. Each gate can be toggled via {@code protean.gate.*} (all on by default — safe default preserving
 * existing behavior). Library consumers can relax them to match their trust level. (③ verification runs
 * conditionally, depending on whether the descriptor has a VerificationPlan.)
 */
@Component
public class PromotionPipeline {

    private static final Logger log = LoggerFactory.getLogger(PromotionPipeline.class);

    private final RuntimeCompiler compiler;
    private final RuleSystem ruleSystem;
    private final SignatureGate signatureGate;
    private final ProteanProperties props;
    private final ProteanProperties.Gate gate;
    private final ModuleTestRunner testRunner = new ModuleTestRunner();

    public PromotionPipeline(RuntimeCompiler compiler, RuleSystem ruleSystem,
                             SignatureGate signatureGate, ProteanProperties props) {
        this.compiler = compiler;
        this.ruleSystem = ruleSystem;
        this.signatureGate = signatureGate;
        this.props = props;
        // The Gate object reference is held live: mutating its fields (protean.gate.*) propagates here (Tier 1).
        this.gate = props.getGate();
    }

    /**
     * Runs the promotion gates: (signature, opt-in) → ① tests → ② review (code check). Each gate is toggled via
     * {@code protean.gate.*}. Disabled gates are skipped and the fact is logged (to prevent silent bypass).
     * Throws {@link GateFailedException} on failure.
     */
    public void runGates(ModuleDescriptor descriptor) {
        if (gate.getSignature().isRequired()) {
            signatureGate.enforce(descriptor);   // integrity, authenticity, authorization — blocked up front
        }
        if (gate.isTestsEnabled()) {
            enforceTestGate(descriptor);
        } else {
            log.warn("gate ①(tests) disabled (protean.gate.tests-enabled=false) — skipping test enforcement for module {}", descriptor.id());
        }
        if (gate.isReviewEnabled()) {
            enforceReviewGate(descriptor);
        } else {
            log.warn("gate ②(review) disabled (protean.gate.review-enabled=false) — skipping code check for module {}", descriptor.id());
        }
    }

    /** Gate ②: bytecode guardrails + registered code rules. Throws {@link GateFailedException} on violation. */
    public void enforceReviewGate(ModuleDescriptor descriptor) {
        // Compile the main sources under the real module id (not the shared "module" slot): this both lets the
        // subsequent deploy hit the compile cache (same id + sources → no re-javac) and attributes the observed
        // shared-lib usage (§B) to this module, so ModulePlatform can persist it before the state save. Resolve the
        // parent tier from the module's `uses` so a dependent's references to a used library's exported types compile.
        ModuleClassLoader loader = compiler.compileAll(descriptor.sources(), Map.of(), descriptor.id(),
                compiler.resolveParentTier(descriptor.uses()));
        List<String> violations = ruleSystem.check(loader.bytecode());
        if (!violations.isEmpty()) {
            throw new GateFailedException("review", "code check violations: " + violations);
        }
    }

    /** Gate ①: enforce and run unit tests. Throws {@link GateFailedException} on failure. */
    public void enforceTestGate(ModuleDescriptor descriptor) {
        if (descriptor.tests() == null || descriptor.tests().isEmpty()) {
            throw new GateFailedException("tests", "unit tests are required (tests must be bundled)");
        }

        // Compile main + test sources with a single throwaway module loader (tests reference the target classes).
        // Ephemeral: it must not pollute this module's compile-cache entry or retain library generations. The parent
        // tier from `uses` puts any used library's exported types on the test compile classpath + parent.
        Map<String, String> all = new HashMap<>(descriptor.sources());
        all.putAll(descriptor.tests());
        ModuleClassLoader loader = compiler.compileEphemeral(all, compiler.resolveParentTier(descriptor.uses()));

        boolean captureTestOutput = props.getMcp().isCaptureTestOutput();   // live (Tier 1)
        ModuleTestRunner.Result result =
                testRunner.run(loader, new ArrayList<>(descriptor.tests().keySet()), captureTestOutput);
        if (!result.green()) {
            String detail = "unit tests failed "
                    + result.failed() + "/" + (result.succeeded() + result.failed()) + " "
                    + result.failures();
            if (captureTestOutput && !result.output().isBlank()) {
                detail += "\n--- test output ---\n" + result.output();
            }
            throw new GateFailedException("tests", detail);
        }
    }

    /**
     * Promotion gate rejection. Carries the stable code {@link ErrorCode#GATE_FAILED} plus the identifier of the
     * failed gate (the {@code gate} extension member) so the boundary can emit it in structured form. {@code gate}
     * is one of {@code signature}/{@code tests}/{@code review}/{@code verification}.
     */
    public static class GateFailedException extends ProteanException {
        public GateFailedException(String gate, String detail) {
            super(ErrorCode.GATE_FAILED, gate, detail);
            with("gate", gate);
        }
    }
}
