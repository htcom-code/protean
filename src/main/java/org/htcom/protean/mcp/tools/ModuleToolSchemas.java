/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Output JSON Schema builders shared by the built-in protean tools. {@code ModuleStatus} and version
 * history are returned in the same shape by several tools, so they are built in one place. Must stay
 * consistent with the fields of {@link org.htcom.protean.web.ModuleStatus} /
 * {@link org.htcom.protean.module.ModuleVersion}.
 */
final class ModuleToolSchemas {

    private ModuleToolSchemas() {
    }

    /** Adds a {@code {type: ["string","null"]}} property — a field that is present-but-null or omitted at runtime. */
    private static void nullableString(ObjectNode properties, String name, String description) {
        ObjectNode field = properties.putObject(name);
        field.putArray("type").add("string").add("null");
        field.put("description", description);
    }

    /** A single {@code ModuleStatus} (shared by deploy/get/patch/update/rollback/approve/reload_resources). */
    static ObjectNode moduleStatus(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("id").put("type", "string");
        p.putObject("version").put("type", "string");
        p.putObject("trustTier").put("type", "string");
        p.putObject("desiredState").put("type", "string");
        p.putObject("controllerFqcn").put("type", "string");
        p.putObject("mode").put("type", "string");
        p.putObject("needsSharedBeans").put("type", "boolean");
        ObjectNode bridged = p.putObject("bridgedInterfaces");
        bridged.putArray("type").add("array").add("null"); // null when the module bridges no interfaces
        bridged.putObject("items").put("type", "string");
        // The parent-tier shared-lib generation the live ClassLoader is bound to; null when not loaded.
        p.putObject("boundGeneration").putArray("type").add("integer").add("null");
        // Shared-module typed sharing: kind, the packages a library exports, the libraries a module uses, the
        // library generations a dependent is bound to, and a library's own currently published generation.
        p.putObject("kind").put("type", "string").put("description", "NORMAL | LIBRARY");
        ObjectNode exports = p.putObject("exports");
        exports.putArray("type").add("array");
        exports.putObject("items").put("type", "string");
        ObjectNode uses = p.putObject("uses");
        uses.putArray("type").add("array");
        uses.putObject("items").put("type", "string");
        ObjectNode boundLibs = p.putObject("boundLibraryGenerations");
        boundLibs.putArray("type").add("array");
        boundLibs.putObject("items").put("type", "integer");
        p.putObject("libraryGeneration").putArray("type").add("integer").add("null");
        s.putArray("required").add("id").add("version").add("desiredState");
        return s;
    }

    /** {@code list_modules} result — arrays cannot be top-level, so wrap in {@code modules}. {@code nextCursor} if more remain. */
    static ObjectNode moduleStatusList(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        ObjectNode modules = props.putObject("modules");
        modules.put("type", "array");
        modules.set("items", moduleStatus(m));
        props.putObject("nextCursor").put("type", "string")
                .put("description", "Cursor for the next page (absent on the last page)");
        s.putArray("required").add("modules");
        return s;
    }

