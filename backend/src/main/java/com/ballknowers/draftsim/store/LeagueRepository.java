package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.List;
import java.util.Optional;

@Repository
public class LeagueRepository {

    private final JdbcClient db;

    public LeagueRepository(JdbcClient db) {
        this.db = db;
    }

    public long upsert(Sport sport, int season, String sleeperId, String previousLeagueId,
                       String name, int totalRosters, String settingsJson, String scoringJson,
                       List<String> rosterPositions) {
        return db.sql("""
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
                """)
                .param(1, sport.code()).param(2, season).param(3, sleeperId)
                .param(4, previousLeagueId).param(5, name).param(6, totalRosters)
                .param(7, settingsJson).param(8, scoringJson)
                .param(9, rosterPositions.toArray(String[]::new))
                .query(Long.class)
                .single();
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
