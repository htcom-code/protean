/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.tools.ModuleMetricsTool;
import org.htcom.protean.runtime.TraceMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link ModuleMetricsTool}'s disabled branch: when metrics are off it returns a
 * non-error result flagged {@code enabled:false} (so an agent can detect the toggle) rather than failing.
 */
class ModuleMetricsToolTest {

    @Test
    void reports_disabled_without_error_when_metrics_off() {
        ObjectMapper mapper = new ObjectMapper();
        TraceMetrics disabled = new TraceMetrics(new ProteanProperties()); // metrics.enabled defaults to false
        ModuleMetricsTool tool = new ModuleMetricsTool(mapper, disabled);

        McpToolResult result = tool.call(mapper.missingNode(), McpCallContext.anonymous());

        assertThat(result.isError()).isFalse();
        JsonNode structured = result.structured();
        assertThat(structured.path("enabled").asBoolean(true)).isFalse();
        assertThat(structured.path("metrics").isArray()).isTrue();
        assertThat(structured.path("metrics").size()).isZero();
    }
}
