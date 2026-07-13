/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.compiler.UsedSharedLib;

import java.util.List;
import java.util.Map;

/**
 * Declarative metadata for a module deployment unit. The single representation for durable persistence/recovery.
 *
 * @param id             module identifier
 * @param version        version (a pin used to recompile the same version on recovery)
 * @param trustTier      trust tier
 * @param desiredState   desired state (if ACTIVE, subject to reconcile on startup)
 * @param controllerFqcn FQCN of the controller whose REST mappings will be registered
 * @param componentFqcns component FQCNs to register in the child context (including the controller)
 * @param sources        FQCN -> Java source (runtime compilation input)
 * @param tests          FQCN -> JUnit test source (promotion gate 1 input, mandatory)
 * @param needsSharedBeans whether it depends on shared in-process beans (used to decide isolation-mode compatibility)
 * @param verification   promotion gate 3 verification plan (null = skip verification)
 * @param isolationMode  this module's isolation mode ("in-process"|"worker", null = global default)
 * @param bridgedInterfaces list of interface FQCNs to call the main shared beans over RPC in worker mode (null/empty = none)
 * @param signerKeyId    signing key identifier (for promotion-gate signature verification, null = unsigned). Excluded from the signing-target normalization.
 * @param signature      Ed25519 signature over the normalized content (Base64, null = unsigned). Excluded from the signing-target normalization.
 * @param resources      classpath path -> non-Java resource (mapper XML, etc.). null = none (normalized to an empty map).
 * @param usedSharedLibs shared-lib jars ({name, sha256}) this version's compile actually opened. Server-observed (not
 *                       consumer-authored) and excluded from the signing-target normalization — for precise
 *                       invalidation. null = none (normalized to an empty list).
 * @param kind           deployment kind (see {@link ModuleKind}). NORMAL = routes/lifecycle; LIBRARY = exposes shared
 *                       types on the parent tier, no routes (shared-module typed sharing). null = NORMAL.
 * @param exports        packages this module exposes as shared types when {@code kind == LIBRARY} (ignored otherwise).
 *                       Consumer-authored → part of the signing-target normalization. null = none.
 * @param uses           ids of the LIBRARY modules whose exported types this module compiles and links against
 *                       (shared-module typed sharing). Consumer-authored → part of the signing-target. null = none.
 */
