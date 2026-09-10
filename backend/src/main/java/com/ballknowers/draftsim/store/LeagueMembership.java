package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * "Which leagues are this manager's?" -- the one definition of that rule.
 *
 * It used to exist only as a CTE inlined in
 * {@link DraftRepository#allWithLeagueFor}, which was fine while exactly one
 * endpoint scoped anything. The moment a second caller needs the same answer, a
 * second copy of this SQL is the failure this project has already had once: the
 * frontend's own plain-snake implementation disagreed with the engine's
 * reversal-round one, and the board on screen contradicted the simulator. Two
 * implementations of one rule is how the app disagrees with itself. So the walk
 * lives here exactly once, in {@link #MINE_AND_CHAIN}, and the two ways of
 * naming a manager -- by their Sleeper user id (the request header) and by their
 * {@code manager.id} (a link from a standings row) -- are just two different
 * {@code me} clauses in front of the same body.
 *
 * <p><b>This is scoping, not security.</b> {@code X-Sleeper-User} is an
 * unverified claim: anyone can send anyone's Sleeper id, and Sleeper ids are
 * public. What this buys is that the app stops handing every visitor every
 * league by default. It does not stop someone who wants the data. Real auth is
 * the answer to that; see DEPLOY.md's "Multiple people".
 */
@Repository
public class LeagueMembership {

    private final JdbcClient db;
    private final DraftRepository drafts;

    public LeagueMembership(JdbcClient db, DraftRepository drafts) {
        this.db = db;
        this.drafts = drafts;
    }

    /**
     * The membership walk, minus the {@code me} clause that names whose it is.
     *
     * Membership is the union of three paths, since each alone misses leagues
     * the others cover: {@code roster_season} catches a league with no
     * ingested draft at all, {@code slot_to_manager} catches a draft ingested
     * before that manager had any scored season, and {@code league_member}
     * (claude/power-rankings-ballots.md, added for the ballots feature)
     * catches a league with NEITHER -- which is every league on the day it is
     * created, since both of the other two paths need a whole ingest run to
     * have happened first. The {@code chain} step then walks
     * {@code previous_league_id} backwards to pull in predecessor seasons --
     * forwards only, since a successor league you were later dropped from is
     * genuinely not yours any more.
     */
    private static final String MINE_AND_CHAIN = """
            mine as (
                select rs.league_id from roster_season rs join me on me.id = rs.manager_id
                union
                select d.league_id from draft d, me
                 where exists (select 1 from jsonb_each_text(d.slot_to_manager) x
                                where x.value = me.id::text)
                union
                select lm.league_id from league_member lm join me on me.id = lm.manager_id
            ),
            chain as (
                select l.id, l.previous_league_id
                  from league l join mine on mine.league_id = l.id
                union
                select p.id, p.previous_league_id
                  from league p join chain c on p.sleeper_id = c.previous_league_id
            )
            """;

    private static String cteNaming(String meSelect) {
        return "with recursive me as (" + meSelect + "),\n" + MINE_AND_CHAIN;
    }

    /**
     * Binds one parameter, a Sleeper user id, and ends with a {@code chain} CTE
     * whose {@code id} column is the set of league ids that user can see -- so a
     * consumer appends its own {@code select ...} referencing it.
     */
    public static final String MEMBER_LEAGUE_IDS_CTE =
            cteNaming("select id from manager where sleeper_user_id = ?");

    /** Same body, naming the manager by {@code manager.id} instead. */
    private static final String MANAGER_LEAGUE_IDS_CTE =
            cteNaming("select ?::bigint as id");

    /**
     * The league ids this Sleeper user belongs to. An unknown user (no
     * {@code manager} row) gets an empty set, not everything -- a scoping bug
     * that silently falls through to "all leagues" looks exactly like working
     * software to the one person who is in every league.
     */
    public Set<Long> leagueIdsFor(String sleeperUserId) {
        return idSet(MEMBER_LEAGUE_IDS_CTE + "select id from chain", sleeperUserId);
    }

    /** The league ids this manager plays in, by {@code manager.id}. */
    public Set<Long> leagueIdsForManager(long managerId) {
        return idSet(MANAGER_LEAGUE_IDS_CTE + "select id from chain", managerId);
    }

    private Set<Long> idSet(String sql, Object param) {
        List<Long> ids = db.sql(sql).param(param).query(Long.class).list();
        return Set.copyOf(ids);
    }

    /**
     * Whether this caller may see this league.
     *
     * A null or blank {@code sleeperUserId} -- no {@code X-Sleeper-User} header
     * at all -- is allowed through unchanged. That is the pre-identity contract
     * every endpoint had, it is what the {@code APP_OWNER_SLEEPER_USER_ID}
     * fallback and the curl-driven draft-night escape hatches rely on, and it is
     * deliberately not a hole this method can close: an unauthenticated header
     * is not something to build a boundary on either way.
     */
    public boolean canSee(String sleeperUserId, long leagueId) {
        if (anonymous(sleeperUserId)) return true;
        return Boolean.TRUE.equals(db.sql(MEMBER_LEAGUE_IDS_CTE
                        + "select exists (select 1 from chain where id = ?)")
                .param(sleeperUserId)
                .param(leagueId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Whether this caller may see this manager -- true when the two share at
     * least one league.
     *
     * A manager is not addressed by a league, so {@link #canSee} cannot answer
     * this; "someone you actually play against" is the nearest honest reading of
     * the same rule. Two queries rather than one merged CTE on purpose: both
     * sides are the same walk with a different {@code me}, and expressing that
     * as an intersection in Java keeps the SQL to the one body above instead of
     * growing a second, subtly different one.
     */
    public boolean canSeeManager(String sleeperUserId, long managerId) {
        if (anonymous(sleeperUserId)) return true;
        Set<Long> mine = leagueIdsFor(sleeperUserId);
        if (mine.isEmpty()) return false;
        return !Collections.disjoint(mine, leagueIdsForManager(managerId));
    }

    /**
     * The draft behind this Sleeper id, if this caller may see it -- empty both
     * when no such draft is ingested and when it belongs to a league that isn't
     * theirs.
     *
     * Collapsing those two cases is the point, and every caller keeps it
     * collapsed: telling the two apart confirms that a draft exists and which
     * league owns it, which is most of what enumerating draft ids wanted.
     *
     * <p>The single home for that rule. It briefly had a twin -- an identical
     * private copy in LeagueController, from when {@code /api/sims} needed the
     * same answer and that file was being edited elsewhere. Two implementations
     * of one rule is the failure this class's own header is about, and this pair
     * was the nastier shape of it: same name, same two {@code String}
     * parameters, opposite order, so reaching for the wrong one compiled
     * cleanly. Collapsed rather than left as a delegate, so there is no second
     * {@code visibleDraft} to reach for at all.
     *
     * <p>Every {@code /api/drafts/{id}/...} route and {@code /api/sims} goes
     * through here rather than calling {@code drafts.bySleeperId} directly, so
     * adding a route without scoping it is a visible omission instead of a
     * silent default.
     */
    public Optional<DraftRepository.DraftRow> visibleDraft(String sleeperUserId, String sleeperDraftId) {
        Optional<DraftRepository.DraftRow> found = drafts.bySleeperId(sleeperDraftId);
        if (found.isEmpty()) return found;
        return canSee(sleeperUserId, found.get().leagueId()) ? found : Optional.empty();
    }

    private static boolean anonymous(String sleeperUserId) {
        return sleeperUserId == null || sleeperUserId.isBlank();
    }
}
