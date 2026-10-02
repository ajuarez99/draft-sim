package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-013: which sections the spotlight builds and renders follows from the sport's own rules,
 * never from a sport name compared in a service, controller or component
 * (specs/014-home-player-spotlight, T011). Same approach, and same reasons, as
 * {@link NoSportNameInWeeklyReportTest}: a rule expressed as a sport check is easy to write in a
 * one-line hotfix, and nothing else notices.
 *
 * <p>Comment lines are skipped so the reasoning may name the sports it reasons about. The
 * component file is written by a separate piece of work; it is scanned when present and
 * skipped (not failed) when it does not exist yet.
 */
class NoSportNameInPlayerSpotlightTest {

    /** Java: {@code "nba"}, {@code Sport.NFL}. TSX additionally uses single quotes. */
    private static final Pattern SPORT_LITERAL = Pattern.compile(
            "[\"'](nba|nfl|NBA|NFL)[\"']|\\bSport\\.(NBA|NFL)\\b");

    private static List<String> offendingLines(Path file) throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            if (SPORT_LITERAL.matcher(lines.get(i)).find()) bad.add((i + 1) + ": " + trimmed);
        }
        return bad;
    }

    private static void assertNamesNoSport(String relative) throws Exception {
        Path f = Path.of(relative);
        assertTrue(Files.exists(f), "expected to find " + f.toAbsolutePath());
        List<String> bad = offendingLines(f);
        assertTrue(bad.isEmpty(), relative + " must not decide anything by sport name. "
                + "Use SportRules.playsMultipleGamesPerScoringPeriod() / the payload's "
                + "playersPlayMultiplePerPeriod. Offending lines:\n" + String.join("\n", bad));
    }

    @Test
    void thePlayerSpotlightServiceNamesNoSport() throws Exception {
        assertNamesNoSport("src/main/java/com/ballknowers/draftsim/engine/PlayerSpotlightService.java");
    }

    @Test
    void thePlayerSpotlightControllerNamesNoSport() throws Exception {
        assertNamesNoSport("src/main/java/com/ballknowers/draftsim/api/PlayerSpotlightController.java");
    }

    /** Gradle runs tests with the backend directory as cwd, so the web tree is one level up. */
    @Test
    void thePlayerSpotlightComponentNamesNoSportWhenItExists() throws Exception {
        Path f = Path.of("../web/src/components/PlayerSpotlight.tsx");
        if (!Files.exists(f)) return; // written by a separate task; scanned as soon as it lands
        assertNamesNoSport("../web/src/components/PlayerSpotlight.tsx");
    }

    /**
     * specs/015: the home page's spotlight tabs. Unlike the check above this one is unconditional:
     * the file ships in the same change, so a rename must fail here rather than skip silently.
     */
    @Test
    void theHomeSpotlightComponentNamesNoSport() throws Exception {
        assertNamesNoSport("../web/src/components/HomeSpotlight.tsx");
    }

    /** The scan must be able to fail, or a green run proves nothing. */
    @Test
    void theScanActuallyCatchesASportLiteral() throws Exception {
        Path tmp = Files.createTempFile("spotlight-scan", ".tsx");
        try {
            Files.writeString(tmp, "// 'nba' in a comment is fine\nconst x = sport === 'nba';\n"
                    + "const y = Sport.NFL;\nconst z = \"nfl\";\n");
            assertEquals(3, offendingLines(tmp).size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
