package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One season's played games per player, joined to the box-score totals of both teams
 * (specs/022-player-stat-analysis data-model "NbaGameLines"). The join was
 * {@code PlayerTrendsService.prepare}'s and moved here unchanged, so that Trends and the new player
 * pages read one definition of "a played game".
 *
 * <p>Rules: {@code TEAM_} rows are never lines; the bare {@code TEAM_} row and any game whose
 * opponent is not a season team code (the All-Star game) are dropped; {@code sp <= 0} is not a game;
 * a game's team row is the same-game row whose code is not the opponent, the opponent row the one
 * whose code is. Lines are ordered by {@code (date, gameId)}. A game whose id is in
 * {@code excludedGameIds} (NBA Cup finals, which Sleeper stores as ordinary regular-season games and
 * the NBA does not count) is dropped entirely: its player lines and both team rows, so a team's game
 * count and the season's maximum return to the official figure.
 *
 * <p>Pure and immutable: every list, map and set is unmodifiable, because the result is shared by
 * {@code SeasonBoxCache}. A caller that needs another order sorts a copy.
 *
 * @param byPlayer   sleeperPlayerId to his lines, oldest first
 * @param teamGames  team code to that team's box-score rows, in the order the rows were given
 * @param teamCodes  every non-empty team code in the season's team rows
 */
public record NbaGameLines(Map<String, List<Line>> byPlayer, Map<String, List<TeamGame>> teamGames,
                           Set<String> teamCodes) {

    /**
     * One played game of one player. {@code team} is his team's code that night, taken from the team
     * row, so a traded player's lines each carry their own team; it is null when there is no team row.
     * {@code isHome} is {@code !isAway}, null when {@code is_away} is null. {@code teamRow} and
     * {@code oppRow} are nullable.
     */
    public record Line(String gameId, LocalDate date, int week, String team, String opponent, Boolean isHome,
                       double minutes, Map<String, Object> stats, TeamGame teamRow, TeamGame oppRow) {}

    public static NbaGameLines of(List<SeasonGame> games, List<TeamGame> teamRows, Set<String> excludedGameIds) {
        Map<String, List<TeamGame>> rowsByGame = new HashMap<>();
        Map<String, List<TeamGame>> byTeam = new HashMap<>();
        Set<String> codes = new HashSet<>();
        for (TeamGame t : teamRows) {
            if (t.code() == null || t.code().isEmpty()) continue;       // the All-Star game's bare TEAM_ row
            if (excludedGameIds.contains(t.gameId())) continue;         // a configured Cup final
            codes.add(t.code());
            rowsByGame.computeIfAbsent(t.gameId(), k -> new ArrayList<>()).add(t);
            byTeam.computeIfAbsent(t.code(), k -> new ArrayList<>()).add(t);
        }

        Map<String, List<Line>> byPlayer = new HashMap<>();
        for (SeasonGame g : games) {
            if (g.sleeperPlayerId() == null
                    || g.sleeperPlayerId().startsWith(PlayerGameRepository.TEAM_ID_PREFIX)) continue;
            if (excludedGameIds.contains(g.gameId())) continue;                    // a configured Cup final
            if (g.opponent() == null || !codes.contains(g.opponent())) continue;   // All-Star: not a team's game
            double sp = AdvancedStats.num(g.stats(), "sp");
            if (sp <= 0) continue;
            TeamGame teamRow = null;
            TeamGame oppRow = null;
            for (TeamGame t : rowsByGame.getOrDefault(g.gameId(), List.of())) {
                if (!t.code().equals(g.opponent())) teamRow = t;
                else oppRow = t;
            }
            byPlayer.computeIfAbsent(g.sleeperPlayerId(), k -> new ArrayList<>())
                    .add(new Line(g.gameId(), g.gameDate(), g.week(), teamRow == null ? null : teamRow.code(),
                            g.opponent(), g.isAway() == null ? null : !g.isAway(), sp / 60.0, g.stats(),
                            teamRow, oppRow));
        }
        Comparator<Line> chrono = Comparator.comparing(Line::date).thenComparing(Line::gameId);
        Map<String, List<Line>> frozenPlayers = new HashMap<>();
        for (Map.Entry<String, List<Line>> e : byPlayer.entrySet()) {
            e.getValue().sort(chrono);
            frozenPlayers.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        Map<String, List<TeamGame>> frozenTeams = new HashMap<>();
        byTeam.forEach((c, l) -> frozenTeams.put(c, Collections.unmodifiableList(l)));
        return new NbaGameLines(Collections.unmodifiableMap(frozenPlayers), Collections.unmodifiableMap(frozenTeams),
                Collections.unmodifiableSet(codes));
    }

    public static NbaGameLines empty() {
        return of(List.of(), List.of(), Set.of());
    }
}
