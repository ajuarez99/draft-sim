package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.NbaGameLines.Line;

import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real-basketball derived figures over {@link Line}s (specs/022-player-stat-analysis). Pure: no I/O,
 * no Spring. {@code usage} moved here from {@code PlayerTrendsService} so there is one definition.
 */
public final class AdvancedStats {

    private AdvancedStats() {}

    public static final String NO_ATTEMPTS = "NO_ATTEMPTS";
    /** {@code player_absence.basis} of a game Sleeper listed him for and he did not play (V22). */
    static final String ENTRY_WITHOUT_PLAY = "ENTRY_WITHOUT_PLAY";
    public static final String NO_MINUTES = "NO_MINUTES";
    public static final String NO_TEAM_ROW = "NO_TEAM_ROW";

    /**
     * A rate that is either a value or a reason there is none (invariant I2): exactly one of the two
     * is non-null.
     */
    public record Rate(Double value, String reason) {
        public Rate {
            if ((value == null) == (reason == null)) {
                throw new IllegalArgumentException("a Rate has exactly one of value and reason");
            }
        }

        public static Rate of(double v) {
            return new Rate(v, null);
        }

        public static Rate none(String reason) {
            return new Rate(null, reason);
        }
    }

    /**
     * 100 * sum((FGA + 0.44 FTA + TO) * (TmMIN / 5)) / sum(MIN * (TmFGA + 0.44 TmFTA + TmTO)), pooled
     * over the games that have a team row (F12).
     *
     * <p>Reasons: {@code NO_TEAM_ROW} when no game in the window has a team row (including an empty
     * window); {@code NO_MINUTES} when the games that do have one carry no minutes; {@code NO_ATTEMPTS}
     * when minutes exist but the team's possessions-used are zero, so the denominator is still zero.
     */
    public static Rate usage(List<Line> games) {
        double num = 0;
        double den = 0;
        double minutes = 0;
        boolean anyTeamRow = false;
        for (Line g : games) {
            if (g.teamRow() == null) continue;
            anyTeamRow = true;
            Map<String, Object> t = g.teamRow().stats();
            double tmMin = num(t, "sp") / 60.0;
            num += (num(g.stats(), "fga") + 0.44 * num(g.stats(), "fta") + num(g.stats(), "to")) * (tmMin / 5.0);
            den += g.minutes() * (num(t, "fga") + 0.44 * num(t, "fta") + num(t, "to"));
            minutes += g.minutes();
        }
        if (!anyTeamRow) return Rate.none(NO_TEAM_ROW);
        if (minutes <= 0) return Rate.none(NO_MINUTES);
        if (den <= 0) return Rate.none(NO_ATTEMPTS);
        return Rate.of(100.0 * num / den);
    }

    // ------------------------------------------------------------------ windows (spec 022 US1)

    /**
     * The windows a page shows. {@code lastN} is null for the whole season: a last-N window covers
     * {@code min(N, games)} games and says how many (FR-006).
     */
    public enum WindowKind {
        SEASON(null), LAST_10(10), LAST_5(5);

        private final Integer lastN;

        WindowKind(Integer lastN) {
            this.lastN = lastN;
        }

        /** N for a last-N window; null for {@link #SEASON}. */
        public Integer lastN() {
            return lastN;
        }

        /** The window's lines out of a player's season lines, which must be oldest first. */
        public List<Line> select(List<Line> chronological) {
            if (lastN == null || chronological.size() <= lastN) return chronological;
            return chronological.subList(chronological.size() - lastN, chronological.size());
        }
    }

