package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
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

    public record LeagueRow(long id, Sport sport, String sleeperId, String name, int season,
                            int totalRosters, List<String> rosterPositions, double ppr,
                            String previousLeagueId) {}

    private static final String ROW_COLUMNS = """
            id, sport, sleeper_id, name, season, total_rosters, roster_positions,
            coalesce((scoring_json->>'rec')::numeric, 0) as ppr, previous_league_id
            """;

    private static LeagueRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Array a = rs.getArray("roster_positions");
        List<String> slots = List.of((String[]) a.getArray());
        return new LeagueRow(rs.getLong("id"), Sport.fromCode(rs.getString("sport")), rs.getString("sleeper_id"),
                rs.getString("name"), rs.getInt("season"), rs.getInt("total_rosters"),
                slots, rs.getDouble("ppr"), rs.getString("previous_league_id"));
    }

    public Optional<LeagueRow> bySleeperId(String sleeperId) {
        return db.sql("select " + ROW_COLUMNS + " from league where sleeper_id = ?")
                .param(sleeperId)
                .query((rs, i) -> mapRow(rs))
                .optional();
    }

    /**
     * Forward-keyed lookup by the internal id -- added for
     * LeagueController.seats(), which only has DraftRow.leagueId() (internal
     * id) on hand and previously had no path from that to a league's
     * roster_positions; bySleeperId()/all() weren't it.
     */
    public Optional<LeagueRow> byId(long id) {
        return db.sql("select " + ROW_COLUMNS + " from league where id = ?")
                .param(id)
                .query((rs, i) -> mapRow(rs))
                .optional();
    }

    public List<LeagueRow> all() {
        return db.sql("select " + ROW_COLUMNS + " from league order by season desc, name")
                .query((rs, i) -> mapRow(rs))
                .list();
    }

    /** Back-compat: no reversal round to report (see the 3-arg overload) -- plain snake. */
    public static LeagueSettings toSettings(LeagueRow row, int rounds) {
        return toSettings(row, rounds, 0);
    }

    /**
     * @param reversalRound the persisted {@code draft.reversal_round} for the
     *                      specific draft {@code rounds} came from --
     *                      {@link com.ballknowers.draftsim.store.DraftRepository.DraftRow#reversalRound()}
     *                      for a real, persisted draft; 0 for anything else
     *                      (multi-sport-and-rebrand.md Phase 5/6).
     */
    public static LeagueSettings toSettings(LeagueRow row, int rounds, int reversalRound) {
        return new LeagueSettings(row.sport(), row.totalRosters(), rounds, row.rosterPositions(), row.ppr(),
                reversalRound);
    }

    /**
     * Walks {@code previous_league_id} backwards from a season already in this
     * DB, newest first -- entirely local, unlike {@code SleeperClient.leagueChain}
     * which re-hits Sleeper. Backs the read-only history view: once
     * {@code LeagueHistoryIngestService} has ingested a chain, rendering it
     * again needs no network call. Stops at whatever this DB has, which may be
     * a prefix of the real chain if an earlier season was never ingested.
     */
    public List<LeagueRow> chainBySleeperId(String sleeperId) {
        List<LeagueRow> chain = new ArrayList<>();
        String id = sleeperId;
        while (id != null && !id.isBlank() && !"null".equals(id)) {
            Optional<LeagueRow> row = bySleeperId(id);
            if (row.isEmpty()) break;
            chain.add(row.get());
            id = row.get().previousLeagueId();
        }
        return chain;
    }
}
