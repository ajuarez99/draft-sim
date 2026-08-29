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
                .query((rs, i) -> {
                    String[] raw = (String[]) rs.getArray("positions").getArray();
                    List<Position> pos = Arrays.stream(raw)
                            .map(Position::fromSleeper)
                            .flatMap(Optional::stream)
                            .toList();
                    Integer age = rs.getObject("age") == null ? null : rs.getInt("age");
                    Integer exp = rs.getObject("years_exp") == null ? null : rs.getInt("years_exp");
                    return new Player(
                            rs.getLong("id"), sport, rs.getString("sleeper_id"), rs.getString("name"),
                            pos, rs.getString("team"), rs.getString("status"),
                            rs.getString("injury_status"), age, exp);
                })
                .list();
    }

    public long count(Sport sport) {
        return db.sql("select count(*) from player where sport = ?")
                .param(sport.code()).query(Long.class).single();
    }
}
