/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.Generation;
import org.htcom.protean.compiler.ModuleSharedLibs;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live shared-lib store (put-jar surface): persistence, idempotency, the
 * name+version+sha256 coordinate conflict, generation advancement on change, and boot restore. Uses a
 * {@link ModuleSharedLibs#standalone() standalone} registry so no Spring context is needed.
 */
class SharedLibStoreTest {

    private static final byte[] JAR_A = "jar-A-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAR_A2 = "jar-A-bytes-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAR_B = "jar-B-bytes".getBytes(StandardCharsets.UTF_8);

    private static SharedLibStore store(Path storeDir, ModuleSharedLibs registry) {
        ProteanProperties props = new ProteanProperties();
        props.getModule().setSharedLibStoreDir(storeDir.toString());
        // No-op event publisher: these tests exercise store mechanics only, not the invalidation listener.
        return new SharedLibStore(props, registry, new ObjectMapper(), event -> { });
    }

    private static SharedLibStore.IncomingLib lib(String name, String version, byte[] bytes) {
        return new SharedLibStore.IncomingLib(name, version, bytes, null, null);
    }

    @Test
    void deploy_persists_and_advances_the_generation(@TempDir Path dir) {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        SharedLibStore store = store(dir, registry);
        assertEquals(Generation.GEN0, registry.currentGeneration().id(), "no uploads yet → still gen0");

        Generation gen = store.deploy(List.of(lib("acme", "1.0.0", JAR_A)));
        assertEquals(1L, gen.id(), "first deploy publishes gen1");
        assertSame(gen, registry.currentGeneration(), "the published generation becomes current");
        assertTrue(Files.exists(dir.resolve("acme.jar")), "jar bytes are persisted");
        assertTrue(Files.exists(dir.resolve("acme.json")), "metadata is persisted");

        List<SharedLibStore.StoredLib> listed = store.list();
        assertEquals(1, listed.size());
        assertEquals("acme", listed.get(0).name());
        assertEquals("1.0.0", listed.get(0).version());
        assertEquals(JAR_A.length, listed.get(0).size());
        assertEquals(ModuleSharedLibs.sha256Hex(JAR_A), listed.get(0).sha256());
    }

    @Test
    void identical_redeploy_is_a_noop_and_publishes_no_new_generation(@TempDir Path dir) {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        SharedLibStore store = store(dir, registry);
        Generation gen1 = store.deploy(List.of(lib("acme", "1.0.0", JAR_A)));

        Generation again = store.deploy(List.of(lib("acme", "1.0.0", JAR_A)));
        assertSame(gen1, again, "same name+version+sha256 → idempotent, no new generation");
        assertEquals(1L, registry.currentGeneration().id());
    }

    @Test
    void same_coordinate_different_bytes_is_rejected(@TempDir Path dir) {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        SharedLibStore store = store(dir, registry);
        store.deploy(List.of(lib("acme", "1.0.0", JAR_A)));

        ProteanException ex = assertThrows(ProteanException.class,
                () -> store.deploy(List.of(lib("acme", "1.0.0", JAR_A2))));
        assertEquals(ErrorCode.STATE_CONFLICT, ex.code());
        assertEquals(1L, registry.currentGeneration().id(), "a rejected deploy does not advance the generation");
    }

    @Test
    void a_new_version_of_a_name_replaces_it_and_advances(@TempDir Path dir) {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        SharedLibStore store = store(dir, registry);
        store.deploy(List.of(lib("acme", "1.0.0", JAR_A)));

        Generation gen2 = store.deploy(List.of(lib("acme", "2.0.0", JAR_A2)));
        assertEquals(2L, gen2.id(), "replacing a name's version publishes a new generation");
        assertEquals("2.0.0", store.get("acme").orElseThrow().version());
        assertEquals(1, store.list().size(), "one active version per name");
    }

    @Test
    void a_bundle_publishes_exactly_one_generation(@TempDir Path dir) {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        SharedLibStore store = store(dir, registry);

        Generation gen = store.deploy(List.of(lib("acme", "1.0.0", JAR_A), lib("beta", "1.0.0", JAR_B)));
        assertEquals(1L, gen.id(), "a two-jar bundle is one generation, not two");
        assertEquals(2, store.list().size());
    }

    @Test
    void empty_or_malformed_input_is_rejected(@TempDir Path dir) {
        SharedLibStore store = store(dir, ModuleSharedLibs.standalone());
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(ProteanException.class, () -> store.deploy(List.of())).code());
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(ProteanException.class, () -> store.deploy(List.of(lib("../evil", "1.0.0", JAR_A)))).code());
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(ProteanException.class, () -> store.deploy(List.of(lib("acme", "1.0.0", new byte[0])))).code());
    }

    @Test
    void remove_advances_the_generation_and_drops_the_entry(@TempDir Path dir) {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        SharedLibStore store = store(dir, registry);
        store.deploy(List.of(lib("acme", "1.0.0", JAR_A)));

        Generation afterRemove = store.remove("acme");
        assertEquals(2L, afterRemove.id(), "remove publishes a new (future) generation without the lib");
        assertTrue(store.list().isEmpty());
        assertTrue(Files.notExists(dir.resolve("acme.jar")), "jar file is deleted");

        assertEquals(ErrorCode.SHARED_LIB_NOT_FOUND,
                assertThrows(ProteanException.class, () -> store.remove("acme")).code());
    }

    @Test
    void persisted_uploads_are_restored_at_boot_as_a_generation(@TempDir Path dir) {
        // First store instance uploads; a second, fresh instance over the same dir simulates a restart.
        store(dir, ModuleSharedLibs.standalone()).deploy(List.of(lib("acme", "1.0.0", JAR_A)));

        ModuleSharedLibs restarted = ModuleSharedLibs.standalone();
        SharedLibStore reopened = store(dir, restarted);
        reopened.load();   // @PostConstruct equivalent

        assertEquals(1, reopened.list().size(), "the persisted upload is reloaded");
        assertNotEquals(Generation.GEN0, restarted.currentGeneration().id(),
                "restored uploads are re-published as the current generation");
    }
}
