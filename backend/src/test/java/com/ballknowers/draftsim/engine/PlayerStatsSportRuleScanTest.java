package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/022-player-stat-analysis (T069): the basketball-only gate on the player-stats pages goes
 * through {@code SportRules.playsMultipleGamesPerScoringPeriod()}, never through a sport name
 * compared by string or enum. Same approach as {@link NoSportNameInPlayerSpotlightTest}; comment
 * lines are skipped so the reasoning may name the sports.
 *
 * <p>What is NOT flagged, deliberately: {@code sport.code()} used as a payload value, a
 * {@code Sport} passed through as a repository / cache key (data, not a rule), the
 * {@code NBA_POSITION} group label, and team-code comparisons ({@code t.code().equals(...)} on a
 * team, not a sport). Only comparing a sport to a literal is a rule. At the time of writing the
 * scan found no allow-listed lines at all in these files.
 */
class PlayerStatsSportRuleScanTest {

    private static final String DIR = "src/main/java/com/ballknowers/draftsim/engine/";

    private static final List<String> SOURCES = List.of(
            "PlayerStatsService.java", "PlayerPercentiles.java", "AdvancedStats.java",
            "ReplacementLevel.java", "DraftAndAdpJoin.java", "PlayerOwnership.java",
            "NbaGameLines.java", "SeasonBoxCache.java");

    // Written without backslash escapes on purpose: [.] [(] [)] and explicit word classes.
    private static final String W = "[A-Za-z0-9_]";
    private static final String NB = "(?<![A-Za-z0-9_])"; // left word boundary

    private static final List<Pattern> RULE_PATTERNS = List.of(
            // "nba" / 'NFL' literals, and Sport.NBA / Sport.NFL anywhere (a rule or a data key: see allow-list)
            Pattern.compile("[\"'](nba|nfl|NBA|NFL)[\"']|" + NB + "Sport[.](NBA|NFL)(?!" + W + ")"),
            // enum compared by == / != against a Sport constant, or a sport variable/accessor
            Pattern.compile("(==|!=) *Sport[.]|" + NB + "Sport[.]" + W + "+ *(==|!=)"),
            Pattern.compile(NB + "sport" + W + "*([(][)])? *(==|!=)|(==|!=) *" + W + "*[sS]port" + W + "*"),
            // string compare on the sport's code, either direction
            Pattern.compile(NB + "sport" + W + "*([(][)])?[.]code[(][)] *[.]equals"),
            Pattern.compile("[.]equals[(] *" + W + "*[sS]port" + W + "*([(][)])?([.]code[(][)])? *[)]"),
            // switch / case on a sport
            Pattern.compile(NB + "switch *[(] *" + W + "*[sS]port"),
            Pattern.compile(NB + "case +(NBA|NFL)(?!" + W + ")"));

    /**
     * Lines (trimmed, exact) that name a sport as data rather than as a rule, each with why.
     * Empty: no such line exists in these sources today. Add here, with a comment, rather than
     * loosening a pattern.
     */
    private static final List<String> ALLOWED_DATA_USES = List.of();

    private static List<String> offendingLines(Path file) throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            if (ALLOWED_DATA_USES.contains(trimmed)) continue;
            for (Pattern p : RULE_PATTERNS) {
                if (p.matcher(lines.get(i)).find()) {
                    bad.add((i + 1) + ": " + trimmed);
                    break;
                }
            }
        }
        return bad;
    }

    @Test
    void thePlayerStatsSourcesNameNoSportAsARule() throws Exception {
        for (String name : SOURCES) {
            Path f = Path.of(DIR + name);
            assertTrue(Files.exists(f), "expected to find " + f.toAbsolutePath());
            List<String> bad = offendingLines(f);
            assertTrue(bad.isEmpty(), name + " must not decide anything by sport name. "
                    + "Use SportRules.playsMultipleGamesPerScoringPeriod(). Offending lines:\n"
                    + String.join("\n", bad));
        }
    }

    /** The scan must be able to fail, or a green run proves nothing. */
    @Test
    void theScanActuallyCatchesSportComparisons() throws Exception {
        Path tmp = Files.createTempFile("player-stats-scan", ".java");
        try {
            Files.writeString(tmp, "// 'nba' in a comment is fine\n"
                    + "if (sport == Sport.NBA) {}\n"
                    + "if (!sport.code().equals(other)) {}\n"
                    + "if (\"nba\".equals(sport.code())) {}\n"
                    + "if (league.sport() != x) {}\n"
                    + "switch (sport) { case NFL: break; }\n"
                    + "adp.latestBefore(sport, date);\n"
                    + "return new Page(sport.code(), season);\n");
            List<String> bad = offendingLines(tmp);
            assertEquals(5, bad.size(), bad.toString());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
