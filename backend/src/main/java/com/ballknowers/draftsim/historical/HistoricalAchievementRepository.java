package com.ballknowers.draftsim.historical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spec 064 persistence (V48): {@code historical_game_award}, {@code historical_achievement},
 * {@code historical_profile}. Every method joins the caller's transaction; none opens its own, because
 * {@link HistoricalAchievementService#settle} holds one advisory lock and nests a savepoint per game.
 */
@Repository
public class HistoricalAchievementRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public HistoricalAchievementRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** A completed game with no award row yet. {@code resultJson} is never null (the query requires it). */
    public record UnsettledGame(long id, String sport, int teams, int rounds, int userSlot, String resultJson,
                                String pickFactsJson, boolean preAchievements,
                                OffsetDateTime completedAt) {}

    public record AwardRow(long gameId, int xp, int weeklyWins, int playoffWins, boolean title,
                           boolean fromSavedResult, String xpLinesJson, String momentsJson,
                           boolean momentsSeen) {}

    public record AchievementRow(String code, Long gameId, int xp, String detailJson, OffsetDateTime unlockedAt,
                                 boolean seen) {}

    /** Sums over the user's award rows. */
    public record Totals(int games, int gamesFromSavedResults, int weeklyWins, int playoffWins, int titles,
                         int nbaTitles, int nflTitles) {}

    /** Serialises one user's settles. Held until the caller's transaction ends. */
    public void lockUser(String sleeperUserId) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", "tm-ach:" + sleeperUserId);
    }

    public List<UnsettledGame> unsettled(String sleeperUserId) {
        return db.sql("""
                select g.id, g.sport, g.teams, g.rounds, g.user_slot, g.result::text as result,
                       g.pick_facts::text as pick_facts, g.pre_achievements, g.completed_at
                from historical_game g
                left join historical_game_award a on a.game_id = g.id
                where g.sleeper_user_id = ? and g.status = 'COMPLETE' and g.result is not null
                  and a.game_id is null
                order by g.completed_at, g.id
                """)
                .param(sleeperUserId)
                .query((rs, i) -> new UnsettledGame(rs.getLong("id"), rs.getString("sport"), rs.getInt("teams"),
                        rs.getInt("rounds"), rs.getInt("user_slot"), rs.getString("result"),
                        rs.getString("pick_facts"), rs.getBoolean("pre_achievements"),
                        rs.getObject("completed_at", OffsetDateTime.class)))
                .list();
    }

    /** @return rows inserted: 0 when another settle already wrote this game's award */
    public int insertAward(long gameId, String sleeperUserId, String sport, int xp, int weeklyWins, int playoffWins,
                           boolean title, boolean fromSavedResult, String xpLinesJson, String momentsJson,
                           boolean momentsSeen) {
        return db.sql("""
                insert into historical_game_award (game_id, sleeper_user_id, sport, xp, weekly_wins, playoff_wins,
                    title, from_saved_result, xp_lines, moments, moments_seen_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, case when ? then now() else null end)
                on conflict (game_id) do nothing
                """)
                .params(gameId, sleeperUserId, sport, xp, weeklyWins, playoffWins, title, fromSavedResult,
                        xpLinesJson, momentsJson, momentsSeen)
                .update();
    }

    /**
     * @param unlockedAt when the unlocking game finished; null falls back to now()
     * @return 1 when newly unlocked, 0 when the user already had it
     */
    public int insertBadge(String sleeperUserId, String code, long gameId, int xp, String detailJson,
                           OffsetDateTime unlockedAt, boolean seen) {
        return db.sql("""
                insert into historical_achievement (sleeper_user_id, code, game_id, xp, detail, unlocked_at, seen_at)
                values (?, ?, ?, ?, ?::jsonb, coalesce(?::timestamptz, now()), case when ? then now() else null end)
                on conflict (sleeper_user_id, code) do nothing
                """)
                .params(sleeperUserId, code, gameId, xp, detailJson, unlockedAt, seen)
                .update();
    }

    public Totals totals(String sleeperUserId) {
        return db.sql("""
                select count(*) as games,
                       count(*) filter (where from_saved_result) as saved,
                       coalesce(sum(weekly_wins), 0) as wins,
                       coalesce(sum(playoff_wins), 0) as playoff_wins,
                       count(*) filter (where title) as titles,
                       count(*) filter (where title and sport = 'NBA') as nba_titles,
                       count(*) filter (where title and sport = 'NFL') as nfl_titles
                from historical_game_award where sleeper_user_id = ?
                """)
                .param(sleeperUserId)
                .query((rs, i) -> new Totals(rs.getInt("games"), rs.getInt("saved"), rs.getInt("wins"),
                        rs.getInt("playoff_wins"), rs.getInt("titles"), rs.getInt("nba_titles"),
                        rs.getInt("nfl_titles")))
                .single();
    }

    /** Sum of every game's xp plus every unlocked badge's xp (spec 064 SC-002 as amended). */
    public long totalXp(String sleeperUserId) {
        Long a = db.sql("select coalesce(sum(xp), 0) from historical_game_award where sleeper_user_id = ?")
                .param(sleeperUserId).query(Long.class).single();
        Long b = db.sql("select coalesce(sum(xp), 0) from historical_achievement where sleeper_user_id = ?")
                .param(sleeperUserId).query(Long.class).single();
        return a + b;
    }

    public List<AchievementRow> achievements(String sleeperUserId) {
        return db.sql("""
                select code, game_id, xp, detail::text as detail, unlocked_at, seen_at
                from historical_achievement where sleeper_user_id = ? order by unlocked_at, code
                """)
                .param(sleeperUserId)
                .query((rs, i) -> new AchievementRow(rs.getString("code"), rs.getObject("game_id", Long.class),
                        rs.getInt("xp"), rs.getString("detail"), rs.getObject("unlocked_at", OffsetDateTime.class),
                        rs.getObject("seen_at") != null))
                .list();
    }

    public Optional<AwardRow> award(long gameId) {
        return db.sql("""
                select game_id, xp, weekly_wins, playoff_wins, title, from_saved_result, xp_lines::text as lines,
                       moments::text as moments, moments_seen_at
                from historical_game_award where game_id = ?
                """)
                .param(gameId)
                .query((rs, i) -> new AwardRow(rs.getLong("game_id"), rs.getInt("xp"), rs.getInt("weekly_wins"),
                        rs.getInt("playoff_wins"), rs.getBoolean("title"), rs.getBoolean("from_saved_result"),
                        rs.getString("lines"), rs.getString("moments"), rs.getObject("moments_seen_at") != null))
                .optional();
    }

    public int seenLevel(String sleeperUserId) {
        return db.sql("select seen_level from historical_profile where sleeper_user_id = ?")
                .param(sleeperUserId).query(Integer.class).optional().orElse(1);
    }

    /** Upsert; never lowers an existing value. */
    public void raiseSeenLevel(String sleeperUserId, int level) {
        db.sql("""
                insert into historical_profile (sleeper_user_id, seen_level) values (?, ?)
                on conflict (sleeper_user_id)
                do update set seen_level = greatest(historical_profile.seen_level, excluded.seen_level)
                """)
                .params(sleeperUserId, level)
                .update();
    }

    /** Stamps seen_at on those of the user's badges that have none. Unknown or foreign codes are a no-op. */
    public int markBadgesSeen(String sleeperUserId, List<String> codes) {
        if (codes.isEmpty()) return 0;
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    update historical_achievement set seen_at = now()
                    where sleeper_user_id = ? and seen_at is null and code = any(?)
                    """);
            ps.setString(1, sleeperUserId);
            Array arr = con.createArrayOf("text", codes.toArray());
            ps.setArray(2, arr);
            return ps;
        });
    }

    public int markMomentsSeen(String sleeperUserId, long gameId) {
        return db.sql("""
                update historical_game_award set moments_seen_at = now()
                where game_id = ? and sleeper_user_id = ? and moments_seen_at is null
                """)
                .params(gameId, sleeperUserId)
                .update();
    }
}
