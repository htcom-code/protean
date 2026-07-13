/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Output JSON Schema builders for the {@code debug.*} MCP tools. Each must stay consistent with the
 * {@code structuredContent} the matching tool emits in {@link DebugTools}. Only the top-level
 * {@code required} is enforced by the dispatcher guard; nested/type fields are client-advisory.
 */
final class DebugToolSchemas {

    private DebugToolSchemas() {
    }

    /** {@code debug.list_sessions} → {@code {sessions: Session[]}} (arrays cannot be top-level → wrapped). */
    static ObjectNode sessionList(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode sessions = s.putObject("properties").putObject("sessions");
        sessions.put("type", "array");
        ObjectNode item = sessions.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("sessionId").put("type", "string");
        ip.putObject("vmName").put("type", "string");
        ip.putObject("owner").put("type", "string").put("description", "requester identity (omitted if unauthenticated)");
        ip.putObject("idleMs").put("type", "integer");
        ip.putObject("paused").put("type", "boolean");
        ip.putObject("lastStop").put("type", "string")
                .put("description", "<class>.<method>:<line> (omitted if never stopped)");
        item.putArray("required").add("sessionId").add("vmName").add("idleMs").add("paused");
        s.putArray("required").add("sessions");
        return s;
    }

    /** {@code debug.attach} → {@code {sessionId, vmName}}. */
    static ObjectNode sessionRef(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("sessionId").put("type", "string");
        p.putObject("vmName").put("type", "string");
        s.putArray("required").add("sessionId").add("vmName");
        return s;
    }

    /** {@code debug.launch} → {@code {sessionId, vmName, moduleId, workerPort, jdwpPort, paths[]}}. */
    static ObjectNode launchResult(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("sessionId").put("type", "string");
        p.putObject("vmName").put("type", "string");
        p.putObject("moduleId").put("type", "string");
        p.putObject("workerPort").put("type", "integer");
        p.putObject("jdwpPort").put("type", "integer");
        ObjectNode paths = p.putObject("paths");
        paths.put("type", "array");
        paths.putObject("items").put("type", "string");
        s.putArray("required").add("sessionId").add("vmName").add("moduleId")
                .add("workerPort").add("jdwpPort").add("paths");
        return s;
    }

    /**
     * {@code debug.await_stop} → {@code {stopped}} on timeout or {@code {stopped, className, method, line}}
     * on a stop. Only {@code stopped} is always present, so it is the sole top-level required field.
     */
    static ObjectNode stopLocation(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("stopped").put("type", "boolean");
        p.putObject("className").put("type", "string").put("description", "present only when stopped=true");
        p.putObject("method").put("type", "string").put("description", "present only when stopped=true");
        p.putObject("line").put("type", "integer").put("description", "present only when stopped=true");
        s.putArray("required").add("stopped");
        return s;
    }

    /** {@code debug.frames} → {@code {frames: Frame[]}}. */
    static ObjectNode frameList(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode frames = s.putObject("properties").putObject("frames");
        frames.put("type", "array");
        ObjectNode item = frames.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("index").put("type", "integer");
        ip.putObject("className").put("type", "string");
        ip.putObject("method").put("type", "string");
        ip.putObject("line").put("type", "integer");
        s.putArray("required").add("frames");
        return s;
    }

    /**
     * {@code debug.get_variables} → a dynamic map {@code {varName: value}} with string values. Keys are the
     * local variable names, so there are no fixed properties and no top-level {@code required}.
     */
    static ObjectNode variableMap(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        s.putObject("additionalProperties").put("type", "string");
        s.put("description", "Local variable name → rendered value (dynamic keys)");
        return s;
    }

    /** {@code debug.evaluate} → {@code {value, type}}. */
    static ObjectNode evalResult(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("value").put("type", "string");
        p.putObject("type").put("type", "string");
        s.putArray("required").add("value").add("type");
        return s;
    }

    /** {@code debug.redefine} → {@code {redefined: str[]}} (FQCNs replaced in place). */
    static ObjectNode redefineResult(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode redefined = s.putObject("properties").putObject("redefined");
        redefined.put("type", "array");
        redefined.putObject("items").put("type", "string");
        s.putArray("required").add("redefined");
        return s;
    }

    /**
     * {@code debug.step} → {@code {depth}} (the step kind that was executed). The landing location is not
     * returned here — it is observed via {@code debug.await_stop} — so this is a thin echo of the input.
     */
    static ObjectNode stepResult(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        s.putObject("properties").putObject("depth").put("type", "string").put("description", "over|into|out");
        s.putArray("required").add("depth");
        return s;
    }
}
