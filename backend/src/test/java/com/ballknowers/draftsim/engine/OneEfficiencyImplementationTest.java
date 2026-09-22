package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SC-004 / research R3 (specs/006-deeper-history-both-sports): a guard
 * against a future regression, not a one-time check.
 *
 * <p>{@code roster_season.points_possible} (Sleeper's own {@code ppts}) and
 * {@link RosterManagementService}'s per-week optimal lineup disagree for the
 * same manager-season -- 92.7% against 91.6% for popsharky, (Foot) Ball
 * Knowers 2025 (baseline.md T004) -- because Sleeper's figure is regular
 * season only while this app sums every stored week, the same reconciliation
 * already recorded for NBA weekly points. Both are defensible answers to
 * different questions, but only one of them may be "what this app calls
 * efficiency" at a time: reading {@code points_possible} anywhere in
 * {@code engine/} or {@code api/} would create a SECOND implementation of
 * "what could this roster have scored" sitting beside the one
 * specs/004-ffwrapped-feature-parity built and proved optimal -- the exact
 * defect class (adp_at_time, league_matchup's fixture gate, and now this)
 * this repo has shipped repeatedly under different names.
 *
 * <p>{@code points_possible} stays stored and stays written by
 * {@link com.ballknowers.draftsim.store.RosterSeasonRepository}'s own upsert
 * and row mapper -- that one file is the sole permitted reference, and this
 * test walks the real source tree rather than trusting a code comment to stay
 * true.
 */
class OneEfficiencyImplementationTest {

    private static final List<String> FORBIDDEN = List.of("points_possible", "pointsPossible");

    /** The one file allowed to mention it: it owns the column, up and down. */
    private static final String ALLOWED_FILE = "RosterSeasonRepository.java";

    @Test
    void noEngineOrApiClassReadsPointsPossible() throws IOException {
        Path root = mainJavaRoot();
        List<String> violations = new ArrayList<>();

        for (String subtree : List.of("engine", "api")) {
            Path dir = root.resolve("com/ballknowers/draftsim/" + subtree);
            if (!Files.isDirectory(dir)) continue;

            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals(ALLOWED_FILE))
                        .forEach(p -> checkFile(p, violations));
            }
        }

        assertTrue(violations.isEmpty(), () -> """
                %s reads points_possible / pointsPossible outside RosterSeasonRepository.

                That column is Sleeper's own regular-season-only ppts, which reads
                92.7%% where RosterManagementService's per-week optimal lineup reads
                91.6%% for the SAME manager-season (baseline.md T004, research R3).
                Taking it here would be a second implementation of "what could this
                roster have scored" -- reuse RosterManagementService's TeamRow.efficiency()
                instead, the way ManagerCareerService already does.
                """.formatted(violations));
    }

    private static void checkFile(Path file, List<String> violations) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String needle : FORBIDDEN) {
            if (content.contains(needle)) {
                violations.add(file + " (contains \"" + needle + "\")");
            }
        }
    }

    /**
     * Gradle's {@code test} task runs with the module directory ({@code backend/})
     * as the working directory, so {@code src/main/java} is reachable directly --
     * no dependency on the artifact jar or the classpath, which would only prove
     * the class COMPILED, not what string literals it contains.
     */
    private static Path mainJavaRoot() {
        Path fromWorkingDir = Path.of("src/main/java");
        if (Files.isDirectory(fromWorkingDir)) return fromWorkingDir;
        Path fromModuleRoot = Path.of("backend/src/main/java");
        if (Files.isDirectory(fromModuleRoot)) return fromModuleRoot;
        throw new IllegalStateException(
                "could not locate src/main/java from working directory " + Path.of("").toAbsolutePath());
    }
}
