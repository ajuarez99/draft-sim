package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.config.AdminAccess;
import com.ballknowers.draftsim.config.OwnerProperties;
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
    private final LeagueRepository leagues;
    private final OwnerProperties ownerProperties;
    private final ManagerRepository managers;
    private final LeagueMemberRepository leagueMembers;
    private final AdminAccess admin;

    public LeagueMembership(JdbcClient db, DraftRepository drafts, LeagueRepository leagues,
                             OwnerProperties ownerProperties, ManagerRepository managers,
                             LeagueMemberRepository leagueMembers, AdminAccess admin) {
        this.db = db;
        this.drafts = drafts;
        this.leagues = leagues;
        this.ownerProperties = ownerProperties;
        this.managers = managers;
        this.leagueMembers = leagueMembers;
        this.admin = admin;
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
     * <p><b>A null or blank {@code sleeperUserId} sees nothing.</b> This used to
     * return {@code true} -- "no header means the pre-identity contract" -- which
     * meant a caller who simply left {@code X-Sleeper-User} off got MORE than a
     * signed-in stranger did (measured on production 2026-09-28: 200 with the
     * header absent, 404 with a stranger's id). Fail-open on a missing identity
     * rewards opting out, which is worse than no scoping at all
     * (claude/audit-2026-09-28/01, claude/lessons.md). The browser always sends
     * the header once signed in and the sign-in gate blocks everything before
     * that, so denying is safe for real traffic.
     *
     * <p>The one exception is an operator: a request carrying a valid
     * {@code X-Admin-Token} ({@link AdminAccess}) is let through, which is what
     * DEPLOY.md's curl escape hatches (a manual pick on draft night) run on.
     */
    public boolean canSee(String sleeperUserId, long leagueId) {
        if (anonymous(sleeperUserId)) return admin.isAdmin();
        return Boolean.TRUE.equals(db.sql(MEMBER_LEAGUE_IDS_CTE
                        + "select exists (select 1 from chain where id = ?)")
                .param(sleeperUserId)
                .param(leagueId)
                .query(Boolean.class)
                .single());
    }

    /**
     * This league, if the caller may see it -- empty for both "no such league"
     * and "not yours", which the callers turn into the same 404 for the same
     * reason {@link #visibleDraft} does.
     *
     * <p>Moved here from {@code LeagueHistoryController} (specs/008-season-superlatives
     * T009): a second caller (the superlatives endpoints) needs the same
     * answer, which is exactly the "the moment something does" trigger this
     * class's own javadoc named for when a local copy should stop being local.
     */
    public Optional<LeagueRepository.LeagueRow> visibleLeague(String sleeperId, String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = leagues.bySleeperId(sleeperId);
        if (league.isEmpty()) return league;
        return canSee(sleeperUserId, league.get().id()) ? league : Optional.empty();
    }

    /**
     * Whether this caller may save a ranking for this league -- the header
     * names a {@code league_member} row with {@code is_commissioner}, or
     * equals the configured app owner. Shared between {@code GET /ballot}'s
     * {@code canCommission} (display) and {@code POST /power/commissioner}'s
     * own gate (enforcement), so the two can never disagree about who is
     * allowed to save.
     *
     * <p>Moved here from {@code LeagueHistoryController} (specs/008-season-superlatives
     * T009) so the conduct-list endpoints can share the same gate rather than
     * growing their own copy.
     */
    public boolean canCommission(long leagueId, String sleeperUserId) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) return false;
        if (ownerProperties.configured() && sleeperUserId.equals(ownerProperties.sleeperUserId())) return true;
        Long managerId = managers.idsBySleeperUserId().get(sleeperUserId);
        return managerId != null && leagueMembers.isCommissioner(leagueId, managerId);
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
        if (anonymous(sleeperUserId)) return admin.isAdmin();
        Set<Long> mine = leagueIdsFor(sleeperUserId);
        if (mine.isEmpty()) return false;
        return !Collections.disjoint(mine, leagueIdsForManager(managerId));
    }

    /**
     * Of these candidate managers, the ones this caller may see -- {@link #canSeeManager}
     * applied to each, with the caller's own league set computed once instead of once
     * per candidate. Anonymous callers see nobody unless they carry the admin token,
     * which sees everyone.
     */
    public Set<Long> visibleManagerIds(String sleeperUserId, java.util.Collection<Long> candidates) {
        if (anonymous(sleeperUserId)) return admin.isAdmin() ? Set.copyOf(candidates) : Set.of();
        Set<Long> mine = leagueIdsFor(sleeperUserId);
        if (mine.isEmpty()) return Set.of();
        Set<Long> out = new java.util.HashSet<>();
        for (Long id : candidates) {
            if (!Collections.disjoint(mine, leagueIdsForManager(id))) out.add(id);
        }
        return out;
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

    /** Whether this request carries a valid {@code X-Admin-Token} -- the operator override. */
    public boolean isAdminRequest() {
        return admin.isAdmin();
    }

    /** No identity at all. Public so the routes that list rather than address a league can share the one definition. */
    public static boolean isAnonymous(String sleeperUserId) {
        return sleeperUserId == null || sleeperUserId.isBlank();
    }

    private static boolean anonymous(String sleeperUserId) {
        return isAnonymous(sleeperUserId);
    }
}
