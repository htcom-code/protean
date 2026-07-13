/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Security filter chain coverage: <b>verifies servlet filters apply</b>.
 *
 * Adding full Spring Security would break every existing test, so this verifies on a real Tomcat that the
 * servlet {@code Filter} underlying a security filter chain intercepts dynamically registered endpoints
 * (i.e. cannot be bypassed). Filters run by path before dispatch, so dynamic endpoints must be caught the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FilterChainModuleTest {

    @TestConfiguration
    static class FilterConfig {
        @Bean
        FilterRegistrationBean<Filter> probeFilter() {
            FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
            reg.setFilter((request, response, chain) -> {
                ((HttpServletResponse) response).setHeader("X-Filter", "hit");
                chain.doFilter(request, response);
            });
            reg.addUrlPatterns("/flt/*");
            return reg;
        }
    }

    @LocalServerPort int port;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String FQCN = "runtime.flt.FilteredController";
    static final String SRC = """
            package runtime.flt;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class FilteredController {
                @GetMapping("/flt/ping")
                public String ping() { return "pong"; }
            }
            """;

    @Test
    void servlet_filter_intercepts_dynamic_endpoint() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(Map.of(FQCN, SRC));
        container.deploy("flt-mod", loader, List.of(FQCN), FQCN);
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/flt/ping")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertEquals("pong", resp.body());
            // The filter intercepted the dynamic endpoint and injected a header = the filter chain applies to dynamic mappings too.
            assertEquals("hit", resp.headers().firstValue("X-Filter").orElse(null),
                    "the servlet filter must also intercept dynamically registered endpoints (no security bypass)");
        } finally {
            container.undeploy("flt-mod");
        }
    }
}