public record ModuleDescriptor(
        String id,
        String version,
        TrustTier trustTier,
        DesiredState desiredState,
        String controllerFqcn,
        List<String> componentFqcns,
        Map<String, String> sources,
        Map<String, String> tests,
        boolean needsSharedBeans,
        VerificationPlan verification,
        String isolationMode,
        List<String> bridgedInterfaces,
        String signerKeyId,
        String signature,
        Map<String, ModuleResource> resources,
        List<UsedSharedLib> usedSharedLibs,
        ModuleKind kind,
        List<String> exports,
        List<String> uses
) {
    public enum TrustTier { TRUSTED, UNTRUSTED }

    public enum DesiredState { ACTIVE, INACTIVE, PENDING_APPROVAL }

    /**
     * Deployment kind. A NORMAL module registers routes and has a request lifecycle. A LIBRARY module registers no
     * routes; its "activation" is publishing its compiled {@code exports} packages as a parent-tier generation that
     * dependents ({@code uses}) compile and link against with a single type identity (shared-module typed sharing).
     */
    public enum ModuleKind { NORMAL, LIBRARY }

    /** Normalizes null collections to empty (for deserializing older persisted records without these fields). */
    public ModuleDescriptor {
        resources = resources == null ? Map.of() : resources;
        usedSharedLibs = usedSharedLibs == null ? List.of() : usedSharedLibs;
        kind = kind == null ? ModuleKind.NORMAL : kind;
        exports = exports == null ? List.of() : exports;
        uses = uses == null ? List.of() : uses;
    }

    /** Whether this is a LIBRARY module (exposes shared types on the parent tier instead of registering routes). */
    public boolean isLibrary() {
        return kind == ModuleKind.LIBRARY;
    }

    /** A copy with the observed shared-lib usage filled in (attached after compile, before persisting). */
    public ModuleDescriptor withUsedSharedLibs(List<UsedSharedLib> used) {
        return toBuilder().usedSharedLibs(used).build();
    }

    public ModuleDescriptor withDesiredState(DesiredState newState) {
        return toBuilder().desiredState(newState).build();
    }

    /** A copy with the signature (signerKeyId/signature) filled in. For attaching after generating a signature. */
    public ModuleDescriptor withSignature(String signerKeyId, String signature) {
        return toBuilder().signerKeyId(signerKeyId).signature(signature).build();
    }

    /** A copy with the resource map filled in (for attaching at the normalization entry point). */
    public ModuleDescriptor withResources(Map<String, ModuleResource> newResources) {
        return toBuilder().resources(newResources).build();
    }

    /** An empty builder. Optional fields use conventional defaults (trustTier=TRUSTED, desiredState=ACTIVE, collections=empty, the rest null). */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-populated with all values of this descriptor (for functional updates — an alternative to the {@code with*} methods). */
    public Builder toBuilder() {
        return new Builder()
                .id(id).version(version).trustTier(trustTier).desiredState(desiredState)
                .controllerFqcn(controllerFqcn).componentFqcns(componentFqcns)
                .sources(sources).tests(tests).needsSharedBeans(needsSharedBeans)
                .verification(verification).isolationMode(isolationMode).bridgedInterfaces(bridgedInterfaces)
                .signerKeyId(signerKeyId).signature(signature).resources(resources)
                .usedSharedLibs(usedSharedLibs)
                .kind(kind).exports(exports).uses(uses);
    }

    /**
     * Builder for {@link ModuleDescriptor}. The record stays an immutable value type, but since it has many
     * mostly-optional fields, instances are constructed and derived through this builder instead of telescoping
     * constructors (10/11/12/14-arg). {@code build()} calls the canonical constructor, so it goes through the
     * compact-constructor normalization (resources null→empty map).
     */
    public static final class Builder {
        private String id;
        private String version;
        private TrustTier trustTier = TrustTier.TRUSTED;
        private DesiredState desiredState = DesiredState.ACTIVE;
        private String controllerFqcn;
        private List<String> componentFqcns = List.of();
        private Map<String, String> sources = Map.of();
        private Map<String, String> tests = Map.of();
        private boolean needsSharedBeans = false;
        private VerificationPlan verification;
        private String isolationMode;
        private List<String> bridgedInterfaces;
        private String signerKeyId;
        private String signature;
        private Map<String, ModuleResource> resources = Map.of();
        private List<UsedSharedLib> usedSharedLibs = List.of();
        private ModuleKind kind = ModuleKind.NORMAL;
        private List<String> exports = List.of();
        private List<String> uses = List.of();

        private Builder() {
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder trustTier(TrustTier trustTier) { this.trustTier = trustTier; return this; }
        public Builder desiredState(DesiredState desiredState) { this.desiredState = desiredState; return this; }
        public Builder controllerFqcn(String controllerFqcn) { this.controllerFqcn = controllerFqcn; return this; }
        public Builder componentFqcns(List<String> componentFqcns) { this.componentFqcns = componentFqcns; return this; }
        public Builder sources(Map<String, String> sources) { this.sources = sources; return this; }
        public Builder tests(Map<String, String> tests) { this.tests = tests; return this; }
        public Builder needsSharedBeans(boolean needsSharedBeans) { this.needsSharedBeans = needsSharedBeans; return this; }
        public Builder verification(VerificationPlan verification) { this.verification = verification; return this; }
        public Builder isolationMode(String isolationMode) { this.isolationMode = isolationMode; return this; }
        public Builder bridgedInterfaces(List<String> bridgedInterfaces) { this.bridgedInterfaces = bridgedInterfaces; return this; }
        public Builder signerKeyId(String signerKeyId) { this.signerKeyId = signerKeyId; return this; }
        public Builder signature(String signature) { this.signature = signature; return this; }
        public Builder resources(Map<String, ModuleResource> resources) { this.resources = resources; return this; }
        public Builder usedSharedLibs(List<UsedSharedLib> usedSharedLibs) { this.usedSharedLibs = usedSharedLibs; return this; }
        public Builder kind(ModuleKind kind) { this.kind = kind; return this; }
        public Builder exports(List<String> exports) { this.exports = exports; return this; }
        public Builder uses(List<String> uses) { this.uses = uses; return this; }

        public ModuleDescriptor build() {
            return new ModuleDescriptor(id, version, trustTier, desiredState, controllerFqcn, componentFqcns,
                    sources, tests, needsSharedBeans, verification, isolationMode, bridgedInterfaces,
                    signerKeyId, signature, resources, usedSharedLibs, kind, exports, uses);
        }
    }
}
