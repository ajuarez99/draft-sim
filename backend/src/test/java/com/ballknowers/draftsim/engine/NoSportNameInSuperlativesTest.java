package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/008-season-superlatives, AGENTS.md standing rule: no sport-name
 * literal (`"nfl"`, `"nba"`, `Sport.NFL`, `Sport.NBA`) in the new service or
 * controller. Decisions go through {@code SportRules}.
 *
 * <p>Modelled exactly on {@code NoSportNameInWeeklyReportTest}
 * (specs/005-daily-weekly-top-players, T050): grepping source text from a
 * test is ugly, and this class knows it. The alternative is uglier -- this
 * repo has shipped "a rule expressed as a sport check" three times under
 * three different names, and nothing else notices a fourth.
 *
 * <p>What it permits: {@code Sport} as a type, {@code settings.sport()}
 * passed through to a repository, an import. What it forbids is a literal
 * sport name appearing where a decision is made.
 */
class NoSportNameInSuperlativesTest {

    /** Word-boundary matches so {@code Sport}, {@code sport()} and imports pass. */
    private static final Pattern SPORT_LITERAL = Pattern.compile(
            "\"(nba|nfl|NBA|NFL)\"|\\bSport\\.(NBA|NFL)\\b");

    private static List<String> offendingLines(Path file) throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            // Comments are where the reasoning lives, and the reasoning has to
            // be allowed to name the sports it is reasoning about.
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            Matcher m = SPORT_LITERAL.matcher(line);
            if (m.find()) bad.add((i + 1) + ": " + trimmed);
        }
        return bad;
    }

    private void assertNoSportName(String relativePath) throws Exception {
        Path f = Path.of(relativePath);
        assertTrue(Files.exists(f), "expected to find " + f.toAbsolutePath());
        List<String> bad = offendingLines(f);
        assertTrue(bad.isEmpty(),
                relativePath + " must not decide anything by sport name. Decisions go through SportRules. "
                        + "Offending lines:\n" + String.join("\n", bad));
    }

    @Test
    void theSeasonSuperlativesServiceNamesNoSport() throws Exception {
        assertNoSportName("src/main/java/com/ballknowers/draftsim/engine/SeasonSuperlativesService.java");
    }

    @Test
    void theWaiverPickupAttributionNamesNoSport() throws Exception {
        assertNoSportName("src/main/java/com/ballknowers/draftsim/engine/WaiverPickupAttribution.java");
    }

    @Test
    void theAbsenceCostNamesNoSport() throws Exception {
        assertNoSportName("src/main/java/com/ballknowers/draftsim/engine/AbsenceCost.java");
    }

    @Test
    void theSuperlativesControllerNamesNoSport() throws Exception {
        assertNoSportName("src/main/java/com/ballknowers/draftsim/api/SuperlativesController.java");
    }

    @Test
    void thePlayerGameIngestServiceNamesNoSport() throws Exception {
        assertNoSportName("src/main/java/com/ballknowers/draftsim/ingest/PlayerGameIngestService.java");
    }
}
