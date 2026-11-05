package com.ballknowers.draftsim.historical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * One user's play of one historical season (spec 053, V45 {@code historical_game} and
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
    public record GameRow(long id, String sleeperUserId, int seasonStart, int teams, int rounds, int reversalRound,
                          List<String> rosterPositions, String scoringJson, int userSlot, long rngSeed,
                          int currentPickNo, String boardJson, String status, String resultJson,
                          OffsetDateTime createdAt, OffsetDateTime completedAt) {}

    public record PickRow(long gameId, int pickNo, int round, int draftSlot, String seatType, long playerId) {}

    /** A new game, {@code DRAFTING}, at pick 1. */
    public long create(String sleeperUserId, int seasonStart, int teams, int rounds, int reversalRound,
                       List<String> rosterPositions, String scoringJson, int userSlot, long rngSeed,
                       String boardJson, OffsetDateTime createdAt) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into historical_game (sleeper_user_id, season_start, teams, rounds, reversal_round,
                        roster_positions, scoring, user_slot, rng_seed, current_pick_no, board, status, created_at)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 1, ?::jsonb, 'DRAFTING', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sleeperUserId);
            ps.setInt(2, seasonStart);
            ps.setInt(3, teams);
            ps.setInt(4, rounds);
            ps.setInt(5, reversalRound);
            ps.setArray(6, con.createArrayOf("text", rosterPositions.toArray()));
            ps.setString(7, scoringJson);
            ps.setInt(8, userSlot);
            ps.setLong(9, rngSeed);
            ps.setString(10, boardJson);
            ps.setObject(11, createdAt);
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
    public record SummaryRow(long id, String seasonLabel, String status, int userSlot, Integer finish,
                             OffsetDateTime createdAt) {}

    /** A user's games for the list screen, newest first. {@code finish} comes straight out of the frozen result. */
    public List<SummaryRow> summaries(String sleeperUserId) {
        return db.sql("""
                select g.id, s.label, g.status, g.user_slot, (g.result ->> 'userFinish')::int as finish,
                       g.created_at
                from historical_game g join historical_season s on s.season_start = g.season_start
                where g.sleeper_user_id = ? order by g.created_at desc, g.id desc
                """)
                .param(sleeperUserId)
                .query((rs, i) -> {
                    int f = rs.getInt("finish");
                    return new SummaryRow(rs.getLong("id"), rs.getString("label"), rs.getString("status"),
                            rs.getInt("user_slot"), rs.wasNull() ? null : f,
                            rs.getObject("created_at", OffsetDateTime.class));
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
        db.sql("update historical_game set status = 'COMPLETE', result = ?::jsonb, completed_at = ? where id = ?")
                .params(resultJson, completedAt, id)
                .update();
    }

    private static final String SELECT = """
            select id, sleeper_user_id, season_start, teams, rounds, reversal_round, roster_positions,
                   scoring::text as scoring, user_slot, rng_seed, current_pick_no, board::text as board, status,
                   result::text as result, created_at, completed_at
            from historical_game
            """;

    private GameRow map(ResultSet rs, int i) throws SQLException {
        Array a = rs.getArray("roster_positions");
        return new GameRow(rs.getLong("id"), rs.getString("sleeper_user_id"), rs.getInt("season_start"),
                rs.getInt("teams"), rs.getInt("rounds"), rs.getInt("reversal_round"),
                List.of((String[]) a.getArray()), rs.getString("scoring"), rs.getInt("user_slot"),
                rs.getLong("rng_seed"), rs.getInt("current_pick_no"), rs.getString("board"),
                rs.getString("status"), rs.getString("result"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }
}
