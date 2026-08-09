/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the <b>published</b> configuration-documentation surfaces. Property javadoc on the
 * {@code @ConfigurationProperties} class is not an internal comment: the annotation processor copies it verbatim into
 * {@code META-INF/spring-configuration-metadata.json}, which ships inside the jar and is what a consumer's IDE shows on
 * autocomplete. That makes it a consumer-facing doc surface with no review gate of its own — this test is that gate.
 *
 * <p>Four checks, each closing a drift mode observed in practice:
 *
 * <ol>
 *   <li><b>Every shipped property carries a description.</b> The other checks only inspect descriptions that exist, so
 *       a field with no javadoc at all was invisible to all of them — it still autocompletes in the consumer's IDE,
 *       just with an empty tooltip.</li>
 *   <li><b>No javadoc markup or internal-doc references in shipped descriptions.</b> Inline tags survive into the
 *       metadata as literal text (a consumer reads "{@code true}" instead of "true"), and a reference to a design doc
 *       excluded from the public repo becomes a dangling pointer.</li>
 *   <li><b>Every shipped property key is documented in the configuration guide.</b> Catches a new knob that reached the
 *       properties class but not the reference table.</li>
 *   <li><b>No sentence truncated by a line-scoped comment edit.</b> When a wrapped javadoc sentence is rewritten one
 *       line at a time, the untouched line is left dangling mid-clause — invisible in a diff that only reads the added
 *       line. Signature: a comment line ending in a function word, followed by a line that starts a new sentence.</li>
 * </ol>
 */
class ConfigDocSurfaceTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path CONFIG_GUIDE = Path.of("docs/guide/03-configuration.md");

    /** Markup and reference forms that must never reach a shipped description. */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "{@", "javadoc inline tag (survives as literal text in the shipped metadata)",
            "<code>", "raw HTML markup",
            "docs/plan", "reference to a design doc excluded from the public repo",
            "docs/mcp", "reference to a design doc excluded from the public repo",
            "§", "section reference into an internal design document");

    /**
     * Keys documented in the guide but bound with {@code @Value}/{@code @ConditionalOnProperty} rather than
     * {@code @ConfigurationProperties}, so the processor emits no metadata for them. Listed to keep the parity check
     * exact; migrating them to the properties class would also give them IDE descriptions.
     */
    private static final Set<String> NOT_METADATA_BOUND = Set.of(
            "protean.mcp.session.enabled",
            "protean.mcp.session.timeout",
            "protean.mcp.session.replay-buffer",
            "protean.mcp.session.stream-timeout");

    /** Function words that legitimately end a wrapped line; a new sentence must not follow one. */
    private static final Set<String> DANGLING_WORDS = Set.of(
            "a", "an", "the", "per", "of", "to", "and", "or", "with", "for", "in", "by", "from", "that", "than",
            "on", "at", "as", "into", "but", "if", "when", "while", "its", "their", "this", "these", "those");

    /** Legitimate wraps where a capitalized proper noun follows a function word (file:line of the first line). */
    private static final Set<String> WRAP_ALLOWLIST = Set.of(
            "org/htcom/protean/module/SharedLibUsageIndex.java:39",
            "org/htcom/protean/isolation/WorkerRuntimeProvider.java:22");

    @Test
    void every_shipped_property_carries_a_description() throws Exception {
        List<JsonNode> properties = properties();
        List<String> undescribed = new ArrayList<>();
        for (JsonNode property : properties) {
            if (property.path("description").asText("").isBlank()) {
                undescribed.add(property.path("name").asText());
            }
        }
        // All blank means the javadoc was never read rather than never written: the processor takes descriptions from
        // the source, and an incremental compile hands it elements restored from class files, which carry no doc
        // comments. Saying so here saves the next person from "fixing" javadoc that is already there.
        if (!properties.isEmpty() && undescribed.size() == properties.size()) {
            fail("every description is blank, which is the incremental-compile signature rather than missing javadoc"
                    + " — rebuild with `./gradlew compileJava --rerun` (or a clean build) before trusting this run");
        }
        assertTrue(undescribed.isEmpty(), "these properties reach a consumer's IDE with an empty tooltip — add javadoc"
                + " to the field on ProteanProperties:\n  " + String.join("\n  ", undescribed));
    }

    @Test
    void shipped_property_descriptions_carry_no_javadoc_markup_or_internal_doc_references() throws Exception {
        List<String> violations = new ArrayList<>();
        for (JsonNode property : properties()) {
            String name = property.path("name").asText();
            String description = property.path("description").asText("");
            FORBIDDEN.forEach((marker, why) -> {
                if (description.contains(marker)) {
                    violations.add(name + " — contains '" + marker + "': " + why);
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "shipped configuration metadata must read as plain prose; write the javadoc without markup:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    void every_shipped_property_key_is_documented_in_the_configuration_guide() throws Exception {
        String guide = Files.readString(CONFIG_GUIDE);
        Set<String> documented = new LinkedHashSet<>();
        Matcher rows = Pattern.compile("`(protean\\.[a-z0-9.\\-]+)`\\s*\\|").matcher(guide);
        while (rows.find()) {
            documented.add(rows.group(1));
        }
        assertTrue(documented.size() > 50, "the configuration guide table did not parse (found " + documented.size()
                + " keys) — check the table format before trusting this test");

        List<String> undocumented = new ArrayList<>();
        for (JsonNode property : properties()) {
            String name = property.path("name").asText();
            if (!documented.contains(name)) {
                undocumented.add(name);
            }
        }
        assertTrue(undocumented.isEmpty(), "properties shipped without a row in " + CONFIG_GUIDE + ":\n  "
                + String.join("\n  ", undocumented));

        List<String> phantom = documented.stream()
                .filter(key -> !NOT_METADATA_BOUND.contains(key))
                .filter(key -> properties().stream().noneMatch(p -> p.path("name").asText().equals(key)))
                .toList();
        assertTrue(phantom.isEmpty(), "the guide documents keys that no property binds (typo, or renamed in code) — "
                + "add them to NOT_METADATA_BOUND only if bound by @Value:\n  " + String.join("\n  ", phantom));
    }

    @Test
    void no_comment_sentence_is_left_truncated_by_a_line_scoped_edit() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(SOURCE_ROOT)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size() - 1; i++) {
                    String current = lines.get(i).strip();
                    String next = lines.get(i + 1).strip();
                    if (!current.startsWith("*") || !next.startsWith("*")) {
                        continue;
                    }
                    String text = current.replaceFirst("^\\*\\s?", "").stripTrailing();
                    String following = next.replaceFirst("^\\*\\s?", "").stripLeading();
                    if (text.isEmpty() || following.isEmpty() || endsSentence(text) || !startsSentence(following)) {
                        continue;
                    }
                    if (!endsWithFunctionWord(text)) {
                        continue;
                    }
                    String at = SOURCE_ROOT.relativize(file) + ":" + (i + 1);
                    if (WRAP_ALLOWLIST.contains(at.replace('\\', '/'))) {
                        continue;
                    }
                    violations.add(at + "\n      …" + text + "\n      → " + following);
                }
            }
        }
        assertTrue(violations.isEmpty(), "a wrapped comment sentence looks truncated — rewrite the whole sentence, "
                + "not one line of it (add a genuine wrap to WRAP_ALLOWLIST):\n  " + String.join("\n  ", violations));
    }

    /** Whether the line closes its sentence (or ends in markup/punctuation that makes a following capital fine). */
    private static boolean endsSentence(String text) {
        return text.endsWith(".") || text.endsWith("!") || text.endsWith(":") || text.endsWith(";")
                || text.endsWith(",") || text.endsWith(">") || text.endsWith("}") || text.endsWith(")");
    }

    /** Whether the next line opens with a capitalized word that is not a CamelCase identifier. */
    private static boolean startsSentence(String following) {
        Matcher first = Pattern.compile("^([A-Z][A-Za-z]*)").matcher(following);
        if (!first.find()) {
            return false;
        }
        String word = first.group(1);
        return word.substring(1).chars().noneMatch(Character::isUpperCase);   // CamelCase → an identifier, not prose
    }

    private static boolean endsWithFunctionWord(String text) {
        Matcher words = Pattern.compile("[A-Za-z][A-Za-z\\-']*").matcher(text);
        String last = null;
        while (words.find()) {
            last = words.group();
        }
        return last != null && DANGLING_WORDS.contains(last.toLowerCase(Locale.ROOT));
    }

    /** The shipped metadata, read off the test classpath exactly as a consumer's IDE would read it from the jar. */
    private static List<JsonNode> properties() {
        try (InputStream in = ConfigDocSurfaceTest.class
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertNotNull(in, "spring-configuration-metadata.json is missing — is spring-boot-configuration-processor "
                    + "still wired in build.gradle?");
            JsonNode root = new ObjectMapper().readTree(in);
            List<JsonNode> properties = new ArrayList<>();
            root.path("properties").forEach(properties::add);
            assertTrue(properties.size() > 50, "only " + properties.size() + " properties in the metadata — the "
                    + "annotation processor output looks incomplete");
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("failed to read the shipped configuration metadata", e);
        }
    }
}