    /**
     * Shared input schema for {@code deploy_module} and {@code update_module} (their input contracts are
     * identical — normalized by {@link org.htcom.protean.mcp.ModuleInputNormalizer}). Because the two
     * forms (files[] / manifest) are mutually exclusive, the conditional required fields are encoded as
     * two {@code oneOf} branches instead of a top-level required list. The normalizer is the single
     * source of truth for runtime validation; this schema is the client-facing (self-documenting) contract.
     */
    static ObjectNode moduleInput(ObjectMapper m) {
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("id").put("type", "string").put("description", "Module id (required for files[] form)");
        props.putObject("version").put("type", "string").put("description", "Module version (required for files[] form)");
        props.putObject("controller").put("type", "string")
                .put("description", "REST controller FQCN (required for files[] form, except kind=LIBRARY)");
        props.putObject("kind").put("type", "string")
                .put("description", "NORMAL | LIBRARY (optional, defaults to NORMAL). A LIBRARY exposes shared types "
                        + "via exports and registers no routes (no controller).");
        ObjectNode exportsIn = props.putObject("exports");
        exportsIn.put("type", "array").put("description",
                "Packages a LIBRARY module exposes as shared types (typed code sharing; ignored for NORMAL)");
        exportsIn.putObject("items").put("type", "string");
        ObjectNode usesIn = props.putObject("uses");
        usesIn.put("type", "array").put("description",
                "Ids of LIBRARY modules whose exported types this module compiles and links against (typed sharing)");
        usesIn.putObject("items").put("type", "string");
        props.putObject("isolationMode").put("type", "string")
                .put("description", "in-process|worker|container (optional, defaults to the global setting)");
        ObjectNode trustTier = props.putObject("trustTier");
        trustTier.put("type", "string").put("description", "Trust tier (optional, defaults to TRUSTED)");
        trustTier.putArray("enum").add("TRUSTED").add("UNTRUSTED");
        props.putObject("needsSharedBeans").put("type", "boolean")
                .put("description", "Whether the module depends on host shared beans (optional, defaults to false)");
        ObjectNode components = props.putObject("components");
        components.put("type", "array").put("description",
                "FQCNs of components to register in the child context (optional, defaults to [controller])");
        components.putObject("items").put("type", "string");
        ObjectNode bridged = props.putObject("bridgedInterfaces");
        bridged.put("type", "array").put("description",
                "FQCNs of interfaces to invoke on main shared beans via RPC in worker mode (optional)");
        bridged.putObject("items").put("type", "string");
        props.set("verification", verificationSchema(m));

        ObjectNode files = props.putObject("files");
        files.put("type", "array").put("description",
                "Source/test/resource files. For source and test the FQCN is derived automatically from "
                        + "the package and filename; for resource the filename is used as the classpath path (e.g. mapper XML)");
        ObjectNode item = files.putObject("items");
        item.put("type", "object");
        ObjectNode fp = item.putObject("properties");
        fp.putObject("kind").put("type", "string").put("description", "source|test|resource (defaults to source)");
        fp.putObject("filename").put("type", "string")
                .put("description", "For source/test the filename; for resource the classpath path (e.g. mapper/X.xml)");
        fp.putObject("content").put("type", "string").put("description", "File content (plain text or Base64)");
        fp.putObject("base64").put("type", "boolean")
                .put("description", "true if the resource is binary (content is Base64). Defaults to false (plain text)");
        item.putArray("required").add("filename").add("content");

        props.putObject("manifest").put("type", "string").put("description", "Alternative: module.yaml text (mutually exclusive with files)");

        var oneOf = schema.putArray("oneOf");
        ObjectNode filesBranch = oneOf.addObject();
        filesBranch.put("title", "files[] path");
        filesBranch.putArray("required").add("id").add("version").add("controller").add("files");
        filesBranch.putObject("not").putArray("required").add("manifest");
        ObjectNode manifestBranch = oneOf.addObject();
        manifestBranch.put("title", "manifest path");
        manifestBranch.putArray("required").add("manifest");
        manifestBranch.putObject("not").putArray("required").add("files");
        return schema;
    }

    /** JSON Schema for {@link org.htcom.protean.module.VerificationPlan} (promotion verification gate plan, optional). */
    static ObjectNode verificationSchema(ObjectMapper m) {
        ObjectNode v = m.createObjectNode();
        v.put("type", "object");
        v.put("description", "Promotion verification gate plan. Any omitted item skips that verification (optional)");
        ObjectNode vp = v.putObject("properties");
        ObjectNode integration = vp.putObject("integration");
        integration.put("type", "array").put("description", "List of integration probes (HTTP checks)");
        ObjectNode probe = integration.putObject("items");
        probe.put("type", "object");
        ObjectNode pp = probe.putObject("properties");
        pp.putObject("method").put("type", "string");
        pp.putObject("path").put("type", "string");
        pp.putObject("expectedStatus").put("type", "integer");
        pp.putObject("bodyContains").put("type", "string");
        probe.putArray("required").add("method").add("path").add("expectedStatus");
        vp.putObject("loadPath").put("type", "string").put("description", "Target path for load testing");
        vp.putObject("concurrency").put("type", "integer").put("description", "Number of concurrent threads (null = skip load verification)");
        vp.putObject("requestsPerThread").put("type", "integer").put("description", "Requests per thread");
        vp.putObject("maxAvgLatencyMs").put("type", "integer").put("description", "Max average latency (ms, null = skip)");
        vp.putObject("maxHeapGrowthBytes").put("type", "integer").put("description", "Max heap growth (bytes, null = skip)");
        return v;
    }

