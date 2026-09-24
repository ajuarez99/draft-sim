package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A scoring period a player's real team played and he didn't
 * (specs/008-season-superlatives, research R9, data-model.md). See
 * {@code V22__season_superlatives.sql} for the table and its natural key.
 *
 * <p>Shared across leagues, like {@code player_game}: a missed game is a fact
 * about a player, a season and a week, not one league's reading of it. Written
 * by {@code PlayerGameIngestService} in the same walk that writes
 * {@code player_game} rows.
 */
@Repository
public class PlayerAbsenceRepository {

    private final JdbcClient db;

    public PlayerAbsenceRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param gameId   basketball: the missed game's id. Null for a football
     *                 {@code None} week (research R9's TEAM_PLAYED_NO_ENTRY).
     * @param gameDate null for a football {@code None} week.
     * @param team     the player's real team that week, from the entry itself
     *                 or, for a football {@code None} week, a neighbouring entry.
     * @param basis    {@code ENTRY_WITHOUT_PLAY} or {@code TEAM_PLAYED_NO_ENTRY}
     *                 -- see {@code V22}'s check constraint.
     */
    public record Row(Sport sport, int season, int week, String playerId, String gameId,
                      LocalDate gameDate, String team, String basis) {}

    /**
     * Idempotent on the V22 natural key {@code (sport, season, sleeper_player_id,
     * week, coalesce(game_id, ''))}, the same shape as
     * {@code PlayerGameRepository.upsert}: a re-run of the backfill overwrites
     * rather than duplicates.
     */
    public void upsert(Row r) {
        db.sql("""
                insert into player_absence (sport, season, week, sleeper_player_id, game_id, game_date, team, basis)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (sport, season, sleeper_player_id, week, coalesce(game_id, '')) do update set
                    game_date = excluded.game_date,
                    team = excluded.team,
                    basis = excluded.basis
                """)
                .params(r.sport().code(), r.season(), r.week(), r.playerId(), r.gameId(),
                        r.gameDate() == null ? null : Date.valueOf(r.gameDate()), r.team(), r.basis())
                .update();
    }

    /**
     * Removes a specific ENTRY_WITHOUT_PLAY absence, keyed by its game (
     * coordinator follow-up 2026-09-23, item 2): called when a later ingest
     * run sees the SAME game actually played (Sleeper listed an upcoming
     * game as {@code stats: {}}, stored as missed, then filled in the box
     * score once it was played). Without this, both an absence row and a
     * player_game row exist for the one real game, and the award would
     * charge a game he actually played. A no-op if no such row exists.
     */
    public void deleteByGame(Sport sport, int season, String playerId, String gameId) {
        db.sql("delete from player_absence where sport = ? and season = ? and sleeper_player_id = ? and game_id = ?")
                .params(sport.code(), season, playerId, gameId)
                .update();
    }

    /**
     * Removes any football week-level absence (TEAM_PLAYED_NO_ENTRY or
     * UNCLASSIFIED -- the two bases with no {@code game_id}) for one
     * (player, week) (coordinator follow-up 2026-09-23, item 2): called
     * whenever a later ingest run sees an actual per-entry classification
     * for that same week (played or ENTRY_WITHOUT_PLAY), since that's strictly
     * better evidence than the earlier neighbour-team inference. A no-op if
     * no such row exists.
     */
    public void deleteWeeklyBasis(Sport sport, int season, String playerId, int week) {
        db.sql("""
                delete from player_absence
                where sport = ? and season = ? and sleeper_player_id = ? and week = ? and game_id is null
                """)
                .params(sport.code(), season, playerId, week)
                .update();
    }

    /** Every stored absence for one sport and season, regardless of who currently rosters the player. */
    public List<Row> forSeason(Sport sport, int season) {
        return db.sql("""
                select sport, season, week, sleeper_player_id, game_id, game_date, team, basis
                from player_absence
                where sport = ? and season = ?
                """)
                .params(sport.code(), season)
                .query(PlayerAbsenceRepository::toRow)
                .list();
    }

    /** Every stored absence for a specific set of players, one sport and season. */
    public List<Row> forPlayers(Sport sport, int season, Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(playerIds.size(), "?"));
        String sql = """
                select sport, season, week, sleeper_player_id, game_id, game_date, team, basis
                from player_absence
                where sport = ? and season = ? and sleeper_player_id in (%s)
                """.formatted(placeholders);
        List<Object> params = new ArrayList<>();
        params.add(sport.code());
        params.add(season);
        params.addAll(playerIds);
        return db.sql(sql)
                .params(params)
                .query(PlayerAbsenceRepository::toRow)
                .list();
    }

    private static Row toRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate("game_date");
        return new Row(
                Sport.fromCode(rs.getString("sport")),
                rs.getInt("season"),
                rs.getInt("week"),
                rs.getString("sleeper_player_id"),
                rs.getString("game_id"),
                d == null ? null : d.toLocalDate(),
                rs.getString("team"),
                rs.getString("basis"));
    }
}
