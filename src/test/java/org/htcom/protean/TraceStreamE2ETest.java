/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-JVM e2e for the console SSE push transport ({@code GET /platform/traces/stream}). Verifies the
 * multiplexed initial snapshot (trace/metrics/modules named events) and that the server-side ticker keeps
 * pushing without any client polling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceStreamE2ETest {

    static final Path STORE = Path.of(System.getProperty("java.io.tmpdir"), "protean-trace-stream-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("protean.module-store.dir", STORE::toString);
    }

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void stream_emits_initial_snapshot_and_keeps_ticking() throws Exception {
        // Collect enough events to cover the synchronous initial snapshot (trace+metrics+modules = 3) plus at
        // least one later ticker cycle, proving push continues with no client polling.
        List<String> events = readSseEventNames(6, Duration.ofSeconds(5));

        // Initial snapshot delivers all three multiplexed event types.
        assertThat(events).contains("trace", "metrics", "modules");
        // The first three are exactly the initial snapshot, in order.
        assertThat(events.subList(0, 3)).containsExactly("trace", "metrics", "modules");
        // The ticker pushed at least one more cycle after the initial snapshot (no polling involved).
        assertThat(events.size()).isGreaterThan(3);
    }

    /** Opens the SSE stream and returns the {@code event:} names in arrival order, up to {@code expected} or the deadline. */
    private List<String> readSseEventNames(int expected, Duration deadline) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/platform/traces/stream"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");

        List<String> names = new ArrayList<>();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while (names.size() < expected && (line = br.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        names.add(line.substring("event:".length()).trim());
                    }
                }
            } catch (Exception ignored) {
            }
        });
        try {
            reader.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception timeout) {
            reader.cancel(true);
        }
        resp.body().close();
        return names;
    }
}