    /** {@code patch_module} input schema — delta (add/replace files + removeFiles). Only id is required. */
    static ObjectNode modulePatchInput(ObjectMapper m) {
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string").put("description", "id of the module to patch");
        props.putObject("version").put("type", "string").put("description", "New version (optional; keeps the current version if omitted)");
        ObjectNode files = props.putObject("files");
        files.put("type", "array").put("description",
                "Files to add/replace (kind: source|test|resource). Replaces on key collision (optional)");
        ObjectNode item = files.putObject("items");
        item.put("type", "object");
        ObjectNode fp = item.putObject("properties");
        fp.putObject("kind").put("type", "string").put("description", "source|test|resource (defaults to source)");
        fp.putObject("filename").put("type", "string")
                .put("description", "For source/test the filename; for resource the classpath path");
        fp.putObject("content").put("type", "string").put("description", "File content (plain text or Base64)");
        fp.putObject("base64").put("type", "boolean").put("description", "true if binary (defaults to false)");
        item.putArray("required").add("filename").add("content");
        ObjectNode removeFiles = props.putObject("removeFiles");
        removeFiles.put("type", "array").put("description", "Keys to remove (source/test FQCN or resource path) (optional)");
        removeFiles.putObject("items").put("type", "string");
        schema.putArray("required").add("id");
        return schema;
    }

    /** {@code get_module_source} result — id, version, files (FQCN -> source text map). */
    static ObjectNode moduleSource(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("id").put("type", "string");
        p.putObject("version").put("type", "string");
        ObjectNode files = p.putObject("files");
        files.put("type", "object");
        files.put("description", "FQCN -> Java source text");
        s.putArray("required").add("id").add("files");
        return s;
    }

    /** {@code module_versions} result — array of {@code ModuleVersion} wrapped in {@code versions}. */
    static ObjectNode versionList(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode versions = s.putObject("properties").putObject("versions");
        versions.put("type", "array");
        ObjectNode item = versions.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("seq").put("type", "integer");
        ip.putObject("version").put("type", "string");
        ip.putObject("savedAtMillis").put("type", "integer");
        ip.putObject("desiredState").put("type", "string");
        s.putArray("required").add("versions");
        return s;
    }

    /**
     * {@code module_metrics} result — {@code {enabled, metrics[]}}. Both keys are always present:
     * when metrics are disabled the tool still returns {@code enabled:false} with an empty {@code metrics}
     * array. Item fields must stay consistent with {@link org.htcom.protean.runtime.ModuleMetricsSnapshot}.
     */
    static ObjectNode moduleMetrics(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("enabled").put("type", "boolean")
                .put("description", "Whether protean.trace.metrics.enabled is on (metrics is empty when false)");
        ObjectNode metrics = props.putObject("metrics");
        metrics.put("type", "array");
        ObjectNode item = metrics.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("moduleId").put("type", "string");
        ip.putObject("count").put("type", "integer");
        ip.putObject("errorCount").put("type", "integer");
        ip.putObject("errorRate").put("type", "number");
        ip.putObject("p50LatencyMs").put("type", "integer");
        ip.putObject("p95LatencyMs").put("type", "integer");
        ip.putObject("p99LatencyMs").put("type", "integer");
        ip.putObject("maxLatencyMs").put("type", "integer");
        ip.putObject("lastSeenEpochMillis").put("type", "integer");
        item.putArray("required").add("moduleId").add("count").add("errorCount").add("errorRate")
                .add("p50LatencyMs").add("p95LatencyMs").add("p99LatencyMs").add("maxLatencyMs").add("lastSeenEpochMillis");
        s.putArray("required").add("enabled").add("metrics");
        return s;
    }

    /**
     * {@code query_traces} result — array of {@code RequestTrace} wrapped in {@code traces} (arrays cannot be
     * top-level). Item fields must stay consistent with {@link org.htcom.protean.runtime.RequestTrace};
     * {@code pattern/moduleId/error/traceId} are nullable so they are left out of the item {@code required}.
     */
    static ObjectNode traceList(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode traces = s.putObject("properties").putObject("traces");
        traces.put("type", "array");
        ObjectNode item = traces.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("seq").put("type", "integer");
        ip.putObject("epochMillis").put("type", "integer");
        ip.putObject("method").put("type", "string");
        ip.putObject("uri").put("type", "string");
        nullableString(ip, "pattern", "matched handler pattern (null if unmatched)");
        nullableString(ip, "moduleId", "module that registered the pattern (null if platform/static)");
        ip.putObject("status").put("type", "integer");
        ip.putObject("latencyMs").put("type", "integer");
        nullableString(ip, "error", "thrown exception FQCN (null if none)");
        nullableString(ip, "traceId", "correlation id shared with logs (null if unavailable)");
        item.putArray("required").add("seq").add("epochMillis").add("method").add("uri").add("status").add("latencyMs");
        s.putArray("required").add("traces");
        return s;
    }
}
