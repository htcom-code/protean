/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against an in-place isolation-mode change through {@code update} (step (a)). Changing the mode via
 * update would hot-swap with the new mode's strategy while the module still runs under the old mode, orphaning
 * the old-mode instance (e.g. a leaked worker JVM). The platform must reject it before any strategy work, and
 * leave the running module untouched. The global default here is in-process, so the reject path never spawns a
 * worker (the guard fires before the worker strategy is even selected).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpdateModeChangeGuardTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-modeguard-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @LocalServerPort int port;
    @Autowired ModulePlatform platform;

    static final String ID = "modeguard-mod";
    static final String CTRL = "runtime.mg.MgController";
    static final String TEST = "runtime.mg.MgControllerTest";

    static ModuleDescriptor descriptor(String isolationMode) {
        String src = """
                package runtime.mg;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class MgController {
                    @GetMapping("/modeguard/ping")
                    public String ping() { return "in-process"; }
                }
                """;
        String test = """
                package runtime.mg;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                public class MgControllerTest {
                    @Test void instantiable() { assertNotNull(new MgController()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL))
                .sources(Map.of(CTRL, src)).tests(Map.of(TEST, test))
                .isolationMode(isolationMode)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void update_rejects_isolation_mode_change_and_leaves_the_running_module_intact() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // deploy in-process (isolationMode=null -> global default in-process)
        platform.install(descriptor(null));
        assertEquals("in-process", body(client));
        assertEquals("in-process", platform.effectiveMode(platform.find(ID).orElseThrow()));

        // update with isolationMode=worker -> rejected before any strategy work (no worker JVM spawned)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> platform.update(descriptor("worker")));
        assertTrue(ex.getMessage().contains("cannot change the isolation mode"),
                "message explains the rejection: " + ex.getMessage());

        // the original in-process deployment is untouched (not torn down, still serving, still in-process)
        assertEquals("in-process", body(client));
        assertEquals("in-process", platform.effectiveMode(platform.find(ID).orElseThrow()));
    }

    private String body(HttpClient client) throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/modeguard/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }
}
