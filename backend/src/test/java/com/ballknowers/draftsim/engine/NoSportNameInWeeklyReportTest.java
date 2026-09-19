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
 * FR-004: which form the Weekly Report renders follows from the sport's own
 * rules, never from a sport name compared in a service or a component
 * (specs/005-daily-weekly-top-players, T050).
 *
 * <p>Grepping source text from a test is ugly, and this class knows it. The
 * alternative is uglier: this repo has shipped "a rule expressed as a sport
 * check" three times under three different names, most recently the throwing
 * {@code startingLineup} default that locked basketball out of views whose
 * data it already had. The rule is easy to state and easy to violate in a
 * one-line hotfix, and nothing else notices.
 *
 * <p>What it permits: {@code Sport} as a type, {@code settings.sport()} passed
 * through to a repository, an import. What it forbids is a literal sport name
 * appearing where a decision is made.
 */
class NoSportNameInWeeklyReportTest {

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

    @Test
    void theWeeklyReportServiceNamesNoSport() throws Exception {
        Path f = Path.of("src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java");
        assertTrue(Files.exists(f), "expected to find " + f.toAbsolutePath());

        List<String> bad = offendingLines(f);
        assertTrue(bad.isEmpty(),
                "WeeklyReportService must not decide anything by sport name. "
                        + "Which form renders comes from SportRules.playsMultipleGamesPerScoringPeriod(). "
                        + "Offending lines:\n" + String.join("\n", bad));
    }

    /**
     * The scoring service is the other place a sport check would be tempting --
     * "basketball scores differently" is true and is still not a reason to
     * branch, because the league's own scoring_json already says how.
     */
    @Test
    void theGameScoringServiceNamesNoSport() throws Exception {
        Path f = Path.of("src/main/java/com/ballknowers/draftsim/engine/GameScoringService.java");
        assertTrue(Files.exists(f), "expected to find " + f.toAbsolutePath());
        assertTrue(offendingLines(f).isEmpty(),
                "GameScoringService must score from the league's scoring_json, not from a sport name");
    }
}