    /**
     * Sixteen counting figures, as per-game, total or per-36. {@code tpm}/{@code tpa} are three-pointers
     * made/attempted (the stored keys).
     */
    public record Counting(double pts, double reb, double oreb, double dreb, double ast, double stl, double blk,
                           double tov, double pf, double fgm, double fga, double tpm, double tpa, double ftm,
                           double fta) {

        static Counting zero() {
            return new Counting(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Counting scaled(double factor) {
            return new Counting(pts * factor, reb * factor, oreb * factor, dreb * factor, ast * factor,
                    stl * factor, blk * factor, tov * factor, pf * factor, fgm * factor, fga * factor,
                    tpm * factor, tpa * factor, ftm * factor, fta * factor);
        }

        static Counting of(Map<String, Object> s) {
            return new Counting(num(s, "pts"), num(s, "reb"), num(s, "oreb"), num(s, "dreb"), num(s, "ast"),
                    num(s, "stl"), num(s, "blk"), num(s, "to"), num(s, "pf"), num(s, "fgm"), num(s, "fga"),
                    num(s, "tpm"), num(s, "tpa"), num(s, "ftm"), num(s, "fta"));
        }
    }

    /** Pooled shooting percentages in percent points (47.3, not 0.473). */
    public record Shooting(Rate fgPct, Rate tpPct, Rate ftPct) {}

    /**
     * One window over a player's lines. {@code perGame}, {@code per36}, {@code gameScorePerGame} and
     * {@code plusMinusPerGame} are null for an empty window (no games, no per-game figure); dates are
     * null likewise. {@code totals} is zero then. {@code minutes} is the window's total.
     */
    public record Window(int games, LocalDate firstGameDate, LocalDate lastGameDate, double minutes,
                         double minutesPerGame, Counting perGame, Counting totals, Counting per36,
                         Shooting shooting, Double gameScorePerGame, Double plusMinusPerGame, boolean smallSample) {}

    /**
     * Pools {@code games} (oldest first): totals are sums, per-game is total over games, per-36 is
     * total x 36 over total minutes (never a mean of per-game rates). {@code smallSample} is minutes
     * strictly below {@code smallSampleMinutes}. A missing stat key counts as 0.
     */
    public static Window window(List<Line> games, int smallSampleMinutes) {
        int n = games.size();
        double minutes = 0;
        double[] t = new double[15];
        double gameScore = 0;
        double plusMinus = 0;
        for (Line g : games) {
            minutes += g.minutes();
            Counting c = Counting.of(g.stats());
            t[0] += c.pts(); t[1] += c.reb(); t[2] += c.oreb(); t[3] += c.dreb(); t[4] += c.ast();
            t[5] += c.stl(); t[6] += c.blk(); t[7] += c.tov(); t[8] += c.pf(); t[9] += c.fgm();
            t[10] += c.fga(); t[11] += c.tpm(); t[12] += c.tpa(); t[13] += c.ftm(); t[14] += c.fta();
            gameScore += gameScore(g);
            plusMinus += num(g.stats(), "plus_minus");
        }
        Counting totals = new Counting(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11],
                t[12], t[13], t[14]);
        Shooting shooting = new Shooting(pct(totals.fgm(), totals.fga()), pct(totals.tpm(), totals.tpa()),
                pct(totals.ftm(), totals.fta()));
        boolean small = minutes < smallSampleMinutes;
        if (n == 0) {
            return new Window(0, null, null, 0.0, 0.0, null, Counting.zero(), null, shooting, null, null, small);
        }
        return new Window(n, games.getFirst().date(), games.getLast().date(), minutes, minutes / n,
                totals.scaled(1.0 / n), totals, minutes > 0 ? totals.scaled(36.0 / minutes) : null, shooting,
                gameScore / n, plusMinus / n, small);
    }

    private static Rate pct(double made, double attempted) {
        return attempted > 0 ? Rate.of(100.0 * made / attempted) : Rate.none(NO_ATTEMPTS);
    }

    /**
     * PTS + 0.4 FGM - 0.7 FGA - 0.4 (FTA - FTM) + 0.7 ORB + 0.3 DRB + STL + 0.7 AST + 0.7 BLK - 0.4 PF - TOV
     * (research R5).
     */
    public static double gameScore(Line g) {
        Map<String, Object> s = g.stats();
        return num(s, "pts") + 0.4 * num(s, "fgm") - 0.7 * num(s, "fga") - 0.4 * (num(s, "fta") - num(s, "ftm"))
                + 0.7 * num(s, "oreb") + 0.3 * num(s, "dreb") + num(s, "stl") + 0.7 * num(s, "ast")
                + 0.7 * num(s, "blk") - 0.4 * num(s, "pf") - num(s, "to");
    }

    /**
     * Games a player missed while he was on a team (F10, amended after code review B1). Per team he played
     * for: that team's games from his first game with it to his last, minus the games he played for it.
     * Plus, for his LAST team only: that team's games after his last appearance for which Sleeper stored a
     * game-level {@code ENTRY_WITHOUT_PLAY} absence row for him (a season-ending injury: he was on the
     * roster and not playing). Later team games with no such row are not counted, because he may have been
     * waived, traded or out of the league. Lines without a team are not attributable and are ignored. Not
     * Trends' {@code missedTeamGames}, which is the consecutive most-recent run.
     *
     * @param absences the player's own stored absences for the season; other basis, week-level rows
     *                 (no game id) and rows carrying a different team are ignored
     */
    public static int teamGamesMissed(List<Line> lines, Map<String, List<TeamGame>> teamGames,
                                      List<PlayerAbsenceRepository.Row> absences) {
        Map<String, LocalDate> first = new HashMap<>();
        Map<String, LocalDate> last = new HashMap<>();
        Map<String, Set<String>> played = new HashMap<>();
        String lastTeam = null;
        LocalDate lastDate = null;
        for (Line l : lines) {
            if (l.team() == null) continue;
            first.merge(l.team(), l.date(), (a, b) -> a.isBefore(b) ? a : b);
            last.merge(l.team(), l.date(), (a, b) -> a.isAfter(b) ? a : b);
            played.computeIfAbsent(l.team(), k -> new HashSet<>()).add(l.gameId());
            if (lastDate == null || !l.date().isBefore(lastDate)) {
                lastDate = l.date();
                lastTeam = l.team();
            }
        }
        int missed = 0;
        for (String team : first.keySet()) {
            Set<String> inRange = new HashSet<>();
            for (TeamGame g : teamGames.getOrDefault(team, List.of())) {
                if (!g.date().isBefore(first.get(team)) && !g.date().isAfter(last.get(team))) inRange.add(g.gameId());
            }
            inRange.removeAll(played.get(team));
            missed += inRange.size();
        }
        if (lastTeam != null) {
            Set<String> absentGames = new HashSet<>();
            for (PlayerAbsenceRepository.Row a : absences) {
                if (a.gameId() == null || !ENTRY_WITHOUT_PLAY.equals(a.basis())) continue;
                if (a.team() != null && !a.team().equals(lastTeam)) continue;
                absentGames.add(a.gameId());
            }
            for (TeamGame g : teamGames.getOrDefault(lastTeam, List.of())) {
                if (g.date().isAfter(lastDate) && absentGames.contains(g.gameId())
                        && !played.get(lastTeam).contains(g.gameId())) {
                    missed++;
                }
            }
        }
        return missed;
    }

    /** A missing or non-numeric stat key is 0 (F12). */
    static double num(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Number n ? n.doubleValue() : 0.0;
    }
}
