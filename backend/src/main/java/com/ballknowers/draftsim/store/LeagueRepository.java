package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                       List<String> rosterPositions, String status) {
        String sql = """
                insert into league (sport, season, sleeper_id, previous_league_id, name,
                                    total_rosters, settings_json, scoring_json, roster_positions, status)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                on conflict (sleeper_id) do update set
                    season = excluded.season,
                    previous_league_id = excluded.previous_league_id,
                    name = excluded.name,
                    total_rosters = excluded.total_rosters,
                    settings_json = excluded.settings_json,
                    scoring_json = excluded.scoring_json,
                    roster_positions = excluded.roster_positions,
                    status = excluded.status
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
            ps.setString(10, status);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        });
        if (id == null) throw new IllegalStateException("league upsert returned no id: " + sleeperId);
        return id;
    }

    /**
     * The league's full scoring settings, category to multiplier
     * (specs/005-daily-weekly-top-players).
     *
     * <p>A separate read rather than another field on {@link LeagueRow}, which
     * is constructed on every league query in the app and derives only the one
     * scoring value the draft board needs ({@code ppr}). Per-game scoring is
     * asked for by one page for one sport; widening a record everybody builds
     * to carry it would be the wrong trade.
     *
     * <p>Values arrive as numbers and are returned as doubles. A non-numeric
     * entry is dropped rather than defaulted, because a scoring category whose
     * multiplier cannot be read is not a zero -- it is a category this app does
     * not understand, and silently scoring it as nothing would quietly change
     * every total that depends on it.
     */
    public Map<String, Double> scoringOf(long leagueId) {
        String json = db.sql("select scoring_json from league where id = ?")
                .param(leagueId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (json == null || json.isBlank()) return Map.of();

        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsonUtil.readMap(json).entrySet()) {
            if (e.getValue() instanceof Number n) out.put(e.getKey(), n.doubleValue());
        }
        return out;
    }

    public record LeagueRow(long id, Sport sport, String sleeperId, String name, int season,
                            int totalRosters, List<String> rosterPositions, double ppr,
                            String previousLeagueId, String status) {

        /**
         * specs/006-deeper-history-both-sports data-model.md, "league.status",
         * nullability rule, verbatim: "A null status means not yet known, and
         * is treated as not complete. It must never be treated as complete,
         * because that is the failure mode this feature exists to fix."
         *
         * <p>That failure mode is not hypothetical -- it is the two wrong
         * champions already stored (popsharky and gregmullen, both crowned for
         * a 2026 season after one week, from a metadata key that actually
         * names the *previous* season's winner). A {@code status} of
         * {@code null} means this row predates V21 and has not been
         * re-ingested yet, not that its season is somehow finished, so it must
         * read exactly like {@code pre_draft} / {@code drafting} /
         * {@code in_season} here: not complete.
         */
        public boolean complete() {
            return isComplete(status);
        }

        /**
         * The same rule as {@link #complete()}, exposed statically for the one
         * caller that has to apply it before a {@code LeagueRow} exists at all:
         * {@code LeagueHistoryIngestService#ingestStandings} (T023) reads
         * Sleeper's raw {@code status} straight off the league map it is
         * already mid-ingest with, in the same pass that upserts this very
         * row -- going back to the DB to re-read what this pass just wrote
         * would be a slower way to ask a question already answered, and
         * copying the null-is-not-complete rule into the ingest service would
         * make it a second definition of "complete" to keep in sync with this
         * one. specs/006-deeper-history-both-sports research R2.
         */
        public static boolean isComplete(String status) {
            return "complete".equals(status);
        }
    }

    private static final String ROW_COLUMNS = """
            id, sport, sleeper_id, name, season, total_rosters, roster_positions,
            coalesce((scoring_json->>'rec')::numeric, 0) as ppr, previous_league_id, status
            """;

    private static LeagueRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Array a = rs.getArray("roster_positions");
        List<String> slots = List.of((String[]) a.getArray());
        return new LeagueRow(rs.getLong("id"), Sport.fromCode(rs.getString("sport")), rs.getString("sleeper_id"),
                rs.getString("name"), rs.getInt("season"), rs.getInt("total_rosters"),
                slots, rs.getDouble("ppr"), rs.getString("previous_league_id"), rs.getString("status"));
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
    /**
     * The league's playoff format, read straight out of {@code settings_json}
     * (claude/playoff-odds.md). Every field here is one Sleeper setting, read
     * in SQL rather than by parsing the blob in Java -- there is no mapping
     * step to get wrong.
     *
     * @param seedType       Sleeper's {@code playoff_seed_type}. 0 is the plain
     *                       "best record seeds first" ladder this app models;
     *                       anything else is a format whose seeding we do not
     *                       reproduce, and odds are withheld rather than guessed.
     * @param hasDivisions   division seeding is likewise not modeled.
     * @param medianMatch    {@code league_average_match}: every team also plays
     *                       the weekly median, which silently doubles the games.
     */
    public record PlayoffFormat(int playoffTeams, int playoffWeekStart, int seedType,
                                boolean hasDivisions, boolean medianMatch) {

        /** claude/playoff-odds.md "Honesty rules": no snapshot at all for a format we cannot seed. */
        public boolean modelable() {
            return playoffTeams > 0 && playoffWeekStart > 1 && seedType == 0 && !hasDivisions;
        }
    }

    public Optional<PlayoffFormat> playoffFormat(long leagueId) {
        return db.sql("""
                select coalesce((settings_json->>'playoff_teams')::int, 0),
                       coalesce((settings_json->>'playoff_week_start')::int, 0),
                       coalesce((settings_json->>'playoff_seed_type')::int, 0),
                       coalesce((settings_json->>'divisions')::int, 0) > 0,
                       coalesce((settings_json->>'league_average_match')::int, 0) > 0
                from league where id = ?
                """)
                .param(leagueId)
                .query((rs, i) -> new PlayoffFormat(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getBoolean(4), rs.getBoolean(5)))
                .optional();
    }

    /**
     * A season's waiver format, read straight out of {@code settings_json}
     * (specs/006-deeper-history-both-sports research R8). {@code waiverType}
     * 2 is FAAB bidding; 0 (and, per Sleeper's own docs, 1) is waiver
     * PRIORITY, with no bidding at all -- which is why a priority season can
     * hold hundreds of {@code WAIVER} rows and zero {@code faab_bid} values.
     * That is the correct format for that season, not an ingest defect, and
     * {@code TransactionAnalysisService}'s career-grain FAAB aggregation
     * excludes exactly these seasons rather than averaging a zero bid into
     * everyone else's.
     *
     * @param waiverBudget the season's own starting FAAB budget. Measured to
     *                      range 100 -> 10000 across this database's six
     *                      played seasons, so a raw dollar figure never
     *                      survives across a single manager's own career --
     *                      only a percentage of THIS field does.
     */
    public record WaiverFormat(int waiverType, double waiverBudget) {

        public boolean usesFaab() {
            return waiverType == 2;
        }
    }

    public Optional<WaiverFormat> waiverFormat(long leagueId) {
        return db.sql("""
                select coalesce((settings_json->>'waiver_type')::int, 0),
                       coalesce((settings_json->>'waiver_budget')::numeric, 0)
                from league where id = ?
                """)
                .param(leagueId)
                .query((rs, i) -> new WaiverFormat(rs.getInt(1), rs.getDouble(2)))
                .optional();
    }

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
