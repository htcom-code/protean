/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.poc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumulates the PoC verdict — one row per (combination × criterion) — and writes it as the run's artifact.
 *
 * <p>The verdict is the only thing that authorizes applying a change, so it is a file rather than something to be
 * reconstructed from a log: the CI job uploads it, and it says what was actually exercised. That last part is why
 * {@link Outcome#SKIPPED} and {@link Outcome#NOT_APPLICABLE} are distinct. A criterion that does not exist for a
 * combination (no worker secrets when nothing is isolated) is settled — it can never fail. A criterion skipped because
 * its prerequisite was missing (no Docker, no database) is <b>unknown</b>, and treating unknown as passed is how a
 * green run comes to mean nothing. The CI job fails when a required combination reports SKIPPED.
 */
public final class PocReport {

    /** Report location. Written under build/ so a clean build discards last run's verdict with everything else. */
    private static final Path FILE = Path.of("build", "poc", "poc-report.md");

    public enum Outcome {
        PASSED,
        FAILED,
        /** Prerequisite absent (Docker, database) — the criterion is <b>unverified</b>, not satisfied. */
        SKIPPED,
        /** The criterion does not exist for this combination — settled, nothing to verify. */
        NOT_APPLICABLE
    }

    /**
     * What was judged. Without it a verdict file is indistinguishable from an older one for a different commit — which
     * happened: a report from the previous day was read as a fresh judgment because nothing in the file said otherwise.
     * The Gradle task also refuses to be skipped, so the two together make a stale verdict both unlikely and obvious.
     */
    private static final String COMMIT = System.getProperty("poc.commit", "unknown");
    private static final String STARTED = java.time.ZonedDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));

    /** combination -> criterion -> outcome (+ note). Insertion-ordered so the table reads in execution order. */
    private static final Map<String, Map<PocCriterion, String>> ROWS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private PocReport() {
    }

    /** Records one verdict cell. Later calls for the same cell overwrite (a retried test reports its final state). */
    public static void record(String combination, PocCriterion criterion, Outcome outcome, String note) {
        String cell = outcome.name() + (note == null || note.isBlank() ? "" : " — " + note);
        ROWS.computeIfAbsent(combination, k -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(criterion, cell);
        flush();   // written on every cell so a crashed or killed run still leaves the rows it did reach
    }

    /** Rewrites the artifact from the rows recorded so far. */
    private static synchronized void flush() {
        StringBuilder md = new StringBuilder();
        md.append("# PoC verdict\n\n")
                .append("- commit judged: `").append(COMMIT).append("`\n")
                .append("- run started: ").append(STARTED).append("\n\n")
                .append("One row per combination, one column per criterion. `SKIPPED` means the criterion was **not "
                        + "verified** (prerequisite missing) — it is not a pass. `N/A` means the criterion does not "
                        + "exist for that combination.\n\n")
                .append("| Combination |");
        for (PocCriterion c : PocCriterion.values()) {
            md.append(' ').append(c.label()).append(" |");
        }
        md.append("\n|---|");
        md.append("---|".repeat(PocCriterion.values().length));
        md.append('\n');

        synchronized (ROWS) {
            for (Map.Entry<String, Map<PocCriterion, String>> row : ROWS.entrySet()) {
                md.append("| `").append(row.getKey()).append("` |");
                for (PocCriterion c : PocCriterion.values()) {
                    String cell = row.getValue().get(c);
                    md.append(' ').append(cell == null ? "—" : cell.replace("NOT_APPLICABLE", "N/A")).append(" |");
                }
                md.append('\n');
            }
        }
        md.append("\n`—` = the combination did not report this criterion at all (test never ran: compile error, "
                + "context failure, or the run was cut short). Treat it as unverified.\n");

        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, md.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the PoC report to " + FILE.toAbsolutePath(), e);
        }
    }
}
