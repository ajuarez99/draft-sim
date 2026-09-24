package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.*;

@Repository
public class PlayerRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public PlayerRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** Bulk upsert straight from the Sleeper players dump. ~11k rows. */
    public void upsertAll(Sport sport, List<Player> players) {
        jdbc.batchUpdate("""
                insert into player (sport, sleeper_id, name, positions, team, status, injury_status, age, years_exp)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (sport, sleeper_id) do update set
                    name = excluded.name,
                    positions = excluded.positions,
                    team = excluded.team,
                    status = excluded.status,
                    injury_status = excluded.injury_status,
                    age = excluded.age,
                    years_exp = excluded.years_exp
                """,
                players,
                500,
                (ps, p) -> {
                    Array arr = ps.getConnection().createArrayOf(
                            "text", p.positions().stream().map(Enum::name).toArray());
                    ps.setString(1, sport.code());
                    ps.setString(2, p.sleeperId());
                    ps.setString(3, p.name());
                    ps.setArray(4, arr);
                    ps.setString(5, p.team());
                    ps.setString(6, p.status());
                    ps.setString(7, p.injuryStatus());
                    if (p.age() == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setInt(8, p.age());
                    if (p.yearsExp() == null) ps.setNull(9, java.sql.Types.INTEGER); else ps.setInt(9, p.yearsExp());
                });
    }

    public Map<String, Long> idsBySleeperId(Sport sport) {
        Map<String, Long> out = new HashMap<>();
        db.sql("select sleeper_id, id from player where sport = ?")
                .param(sport.code())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public List<Player> findAll(Sport sport) {
        return db.sql("""
                select id, sleeper_id, name, positions, team, status, injury_status, age, years_exp
                from player where sport = ?
                """)
                .param(sport.code())
                .query((rs, i) -> mapRow(rs, sport))
                .list();
    }

    /**
     * One player, if this sport has one under this Sleeper id -- used by the
     * conduct-list write endpoints (specs/008-season-superlatives T055) to
     * validate a submitted playerId is a known player for the league's sport,
     * without pulling the whole ~11k-row table for a single lookup.
     */
    public Optional<Player> bySleeperId(Sport sport, String sleeperId) {
        return db.sql("""
                select id, sleeper_id, name, positions, team, status, injury_status, age, years_exp
                from player where sport = ? and sleeper_id = ?
                """)
                .params(sport.code(), sleeperId)
                .query((rs, i) -> mapRow(rs, sport))
                .optional();
    }

    /**
     * Several players in one lookup, keyed by {@code sleeperId}
     * (specs/008-season-superlatives, coordinator follow-up 2026-09-23, item
     * 7): the conduct-list GET used to call {@link #bySleeperId} once per
     * entry, an N+1 that grows with the list. An id this sport has no row for
     * is simply absent from the returned map, the same "caller checks for
     * null/absence" contract {@link #bySleeperId} already has.
     */
    public Map<String, Player> byIds(Sport sport, Collection<String> sleeperIds) {
        if (sleeperIds == null || sleeperIds.isEmpty()) return Map.of();
        String placeholders = String.join(", ", Collections.nCopies(sleeperIds.size(), "?"));
        String sql = ("""
                select id, sleeper_id, name, positions, team, status, injury_status, age, years_exp
                from player where sport = ? and sleeper_id in (%s)
                """).formatted(placeholders);
        List<Object> params = new ArrayList<>();
        params.add(sport.code());
        params.addAll(sleeperIds);
        Map<String, Player> out = new HashMap<>();
        for (Player p : db.sql(sql).params(params).query((rs, i) -> mapRow(rs, sport)).list()) {
            out.put(p.sleeperId(), p);
        }
        return out;
    }

    private static Player mapRow(java.sql.ResultSet rs, Sport sport) throws java.sql.SQLException {
        String[] raw = (String[]) rs.getArray("positions").getArray();
        List<Position> pos = Arrays.stream(raw)
                .map(p -> Position.fromSleeper(p, sport))
                .flatMap(Optional::stream)
                .toList();
        Integer age = rs.getObject("age") == null ? null : rs.getInt("age");
        Integer exp = rs.getObject("years_exp") == null ? null : rs.getInt("years_exp");
        return new Player(
                rs.getLong("id"), sport, rs.getString("sleeper_id"), rs.getString("name"),
                pos, rs.getString("team"), rs.getString("status"),
                rs.getString("injury_status"), age, exp);
    }

    public long count(Sport sport) {
        return db.sql("select count(*) from player where sport = ?")
                .param(sport.code()).query(Long.class).single();
    }
}
