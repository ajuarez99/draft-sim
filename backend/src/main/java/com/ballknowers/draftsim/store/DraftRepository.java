package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DraftRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public DraftRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** Back-compat overload: {@code reversal_round} defaults to 0 (no reversal). */
    public long upsert(long leagueId, String sleeperDraftId, int season, int rounds, int teams,
                       String type, String status, Instant startTime, String slotToManagerJson) {
        return upsert(leagueId, sleeperDraftId, season, rounds, teams, type, status, startTime,
                slotToManagerJson, 0);
    }

    /**
     * @param reversalRound Sleeper's {@code settings.reversal_round}, read by
     *                      {@link com.ballknowers.draftsim.ingest.LeagueIngestService}
     *                      off the draft object (multi-sport-and-rebrand.md Phase 5).
     *                      0 means plain snake for the whole draft.
     */
    public long upsert(long leagueId, String sleeperDraftId, int season, int rounds, int teams,
                       String type, String status, Instant startTime, String slotToManagerJson,
                       int reversalRound) {
        return db.sql("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams,
                                   draft_type, status, start_time, slot_to_manager, reversal_round)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (sleeper_draft_id) do update set
                    rounds = excluded.rounds,
                    teams = excluded.teams,
                    status = excluded.status,
                    start_time = excluded.start_time,
                    slot_to_manager = excluded.slot_to_manager,
                    reversal_round = excluded.reversal_round
                returning id
                """)
                .param(1, leagueId).param(2, sleeperDraftId).param(3, season).param(4, rounds)
                .param(5, teams).param(6, type).param(7, status)
                .param(8, startTime == null ? null : OffsetDateTime.ofInstant(startTime, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(9, slotToManagerJson)
                .param(10, reversalRound)
                .query(Long.class)
                .single();
    }

    public record PickRow(long draftId, int pickNo, int round, int draftSlot,
                          Long managerId, Long playerId, Double adpAtTime) {}

    /**
     * Makes {@code draft_pick} match {@code picks} exactly, without destroying
     * adp_at_time.
     *
     * This used to be a literal delete-then-insert, and the insert bound
     * {@code PickRow.adpAtTime()}, which {@code PickMapper} hardcodes to null. So
     * any league ingest run after a board rebuild silently zeroed the
     * contemporaneous board position on every pick in that league, which zeroed
     * every fitted manager profile behind it — HANDOFF's "Known live bug", and
     * one click of the UI's own "Add a draft" button was enough to trigger it.
     *
     * The fix is a shape change, not an {@code on conflict} clause: line-one's
     * delete makes any conflict unreachable. Prune only the picks that are
     * genuinely gone, then hand the rest to {@link #upsertPicks}, whose
     * {@code coalesce} already gets this right. Coalescing a stale value is safe
     * because {@code BoardService.backfillAdpAtTime} overwrites unconditionally
     * rather than only filling nulls.
     */
    public void replacePicks(long draftId, List<PickRow> picks) {
        if (picks == null || picks.isEmpty()) {
            jdbc.update("delete from draft_pick where draft_id = ?", draftId);
            return;
        }
        Integer[] keep = picks.stream().map(PickRow::pickNo).toArray(Integer[]::new);
        // createArrayOf rather than binding a bare array and letting pgjdbc infer
        // the SQL type — same reasoning as LeagueRepository's text[] binding
        // (claude/lessons.md #4): nothing left to infer.
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "delete from draft_pick where draft_id = ? and not (pick_no = any (?))");
            ps.setLong(1, draftId);
            ps.setArray(2, con.createArrayOf("integer", keep));
            return ps;
        });
        upsertPicks(draftId, picks);
    }

    /**
     * Re-upserts the full pick list every call — safe to call every poll tick.
     * adp_at_time is coalesced, not overwritten: a freshly-observed pick always
     * carries adp_at_time = null (BoardService backfills it later), and a naive
     * `= excluded.adp_at_time` would null out that backfill on every subsequent
     * tick for the rest of the draft.
     */
    public void upsertPicks(long draftId, List<PickRow> picks) {
        if (picks == null || picks.isEmpty()) return;
        jdbc.batchUpdate("""
                insert into draft_pick (draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (draft_id, pick_no) do update set
                    round = excluded.round,
                    draft_slot = excluded.draft_slot,
                    manager_id = excluded.manager_id,
                    player_id = excluded.player_id,
                    adp_at_time = coalesce(draft_pick.adp_at_time, excluded.adp_at_time)
                """,
                picks, 500, DraftRepository::bindPickRow);
    }

    private static void bindPickRow(java.sql.PreparedStatement ps, PickRow p) throws java.sql.SQLException {
        ps.setLong(1, p.draftId());
        ps.setInt(2, p.pickNo());
        ps.setInt(3, p.round());
        ps.setInt(4, p.draftSlot());
        if (p.managerId() == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, p.managerId());
        if (p.playerId() == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, p.playerId());
        if (p.adpAtTime() == null) ps.setNull(7, Types.NUMERIC); else ps.setDouble(7, p.adpAtTime());
    }

    /** Flips only status, without needing the full row this poller doesn't have on hand. */
    public void updateStatus(long draftId, String status) {
        jdbc.update("update draft set status = ? where id = ?", status, draftId);
    }

    /**
     * Flips only the seat map, same shape and same reason as {@link #updateStatus}:
     * {@link #upsert} would demand league/season/rounds/teams/type/startTime that
     * LiveDraftPoller does not carry, and its {@code on conflict} sets start_time
     * from the incoming row — so reusing it would null out a start time the poller
     * never had.
     */
    public void updateSlotToManager(long draftId, String slotToManagerJson) {
        jdbc.update("update draft set slot_to_manager = ?::jsonb where id = ?", slotToManagerJson, draftId);
    }

    /**
     * @param reversalRound the <em>effective</em> reversal round -- the user's
     *                      {@code reversal_round_override} when they have set
     *                      one, otherwise the persisted {@code reversal_round}
     *                      Sleeper reported (multi-sport-and-rebrand.md Phases
     *                      5 and 6b). 0 for every draft ingested before that
     *                      column existed, and for every NFL draft in the
     *                      database, since Sleeper's own default is 0 and
     *                      football never reverses.
     *                      <p>
     *                      The coalesce happens in the query rather than at the
     *                      three call sites that read this field, so there is no
     *                      raw-vs-effective distinction for a caller to get
     *                      wrong. Anything that needs to tell the two apart --
     *                      only the settings UI does -- calls
     *                      {@link #reversalRound(long)}.
     */
    public record DraftRow(long id, long leagueId, String sleeperDraftId, int season,
                           int rounds, int teams, String status, Map<String, Object> slotToManager,
                           int reversalRound) {

        /** Back-compat for callers/tests built before {@link #reversalRound} existed -- plain snake. */
        public DraftRow(long id, long leagueId, String sleeperDraftId, int season,
                        int rounds, int teams, String status, Map<String, Object> slotToManager) {
            this(id, leagueId, sleeperDraftId, season, rounds, teams, status, slotToManager, 0);
        }
    }

    public Optional<DraftRow> bySleeperId(String sleeperDraftId) {
        return db.sql("""
                select id, league_id, sleeper_draft_id, season, rounds, teams, status, slot_to_manager::text,
                       coalesce(reversal_round_override, reversal_round)
                from draft where sleeper_draft_id = ?
                """)
                .param(sleeperDraftId)
                .query((rs, i) -> new DraftRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                        JsonUtil.readMap(rs.getString(8)), rs.getInt(9)))
                .optional();
    }

    /**
     * Sleeper's value and the user's, unmerged.
     *
     * The only reason to want them apart is to show them apart: the round from
     * which snake parity flips is an <em>assumption</em> on the one draft that
     * uses it (multi-sport-and-rebrand.md, "Assumed, not verified" -- no
     * completed draft in reach exercises {@code reversal_round: 3}), so the
     * settings UI shows what Sleeper claims next to what the user asserted
     * rather than one number with no provenance.
     *
     * @param override null when the user has expressed no opinion, which is
     *                 every row until someone edits one. Note that a null
     *                 override and an override that happens to equal
     *                 {@code fromSleeper} are different states and stay
     *                 different: the second survives a re-ingest that moves
     *                 Sleeper's value, and the first does not.
     */
    public record ReversalRound(int fromSleeper, Integer override) {
        public int effective() {
            return override == null ? fromSleeper : override;
        }
    }

    public Optional<ReversalRound> reversalRound(long draftId) {
        return db.sql("select reversal_round, reversal_round_override from draft where id = ?")
                .param(draftId)
                .query((rs, i) -> new ReversalRound(
                        rs.getInt(1),
                        rs.getObject(2) == null ? null : rs.getInt(2)))
                .optional();
    }

    /** {@code override == null} clears the user's opinion and goes back to following Sleeper. */
    public void setReversalRoundOverride(long draftId, Integer override) {
        jdbc.update("update draft set reversal_round_override = ? where id = ?", override, draftId);
    }

    /**
     * This draft's sport, off its league.
     *
     * Exists so {@link com.ballknowers.draftsim.ingest.LiveDraftPoller} can
     * resolve a sport without taking a second repository: it already holds this
     * one, and a poll tick that guesses the sport resolves every pick to null
     * (or, worse, to the wrong player -- see below).
     *
     * The tempting cheaper fix was one sport-less {@code idsBySleeperId} map
     * covering both sports. Measured 2026-09-09 against the real player table:
     * **753 sleeper ids are used by a player in both sports.** A combined map
     * would silently resolve an NBA pick to a real NFL player, which is worse
     * than the null it replaces, because nothing downstream can tell it is
     * wrong. Sleeper's id namespaces are per-sport and they overlap.
     *
     * Empty only if the league row is gone, which the schema's foreign key
     * makes unreachable -- the caller decides what that means.
     */
    public Optional<Sport> sportOf(long draftId) {
        return db.sql("select l.sport from draft d join league l on l.id = d.league_id where d.id = ?")
                .param(draftId)
                .query((rs, i) -> Sport.fromCode(rs.getString(1)))
                .optional();
    }

    /** Completed picks for a draft, ordered. Used both for profiles and for resume-from-state. */
    public List<PickRow> picks(long draftId) {
        return db.sql("""
                select draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time
                from draft_pick where draft_id = ? order by pick_no
                """)
                .param(draftId)
                .query((rs, i) -> new PickRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getObject(6) == null ? null : rs.getLong(6),
                        rs.getObject(7) == null ? null : rs.getDouble(7)))
                .list();
    }

    /**
     * {@code previousLeagueId} is Sleeper's own back-pointer to the same league's
     * prior season, null for the earliest one ingested. The picker groups seasons
     * into one card per league with it -- matching on {@code leagueName} instead
     * would collapse two genuinely different leagues that share a name, and split
     * one that got renamed between seasons.
     *
     * {@code sport} backs the deliberately-mixed, sport-tagged picker list --
     * {@link #allWithLeague()} stays unfiltered by design (multi-sport-and-
     * rebrand.md Phase 2), so the frontend needs the tag to render a pill
     * rather than the repository ever splitting this into two lists.
     */
    public record DraftSummary(long id, String sleeperDraftId, long leagueId, String leagueName,
                               int season, int teams, int rounds, String status, Instant startTime,
                               String sleeperLeagueId, String previousLeagueId, Sport sport) {}

    private static final String DRAFT_SUMMARY_COLUMNS = """
            d.id, d.sleeper_draft_id, d.league_id, l.name, d.season, d.teams, d.rounds,
            d.status, d.start_time, l.sleeper_id, l.previous_league_id, l.sport
            """;

    private static DraftSummary mapDraftSummary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DraftSummary(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
                rs.getString(10), rs.getString(11), Sport.fromCode(rs.getString(12)));
    }

    /** Every draft in the DB, joined to its league, newest first. Backs the app-shell picker screen. */
    public List<DraftSummary> allWithLeague() {
        return db.sql("select " + DRAFT_SUMMARY_COLUMNS + """
                from draft d join league l on l.id = d.league_id
                order by d.start_time desc nulls last, d.season desc, d.id desc
                """)
                .query(DraftRepository::mapDraftSummary)
                .list();
    }

    /**
     * {@link #allWithLeague} scoped to the leagues a Sleeper user is actually
     * in (claude/user-identity-and-onboarding.md §4b) -- an unknown
     * {@code sleeperUserId} (no {@code manager} row) returns empty, not the
     * unfiltered list, which is the failure mode that matters here: a bug that
     * silently falls through to "everything" looks exactly like working
     * software to the one person who is in every league.
     *
     * The membership rule itself lives in
     * {@link LeagueMembership#MEMBER_LEAGUE_IDS_CTE} -- it was inlined here
     * until a second caller needed the same answer, and two copies of it is
     * how the app would start disagreeing with itself about whose league is
     * whose. The chain walk, and why it goes backwards only, are documented
     * there; the picker's own {@code leagueLineages} collapses the predecessor
     * seasons it pulls in into one card.
     */
    public List<DraftSummary> allWithLeagueFor(String sleeperUserId) {
        return db.sql(LeagueMembership.MEMBER_LEAGUE_IDS_CTE + "select "
                + DRAFT_SUMMARY_COLUMNS + """
                from draft d join league l on l.id = d.league_id
                where l.id in (select id from chain)
                order by d.start_time desc nulls last, d.season desc, d.id desc
                """)
                .param(sleeperUserId)
                .query(DraftRepository::mapDraftSummary)
                .list();
    }

    /** Every completed pick across every ingested draft, for profile fitting. */
    /**
     * A completed pick together with the shape of the draft it came from.
     *
     * The shape is the whole point: pick 30 is a different fraction of a
     * 12-team draft than of a 14-team one, and profile fitting has to normalize
     * on that before it can pool picks across leagues of different sizes. It is
     * joined on rather than stored, since it is a property of the draft, not of
     * the pick.
     */
    public record CompletedPick(long draftId, int pickNo, int round, int draftSlot,
                                Long managerId, Long playerId, Double adpAtTime,
                                int teams, int rounds) {

        public int totalPicks() {
            return teams * rounds;
        }
    }

    /**
     * Scoped to one sport -- multi-sport-and-rebrand.md Phase 2. Before this,
     * the query joined {@code draft_pick -> draft} with no join to
     * {@code league} and no sport filter, so a completed NBA draft's picks
     * fed straight into a football {@code ProfileService.fit()}. Priors and
     * tilt were already safe by accident (an NBA player never resolves to a
     * football {@code Position}, so those loops {@code continue}), but
     * {@code draftsByManager} counted the NBA draft id against a manager
     * unconditionally, inflating the shrinkage N for every manager who also
     * plays the other sport -- silently under-shrinking their profile in the
     * sport actually being fit.
     */
    public List<CompletedPick> allCompletedPicks(Sport sport) {
        return db.sql("""
                select p.draft_id, p.pick_no, p.round, p.draft_slot, p.manager_id, p.player_id,
                       p.adp_at_time, d.teams, d.rounds
                from draft_pick p
                join draft d on d.id = p.draft_id
                join league l on l.id = d.league_id
                where d.status = 'complete' and p.manager_id is not null and l.sport = ?
                order by p.draft_id, p.pick_no
                """)
                .param(sport.code())
                .query((rs, i) -> new CompletedPick(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getObject(6) == null ? null : rs.getLong(6),
                        rs.getObject(7) == null ? null : rs.getDouble(7),
                        rs.getInt(8), rs.getInt(9)))
                .list();
    }
}
