package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row per player per game (specs/005-daily-weekly-top-players, US1).
 *
 * <p>Not league-scoped, and stores the raw stat line rather than a points
 * figure: two leagues in a sport and season share every game and differ only in
 * how they score it. See {@code V20__player_game.sql} for the argument, which is
 * V15's argument about projections in a second place.
 *
 * <p>So nothing here knows about a league. Turning a row into points is
 * {@code GameScoringService}'s job, given the asking league's scoring.
 */
@Repository
public class PlayerGameRepository {

    private final JdbcClient db;

    public PlayerGameRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * One player's one game.
     *
     * @param week the fantasy week from the upstream entry's own field -- never
     *             derived from {@code gameDate}, which is what makes a postponed
     *             game land in the week it was played
     * @param opponent nullable; a payload without it still stores the game
     * @param isAway nullable, for the same reason
     */
    public record Row(Sport sport, int season, int week, String sleeperPlayerId, String gameId,
                      LocalDate gameDate, String opponent, Boolean isAway, String statsJson) {}

    /**
     * Idempotent on {@code (sleeper_player_id, game_id)}.
     *
     * <p>Updates rather than doing nothing on conflict, because a settled game is
     * not frozen: stat corrections land after the fact, measured at ~25-50 points
     * of drift across a season's roster totals. A backfill re-run is how a
     * correction reaches this table, so it has to overwrite.
     */
    public void upsert(Row r) {
        db.sql("""
                insert into player_game (sport, season, week, sleeper_player_id, game_id, game_date,
                                         opponent, is_away, stats, fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                on conflict (sleeper_player_id, game_id) do update set
                    sport = excluded.sport,
                    season = excluded.season,
                    week = excluded.week,
                    game_date = excluded.game_date,
                    opponent = excluded.opponent,
                    is_away = excluded.is_away,
                    stats = excluded.stats,
                    fetched_at = now()
                """)
                .params(r.sport().code(), r.season(), r.week(), r.sleeperPlayerId(), r.gameId(),
                        java.sql.Date.valueOf(r.gameDate()), r.opponent(), r.isAway(), r.statsJson())
                .update();
    }

    /**
     * Removes a specific game, keyed by (sleeper_player_id, game_id)
     * (coordinator follow-up 2026-09-23, item 2): called when a later ingest
     * run sees the SAME game as an ENTRY_WITHOUT_PLAY (a stat correction, or a
     * game that briefly showed a box score before being corrected to a DNP).
     * Without this, both a player_game row and an absence row would exist for
     * the one game. A no-op if no such row exists.
     */
    public void deleteByGame(Sport sport, int season, String sleeperPlayerId, String gameId) {
        db.sql("delete from player_game where sport = ? and season = ? and sleeper_player_id = ? and game_id = ?")
                .params(sport.code(), season, sleeperPlayerId, gameId)
                .update();
    }

    /** Every game in one fantasy week. The read path both ranking sections use. */
    public List<Row> forWeek(Sport sport, int season, int week) {
        return db.sql("""
                select sport, season, week, sleeper_player_id, game_id, game_date, opponent,
                       is_away, stats::text as stats
                from player_game
                where sport = ? and season = ? and week = ?
                """)
                .params(sport.code(), season, week)
                .query((rs, n) -> new Row(
                        Sport.fromCode(rs.getString("sport")),
                        rs.getInt("season"),
                        rs.getInt("week"),
                        rs.getString("sleeper_player_id"),
                        rs.getString("game_id"),
                        rs.getDate("game_date").toLocalDate(),
                        rs.getString("opponent"),
                        (Boolean) rs.getObject("is_away"),
                        rs.getString("stats")))
                .list();
    }

