package com.ballknowers.draftsim.historical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * One user's play of one historical season (spec 053, V43 {@code historical_game} and
 * {@code historical_game_pick}). Separate from {@link HistoricalDataRepository}, which owns the imported
 * reference data, so the two can change independently.
 *
 * <p>Separate tables from the mock room's, not a reuse of them: {@code mock_draft_pick.player_id} is a foreign
 * key to {@code player(id)}, and historical players are not rows there (research R9).
 *
 * <p>{@link #lockForUpdate} mirrors {@code MockDraftRepository.lockForUpdate}: it is NOT annotated
 * {@code @Transactional}, the service that calls it owns the transaction, and the row lock is held until that
 * transaction ends (plan F10).
 */
@Repository
public class HistoricalGameRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public HistoricalGameRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /**
     * @param scoringJson the era-filtered scoring map as JSON text, frozen at creation
     * @param boardJson   the rank-ordered board snapshot ({playerId, adp, positionalRank}) as JSON text
     * @param resultJson  the frozen results, or null until the game completes
     */
    public record GameRow(long id, String sleeperUserId, String sport, int seasonStart, int teams, int rounds,
                          int reversalRound,
                          List<String> rosterPositions, String scoringJson, int userSlot, long rngSeed,
                          int currentPickNo, String boardJson, String status, String resultJson,
                          OffsetDateTime createdAt, OffsetDateTime completedAt, LocalDate dailyDate,
                          boolean chosenSeason) {}

    public record PickRow(long gameId, int pickNo, int round, int draftSlot, String seatType, long playerId) {}

    /** A new game, {@code DRAFTING}, at pick 1. {@code sport} is named explicitly: the column has no default (V46, F8). */
    public long create(String sleeperUserId, String sport, int seasonStart, int teams, int rounds, int reversalRound,
                       List<String> rosterPositions, String scoringJson, int userSlot, long rngSeed,
                       String boardJson, OffsetDateTime createdAt) {
        return create(sleeperUserId, sport, seasonStart, teams, rounds, reversalRound, rosterPositions, scoringJson,
                userSlot, rngSeed, boardJson, createdAt, null);
    }

    /**
     * As above, for the game of the day (spec 071) when {@code dailyDate} is not null. A second daily game for the
     * same user, sport and date violates {@code historical_game_daily_once} and throws
     * {@link org.springframework.dao.DuplicateKeyException}.
     */
    public long create(String sleeperUserId, String sport, int seasonStart, int teams, int rounds, int reversalRound,
                       List<String> rosterPositions, String scoringJson, int userSlot, long rngSeed,
                       String boardJson, OffsetDateTime createdAt, LocalDate dailyDate) {
        return create(sleeperUserId, sport, seasonStart, teams, rounds, reversalRound, rosterPositions, scoringJson,
                userSlot, rngSeed, boardJson, createdAt, dailyDate, false);
    }

    /**
     * The full form (spec 074). {@code chosenSeason} is true only for a game whose player picked the season; the
     * service passes it explicitly and never infers it. The two shorter forms above are for tooling and tests and
     * always mean "dealt, not chosen". The table forbids chosen and daily together.
     */
    public long create(String sleeperUserId, String sport, int seasonStart, int teams, int rounds, int reversalRound,
                       List<String> rosterPositions, String scoringJson, int userSlot, long rngSeed,
                       String boardJson, OffsetDateTime createdAt, LocalDate dailyDate, boolean chosenSeason) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into historical_game (sleeper_user_id, sport, season_start, teams, rounds,
                        reversal_round, roster_positions, scoring, user_slot, rng_seed, current_pick_no, board,
                        status, created_at, daily_date, chosen_season, started_as_guest)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 1, ?::jsonb, 'DRAFTING', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sleeperUserId);
            ps.setString(2, sport);
            ps.setInt(3, seasonStart);
            ps.setInt(4, teams);
            ps.setInt(5, rounds);
            ps.setInt(6, reversalRound);
            ps.setArray(7, con.createArrayOf("text", rosterPositions.toArray()));
            ps.setString(8, scoringJson);
            ps.setInt(9, userSlot);
            ps.setLong(10, rngSeed);
            ps.setString(11, boardJson);
            ps.setObject(12, createdAt);
            // A LocalDate binds as DATE in pgjdbc; a null needs its type named.
            if (dailyDate == null) ps.setNull(13, java.sql.Types.DATE);
            else ps.setObject(13, dailyDate);
            ps.setBoolean(14, chosenSeason);
            // Spec 076: whether this game was started signed-out, fixed at insert and never updated, so a later
            // claim cannot change it. The one insert site for this table.
            ps.setBoolean(15, Identity.isGuest(sleeperUserId));
            return ps;
        }, keys);
        Number id = (Number) keys.getKeys().get("id");
        return id.longValue();
    }

    public Optional<GameRow> find(long id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional();
    }

    /** Take the row lock; hold it for the rest of the caller's transaction. See the class note. */
    public Optional<GameRow> lockForUpdate(long id) {
        return db.sql(SELECT + " where id = ? for update").param(id).query(this::map).optional();
    }

    /** A user's games, newest first. */
    public List<GameRow> listByUser(String sleeperUserId) {
        return db.sql(SELECT + " where sleeper_user_id = ? order by created_at desc, id desc")
                .param(sleeperUserId)
                .query(this::map)
                .list();
    }

    /** One row of the caller's games list: no board, no result, just what the list shows. */
    public record SummaryRow(long id, String sport, String seasonLabel, String status, int userSlot,
                             Integer finish, Integer formatVersion, String outcome, OffsetDateTime createdAt,
                             Integer draftScore, LocalDate dailyDate, boolean chosenSeason) {}

    /** A user's games for the list screen, newest first. {@code finish} comes straight out of the frozen result. */
    public List<SummaryRow> summaries(String sleeperUserId) {
        return db.sql("""
                select g.id, g.sport, s.label, g.status, g.user_slot, (g.result ->> 'userFinish')::int as finish,
                       (g.result -> 'format' ->> 'version')::int as format_version,
                       g.result ->> 'userOutcome' as outcome, g.created_at,
                       (g.result -> 'hindsight' ->> 'userPointsFor')::float8 as user_points,
                       (g.result -> 'hindsight' ->> 'pointsFor')::float8 as hindsight_points,
                       g.daily_date, g.chosen_season
                from historical_game g
                join historical_season s on s.sport = g.sport and s.season_start = g.season_start
                where g.sleeper_user_id = ? order by g.created_at desc, g.id desc
                """)
                .param(sleeperUserId)
                .query((rs, i) -> {
                    // getObject, not getInt + wasNull(): wasNull() reports on the LAST column read, which used to
                    // be user_slot (never null), so every unfinished game came back finish 0 instead of null.
                    Integer f = rs.getObject("finish", Integer.class);
                    return new SummaryRow(rs.getLong("id"), rs.getString("sport"), rs.getString("label"),
                            rs.getString("status"),
                            rs.getInt("user_slot"), f, rs.getObject("format_version", Integer.class),
                            rs.getString("outcome"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            DraftScore.of(rs.getObject("user_points", Double.class),
                                    rs.getObject("hindsight_points", Double.class)),
                            rs.getObject("daily_date", LocalDate.class), rs.getBoolean("chosen_season"));
                })
                .list();
    }

    public void insertPicks(List<PickRow> picks) {
        jdbc.batchUpdate("""
                insert into historical_game_pick (game_id, pick_no, round, draft_slot, seat_type, player_id)
                values (?, ?, ?, ?, ?, ?)
                """, picks, 1000, (ps, p) -> {
            ps.setLong(1, p.gameId());
            ps.setInt(2, p.pickNo());
            ps.setInt(3, p.round());
            ps.setInt(4, p.draftSlot());
            ps.setString(5, p.seatType());
            ps.setLong(6, p.playerId());
        });
    }

    public List<PickRow> picks(long gameId) {
        return db.sql("""
                select game_id, pick_no, round, draft_slot, seat_type, player_id
                from historical_game_pick where game_id = ? order by pick_no
                """)
                .param(gameId)
                .query((rs, i) -> new PickRow(rs.getLong("game_id"), rs.getInt("pick_no"), rs.getInt("round"),
                        rs.getInt("draft_slot"), rs.getString("seat_type"), rs.getLong("player_id")))
                .list();
    }

    /** Move the draft cursor and status (DRAFTING or ABANDONED) in one write. */
    public void advance(long id, int currentPickNo, String status) {
        db.sql("update historical_game set current_pick_no = ?, status = ? where id = ?")
                .params(currentPickNo, status, id)
                .update();
    }

    /** Freeze the results: status COMPLETE, result written once, completion time stamped. */
    public void writeResult(long id, String resultJson, OffsetDateTime completedAt) {
        writeResult(id, resultJson, null, completedAt);
    }

    /**
     * As above, plus the spec 064 per-pick facts in the same statement ({@code pickFactsJson} null when the
     * capture failed or was skipped: awards then settle from the saved result alone).
     */
    public void writeResult(long id, String resultJson, String pickFactsJson, OffsetDateTime completedAt) {
        db.sql("""
                update historical_game set status = 'COMPLETE', result = ?::jsonb, pick_facts = ?::jsonb,
                    completed_at = ? where id = ?
                """)
                .params(resultJson, pickFactsJson, completedAt, id)
                .update();
    }

    private static final String SELECT = """
            select id, sleeper_user_id, sport, season_start, teams, rounds, reversal_round, roster_positions,
                   scoring::text as scoring, user_slot, rng_seed, current_pick_no, board::text as board, status,
                   result::text as result, created_at, completed_at, daily_date, chosen_season
            from historical_game
            """;

    private GameRow map(ResultSet rs, int i) throws SQLException {
        Array a = rs.getArray("roster_positions");
        return new GameRow(rs.getLong("id"), rs.getString("sleeper_user_id"), rs.getString("sport"),
                rs.getInt("season_start"),
                rs.getInt("teams"), rs.getInt("rounds"), rs.getInt("reversal_round"),
                List.of((String[]) a.getArray()), rs.getString("scoring"), rs.getInt("user_slot"),
                rs.getLong("rng_seed"), rs.getInt("current_pick_no"), rs.getString("board"),
                rs.getString("status"), rs.getString("result"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("daily_date", LocalDate.class), rs.getBoolean("chosen_season"));
    }

    // ---- spec 074: guests, chosen seasons and claim -----------------------------------------------------

    /**
     * Serialises everything that decides something about one owner key: a guest's allowance check against their
     * create, and a claim into one Sleeper id. Held until the caller's transaction ends.
     */
    public void lockOwner(String ownerKey) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", "tm-owner:" + ownerKey);
    }

    /** Games this owner has that are not a game of the day, whatever their status (an abandoned one still counts). */
    public int countNonDaily(String ownerKey) {
        return db.sql("select count(*) from historical_game where sleeper_user_id = ? and daily_date is null")
                .param(ownerKey).query(Integer.class).single();
    }

    public int countOwned(String ownerKey) {
        return db.sql("select count(*) from historical_game where sleeper_user_id = ?")
                .param(ownerKey).query(Integer.class).single();
    }

    /** Takes the row locks on every game this owner has, held to the end of the caller's transaction. */
    public void lockOwnedGames(String ownerKey) {
        db.sql("select id from historical_game where sleeper_user_id = ? order by id for update")
                .param(ownerKey).query(Long.class).list();
    }

    /**
     * Moves every game of {@code from} to {@code to} except a game of the day that {@code to} already has for the
     * same sport and day (the unique index {@code historical_game_daily_once} allows one). Returns how many moved.
     * Two claims into one id at once can both pass the {@code not exists}; the loser hits the index and throws
     * {@link org.springframework.dao.DuplicateKeyException}, which the service answers by running it again.
     */
    public int moveOwner(String from, String to) {
        return db.sql("""
                update historical_game g set sleeper_user_id = ?
                where g.sleeper_user_id = ?
                  and not (g.daily_date is not null and exists (
                        select 1 from historical_game o
                        where o.sleeper_user_id = ? and o.sport = g.sport and o.daily_date = g.daily_date))
                """)
                .params(to, from, to)
                .update();
    }

    // ---- spec 071: the game of the day ------------------------------------------------------------------

    /** The caller's daily game for one sport and day, whatever its status. */
    public Optional<GameRow> findDaily(String sleeperUserId, String sport, LocalDate dailyDate) {
        return db.sql(SELECT + " where sleeper_user_id = ? and sport = ? and daily_date = ?")
                .params(sleeperUserId, sport, dailyDate)
                .query(this::map).optional();
    }

    /** The puzzle dates of the caller's COMPLETE daily games for one sport, in no particular order. */
    public List<LocalDate> completedDailyDates(String sleeperUserId, String sport) {
        return db.sql("""
                select daily_date from historical_game
                where sleeper_user_id = ? and sport = ? and daily_date is not null and status = 'COMPLETE'
                """)
                .params(sleeperUserId, sport)
                .query((rs, i) -> rs.getObject("daily_date", LocalDate.class)).list();
    }

    /** The draft score of one COMPLETE game, or null (not complete, or no hindsight total). */
    public Integer draftScore(long gameId) {
        return db.sql("""
                select (result -> 'hindsight' ->> 'userPointsFor')::float8 as user_points,
                       (result -> 'hindsight' ->> 'pointsFor')::float8 as hindsight_points
                from historical_game where id = ?
                """)
                .param(gameId)
                .query((rs, i) -> java.util.Optional.ofNullable(DraftScore.of(
                        rs.getObject("user_points", Double.class), rs.getObject("hindsight_points", Double.class))))
                .optional().flatMap(o -> o).orElse(null);
    }

    /** One day's dealt puzzle. */
    public record DailyRow(String sport, LocalDate dailyDate, int seasonStart, long rngSeed) {}

    public Optional<DailyRow> findDailyPuzzle(String sport, LocalDate dailyDate) {
        return db.sql("select sport, daily_date, season_start, rng_seed from historical_daily"
                        + " where sport = ? and daily_date = ?")
                .params(sport, dailyDate)
                .query((rs, i) -> new DailyRow(rs.getString("sport"), rs.getObject("daily_date", LocalDate.class),
                        rs.getInt("season_start"), rs.getLong("rng_seed")))
                .optional();
    }

    /** Writes the day's puzzle unless one is already there; the caller reads back whichever row won. */
    public void insertDailyPuzzleIfAbsent(DailyRow row) {
        db.sql("""
                insert into historical_daily (sport, daily_date, season_start, rng_seed) values (?, ?, ?, ?)
                on conflict (sport, daily_date) do nothing
                """)
                .params(row.sport(), row.dailyDate(), row.seasonStart(), row.rngSeed())
                .update();
    }

    /** The seasons this sport's most recent daily puzzles before {@code before} used, newest first. */
    public List<Integer> recentDailySeasons(String sport, LocalDate before, int limit) {
        return db.sql("select season_start from historical_daily where sport = ? and daily_date < ?"
                        + " order by daily_date desc limit ?")
                .params(sport, before, limit)
                .query((rs, i) -> rs.getInt("season_start")).list();
    }
}
