package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class LeagueRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public LeagueRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /**
     * roster_positions is a text[]. Binding it goes through JdbcTemplate with an
     * explicit createArrayOf rather than JdbcClient's generic parameter path:
     * handing the driver a bare String[] relies on pgjdbc inferring the SQL type,
     * which is version-dependent. This way there is nothing to infer.
     */
    public long upsert(Sport sport, int season, String sleeperId, String previousLeagueId,
                       String name, int totalRosters, String settingsJson, String scoringJson,
                       List<String> rosterPositions) {
        String sql = """
                insert into league (sport, season, sleeper_id, previous_league_id, name,
                                    total_rosters, settings_json, scoring_json, roster_positions)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                on conflict (sleeper_id) do update set
                    season = excluded.season,
                    previous_league_id = excluded.previous_league_id,
                    name = excluded.name,
                    total_rosters = excluded.total_rosters,
                    settings_json = excluded.settings_json,
                    scoring_json = excluded.scoring_json,
                    roster_positions = excluded.roster_positions
                returning id
                """;

        Long id = jdbc.execute(sql, (PreparedStatement ps) -> {
            Array slots = ps.getConnection().createArrayOf("text", rosterPositions.toArray());
            ps.setString(1, sport.code());
            ps.setInt(2, season);
            ps.setString(3, sleeperId);
            ps.setString(4, previousLeagueId);
            ps.setString(5, name);
            ps.setInt(6, totalRosters);
            ps.setString(7, settingsJson);
            ps.setString(8, scoringJson);
            ps.setArray(9, slots);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        });
        if (id == null) throw new IllegalStateException("league upsert returned no id: " + sleeperId);
        return id;
    }

    public record LeagueRow(long id, String sleeperId, String name, int season,
                            int totalRosters, List<String> rosterPositions, double ppr) {}

    public Optional<LeagueRow> bySleeperId(String sleeperId) {
        return db.sql("""
                select id, sleeper_id, name, season, total_rosters, roster_positions,
                       coalesce((scoring_json->>'rec')::numeric, 0) as ppr
                from league where sleeper_id = ?
                """)
                .param(sleeperId)
                .query((rs, i) -> {
                    Array a = rs.getArray("roster_positions");
                    List<String> slots = List.of((String[]) a.getArray());
                    return new LeagueRow(rs.getLong("id"), rs.getString("sleeper_id"),
                            rs.getString("name"), rs.getInt("season"), rs.getInt("total_rosters"),
                            slots, rs.getDouble("ppr"));
                })
                .optional();
    }

    /**
     * Forward-keyed lookup by the internal id -- added for
     * LeagueController.seats(), which only has DraftRow.leagueId() (internal
     * id) on hand and previously had no path from that to a league's
     * roster_positions; bySleeperId()/all() weren't it.
     */
    public Optional<LeagueRow> byId(long id) {
        return db.sql("""
                select id, sleeper_id, name, season, total_rosters, roster_positions,
                       coalesce((scoring_json->>'rec')::numeric, 0) as ppr
                from league where id = ?
                """)
                .param(id)
                .query((rs, i) -> {
                    Array a = rs.getArray("roster_positions");
                    List<String> slots = List.of((String[]) a.getArray());
                    return new LeagueRow(rs.getLong("id"), rs.getString("sleeper_id"),
                            rs.getString("name"), rs.getInt("season"), rs.getInt("total_rosters"),
                            slots, rs.getDouble("ppr"));
                })
                .optional();
    }

    public List<LeagueRow> all() {
        return db.sql("""
                select id, sleeper_id, name, season, total_rosters, roster_positions,
                       coalesce((scoring_json->>'rec')::numeric, 0) as ppr
                from league order by season desc, name
                """)
                .query((rs, i) -> {
                    Array a = rs.getArray("roster_positions");
                    return new LeagueRow(rs.getLong("id"), rs.getString("sleeper_id"),
                            rs.getString("name"), rs.getInt("season"), rs.getInt("total_rosters"),
                            List.of((String[]) a.getArray()), rs.getDouble("ppr"));
                })
                .list();
    }

    public static LeagueSettings toSettings(LeagueRow row, int rounds) {
        return new LeagueSettings(row.totalRosters(), rounds, row.rosterPositions(), row.ppr());
    }
}