    /**
     * Every game on one calendar date (specs/014-home-player-spotlight, T017): the NIGHT period's
     * rows. Keyed by {@code game_date}, the one column that names a night, not by week.
     */
    public List<Row> forDate(Sport sport, int season, LocalDate date) {
        return db.sql("""
                select sport, season, week, sleeper_player_id, game_id, game_date, opponent,
                       is_away, stats::text as stats
                from player_game
                where sport = ? and season = ? and game_date = ?
                """)
                .params(sport.code(), season, java.sql.Date.valueOf(date))
                .query((rs, n) -> new Row(
                        Sport.fromCode(rs.getString("sport")),
                        rs.getInt("season"),
                        rs.getInt("week"),
                        rs.getString("sleeper_player_id"),
                        rs.getString("game_id"),
                        rs.getDate("game_date").toLocalDate(),
                        rs.getString("opponent"),
                        (Boolean) rs.getObject("is_away"),
                        rs.getString("stats")))
                .list();
    }

    /**
     * One game date of a sport-season: which fantasy week it falls in and how many
     * distinct games were played that day.
     *
     * @param gamesCount {@code count(distinct game_id)} -- a game has one id however many
     *                   players have a row in it
     */
    public record DateRow(LocalDate gameDate, int week, int gamesCount) {}

    /**
     * Every distinct {@code game_date} stored for a sport-season, with its week and game
     * count, oldest first (specs/014-home-player-spotlight, research R6).
     *
     * <p>Grouped by (date, week) rather than by date alone, so a date whose rows disagree
     * about their week surfaces as two rows instead of one silently chosen week. Night
     * completeness is judged per week, so the disagreement has to be visible to it.
     */
    public List<DateRow> datesForSeason(Sport sport, int season) {
        return db.sql("""
                select game_date, week, count(distinct game_id) as games
                from player_game
                where sport = ? and season = ?
                group by game_date, week
                order by game_date, week
                """)
                .params(sport.code(), season)
                .query((rs, n) -> new DateRow(rs.getDate("game_date").toLocalDate(),
                        rs.getInt("week"), rs.getInt("games")))
                .list();
    }

