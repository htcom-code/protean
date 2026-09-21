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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-JVM e2e for the console SSE push transport ({@code GET /platform/traces/stream}). Verifies the
 * connection ack ({@code ready}) and the multiplexed initial snapshot that follows it, the ack's payload
 * contract, and that the server-side ticker keeps pushing without any client polling.
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stream_acks_then_emits_initial_snapshot_and_keeps_ticking() throws Exception {
        // Collect enough frames to cover the synchronous ack + initial snapshot (ready+trace+metrics+modules
        // +summary = 5) plus at least one later ticker cycle, proving push continues with no client polling.
        List<String> events = readSse(8, Duration.ofSeconds(5)).stream().map(Map.Entry::getKey).toList();

        // The ack comes first, then the four snapshot frames, in this exact order — the console reads the ack
        // to tell "the server is live" from "the socket merely opened", so anything ahead of it defeats that.
        assertThat(events.subList(0, 5)).containsExactly("ready", "trace", "metrics", "modules", "summary");
        // The ticker pushed at least one more cycle after the initial snapshot (no polling involved).
        assertThat(events.size()).isGreaterThan(5);
        // The ack is per connection, not per tick.
        assertThat(events.stream().filter("ready"::equals)).hasSize(1);
    }

    @Test
    void ready_ack_carries_the_connect_time_contract() throws Exception {
        List<Map.Entry<String, String>> frames = readSse(2, Duration.ofSeconds(5));

        JsonNode ready = MAPPER.readTree(frames.get(0).getValue());
        assertEquals("protean", ready.path("platform").asText());
        // Present but null when running from exploded classes (no jar manifest to read the version from); the
        // field must still exist so a client never has to distinguish "absent" from "unknown".
        assertThat(ready.has("platformVersion")).isTrue();
        assertThat(ready.path("tracesEnabled").asBoolean()).isTrue();
        assertThat(ready.path("metricsEnabled").asBoolean()).isFalse();  // protean.trace.metrics.enabled default
        assertEquals(1_000L, ready.path("tickMs").asLong());             // the watchdog contract
        assertEquals(200, ready.path("capacity").asInt());               // protean.trace.capacity default

        // buffered counts the rows of the trace frame that follows, not the rows held by the ring. Announcing a
        // count the client does not then receive is the failure this assertion exists to catch.
        JsonNode traces = MAPPER.readTree(frames.get(1).getValue());
        assertEquals(traces.size(), ready.path("buffered").asInt());
    }

    /**
     * Opens the SSE stream and returns {@code (event, data)} pairs in arrival order, up to {@code expected} or
     * the deadline.
     */
    private List<Map.Entry<String, String>> readSse(int expected, Duration deadline) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/platform/traces/stream"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");

        List<Map.Entry<String, String>> frames = new ArrayList<>();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line, name = null;
                while (frames.size() < expected && (line = br.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        name = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:") && name != null) {
                        frames.add(Map.entry(name, line.substring("data:".length()).trim()));
                        name = null;
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
        return frames;
    }
}
