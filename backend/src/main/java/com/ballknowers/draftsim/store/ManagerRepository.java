package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

@Repository
public class ManagerRepository {

    private final JdbcClient db;

    public ManagerRepository(JdbcClient db) {
        this.db = db;
    }

    /** Keyed on Sleeper user id. Display name and avatar are refreshed but never identifying. */
    public long upsert(String sleeperUserId, String displayName, String avatarId) {
        return db.sql("""
                insert into manager (sleeper_user_id, display_name, avatar_id)
                values (?, ?, ?)
                on conflict (sleeper_user_id) do update set
                    display_name = excluded.display_name,
                    avatar_id = excluded.avatar_id
                returning id
                """)
                .params(sleeperUserId, displayName, avatarId)
                .query(Long.class)
                .single();
    }

    /** Spec 035 F1: managers that came from ESPN, excluded from profile fitting and mock seating. */
    public java.util.Set<Long> espnManagerIds() {
        return new java.util.HashSet<>(db.sql("select id from manager where platform = 'ESPN'").query(Long.class).list());
    }

    public Optional<String> displayName(long managerId) {
        return db.sql("select display_name from manager where id = ?")
                .param(managerId).query(String.class).optional();
    }

    public Map<Long, String> names() {
        return db.sql("select id, display_name from manager")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2) == null ? "?" : rs.getString(2)))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Sleeper avatar id per manager, same wire form SleeperUserController resolves at sign-in. Absent (not empty string) where Sleeper has none. */
    public Map<Long, String> avatarIds() {
        Map<Long, String> out = new java.util.HashMap<>();
        db.sql("select id, avatar_id from manager where avatar_id is not null")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Keyed on Sleeper user id, same shape as {@link PlayerRepository#idsBySleeperId}. */
    public Map<String, Long> idsBySleeperUserId() {
        Map<String, Long> out = new java.util.HashMap<>();
        db.sql("select sleeper_user_id, id from manager")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * Spec 035 {@code upsert} for a non-Sleeper manager. The platform is set on insert and left alone on
     * conflict by the 3-argument form above, so a Sleeper ingest can never flip a row's platform.
     */
    public long upsert(String sleeperUserId, String displayName, String avatarId,
                       com.ballknowers.draftsim.domain.Platform platform) {
        return db.sql("""
                insert into manager (sleeper_user_id, display_name, avatar_id, platform)
                values (?, ?, ?, ?)
                on conflict (sleeper_user_id) do update set
                    display_name = excluded.display_name,
                    avatar_id = excluded.avatar_id,
                    platform = excluded.platform
                returning id
                """)
                .params(sleeperUserId, displayName, avatarId, platform.name())
                .query(Long.class)
                .single();
    }

    /**
     * Who "the caller" is inside these leagues (spec 035 plan F5): the header's own manager if it owns a
     * roster or is a member in any of them, otherwise a manager it has claimed through
     * {@code manager_link} that does. Empty for an anonymous or unknown caller, or when neither plays there.
     *
     * <p>This is the one implementation; {@code LeagueMembership.callerManagerIn} delegates here. For a
     * Sleeper league the linked set is empty and this answers what
     * {@code idsBySleeperUserId().get(header)} always did, which is why callers write
     * {@code callerManagerIn(...).orElse(<the old lookup>)} and keep the old behaviour as the fallback.
     */
    public Optional<Long> callerManagerIn(Collection<Long> leagueIds, String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId) || leagueIds == null || leagueIds.isEmpty()) {
            return Optional.empty();
        }
        String in = String.join(", ", Collections.nCopies(leagueIds.size(), "?"));
        String sql = ("""
                with meuser as (select id from manager where sleeper_user_id = ?),
                candidates as (
                    select id, 0 as ord from meuser
                    union all
                    select ml.linked_manager_id, 1 from manager_link ml join meuser on meuser.id = ml.manager_id
                )
                select c.id from candidates c
                where exists (select 1 from roster_season rs where rs.league_id in (%s) and rs.manager_id = c.id)
                   or exists (select 1 from league_member lm where lm.league_id in (%s) and lm.manager_id = c.id)
                order by c.ord, c.id
                limit 1
                """).formatted(in, in);
        List<Object> params = new ArrayList<>();
        params.add(sleeperUserId);
        params.addAll(leagueIds);
        params.addAll(leagueIds);
        return db.sql(sql).params(params).query(Long.class).optional();
    }

    public Optional<Long> callerManagerIn(long leagueId, String sleeperUserId) {
        return callerManagerIn(List.of(leagueId), sleeperUserId);
    }

    /**
     * The drop-in for the old {@code managers.idsBySleeperUserId().get(header)}: the ESPN-aware caller if
     * there is one in these leagues, otherwise exactly what the old lookup returned.
     *
     * <p>Null-safe about a mocked repository on purpose: an unstubbed {@code callerManagerIn} on a Mockito
     * mock yields an empty Optional, so a test that stubs only {@code idsBySleeperUserId} keeps seeing its
     * stub.
     */
    public static Long caller(ManagerRepository managers, Collection<Long> leagueIds, String sleeperUserId) {
        // S4: an espn:-shaped header is not an identity (see LeagueMembership.isForeignShaped).
        if (LeagueMembership.isAnonymous(sleeperUserId)) return null;
        Long header = managers.idsBySleeperUserId().get(sleeperUserId);
        Optional<Long> in = managers.callerManagerIn(leagueIds, sleeperUserId);
        return in != null && in.isPresent() ? in.get() : header;
    }

    public static Long caller(ManagerRepository managers, long leagueId, String sleeperUserId) {
        return caller(managers, List.of(leagueId), sleeperUserId);
    }
}