    /**
     * Every game played by a specific set of players in one sport and season
     * (specs/008-season-superlatives, research R10): the input to a player's
     * mean points per game played, over however many weeks are stored.
     */
    public List<Row> forPlayers(Sport sport, int season, Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(playerIds.size(), "?"));
        String sql = """
                select sport, season, week, sleeper_player_id, game_id, game_date, opponent,
                       is_away, stats::text as stats
                from player_game
                where sport = ? and season = ? and sleeper_player_id in (%s)
                """.formatted(placeholders);
        List<Object> params = new ArrayList<>();
        params.add(sport.code());
        params.add(season);
        params.addAll(playerIds);
        return db.sql(sql)
                .params(params)
                .query((rs, n) -> new Row(
                        Sport.fromCode(rs.getString("sport")),
                        rs.getInt("season"),
                        rs.getInt("week"),
                        rs.getString("sleeper_player_id"),
                        rs.getString("game_id"),
                        rs.getDate("game_date").toLocalDate(),
                        rs.getString("opponent"),
                        (Boolean) rs.getObject("is_away"),
                        rs.getString("stats")))
                .list();
    }

    /**
     * The id prefix of a team-total row ({@code TEAM_DEN}): a box-score total stored in this table next
     * to the players (spec 019 R2). Every player read excludes it, and it is measured on NFL too, so
     * team reads filter by sport as well (N2). The All-Star game has a bare {@code TEAM_} (suffix empty).
     */
    public static final String TEAM_ID_PREFIX = "TEAM_";

    /**
     * One player-game with only the columns the trends read needs. {@code week} is the fantasy week
     * (the one-game-credit measure groups a player's games by it); trends itself never reads it.
     * {@code isAway} is {@code player_game.is_away}, nullable (the All-Star game's bare row has none;
     * spec 022 F7): read with {@code getObject}, never {@code getBoolean}, which would turn null into home.
     */
    public record SeasonGame(String sleeperPlayerId, String gameId, LocalDate gameDate, String opponent,
                             Map<String, Object> stats, int week, Boolean isAway) {}

    /**
     * The validity token of one sport-season's rows: how many there are and when the newest was
     * fetched. {@code maxFetchedAt} is null when there are no rows.
     */
    public record SeasonToken(long count, java.time.OffsetDateTime maxFetchedAt) {}

    /**
     * {@code count(*), max(fetched_at)} for a sport-season (spec 022 T012). {@code .single()}, not
     * {@code .stream()}: a stream holds its connection until closed (see {@link #playersWithGames}).
     */
    public SeasonToken seasonToken(Sport sport, int season) {
        return db.sql("select count(*), max(fetched_at) from player_game where sport = ? and season = ?")
                .params(sport.code(), season)
                .query((rs, n) -> new SeasonToken(rs.getLong(1), rs.getObject(2, java.time.OffsetDateTime.class)))
                .single();
    }

    /**
     * One team's box-score total for one game. {@code code} is the id's suffix and may be empty (the
     * All-Star game's bare {@code TEAM_}, review F9).
     */
    public record TeamGame(String code, String gameId, LocalDate date, String opponent, Map<String, Object> stats) {}

    private static final int PREFIX_LEN = TEAM_ID_PREFIX.length();

    /**
     * Every PLAYER game of a sport-season (team-total rows excluded), projecting only what the trends
     * read uses. {@code left(id, 5) <> 'TEAM_'} rather than LIKE: no escaping of the underscore (N3).
     */
    public List<SeasonGame> seasonPlayerGames(Sport sport, int season) {
        return db.sql("""
                select sleeper_player_id, game_id, game_date, opponent, stats::text, week, is_away
                from player_game
                where sport = ? and season = ? and left(sleeper_player_id, %d) <> '%s'
                """.formatted(PREFIX_LEN, TEAM_ID_PREFIX))
                .params(sport.code(), season)
                .query((rs, n) -> new SeasonGame(rs.getString(1), rs.getString(2),
                        rs.getDate(3).toLocalDate(), rs.getString(4), JsonUtil.readMap(rs.getString(5)), rs.getInt(6),
                        (Boolean) rs.getObject(7)))
                .list();
    }

    /** The team-total rows of a sport-season, with the id's suffix as {@link TeamGame#code}. */
    public List<TeamGame> seasonTeamGames(Sport sport, int season) {
        return db.sql("""
                select sleeper_player_id, game_id, game_date, opponent, stats::text
                from player_game
                where sport = ? and season = ? and left(sleeper_player_id, %d) = '%s'
                """.formatted(PREFIX_LEN, TEAM_ID_PREFIX))
                .params(sport.code(), season)
                .query((rs, n) -> new TeamGame(rs.getString(1).substring(PREFIX_LEN), rs.getString(2),
                        rs.getDate(3).toLocalDate(), rs.getString(4), JsonUtil.readMap(rs.getString(5))))
                .list();
    }

    /**
     * Which players already have games stored for a season.
     *
     * <p>Reported rather than used as a skip gate. The backfill refetches every
     * player deliberately -- a player present here may still be missing their
     * most recent games, and R6's whole point is that "has rows" and "has all its
     * rows" are different questions. Skipping on presence is the bug this repo
     * has already shipped once, on roster_week_points.
     */
    public Set<String> playersWithGames(Sport sport, int season) {
        // .set() (not .stream()): JdbcClient's stream() holds the connection open
        // until the Stream itself is closed, and this call site never closes it --
        // a live connection leak, one Hikari connection per call, that hung the
        // whole pool after ~10 page loads (specs/008-season-superlatives, found in
        // live verification 2026-09-23). This was dead code on main (spec 005)
        // until SeasonSuperlativesService started calling it every page load.
        // Regression coverage: PlayerGameRepositoryConnectionLeakIT.
        return db.sql("select distinct sleeper_player_id from player_game where sport = ? and season = ?")
                .params(sport.code(), season)
                .query(String.class)
                .set();
    }
}
